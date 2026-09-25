package me.apika.apikaprobe.worldgen;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import me.apika.apikaprobe.mixin.NoiseChunkStateInvoker;
import me.apika.apikaprobe.mixin.NoiseGeneratorFluidAccessor;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.ticks.ProtoChunkTicks;

/**
 * /ferrite bench surface: the surface step of whole chunks, timed with
 * surface rule pruning on and off in rotated rounds.
 *
 * Each chunk is prepared once as the generator would leave it before its
 * surface step: biomes from the biome source (Biolith included) and
 * blocks from the noise stage (the generator's fill loop, no structures),
 * with the ocean floor and world surface heightmaps. Each round builds
 * the surface (NoiseBasedChunkGenerator.buildSurface) on fresh copies of
 * those chunks, and every block state goes into a per-chunk checksum, so
 * the arms are also checked to produce the same blocks. Biomes near the
 * chunk edges are read from the chunk itself, not from neighbours, the
 * same in both arms. Runs on the calling thread; for the CI worldgen
 * bench.
 */
public final class SurfaceBench {
	private SurfaceBench() {}

	public static String run(ServerLevel level, int chunks, int rounds) {
		try {
			return runChecked(level, chunks, rounds);
		} catch (Throwable t) {
			StringBuilder sb = new StringBuilder("[surface-bench] failed: ").append(t);
			StackTraceElement[] frames = t.getStackTrace();
			for (int i = 0; i < Math.min(8, frames.length); i++) sb.append(" < ").append(frames[i]);
			if (t.getCause() != null) sb.append(" caused by ").append(t.getCause());
			return sb.toString();
		}
	}

