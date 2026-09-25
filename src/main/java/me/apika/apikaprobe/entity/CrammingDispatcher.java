package me.apika.apikaprobe.entity;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.apika.apikaprobe.RustBridge;
import me.apika.apikaprobe.monitor.MonitorLog;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.server.level.ServerLevel;

/**
 * Batched mob-vs-mob cramming dispatcher.
 *
 * The first Mob pushEntities call a level sees in a server tick triggers
 * the batch for that level:
 *   1. Collect every Mob in the level.
 *   2. Mark as callers the mobs whose own pushEntities ran last tick,
 *      plus the triggering mob. Vanilla only pushes from mobs that tick,
 *      so frozen mobs past simulation distance, mobs an activation-range
 *      mod skips, and /tick freeze stay out of the caller set.
 *   3. Fill CrammingHandoff.REQUEST_BUF with positions, AABBs, flags,
 *      and root vehicle ids. isPushable() is only asked of mobs that
 *      overlap another (see runBatch).
 *   4. Native call → Rust runs the spatial hash push accumulator. Each
 *      pair is pushed once per caller member, as vanilla pushes it from
 *      each ticking member's own call. Rust also counts pushable
 *      non-passenger overlaps per entity (`crowdedCount`).
 *   5. Apply each mob's velocity delta via entity.push(dx,0,dz), and
 *      store the crowded count on each caller.
 *
 * Every Mob pushEntities call then applies its own cramming damage from
 * the stored count (crowdedCount &gt; maxEntityCramming − 1 AND the
 * per-entity RandomSource fires vanilla's 1-in-4 check) and cancels the
 * vanilla body. A mob the batch did not take as a caller (it started
 * ticking this tick, or the batch overflowed) runs vanilla instead.
 * Callers are predicted from the previous tick, so a mob that stops
 * ticking contributes one extra tick of push force.
 *
 * The batch key is (level, server tick). The old key, game time alone,
 * is shared by every dimension, so the Nether and End skipped their batch
 * whenever the Overworld had already run one that tick.
 *
 * ENABLED=true by default. /ferrite cramming off lets users fall back
 * to vanilla without restart for A/B verification.
 */
public final class CrammingDispatcher {

	public static volatile boolean ENABLED = true;

	// --- Per-server-tick state ---------------------------------------------
	// Levels tick one after another, so the last batched (level, tick)
	// pair is enough to tell a new batch apart from a repeat call.
	private static ServerLevel lastLevel;
	private static long lastTick = Long.MIN_VALUE;
	private static int batchMaxCramming;
	private static final List<Mob> MOB_SCRATCH = new ArrayList<>(1024);

	// Rate limiter for the "too many mobs to batch" warning. Without this,
	// a single overloaded tick would print MAX_ENTITIES-overflow lines on
	// every subsequent tick until the mob count drops, which is pure noise.
	private static final long OVERFLOW_LOG_MIN_GAP_NS = 1_000_000_000L;
	private static volatile long lastOverflowLogNs;

	// Parallel output arrays sized to MAX_ENTITIES; reused every tick.
	private static final double[] ACCUM_DX = new double[CrammingHandoff.MAX_ENTITIES];
	private static final double[] ACCUM_DZ = new double[CrammingHandoff.MAX_ENTITIES];
	private static final int[] NEIGHBOR_COUNT = new int[CrammingHandoff.MAX_ENTITIES];
	private static final int[] CROWDED_COUNT = new int[CrammingHandoff.MAX_ENTITIES];
	private static final boolean[] CALLER = new boolean[CrammingHandoff.MAX_ENTITIES];

	// --- Diagnostics -------------------------------------------------------
	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");
	private static long diagBatches  = 0;
	private static long diagMobs     = 0;
	private static long diagCallers  = 0;
	private static long diagVanilla  = 0;
	private static long diagPushed   = 0;
	private static long diagDamaged  = 0;
	private static long diagPushableChecks = 0;
	private static long diagReruns   = 0;
	private static long diagLastLogNs = System.nanoTime();

	private CrammingDispatcher() {}

