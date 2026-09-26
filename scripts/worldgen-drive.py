#!/usr/bin/env python3
"""Drives the worldgen bench over one RCON connection.

Usage:
  worldgen-drive.py <port> <password> baseline <samples>
  worldgen-drive.py <port> <password> explore <start-chunk-x> <width> <step-seconds> <duration-seconds> [label]
  worldgen-drive.py <port> <password> players <label> <duration-seconds> <mode:x:z:heading>...

baseline: samples /tick query every 5 s and prints tick-time stats.

explore: stands in for a player moving through fresh terrain. Every
step it requests the next column of <width> chunks (z centred on 0) at
chunk x = start, start+1, ... with /ferrite bench explore add, which
loads them through asynchronous tickets the way a player's view does
(ExploreBench.java; /forceload would load them synchronously and stall
the server thread). After <duration> it stops advancing and waits (up to
DRAIN_MAX s) for the backlog to finish. Prints tick-time stats while
exploring, chunks delivered per second, request-to-loaded latency, and
the backlog of requested chunks not yet loaded.

players: joins bench players (/ferrite bench players add, FakeExplorers.java),
one per "mode:x:z:heading" (walk, horse, elytra, boat; block coordinates;
heading as Minecraft yaw, -90 = east), lets them travel for <duration>,
samples /tick query every 5 s meanwhile, then prints tick-time stats,
each player's chunks sent, holes near it and time spent waiting for
terrain, the worldgen workers' CPU per chunk sent, and removes them.
"""
import re
import socket
import statistics
import struct
import sys
import time

DRAIN_MAX = 180
LOGIN, COMMAND = 3, 2


