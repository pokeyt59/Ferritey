#!/usr/bin/env python3
"""Pins the game JVM's threads for the worldgen bench's affinity arm.

Usage: pin-threads.py <pid> split|reset <cpus>

split: the server thread gets a physical core to itself (cpu0; its
hyperthread sibling is left to threads other than the worldgen workers),
and the worldgen workers ("Worker-*") run on the remaining cores.
reset: every thread may run on all of <cpus> again.

A 2-core/4-thread CPU runs the server thread and a worldgen worker on the
same physical core when both are busy, and each then runs slower. This
arm measures what keeping them apart is worth: tick time against chunk
delivery. New pool threads inherit the affinity of the thread that
started them, so the bench re-applies split every few seconds.
"""
import os
import sys


def cpu_list(text):
    cpus = set()
    for part in text.strip().split(","):
        if "-" in part:
            lo, hi = part.split("-")
            cpus.update(range(int(lo), int(hi) + 1))
        elif part:
            cpus.add(int(part))
    return cpus


def main():
    pid, mode, allowed = sys.argv[1], sys.argv[2], cpu_list(sys.argv[3])
    with open("/sys/devices/system/cpu/cpu0/topology/thread_siblings_list") as f:
        core0 = cpu_list(f.read()) & allowed
    workers = allowed - core0 or allowed
    pinned = 0
    for tid in os.listdir(f"/proc/{pid}/task"):
        try:
            with open(f"/proc/{pid}/task/{tid}/comm") as f:
                name = f.read().strip()
            if mode == "reset":
                os.sched_setaffinity(int(tid), allowed)
            elif name == "Server thread":
                os.sched_setaffinity(int(tid), {min(core0 or allowed)})
                pinned += 1
            elif name.startswith("Worker-"):
                os.sched_setaffinity(int(tid), workers)
                pinned += 1
        except (FileNotFoundError, ProcessLookupError):
            continue   # the thread ended meanwhile
    if len(sys.argv) > 4:
        print(f"{mode}: core0={sorted(core0)} workers={sorted(workers)} threads pinned={pinned}")


if __name__ == "__main__":
    main()
