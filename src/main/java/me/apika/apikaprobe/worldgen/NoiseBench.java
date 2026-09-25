package me.apika.apikaprobe.worldgen;

import java.util.Arrays;
import java.util.Random;
import java.util.function.Consumer;

import me.apika.apikaprobe.mixin.NoiseChunkStateInvoker;
import me.apika.apikaprobe.mixin.NoiseGeneratorFluidAccessor;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * /ferrite bench noise: the noise stage of whole chunks, timed with a
 * worldgen switch on and off (lazy-interp, noise-math, map-memo),
 * alternating the order each round.
 *
 * Each chunk gets a NoiseChunk and goes through the generator's own fill
 * loop (NoiseBasedChunkGenerator.doFill: cells, then blocks, stepping y,
 * x and z and taking the interpolated block state), without structures
 * (no beardifier) or blending, and without writing a chunk. Every block
 * state is folded into a checksum per chunk, so the two arms are also
 * checked to produce the same blocks. Runs on the calling thread; for
 * the CI worldgen bench.
 */
public final class NoiseBench {
	private NoiseBench() {}

	public static String run(ServerLevel level, int chunks, int rounds, String name) {
		Consumer<Boolean> set;
		boolean saved;
		switch (name) {
			case "map-memo" -> { saved = MappingMemo.ENABLED; set = on -> MappingMemo.ENABLED = on; }
			case "noise-math" -> { saved = NoiseMath.ENABLED; set = on -> NoiseMath.ENABLED = on; }
			case "lazy-interp" -> { saved = LazyInterpolation.ENABLED; set = on -> LazyInterpolation.ENABLED = on; }
			default -> { return "[noise-bench] unknown switch " + name + " (map-memo, noise-math, lazy-interp)"; }
		}
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		if (!(generator instanceof NoiseBasedChunkGenerator noise)) return "[noise-bench] not a noise generator";
		RandomState random = level.getChunkSource().randomState();
		NoiseGeneratorSettings settings = noise.generatorSettings().value();
		Aquifer.FluidPicker fluids = ((NoiseGeneratorFluidAccessor) (Object) noise).ferrite$globalFluidPicker().get();
		NoiseSettings ns = settings.noiseSettings().clampToHeightAccessor(level);
		PalettedContainerFactory containers = PalettedContainerFactory.create(level.registryAccess());
		DensityFunctions.BeardifierOrMarker noStructures;
		try {
			noStructures = (DensityFunctions.BeardifierOrMarker) Class
					.forName("net.minecraft.world.level.levelgen.DensityFunctions$BeardifierMarker", true,
							DensityFunctions.class.getClassLoader())
					.getField("INSTANCE").get(null);
		} catch (ReflectiveOperationException e) {
			return "[noise-bench] " + e;
		}
		Random rng = new Random(0x401e);
		ProtoChunk[] at = new ProtoChunk[chunks];
		for (int i = 0; i < chunks; i++) {
			// Far from spawn and from the explore corridor, spread over many biomes.
			// Only the position and height are read (NoiseChunk.forChunk); nothing is written.
			ChunkPos pos = new ChunkPos((20_000 + rng.nextInt(200_000)) >> 4, (-100_000 + rng.nextInt(200_000)) >> 4);
			at[i] = new ProtoChunk(pos, UpgradeData.EMPTY, level, containers, null);
		}
		Filler filler = new Filler(random, ns, noStructures, settings, fluids);
		long[][] nanos = new long[2][rounds];
		long[][] sums = new long[2][chunks];
		try {
			for (int arm = 0; arm < 2; arm++) {
				set.accept(arm == 0);
				for (int i = 0; i < Math.min(chunks, 4); i++) filler.fill(at[i]);
			}
			for (int round = 0; round < rounds; round++) {
				for (int step = 0; step < 2; step++) {
					int arm = (round + step) % 2;   // 0 = on, 1 = off
					set.accept(arm == 0);
					long start = System.nanoTime();
					for (int i = 0; i < chunks; i++) sums[arm][i] = filler.fill(at[i]);
					nanos[arm][round] = System.nanoTime() - start;
				}
			}
		} finally {
			set.accept(saved);
		}
		int differing = 0;
		for (int i = 0; i < chunks; i++) {
			if (sums[0][i] != sums[1][i]) differing++;
		}
		StringBuilder rows = new StringBuilder();
		for (int round = 0; round < rounds; round++) {
			rows.append(String.format(" r%d on %.1f off %.1f", round,
					nanos[0][round] / 1e6 / chunks, nanos[1][round] / 1e6 / chunks));
		}
		double on = median(nanos[0]) / 1e6 / chunks;
		double off = median(nanos[1]) / 1e6 / chunks;
		return String.format("[noise-bench] chunks=%d rounds=%d ms/chunk median: %s on %.2f, off %.2f (%+.1f%%); chunks differing %d;%s",
				chunks, rounds, name, on, off, 100.0 * (on - off) / off, differing, rows);
	}

	/** NoiseBasedChunkGenerator.doFill's loop, block states into a checksum. */
	private record Filler(RandomState random, NoiseSettings ns, DensityFunctions.BeardifierOrMarker beardifier,
			NoiseGeneratorSettings settings, Aquifer.FluidPicker fluids) {

		long fill(ProtoChunk at) {
			int minBlockX = at.getPos().getMinBlockX();
			int minBlockZ = at.getPos().getMinBlockZ();
			NoiseChunk chunk = NoiseChunk.forChunk(at, random, beardifier, settings, fluids, Blender.empty());
			NoiseChunkStateInvoker states = (NoiseChunkStateInvoker) (Object) chunk;
			int cellHeight = states.ferrite$cellHeight();
			int cellMinY = Mth.floorDiv(ns.minY(), cellHeight);
			int cellCountY = Mth.floorDiv(ns.height(), cellHeight);
			BlockState fallback = settings.defaultBlock();
			long sum = 17;
			chunk.initializeForFirstCellX();
			int cellWidth = states.ferrite$cellWidth();
			int cells = 16 / cellWidth;
			for (int cellX = 0; cellX < cells; cellX++) {
				chunk.advanceCellX(cellX);
				for (int cellZ = 0; cellZ < cells; cellZ++) {
					for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
						chunk.selectCellYZ(cellY, cellZ);
						for (int yInCell = cellHeight - 1; yInCell >= 0; yInCell--) {
							int y = (cellMinY + cellY) * cellHeight + yInCell;
							chunk.updateForY(y, (double) yInCell / cellHeight);
							for (int xInCell = 0; xInCell < cellWidth; xInCell++) {
								int x = minBlockX + cellX * cellWidth + xInCell;
								chunk.updateForX(x, (double) xInCell / cellWidth);
								for (int zInCell = 0; zInCell < cellWidth; zInCell++) {
									int z = minBlockZ + cellZ * cellWidth + zInCell;
									chunk.updateForZ(z, (double) zInCell / cellWidth);
									BlockState state = states.ferrite$interpolatedState();
									if (state == null) state = fallback;
									sum = sum * 31 + Block.getId(state);
								}
							}
						}
					}
				}
				chunk.swapSlices();
			}
			chunk.stopInterpolation();
			return sum;
		}
	}

	private static double median(long[] values) {
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		int n = sorted.length;
		return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
	}
}