	/** Drops the level reference so a stopped server can be collected. */
	public static void register() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			lastLevel = null;
			lastTick = Long.MIN_VALUE;
		});
	}

	/**
	 * Called from CrammingCancelMixin on every Mob pushEntities call.
	 * Returns true if the batch handled this mob (caller should cancel the
	 * vanilla body); false if the caller should let vanilla run.
	 */
	public static boolean onTickCramming(Mob caller) {
		if (!ENABLED || !RustBridge.NATIVE_AVAILABLE) return false;
		if (!(caller.level() instanceof ServerLevel world)) return false;

		long tick = world.getServer().getTickCount();
		if (world != lastLevel || tick != lastTick) {
			lastLevel = world;
			lastTick = tick;
			runBatch(world, caller, tick);
			maybeLogDiag();
		}

		CrammingState state = (CrammingState) caller;
		state.ferrite$setPushTick(tick);
		if (state.ferrite$batchTick() != tick) {
			diagVanilla++;
			return false;
		}

		// Damage gate mirrors vanilla LivingEntity.pushEntities:
		//   if (maxCramming > 0
		//       && pushableEntities.size() > maxCramming - 1
		//       && entity.random.nextInt(4) == 0) {
		//       int count = non-passenger pushable entities;
		//       if (count > maxCramming - 1) hurt(cramming, 6.0F);
		//   }
		// Rust returns crowdedCount = the inner `count`. Per-entity
		// RandomSource and the threshold check stay in Java so the RNG
		// state advances exactly as vanilla's does.
		if (batchMaxCramming > 0
				&& state.ferrite$crowded() > batchMaxCramming - 1
				&& caller.getRandom().nextInt(4) == 0) {
			caller.hurtServer(world, world.damageSources().cramming(), 6.0F);
			diagDamaged++;
		}
		return true;
	}

	// =========================================================================
	// Batch
	// =========================================================================

	private static void runBatch(ServerLevel world, Mob trigger, long tick) {
		// 1. Collect all eligible mobs in this level.
		MOB_SCRATCH.clear();
		for (Entity e : world.getAllEntities()) {
			if (e instanceof Mob mob && e.isAlive() && !e.isRemoved()) {
				MOB_SCRATCH.add(mob);
			}
		}
		int count = MOB_SCRATCH.size();
		if (count == 0) return;
		if (count > CrammingHandoff.MAX_ENTITIES) {
			// Too many mobs: no mob gets a batch tick, so every call this
			// tick falls through to vanilla. Rare; safer than partial batching.
			maybeLogOverflow(count);
			MOB_SCRATCH.clear();
			return;
		}

		// 2. Callers: mobs that ran pushEntities last tick, and the trigger.
		int callers = 0;
		for (int i = 0; i < count; i++) {
			Mob mob = MOB_SCRATCH.get(i);
			boolean caller = mob == trigger
					|| ((CrammingState) mob).ferrite$pushTick() == tick - 1;
			CALLER[i] = caller;
			if (caller) callers++;
		}

		// 3–4. Build, dispatch, read. Every mob goes in as pushable; the
		//     flag only matters for a mob that overlaps another, so only
		//     those pay for isPushable() (a block lookup through
		//     onClimbable). If one of them is not pushable (climbing, or a
		//     type that never is), the batch runs again with its flag
		//     cleared, so the result always matches the per-mob flags.
		CrammingHandoff.buildRequests(MOB_SCRATCH, CALLER);
		compute(count);
		boolean rerun = false;
		for (int i = 0; i < count; i++) {
			if (NEIGHBOR_COUNT[i] == 0) continue;
			diagPushableChecks++;
			if (!MOB_SCRATCH.get(i).isPushable()) {
				CrammingHandoff.clearPushable(i);
				rerun = true;
			}
		}
		if (rerun) {
			diagReruns++;
			compute(count);
		}

		// 5. Apply pushes; callers keep their crowded count for the damage
		//    check in their own pushEntities call.
		// Yarn 1.21.11: GameRules moved to net.minecraft.world.level.gamerules.GameRules
		// and the typed getInt accessor is gone — only getValue(rule)→Object
		// remains. Cast Integer for the int rule.
		batchMaxCramming = (Integer) world.getGameRules().get(
				net.minecraft.world.level.gamerules.GameRules.MAX_ENTITY_CRAMMING);
		int pushedThisBatch = 0;
		for (int i = 0; i < count; i++) {
			Mob e = MOB_SCRATCH.get(i);
			double dx = ACCUM_DX[i];
			double dz = ACCUM_DZ[i];
			if (dx != 0.0 || dz != 0.0) {
				e.push(dx, 0.0, dz);
				pushedThisBatch++;
			}
			if (CALLER[i]) {
				((CrammingState) e).ferrite$setBatched(tick, CROWDED_COUNT[i]);
			}
		}
		MOB_SCRATCH.clear();

		diagBatches++;
		diagMobs += count;
		diagCallers += callers;
		diagPushed += pushedThisBatch;
	}

	private static void compute(int count) {
		RustBridge.computeCramming(
			CrammingHandoff.REQUEST_BUF,
			CrammingHandoff.RESULT_BUF,
			count
		);
		CrammingHandoff.readResults(count, ACCUM_DX, ACCUM_DZ, NEIGHBOR_COUNT, CROWDED_COUNT);
	}

	private static void maybeLogOverflow(int count) {
		long now = System.nanoTime();
		if (now - lastOverflowLogNs < OVERFLOW_LOG_MIN_GAP_NS) return;
		lastOverflowLogNs = now;
		LOGGER.warn("[cramming-dispatch] {} mobs exceeds MAX_ENTITIES={}, falling back to vanilla",
				count, CrammingHandoff.MAX_ENTITIES);
	}

	private static void maybeLogDiag() {
		long now = System.nanoTime();
		if (now - diagLastLogNs < 5_000_000_000L) return;
		diagLastLogNs = now;
		MonitorLog.info(
			"[cramming-dispatch] batches={} mobsTotal={}  callers={}  pushed={}  damaged={}  vanilla={}"
					+ "  pushableChecks={}  reruns={}",
			diagBatches, diagMobs, diagCallers, diagPushed, diagDamaged, diagVanilla,
			diagPushableChecks, diagReruns
		);
		diagBatches = 0;
		diagMobs = 0;
		diagCallers = 0;
		diagVanilla = 0;
		diagPushed = 0;
		diagDamaged = 0;
		diagPushableChecks = 0;
		diagReruns = 0;
	}
}