class Rcon:
    def __init__(self, port, password):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=120)
        self.next_id = 1
        self.times = {}
        self._send(LOGIN, password)
        if self._recv()[0] == -1:
            sys.exit("rcon login refused")

    def _send(self, kind, body):
        self.next_id += 1
        data = struct.pack("<ii", self.next_id, kind) + body.encode("utf-8") + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(data)) + data)

    def _exact(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("rcon connection closed")
            buf += chunk
        return buf

    def _recv(self):
        size = struct.unpack("<i", self._exact(4))[0]
        data = self._exact(size)
        req_id, _ = struct.unpack("<ii", data[:8])
        return req_id, data[8:-2].decode("utf-8", "replace")

    def cmd(self, text):
        start = time.monotonic()
        self._send(COMMAND, text)
        out = self._recv()[1]
        # Commands run on the server thread between ticks, so a slow
        # round trip is a slow tick (or a slow command) seen from outside.
        kind = text.split(" ")[0]
        self.times.setdefault(kind, []).append(time.monotonic() - start)
        return out


def tick_query(r):
    out = r.cmd("tick query")
    avg = re.search(r"[Aa]verage time per tick: ([0-9.]+) ?ms", out)
    pct = re.search(r"P50: ([0-9.]+) ?ms.*?P95: ([0-9.]+) ?ms.*?P99: ([0-9.]+) ?ms", out, re.S)
    if not avg:
        print(f"unparsed tick query: {out!r}", file=sys.stderr)
        return None
    return {
        "avg": float(avg.group(1)),
        "p95": float(pct.group(2)) if pct else None,
        "p99": float(pct.group(3)) if pct else None,
    }


def summarize(label, samples):
    samples = [s for s in samples if s]
    if not samples:
        print(f"{label}: no samples")
        return
    avg = [s["avg"] for s in samples]
    p95 = [s["p95"] for s in samples if s["p95"] is not None]
    p99 = [s["p99"] for s in samples if s["p99"] is not None]
    line = f"{label}: mspt mean {statistics.mean(avg):.2f}  max-of-means {max(avg):.2f}"
    if p95:
        line += f"  p95 mean {statistics.mean(p95):.2f}"
    if p99:
        line += f"  p99 max {max(p99):.2f}"
    print(line + f"  (n={len(samples)})")


STATUS = re.compile(r"requested=(\d+) done=(\d+) failed=(\d+) latency_ms median=(\d+) p90=(\d+) max=(\d+) worker_cpu_ms=(-?\d+)")


def status(r):
    out = r.cmd("ferrite bench explore status")
    m = STATUS.search(out)
    if not m:
        sys.exit(f"unparsed explore status: {out!r}")
    return [int(g) for g in m.groups()]


def explore(r, start, width, step, duration, label=""):
    tag = f"[{label}] " if label else ""
    samples = []
    backlogs = []
    head = start
    z0 = -(width // 2)
    z1 = z0 + width - 1
    r.cmd("ferrite bench explore reset")
    cpu0 = status(r)[6]
    t0 = time.monotonic()
    next_step = t0
    next_sample = t0 + 5
    while time.monotonic() - t0 < duration:
        now = time.monotonic()
        if now >= next_step:
            r.cmd(f"ferrite bench explore add {head} {z0} {head} {z1}")
            head += 1
            next_step += step
        requested, done, failed = status(r)[:3]
        backlogs.append(requested - done - failed)
        if now >= next_sample:
            samples.append(tick_query(r))
            next_sample += 5
        # Each RCON command runs on the server thread between ticks; poll
        # gently so the driver itself does not show up in the numbers.
        time.sleep(0.5)
    explore_end = time.monotonic()
    done_while_exploring = status(r)[1]
    while time.monotonic() - explore_end < DRAIN_MAX:
        requested, done, failed = status(r)[:3]
        if done + failed >= requested:
            break
        time.sleep(0.5)
    drain = time.monotonic() - explore_end
    requested, done, failed, median, p90, worst, cpu1 = status(r)

    summarize(f"{tag}exploring", samples)
    total = time.monotonic() - t0
    print(f"{tag}requested {requested} chunks ({head - start} columns x {width}) in {duration:.0f} s "
          f"({width / step:.1f} chunks/s asked)")
    print(f"{tag}delivered {done_while_exploring} while exploring ({done_while_exploring / duration:.2f} chunks/s), "
          f"{done} in {total:.0f} s with the drain ({drain:.0f} s): {done / total:.2f} chunks/s; {failed} failed")
    print(f"{tag}chunk latency, request to loaded: median {median} ms  p90 {p90} ms  max {worst} ms")
    if done and cpu0 >= 0:
        print(f"{tag}worldgen worker cpu: {cpu1 - cpu0} ms, {(cpu1 - cpu0) / done:.1f} ms per chunk delivered")
    if backlogs:
        print(f"{tag}backlog (requested, not yet loaded): mean {statistics.mean(backlogs):.0f} chunks, "
              f"max {max(backlogs)} chunks ({max(backlogs) / width:.1f} columns behind the head)")
    for kind, t in sorted(r.times.items()):
        print(f"{tag}rcon round trip '{kind}': n={len(t)} median {statistics.median(t) * 1000:.0f} ms "
              f"max {max(t) * 1000:.0f} ms")
    if done == 0:
        sys.exit("no chunk finished generating")


PLAYER = re.compile(r"chunks_sent=(\d+)")


def players(r, label, duration, specs):
    tag = f"[{label}] "
    r.cmd("ferrite bench players clear")
    cpu0 = status(r)[6]
    for spec in specs:
        mode, x, z, heading = spec.split(":")
        print(tag + r.cmd(f"ferrite bench players add {mode} {x} {z} {heading}").strip())
    samples = []
    t0 = time.monotonic()
    while time.monotonic() - t0 < duration:
        time.sleep(5)
        samples.append(tick_query(r))
    out = r.cmd("ferrite bench players status")
    cpu1 = status(r)[6]
    summarize(f"{tag}players", samples)
    for line in out.strip().splitlines():
        print(tag + line)
    sent = sum(int(m) for m in PLAYER.findall(out.split("total")[0]))
    if sent and cpu0 >= 0:
        print(f"{tag}worldgen worker cpu: {cpu1 - cpu0} ms, {(cpu1 - cpu0) / sent:.1f} ms per chunk sent")
    for kind, t in sorted(r.times.items()):
        print(f"{tag}rcon round trip '{kind}': n={len(t)} median {statistics.median(t) * 1000:.0f} ms "
              f"max {max(t) * 1000:.0f} ms")
    print(tag + r.cmd("ferrite bench players clear").strip())
    if sent == 0:
        sys.exit("no chunk was sent to any player")


def main():
    port, password, mode = int(sys.argv[1]), sys.argv[2], sys.argv[3]
    r = Rcon(port, password)
    if mode == "baseline":
        n = int(sys.argv[4])
        samples = []
        for _ in range(n):
            time.sleep(5)
            samples.append(tick_query(r))
        summarize("baseline (no exploring)", samples)
    elif mode == "explore":
        explore(r, int(sys.argv[4]), int(sys.argv[5]), float(sys.argv[6]), float(sys.argv[7]),
                sys.argv[8] if len(sys.argv) > 8 else "")
    elif mode == "players":
        players(r, sys.argv[4], float(sys.argv[5]), sys.argv[6:])
    else:
        sys.exit(f"unknown mode {mode}")


if __name__ == "__main__":
    main()
