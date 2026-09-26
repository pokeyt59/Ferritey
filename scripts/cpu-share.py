#!/usr/bin/env python3
"""Makes the CPUs the server runs on behave like slower ones.

Usage: cpu-share.py <cpu-list> <nice>     e.g. cpu-share.py 0-3 3

Runs one spinning process per listed CPU, pinned to it, at the given nice
level, until killed. A server thread on that CPU then gets
1024 / (1024 + weight(nice)) of it: nice 0 halves it, nice 3 leaves about
two thirds. The scheduler interleaves the two within milliseconds, so a
tick sees a slower CPU rather than bursts of none, as a CPU quota would
give. While the server thread sleeps the spinner takes the CPU, which
costs the server nothing.

Nice only divides a CPU between processes in the same scheduler
autogroup; the bench turns autogroups off first (kernel.sched_autogroup_enabled=0).
A negative nice needs root.

The bench picks the nice level by timing /ferrite bench noise against a
target (scripts/ci-players-bench.sh), so what it models is a slowdown of
worldgen code, measured, not a clock speed.
"""
import multiprocessing
import os
import signal
import sys


def parse_cpus(spec):
    cpus = []
    for part in spec.split(","):
        if "-" in part:
            lo, hi = part.split("-")
            cpus.extend(range(int(lo), int(hi) + 1))
        else:
            cpus.append(int(part))
    return cpus


def spin(cpu, nice):
    os.sched_setaffinity(0, {cpu})
    os.setpriority(os.PRIO_PROCESS, 0, nice)
    x = 0
    while True:
        x = (x * 1103515245 + 12345) & 0x7FFFFFFF


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    cpus = parse_cpus(sys.argv[1])
    nice = int(sys.argv[2])
    procs = [multiprocessing.Process(target=spin, args=(c, nice), daemon=True) for c in cpus]
    for p in procs:
        p.start()

    def stop(*_):
        for p in procs:
            p.terminate()
        sys.exit(0)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    for p in procs:
        p.join()


if __name__ == "__main__":
    main()
