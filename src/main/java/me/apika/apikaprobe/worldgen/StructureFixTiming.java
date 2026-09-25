package me.apika.apikaprobe.worldgen;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts and times structure template upgrades (DataFixTypes.STRUCTURE):
 * templates saved by an older game version, as mods ship them, go
 * through DataFixerUpper the first time they load after each start.
 * /ferrite worldgen structure-dfu status.
 */
public final class StructureFixTiming {
	private StructureFixTiming() {}

	private static final AtomicLong count = new AtomicLong();
	private static final AtomicLong nanos = new AtomicLong();
	private static final AtomicLong maxNanos = new AtomicLong();

	public static void record(long elapsed) {
		count.incrementAndGet();
		nanos.addAndGet(elapsed);
		maxNanos.accumulateAndGet(elapsed, Math::max);
	}

	public static String status() {
		long n = count.get();
		return String.format("[structure-dfu] templates upgraded=%d total_ms=%d mean_ms=%.1f max_ms=%d",
				n, nanos.get() / 1_000_000L, n == 0 ? 0.0 : nanos.get() / 1e6 / n, maxNanos.get() / 1_000_000L);
	}
}