	private static String runChecked(ServerLevel level, int chunks, int rounds) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		if (!(generator instanceof NoiseBasedChunkGenerator noise)) return "[surface-bench] not a noise generator";
		RandomState random = level.getChunkSource().randomState();
		NoiseGeneratorSettings settings = noise.generatorSettings().value();
		Aquifer.FluidPicker fluids = ((NoiseGeneratorFluidAccessor) (Object) noise).ferrite$globalFluidPicker().get();
		NoiseSettings ns = settings.noiseSettings().clampToHeightAccessor(level);
		PalettedContainerFactory containers = PalettedContainerFactory.create(level.registryAccess());
		DensityFunctions.BeardifierOrMarker noStructures;
		try {
			java.lang.reflect.Field instance = Class
					.forName("net.minecraft.world.level.levelgen.DensityFunctions$BeardifierMarker", true,
							DensityFunctions.class.getClassLoader())
					.getField("INSTANCE");
			instance.setAccessible(true);
			noStructures = (DensityFunctions.BeardifierOrMarker) instance.get(null);
		} catch (ReflectiveOperationException e) {
			return "[surface-bench] " + e;
		}
		long seed = BiomeManager.obfuscateSeed(level.getSeed());
		Random rng = new Random(0x5afe);
		Prepared[] prepared = new Prepared[chunks];
		for (int i = 0; i < chunks; i++) {
			ChunkPos pos = new ChunkPos((20_000 + rng.nextInt(200_000)) >> 4, (-100_000 + rng.nextInt(200_000)) >> 4);
			prepared[i] = prepare(level, generator, random, settings, fluids, ns, containers, noStructures, pos, seed);
		}
		WorldGenerationContext context = new WorldGenerationContext(generator, level);
		long[][] nanos = new long[2][rounds];
		long[][] sums = new long[2][chunks];
		boolean saved = SurfaceRulePrune.ENABLED;
		try {
			for (int arm = 0; arm < 2; arm++) {
				SurfaceRulePrune.ENABLED = arm == 0;
				for (int i = 0; i < Math.min(chunks, 4); i++) {
					ProtoChunk copy = prepared[i].copy(level, containers);
					noise.buildSurface(copy, context, random, null, prepared[i].biomes, Blender.empty(), prepared[i].possible);
				}
			}
			for (int round = 0; round < rounds; round++) {
				for (int step = 0; step < 2; step++) {
					int arm = (round + step) % 2;   // 0 = on, 1 = off
					SurfaceRulePrune.ENABLED = arm == 0;
					ProtoChunk[] copies = new ProtoChunk[chunks];
					for (int i = 0; i < chunks; i++) copies[i] = prepared[i].copy(level, containers);
					long start = System.nanoTime();
					for (int i = 0; i < chunks; i++) {
						noise.buildSurface(copies[i], context, random, null, prepared[i].biomes, Blender.empty(),
								prepared[i].possible);
					}
					nanos[arm][round] = System.nanoTime() - start;
					for (int i = 0; i < chunks; i++) sums[arm][i] = checksum(copies[i]);
				}
			}
		} finally {
			SurfaceRulePrune.ENABLED = saved;
		}
		int differing = 0;
		for (int i = 0; i < chunks; i++) {
			if (sums[0][i] != sums[1][i]) differing++;
		}
		StringBuilder rows = new StringBuilder();
		for (int round = 0; round < rounds; round++) {
			rows.append(String.format(" r%d on %.2f off %.2f", round,
					nanos[0][round] / 1e6 / chunks, nanos[1][round] / 1e6 / chunks));
		}
		double on = median(nanos[0]) / 1e6 / chunks;
		double off = median(nanos[1]) / 1e6 / chunks;
		return String.format("[surface-bench] chunks=%d rounds=%d ms/chunk median: surface-prune on %.2f, off %.2f (%+.1f%%); chunks differing %d;%s",
				chunks, rounds, on, off, 100.0 * (on - off) / off, differing, rows);
	}

	/** A chunk as it stands before its surface step, kept as a template. */
	private record Prepared(ChunkPos pos, LevelChunkSection[] sections, NoiseChunk noiseChunk, BiomeManager biomes,
			Set<Holder<Biome>> possible) {

		ProtoChunk copy(ServerLevel level, PalettedContainerFactory containers) {
			LevelChunkSection[] copied = new LevelChunkSection[sections.length];
			for (int i = 0; i < sections.length; i++) copied[i] = sections[i].copy();
			ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, copied, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(),
					level, containers, null);
			chunk.setPersistedStatus(ChunkStatus.NOISE);
			Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
			chunk.getOrCreateNoiseChunk(c -> noiseChunk);
			return chunk;
		}
	}

	private static Prepared prepare(ServerLevel level, ChunkGenerator generator, RandomState random,
			NoiseGeneratorSettings settings, Aquifer.FluidPicker fluids, NoiseSettings ns, PalettedContainerFactory containers,
			DensityFunctions.BeardifierOrMarker noStructures, ChunkPos pos, long seed) {
		ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, level, containers, null);
		chunk.fillBiomesFromNoise(generator.getBiomeSource(), random.sampler());
		NoiseChunk nc = chunk.getOrCreateNoiseChunk(
				c -> NoiseChunk.forChunk(c, random, noStructures, settings, fluids, Blender.empty()));
		fillNoise(chunk, nc, ns, settings);
		// Biomes are only read from a chunk that has them (ProtoChunk.getNoiseBiome checks the status).
		chunk.setPersistedStatus(ChunkStatus.NOISE);
		LevelChunkSection[] template = new LevelChunkSection[chunk.getSections().length];
		Set<Holder<Biome>> possible = new HashSet<>();
		for (int i = 0; i < template.length; i++) {
			template[i] = chunk.getSections()[i].copy();
			chunk.getSections()[i].getBiomes().getAll(possible::add);
		}
		return new Prepared(pos, template, nc, new BiomeManager(chunk, seed), possible);
	}

	/** NoiseBasedChunkGenerator.doFill: non-air states into the chunk's sections. */
	private static void fillNoise(ProtoChunk chunk, NoiseChunk nc, NoiseSettings ns, NoiseGeneratorSettings settings) {
		NoiseChunkStateInvoker states = (NoiseChunkStateInvoker) (Object) nc;
		int cellHeight = states.ferrite$cellHeight();
		int cellWidth = states.ferrite$cellWidth();
		int cellMinY = Mth.floorDiv(ns.minY(), cellHeight);
		int cellCountY = Mth.floorDiv(ns.height(), cellHeight);
		int minBlockX = chunk.getPos().getMinBlockX();
		int minBlockZ = chunk.getPos().getMinBlockZ();
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState fallback = settings.defaultBlock();
		nc.initializeForFirstCellX();
		int cells = 16 / cellWidth;
		for (int cellX = 0; cellX < cells; cellX++) {
			nc.advanceCellX(cellX);
			for (int cellZ = 0; cellZ < cells; cellZ++) {
				for (int cellY = cellCountY - 1; cellY >= 0; cellY--) {
					nc.selectCellYZ(cellY, cellZ);
					for (int yInCell = cellHeight - 1; yInCell >= 0; yInCell--) {
						int y = (cellMinY + cellY) * cellHeight + yInCell;
						LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
						nc.updateForY(y, (double) yInCell / cellHeight);
						for (int xInCell = 0; xInCell < cellWidth; xInCell++) {
							int x = minBlockX + cellX * cellWidth + xInCell;
							nc.updateForX(x, (double) xInCell / cellWidth);
							for (int zInCell = 0; zInCell < cellWidth; zInCell++) {
								int z = minBlockZ + cellZ * cellWidth + zInCell;
								nc.updateForZ(z, (double) zInCell / cellWidth);
								BlockState state = states.ferrite$interpolatedState();
								if (state == null) state = fallback;
								if (state != air) section.setBlockState(x & 15, y & 15, z & 15, state, false);
							}
						}
					}
				}
			}
			nc.swapSlices();
		}
		nc.stopInterpolation();
	}

	private static long checksum(ProtoChunk chunk) {
		long sum = 17;
		for (LevelChunkSection section : chunk.getSections()) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) sum = sum * 31 + Block.getId(section.getBlockState(x, y, z));
				}
			}
		}
		return sum;
	}

	private static double median(long[] values) {
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		int n = sorted.length;
		return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
	}
}
