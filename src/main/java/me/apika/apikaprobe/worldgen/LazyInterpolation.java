package me.apika.apikaprobe.worldgen;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

import me.apika.apikaprobe.bridge.ExampleMod;

/**
 * Interpolated noise values worked out when read, not for every block.
 *
 * Inside a noise cell, NoiseChunk steps every interpolator (every
 * "interpolated" density function in the router) at every block:
 * updateForZ lerps each one's value along z, whether or not anything
 * reads it at that block. On the CI worldgen bench that loop was 6% of
 * the worldgen workers' time, the largest single frame in the noise stage
 * after noise sampling itself.
 *
 * With this on, updateForZ keeps the z delta and skips the loop, and an
 * interpolator read inside the loop (NoiseInterpolator.compute, the only
 * reader of the value) returns Mth.lerp(delta, valueZ0, valueZ1): the
 * same lerp of the same inputs as updateForZ, so the same double. If x
 * steps after the last z step (updateForX overwrites valueZ0/valueZ1), the
 * old pair is saved first, so a read then still gets the value the last
 * updateForZ would have left.
 *
 * Decided per NoiseChunk when it is built. Oracle: one NoiseChunk in
 * ORACLE_EVERY also runs the per-block loop and compares every read with
 * the stored value, bit for bit.
 * Off: -Dferrite.worldgen.lazyinterp=false or
 * /ferrite worldgen lazy-interp off.
 */
public final class LazyInterpolation {
	private LazyInterpolation() {}

	public static volatile boolean ENABLED = !"false".equals(System.getProperty("ferrite.worldgen.lazyinterp"));

	private static final int ORACLE_EVERY = 64;
	public static final LongAdder lazyChunks = new LongAdder();
	public static final LongAdder oracleChecks = new LongAdder();
	public static final LongAdder oracleMismatches = new LongAdder();

	/** The NoiseChunk's side (mixin). */
	public interface Chunk {
		/** 0: no z step yet; 1: value is lerp(delta, valueZ0, valueZ1); 2: lerp(delta, saved pair). */
		int ferrite$zState();

		double ferrite$zDelta();

		boolean ferrite$checking();
	}

	/** The interpolator's side (mixin). */
	public interface Interpolator {
		/** Keeps valueZ0/valueZ1 as they were at the last z step. */
		void ferrite$saveZ();
	}

	/** For a new NoiseChunk: 0 off, 1 lazy, 2 lazy and checked. */
	public static int modeForNewChunk() {
		if (!ENABLED) return 0;
		lazyChunks.increment();
		return ThreadLocalRandom.current().nextInt(ORACLE_EVERY) == 0 ? 2 : 1;
	}

	public static void check(double lazy, double stored) {
		oracleChecks.increment();
		if (Double.doubleToRawLongBits(lazy) != Double.doubleToRawLongBits(stored)) {
			oracleMismatches.increment();
			if (oracleMismatches.sum() <= 5) {
				ExampleMod.LOGGER.warn("[lazy-interp] MISMATCH: read {} lazily, {} stored", lazy, stored);
			}
		}
	}

	public static String status() {
		return String.format("[lazy-interp] lazy-interp=%s noiseChunks=%d oracleChecks=%d oracleMismatches=%d",
				ENABLED ? "on" : "off", lazyChunks.sum(), oracleChecks.sum(), oracleMismatches.sum());
	}
}
