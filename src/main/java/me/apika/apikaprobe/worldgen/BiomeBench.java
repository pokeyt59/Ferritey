package me.apika.apikaprobe.worldgen;

import java.util.Arrays;
import java.util.Random;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/**
 * /ferrite bench biomes: times the biome source's lookups for whole
 * chunks (every quart position, section by section in the order chunks
 * fill their biomes) with BiomeSearch on and off, alternating the order
 * each round, and counts positions whose biome differs. Runs on the
 * calling thread; for the CI worldgen bench.
 */
public final class BiomeBench {
	private BiomeBench() {}

	public static String run(ServerLevel level, int chunks, int rounds) {
		BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
		Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
		Random rng = new Random(0xb10e);
		int[] cxs = new int[chunks];
		int[] czs = new int[chunks];
		for (int i = 0; i < chunks; i++) {
			// Far from spawn and from the explore corridor, spread over many biomes.
			cxs[i] = (20_000 + rng.nextInt(200_000)) >> 4;
			czs[i] = (-100_000 + rng.nextInt(200_000)) >> 4;
		}
		int minQuartY = level.getMinY() >> 2;
		int quartsY = level.getHeight() >> 2;
		int perChunk = 16 * quartsY;
		@SuppressWarnings("unchecked")
		Holder<Biome>[][] biomes = new Holder[2][chunks * perChunk];
		long[][] nanos = new long[2][rounds];
		boolean saved = BiomeSearch.ENABLED;
		try {
			for (int arm = 0; arm < 2; arm++) {
				BiomeSearch.ENABLED = arm == 0;
				fill(source, sampler, cxs, czs, Math.min(chunks, 8), minQuartY, quartsY, biomes[arm]);
			}
			for (int round = 0; round < rounds; round++) {
				for (int step = 0; step < 2; step++) {
					int arm = (round + step) % 2;   // 0 = flat search on, 1 = off
					BiomeSearch.ENABLED = arm == 0;
					long start = System.nanoTime();
					fill(source, sampler, cxs, czs, chunks, minQuartY, quartsY, biomes[arm]);
					nanos[arm][round] = System.nanoTime() - start;
				}
			}
		} finally {
			BiomeSearch.ENABLED = saved;
		}
		int differing = 0;
		for (int i = 0; i < biomes[0].length; i++) {
			if (biomes[0][i] != biomes[1][i]) differing++;
		}
		StringBuilder rows = new StringBuilder();
		for (int round = 0; round < rounds; round++) {
			rows.append(String.format(" r%d on %.2f off %.2f", round,
					nanos[0][round] / 1e6 / chunks, nanos[1][round] / 1e6 / chunks));
		}
		double on = median(nanos[0]) / 1e6 / chunks;
		double off = median(nanos[1]) / 1e6 / chunks;
		return String.format("[biome-bench] chunks=%d rounds=%d ms/chunk median: biome-search on %.2f, off %.2f (%+.1f%%); biomes differing %d;%s",
				chunks, rounds, on, off, 100.0 * (on - off) / off, differing, rows);
	}

	private static void fill(BiomeSource source, Climate.Sampler sampler, int[] cxs, int[] czs, int chunks,
			int minQuartY, int quartsY, Holder<Biome>[] out) {
		int i = 0;
		for (int c = 0; c < chunks; c++) {
			int qx0 = cxs[c] << 2;
			int qz0 = czs[c] << 2;
			for (int sy = 0; sy < quartsY; sy += 4) {
				for (int x = 0; x < 4; x++) {
					for (int y = 0; y < 4; y++) {
						for (int z = 0; z < 4; z++) {
							out[i++] = source.getNoiseBiome(qx0 + x, minQuartY + sy + y, qz0 + z, sampler);
						}
					}
				}
			}
		}
	}

	private static double median(long[] values) {
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		int n = sorted.length;
		return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
	}
}
