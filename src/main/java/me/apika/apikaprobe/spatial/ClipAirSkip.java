package me.apika.apikaprobe.spatial;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Line-of-sight raycast that answers air blocks without the shape clip.
 *
 * Every mob line-of-sight check ({@code LivingEntity.hasLineOfSight}: target
 * goals, brain sensors) is a {@code Level.clip}: walk the ray block by block,
 * and for each block look up its block and fluid state, build both shapes,
 * and clip the ray against them. Most blocks on a ray are air, and for air
 * that work always ends in "no hit": air has no shape in any
 * {@code ClipContext.Block} mode and holds no fluid, so no
 * {@code ClipContext.Fluid} mode can pick it. Lithium's clip caches the chunk
 * but still builds and clips the shapes of every air block.
 *
 * {@link #clip} runs vanilla's own walk ({@code BlockGetter.traverseBlocks})
 * with a per-block function that reads the block from the chunk section it
 * last used and returns "no hit" for air. Any other block runs a copy of
 * 26.2's per-block function from {@code BlockGetter.clip}; a chunk that is
 * not loaded, or a call off the server thread, also goes that way. The miss
 * result is a copy of clip's miss function. Results equal vanilla's.
 *
 * The oracle runs the original clip (vanilla's, or Lithium's) on 1 in N
 * calls and logs any difference. Kill switch -Dferrite.clip.airskip=false;
 * /ferrite raycast air-skip on|off|status toggles it for A/B.
 */
public final class ClipAirSkip implements BiFunction<ClipContext, BlockPos, BlockHitResult> {

	public static volatile boolean ENABLED = !"false".equals(System.getProperty("ferrite.clip.airskip"));

	/** Oracle: compare 1 in N rays with the original clip; 0 disables. */
	public static final int ORACLE_RATE = Integer.getInteger("ferrite.clip.airskip.oracle", 256);

	// Server-thread counters, read by /ferrite raycast air-skip status.
	public static long rays;
	public static long skipped;
	public static long passed;
	public static long oracleChecks;
	public static long oracleMismatches;
	private static int sampleCounter;

	/** BlockGetter.clip's miss function (lambda$clip$1 in 26.2). */
	private static final Function<ClipContext, BlockHitResult> MISS = context -> {
		Vec3 delta = context.getFrom().subtract(context.getTo());
		return BlockHitResult.miss(context.getTo(),
				Direction.getApproximateNearest(delta.x, delta.y, delta.z),
				BlockPos.containing(context.getTo()));
	};

	private final ServerLevel level;
	private final ServerChunkCache chunks;
	private int chunkX = Integer.MAX_VALUE;
	private int chunkZ = Integer.MAX_VALUE;
	private LevelChunkSection[] sections;

	private ClipAirSkip(ServerLevel level) {
		this.level = level;
		this.chunks = level.getChunkSource();
	}

	/** The clip result, or null when the caller should run the original clip. */
	public static BlockHitResult clip(Level level, ClipContext context) {
		// The debug world computes its states instead of storing them.
		if (!ENABLED || !(level instanceof ServerLevel server) || server.isDebug()) return null;
		rays++;
		return BlockGetter.traverseBlocks(context.getFrom(), context.getTo(), context,
				new ClipAirSkip(server), MISS);
	}

	/** True for the rays the oracle should compare with the original clip. */
	public static boolean sample() {
		return ORACLE_RATE > 0 && ++sampleCounter % ORACLE_RATE == 0;
	}

	/** Oracle: logs when the fast result differs from the original's. */
	public static void check(ClipContext context, BlockHitResult fast, BlockHitResult original) {
		oracleChecks++;
		if (fast.getType() == original.getType()
				&& fast.getLocation().equals(original.getLocation())
				&& fast.getDirection() == original.getDirection()
				&& Objects.equals(fast.getBlockPos(), original.getBlockPos())
				&& fast.isInside() == original.isInside()) {
			return;
		}
		oracleMismatches++;
		ExampleMod.LOGGER.warn("[clip-airskip] MISMATCH: {} -> {}: fast {} {} {} {}, original {} {} {} {}",
				context.getFrom(), context.getTo(),
				fast.getType(), fast.getLocation(), fast.getDirection(), fast.getBlockPos(),
				original.getType(), original.getLocation(), original.getDirection(), original.getBlockPos());
	}

	@Override
	public BlockHitResult apply(ClipContext context, BlockPos pos) {
		if (isAir(pos)) {
			skipped++;
			return null;
		}
		passed++;
		return block(level, context, pos);
	}

	/** BlockGetter.clip's per-block function (lambda$clip$0 in 26.2). */
	private static BlockHitResult block(BlockGetter getter, ClipContext context, BlockPos pos) {
		BlockState blockState = getter.getBlockState(pos);
		FluidState fluidState = getter.getFluidState(pos);
		Vec3 from = context.getFrom();
		Vec3 to = context.getTo();
		VoxelShape blockShape = context.getBlockShape(blockState, getter, pos);
		BlockHitResult blockHit = getter.clipWithInteractionOverride(from, to, pos, blockShape, blockState);
		VoxelShape fluidShape = context.getFluidShape(fluidState, getter, pos);
		BlockHitResult fluidHit = fluidShape.clip(from, to, pos);
		double blockDistance = blockHit == null ? Double.MAX_VALUE
				: context.getFrom().distanceToSqr(blockHit.getLocation());
		double fluidDistance = fluidHit == null ? Double.MAX_VALUE
				: context.getFrom().distanceToSqr(fluidHit.getLocation());
		return blockDistance <= fluidDistance ? blockHit : fluidHit;
	}

	/**
	 * Same answer as vanilla's Level.getBlockState(pos).isAir(), but only
	 * claims air when it can read the block without loading anything:
	 * outside the build height (vanilla returns VOID_AIR there), or in a
	 * loaded chunk. Anything else answers false and takes the full path.
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
