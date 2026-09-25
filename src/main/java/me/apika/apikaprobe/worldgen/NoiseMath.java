package me.apika.apikaprobe.worldgen;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.LongAdder;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/**
 * Noise sampling with the same arithmetic and fewer steps around it.
 *
 * Noise sampling was about 18% of the worldgen workers' time on the CI
 * worldgen bench (PerlinNoise.getValue, SimplexNoise.dot, ImprovedNoise).
 * Two exact shortcuts:
 *
 * - PerlinNoise.wrap(d) is d - lfloor(d / 2^25 + 0.5) * 2^25, applied to
 *   every coordinate of every octave. For |d| < 2^23 the floor is 0
 *   whatever the rounding (d / 2^25 + 0.5 lies in (0.25, 0.75)), so the
 *   result is d - 0.0, which is d (also for -0.0). Those inputs return d.
 * - ImprovedNoise.sampleAndLerp reads its permutation as unsigned bytes
 *   and each gradient as an int[] converted to double per use. Here the
 *   permutation is an int[] and the gradients one flat double[] of the
 *   same values; the dot products, smoothsteps and lerps are the same
 *   operations in the same order (Mth's own lerp3 and smoothstep).
 *
 * Java floating point is strict, so the same operations on the same
 * values give the same bits. Verification: the first VERIFY_SAMPLES
 * samples of each (and again after /ferrite worldgen noise-math verify)
 * are also computed by the game's own code and compared bit for bit.
 * Off: -Dferrite.worldgen.noisemath=false or
 * /ferrite worldgen noise-math off.
 */
public final class NoiseMath {
	private NoiseMath() {}

	public static volatile boolean ENABLED = !"false".equals(System.getProperty("ferrite.worldgen.noisemath"));

	private static final long VERIFY_SAMPLES = 200_000;
	/** The gradients as doubles, three per gradient; null if they could not be read. */
	private static final double[] GRADIENT = readGradients();

	private static volatile boolean verifying = true;
	public static final LongAdder oracleChecks = new LongAdder();
	public static final LongAdder oracleMismatches = new LongAdder();

	private static double[] readGradients() {
		try {
			Field f = SimplexNoise.class.getDeclaredField("GRADIENT");
			f.setAccessible(true);
			int[][] g = (int[][]) f.get(null);
			if (g.length != 16) throw new IllegalStateException(g.length + " gradients");
			double[] flat = new double[16 * 3];
			for (int i = 0; i < 16; i++) {
				for (int k = 0; k < 3; k++) flat[3 * i + k] = g[i][k];
			}
			return flat;
		} catch (ReflectiveOperationException | RuntimeException e) {
			ExampleMod.LOGGER.warn("[noise-math] gradients not read, sampling stays vanilla: {}", e.toString());
			return null;
		}
	}

	public static boolean sampling() {
		return ENABLED && GRADIENT != null;
	}

	public static boolean verifying() {
		return verifying;
	}

	public static void verifyAgain() {
		oracleChecks.reset();
		verifying = true;
	}

	/** The permutation of an ImprovedNoise as unsigned ints. */
	public static int[] permutation(byte[] p) {
		int[] perm = new int[p.length];
		for (int i = 0; i < p.length; i++) perm[i] = p[i] & 255;
		return perm;
	}

	/** PerlinNoise.wrap. */
	public static double wrap(double d) {
		if (!ENABLED || !(Math.abs(d) < 0x1p23)) return PerlinNoise.wrap(d);
		if (verifying) compare(d, PerlinNoise.wrap(d));
		return d;
	}

	/** ImprovedNoise.sampleAndLerp over an int permutation and the flat gradients. */
	public static double sampleAndLerp(int[] perm, int x, int y, int z, double xr, double yr, double zr, double yrOriginal) {
		int x0 = perm[x & 255];
		int x1 = perm[x + 1 & 255];
		int xy00 = perm[x0 + y & 255];
		int xy01 = perm[x0 + y + 1 & 255];
		int xy10 = perm[x1 + y & 255];
		int xy11 = perm[x1 + y + 1 & 255];
		double d000 = gradDot(perm[xy00 + z & 255], xr, yr, zr);
		double d100 = gradDot(perm[xy10 + z & 255], xr - 1.0, yr, zr);
		double d010 = gradDot(perm[xy01 + z & 255], xr, yr - 1.0, zr);
		double d110 = gradDot(perm[xy11 + z & 255], xr - 1.0, yr - 1.0, zr);
		double d001 = gradDot(perm[xy00 + z + 1 & 255], xr, yr, zr - 1.0);
		double d101 = gradDot(perm[xy10 + z + 1 & 255], xr - 1.0, yr, zr - 1.0);
		double d011 = gradDot(perm[xy01 + z + 1 & 255], xr, yr - 1.0, zr - 1.0);
		double d111 = gradDot(perm[xy11 + z + 1 & 255], xr - 1.0, yr - 1.0, zr - 1.0);
		double xa = Mth.smoothstep(xr);
		double ya = Mth.smoothstep(yrOriginal);
		double za = Mth.smoothstep(zr);
		return Mth.lerp3(xa, ya, za, d000, d100, d010, d110, d001, d101, d011, d111);
	}

	/** SimplexNoise.dot(GRADIENT[hash & 15], x, y, z): g0 * x + g1 * y + g2 * z, left to right. */
	private static double gradDot(int hash, double x, double y, double z) {
		double[] g = GRADIENT;
		int o = 3 * (hash & 15);
		return g[o] * x + g[o + 1] * y + g[o + 2] * z;
	}

	public static void compare(double mine, double game) {
		oracleChecks.increment();
		if (Double.doubleToRawLongBits(mine) != Double.doubleToRawLongBits(game)) {
			oracleMismatches.increment();
			if (oracleMismatches.sum() <= 5) {
				ExampleMod.LOGGER.warn("[noise-math] MISMATCH: {} against the game's {}", mine, game);
			}
		}
		if (oracleChecks.sum() >= VERIFY_SAMPLES) verifying = false;
	}

	public static String status() {
		return String.format("[noise-math] noise-math=%s%s verifying=%s oracleChecks=%d oracleMismatches=%d",
				ENABLED ? "on" : "off", GRADIENT == null ? " (unavailable)" : "", verifying ? "yes" : "no",
				oracleChecks.sum(), oracleMismatches.sum());
	}
}
