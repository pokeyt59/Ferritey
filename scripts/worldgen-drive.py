#!/usr/bin/env python3
"""Drives the worldgen bench over one RCON connection.

Usage:
  worldgen-drive.py <port> <password> baseline <samples>
  worldgen-drive.py <port> <password> explore <start-chunk-x> <width> <step-seconds> <duration-seconds>

baseline: samples /tick query every 5 s and prints tick-time stats.

explore: stands in for a player moving through fresh terrain. Every
step it forceloads the next column of <width> chunks (z centred on 0) at
chunk x = start, start+1, ..., and checks with "execute if loaded" which
requested columns have finished generating. Columns more than KEEP
behind the head are released once loaded. After <duration> it stops
advancing and waits (up to DRAIN_MAX s) for the backlog to finish. Prints
tick-time stats while exploring, chunks delivered per second, and the
generation lag (how far behind the head the newest finished column was).
"""
import re
import socket
import statistics
import struct
import sys
import time

KEEP = 12
DRAIN_MAX = 180
LOGIN, COMMAND = 3, 2


class Rcon:
    def __init__(self, port, password):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=120)
        self.next_id = 1
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
        self._send(COMMAND, text)
        return self._recv()[1]


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


def loaded(r, cx):
    return "passed" in r.cmd(f"execute if loaded {cx * 16 + 8} 64 8").lower()


def column_cmd(verb, cx, width):
    z0 = -(width // 2)
    z1 = z0 + width - 1
    return f"forceload {verb} {cx * 16} {z0 * 16} {cx * 16} {z1 * 16}"


def explore(r, start, width, step, duration):
    samples = []
    added = {}          # column -> time requested
    done = {}           # column -> seconds from request to loaded
    released = set()
    lags = []
    head = start
    t0 = time.monotonic()
    next_step = t0
    next_sample = t0 + 5
    while time.monotonic() - t0 < duration:
        now = time.monotonic()
        if now >= next_step:
            r.cmd(column_cmd("add", head, width))
            added[head] = now
            head += 1
            next_step += step
        # Oldest first: record every column that finished since last time.
        for cx in sorted(c for c in added if c not in done):
            if not loaded(r, cx):
                break
            done[cx] = time.monotonic() - added[cx]
        newest = max(done) if done else start - 1
        lags.append(head - 1 - newest)
        for cx in sorted(done):
            if cx < head - KEEP and cx not in released:
                r.cmd(column_cmd("remove", cx, width))
                released.add(cx)
        if now >= next_sample:
            samples.append(tick_query(r))
            next_sample += 5
        # Each RCON command runs on the server thread between ticks; poll
        # gently so the driver itself does not show up in the numbers.
        time.sleep(0.5)
    explore_end = time.monotonic()
    requested = len(added)
    while len(done) < requested and time.monotonic() - explore_end < DRAIN_MAX:
        for cx in sorted(c for c in added if c not in done):
            if not loaded(r, cx):
                break
            done[cx] = time.monotonic() - added[cx]
        time.sleep(0.5)
    drain = time.monotonic() - explore_end
    for cx in added:
        if cx not in released:
            r.cmd(column_cmd("remove", cx, width))

    summarize("exploring", samples)
    total = time.monotonic() - t0
    finished = len(done)
    print(f"requested {requested} columns x {width} = {requested * width} chunks in {duration} s "
          f"({width / step:.1f} chunks/s asked)")
    print(f"finished {finished} columns ({finished * width} chunks) in {total:.0f} s "
          f"(drain {drain:.0f} s): {finished * width / total:.2f} chunks/s delivered")
    if done:
        lat = sorted(done.values())
        print(f"column latency: median {statistics.median(lat):.1f} s  "
              f"p90 {lat[int(0.9 * (len(lat) - 1))]:.1f} s  max {lat[-1]:.1f} s")
    if lags:
        print(f"generation lag behind the head: mean {statistics.mean(lags):.1f} columns, "
              f"max {max(lags)} columns ({max(lags) * 16} blocks)")
    if finished == 0:
        sys.exit("no column finished generating")


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
        explore(r, int(sys.argv[4]), int(sys.argv[5]), float(sys.argv[6]), float(sys.argv[7]))
    else:
        sys.exit(f"unknown mode {mode}")


if __name__ == "__main__":
    main()
