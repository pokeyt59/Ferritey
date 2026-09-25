package me.apika.apikaprobe.spatial;

import java.util.Objects;
import java.util.function.Function;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
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
 * {@link #clip} walks the ray with a copy of 26.2's
 * {@code BlockGetter.traverseBlocks} (same arithmetic, same order), reading
 * each block from the chunk section it last used: air returns "no hit" at
 * once; any other block runs a copy of 26.2's per-block function from
 * {@code BlockGetter.clip} on that state. A chunk that is not loaded, or a
 * call off the server thread, reads the block through the level as vanilla
 * does. The miss result is a copy of clip's miss function. Results equal
 * vanilla's. Walking here rather than through traverseBlocks keeps the
 * per-block step a direct call: traverseBlocks' own call site is shared
 * with Lithium's and vanilla's clip, so the JIT cannot inline through it.
 *
 * The oracle runs the original clip (vanilla's, or Lithium's) on 1 in N
 * calls and logs any difference. Kill switch -Dferrite.clip.airskip=false;
 * /ferrite raycast air-skip on|off|status toggles it for A/B.
 */
public final class ClipAirSkip {

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
	private long skippedHere;
	private long passedHere;

	private ClipAirSkip(ServerLevel level) {
		this.level = level;
		this.chunks = level.getChunkSource();
	}

	/** The clip result, or null when the caller should run the original clip. */
	public static BlockHitResult clip(Level level, ClipContext context) {
		// The debug world computes its states instead of storing them.
		if (!ENABLED || !(level instanceof ServerLevel server) || server.isDebug()) return null;
		rays++;
		ClipAirSkip walker = new ClipAirSkip(server);
		BlockHitResult result = walker.walk(context);
		skipped += walker.skippedHere;
		passed += walker.passedHere;
		return result;
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

	/** BlockGetter.traverseBlocks (26.2) with clip's per-block and miss functions. */
	private BlockHitResult walk(ClipContext context) {
		Vec3 from = context.getFrom();
		Vec3 to = context.getTo();
		if (from.equals(to)) return MISS.apply(context);
		double toX = Mth.lerp(-1.0E-7, to.x, from.x);
		double toY = Mth.lerp(-1.0E-7, to.y, from.y);
		double toZ = Mth.lerp(-1.0E-7, to.z, from.z);
		double fromX = Mth.lerp(-1.0E-7, from.x, to.x);
		double fromY = Mth.lerp(-1.0E-7, from.y, to.y);
		double fromZ = Mth.lerp(-1.0E-7, from.z, to.z);
		int x = Mth.floor(fromX);
		int y = Mth.floor(fromY);
		int z = Mth.floor(fromZ);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
		BlockHitResult hit = visit(context, pos);
		if (hit != null) return hit;
		double dx = toX - fromX;
		double dy = toY - fromY;
		double dz = toZ - fromZ;
		int signX = Mth.sign(dx);
		int signY = Mth.sign(dy);
		int signZ = Mth.sign(dz);
		double stepX = signX == 0 ? Double.MAX_VALUE : (double) signX / dx;
		double stepY = signY == 0 ? Double.MAX_VALUE : (double) signY / dy;
		double stepZ = signZ == 0 ? Double.MAX_VALUE : (double) signZ / dz;
		double tX = stepX * (signX > 0 ? 1.0 - Mth.frac(fromX) : Mth.frac(fromX));
		double tY = stepY * (signY > 0 ? 1.0 - Mth.frac(fromY) : Mth.frac(fromY));
		double tZ = stepZ * (signZ > 0 ? 1.0 - Mth.frac(fromZ) : Mth.frac(fromZ));
		while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
			if (tX < tY) {
				if (tX < tZ) {
					x += signX;
					tX += stepX;
				} else {
					z += signZ;
					tZ += stepZ;
				}
			} else if (tY < tZ) {
				y += signY;
				tY += stepY;
			} else {
				z += signZ;
				tZ += stepZ;
			}
			hit = visit(context, pos.set(x, y, z));
			if (hit != null) return hit;
		}
		return MISS.apply(context);
	}

	/** clip's per-block step: air answers "no hit" without the shape clip. */
	private BlockHitResult visit(ClipContext context, BlockPos pos) {
		BlockState state = stateIfLoaded(pos);
		if (state == null) {
			passedHere++;
			return block(level, context, pos, level.getBlockState(pos), level.getFluidState(pos));
		}
		if (state.isAir()) {
			skippedHere++;
			return null;
		}
		passedHere++;
		// LevelChunkSection.getFluidState is states.get(...).getFluidState().
		return block(level, context, pos, state, state.getFluidState());
	}

	/** BlockGetter.clip's per-block function (lambda$clip$0 in 26.2) on a known state. */
	private static BlockHitResult block(BlockGetter getter, ClipContext context, BlockPos pos,
			BlockState blockState, FluidState fluidState) {
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
	 * Level.getBlockState(pos) when it can be read without loading anything:
	 * outside the build height (VOID_AIR, as vanilla), or from a loaded
	 * chunk's section (AIR for a section with only air, as
	 * LevelChunk.getBlockState). Null otherwise: the caller then asks the
	 * level, which loads or waits as vanilla does.
	 */
	private BlockState stateIfLoaded(BlockPos pos) {
		int y = pos.getY();
		if (level.isOutsideBuildHeight(y)) return Blocks.VOID_AIR.defaultBlockState();
		int x = pos.getX();
		int z = pos.getZ();
		int cx = x >> 4;
		int cz = z >> 4;
		if (cx != chunkX || cz != chunkZ) {
			// getChunkNow is null off the server thread and for chunks that
			// are not loaded to FULL.
			LevelChunk chunk = chunks.getChunkNow(cx, cz);
			sections = chunk == null ? null : chunk.getSections();
			chunkX = cx;
			chunkZ = cz;
		}
		LevelChunkSection[] s = sections;
		if (s == null) return null;
		int index = level.getSectionIndex(y);
		if (index < 0 || index >= s.length) return null;
		LevelChunkSection section = s[index];
		if (section.hasOnlyAir()) return Blocks.AIR.defaultBlockState();
		return section.getBlockState(x & 15, y & 15, z & 15);
	}
}
