package me.apika.apikaprobe.spatial;

import java.util.function.BiFunction;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Air skip for {@code BlockGetter.clip}, the raycast behind every mob
 * line-of-sight check ({@code LivingEntity.hasLineOfSight}), targeting
 * sensors, and projectile and interaction rays.
 *
 * Vanilla walks the ray block by block and, for every block, looks up the
 * chunk twice (block state, fluid state), builds the block and fluid
 * shapes, and clips the ray against both. Most blocks on a ray are air,
 * and for air that work always ends in "no hit": air has no shape in any
 * {@code ClipContext.Block} mode and holds no fluid, so no
 * {@code ClipContext.Fluid} mode can pick it.
 *
 * This wrapper sits between vanilla's walk ({@code traverseBlocks}, which
 * is untouched) and vanilla's per-block function. It reads the block from
 * the chunk section it last used and returns "no hit" for air itself;
 * any other block, a chunk that is not loaded, or a call off the server
 * thread goes to the vanilla function. Hits, their position and face, and
 * the miss result are therefore always vanilla's own.
 *
 * The oracle samples 1 in N skipped blocks, runs the vanilla function on
 * them, and logs any hit it finds. Kill switch -Dferrite.clip.airskip=false;
 * /ferrite raycast air-skip on|off|status toggles it for A/B.
 */
public final class ClipAirSkip<C, T> implements BiFunction<C, BlockPos, T> {

	public static volatile boolean ENABLED = !"false".equals(System.getProperty("ferrite.clip.airskip"));

	/** Oracle: check 1 in N skipped blocks against the vanilla function; 0 disables. */
	public static final int ORACLE_RATE = Integer.getInteger("ferrite.clip.airskip.oracle", 256);

	// Server-thread counters, read by /ferrite raycast air-skip status.
	public static long rays;
	public static long skipped;
	public static long passed;
	public static long oracleChecks;
	public static long oracleHits;
	private static int sampleCounter;

	private final ServerLevel level;
	private final ServerChunkCache chunks;
	private final BiFunction<C, BlockPos, T> vanilla;
	private int chunkX = Integer.MAX_VALUE;
	private int chunkZ = Integer.MAX_VALUE;
	private LevelChunkSection[] sections;

	private ClipAirSkip(ServerLevel level, BiFunction<C, BlockPos, T> vanilla) {
		this.level = level;
		this.chunks = level.getChunkSource();
		this.vanilla = vanilla;
	}

	/** Called once per clip: returns the function to hand to traverseBlocks. */
	public static <C, T> BiFunction<C, BlockPos, T> wrap(Object getter, BiFunction<C, BlockPos, T> vanilla) {
		// The debug world computes its states instead of storing them.
		if (!ENABLED || !(getter instanceof ServerLevel level) || level.isDebug()) {
			return vanilla;
		}
		rays++;
		return new ClipAirSkip<>(level, vanilla);
	}

	@Override
	public T apply(C context, BlockPos pos) {
		if (!isAir(pos)) {
			passed++;
			return vanilla.apply(context, pos);
		}
		skipped++;
		if (ORACLE_RATE > 0 && ++sampleCounter % ORACLE_RATE == 0) {
			oracleChecks++;
			T hit = vanilla.apply(context, pos);
			if (hit != null) {
				oracleHits++;
				ExampleMod.LOGGER.warn("[clip-airskip] MISMATCH: vanilla hit an air block at {} in {}: {}",
						pos, level.dimension(), hit);
				return hit;
			}
		}
		return null;
	}

	/**
	 * Same answer as vanilla's Level.getBlockState(pos).isAir(), but only
	 * claims air when it can read the block without loading anything:
	 * outside the build height (vanilla returns VOID_AIR there), or in a
	 * loaded chunk. Anything else answers false and takes the vanilla path.
	 */
	private boolean isAir(BlockPos pos) {
		int y = pos.getY();
		if (level.isOutsideBuildHeight(y)) return true;
		int x = pos.getX();
		int z = pos.getZ();
		int cx = x >> 4;
		int cz = z >> 4;
		if (cx != chunkX || cz != chunkZ) {
			// getChunkNow is null off the server thread and for chunks that
			// are not loaded to FULL; vanilla then loads or waits as usual.
			LevelChunk chunk = chunks.getChunkNow(cx, cz);
			sections = chunk == null ? null : chunk.getSections();
			chunkX = cx;
			chunkZ = cz;
		}
		LevelChunkSection[] s = sections;
		if (s == null) return false;
		int index = level.getSectionIndex(y);
		if (index < 0 || index >= s.length) return false;
		LevelChunkSection section = s[index];
		// LevelChunk.getBlockState answers AIR for a section with only air.
		return section.hasOnlyAir() || section.getBlockState(x & 15, y & 15, z & 15).isAir();
	}
}
