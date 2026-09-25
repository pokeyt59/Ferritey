package me.apika.apikaprobe.worldgen;

import java.util.Arrays;
import java.util.Random;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * /ferrite bench columns: times the generator's height query (the call
 * structure placement makes for every start and many jigsaw pieces; it
 * builds a whole NoiseChunk for one column) with MappingMemo on and off,
 * alternating the order each round, and checks the heights agree. The
 * height cache is off meanwhile, so every query is computed. Runs on the
 * calling thread; for the CI worldgen bench.
 */
public final class ColumnBench {
	private ColumnBench() {}

	public static String run(ServerLevel level, int queries, int rounds) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		RandomState random = level.getChunkSource().randomState();
		Random rng = new Random(0x5eed);
		int[] xs = new int[queries];
		int[] zs = new int[queries];
		for (int i = 0; i < queries; i++) {
			// Far from spawn and from the explore corridor, spread over
			// many biomes.
			xs[i] = 20_000 + rng.nextInt(200_000);
			zs[i] = -100_000 + rng.nextInt(200_000);
		}
		boolean saved = MappingMemo.ENABLED;
		boolean savedCache = BaseHeightCache.ENABLED;
		BaseHeightCache.ENABLED = false;
		long[][] nanos = new long[2][rounds];
		int[][] heights = new int[2][queries];
		try {
			// Warm up both paths.
			for (int arm = 0; arm < 2; arm++) {
				MappingMemo.ENABLED = arm == 0;
				for (int i = 0; i < Math.min(queries, 100); i++) {
					generator.getBaseHeight(xs[i], zs[i], Heightmap.Types.WORLD_SURFACE_WG, level, random);
				}
			}
			for (int round = 0; round < rounds; round++) {
				for (int step = 0; step < 2; step++) {
					int arm = (round + step) % 2;   // 0 = memo on, 1 = off
					MappingMemo.ENABLED = arm == 0;
					long start = System.nanoTime();
					for (int i = 0; i < queries; i++) {
						heights[arm][i] = generator.getBaseHeight(xs[i], zs[i],
								Heightmap.Types.WORLD_SURFACE_WG, level, random);
					}
					nanos[arm][round] = System.nanoTime() - start;
				}
			}
		} finally {
			MappingMemo.ENABLED = saved;
			BaseHeightCache.ENABLED = savedCache;
		}
		int differing = 0;
		for (int i = 0; i < queries; i++) {
			if (heights[0][i] != heights[1][i]) differing++;
		}
		StringBuilder rows = new StringBuilder();
		for (int round = 0; round < rounds; round++) {
			rows.append(String.format(" r%d on %.0f off %.0f", round,
					nanos[0][round] / 1e3 / queries, nanos[1][round] / 1e3 / queries));
		}
		double on = median(nanos[0]) / 1e3 / queries;
		double off = median(nanos[1]) / 1e3 / queries;
		return String.format("[column-bench] queries=%d rounds=%d us/query median: memo on %.1f, off %.1f (%+.1f%%); heights differing %d;%s",
				queries, rounds, on, off, 100.0 * (on - off) / off, differing, rows);
	}

	private static double median(long[] values) {
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		int n = sorted.length;
		return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
	}
}
