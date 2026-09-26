package me.apika.apikaprobe.worldgen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

import com.google.common.collect.ImmutableList;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Surface rule sequences without the tests that cannot pass in this chunk.
 *
 * Surface rules are built per chunk. Since 26.1 a biome test whose biomes
 * are all absent from the chunk (or all present) is built as a constant
 * false (or true) condition, but the sequence still walks it at every
 * block: a test, a false, the next rule. Terralith's surface rules are
 * long runs of biome tests, and the surface step, one chunk at a time on
 * the worldgen executor, was 12% of the worldgen workers' time on the CI
 * worldgen bench. (Fast Noise's own surface optimisation turns itself off
 * with Biolith installed.)
 *
 * When a sequence is built, a test rule whose condition is the constant
 * false is left out (it always returns null, and a sequence returns its
 * first non-null result), and one whose condition is the constant true is
 * replaced by its follow-up rule (the test passes without side effects,
 * so the result is the follow-up's). The constants are the two
 * non-capturing lambdas the game's biome test returns, recognised by
 * their class and asked once.
 *
 * Oracle: one sequence in ORACLE_EVERY keeps its full rule list too, and
 * every result it gives is compared with the full list's.
 * Off: -Dferrite.worldgen.surfaceprune=false or
 * /ferrite worldgen surface-prune off.
 */
public final class SurfaceRulePrune {
	private SurfaceRulePrune() {}

	public static volatile boolean ENABLED = !"false".equals(System.getProperty("ferrite.worldgen.surfaceprune"));

	private static final int ORACLE_EVERY = 64;
	private static final String CONSTANT_PREFIX = "net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource$$Lambda";

	public static final LongAdder sequences = new LongAdder();
	public static final LongAdder dropped = new LongAdder();
	public static final LongAdder unwrapped = new LongAdder();
	public static final LongAdder kept = new LongAdder();
	public static final LongAdder oracleChecks = new LongAdder();
	public static final LongAdder oracleMismatches = new LongAdder();

	private static volatile boolean resolved;
	private static volatile String disabledReason;
	private static Class<?> testRuleClass;
	private static MethodHandle condition;
	private static MethodHandle followup;
	private static MethodHandle test;
	private static MethodHandle tryApply;
	private static volatile Object constantFalse;
	private static volatile Object constantTrue;

	/** The full list of the sequence being built on this thread, for its oracle. */
	private static final ThreadLocal<List<?>> PENDING_FULL = new ThreadLocal<>();

	/** The rule list a new sequence gets: the pruned one, or rules itself. */
	public static List<?> prune(List<?> rules) {
		if (!ENABLED || disabledReason != null || (!resolved && !resolve())) return rules;
		sequences.increment();
		List<Object> out = null;
		try {
			for (int i = 0; i < rules.size(); i++) {
				Object rule = rules.get(i);
				Object replacement = rule;
				if (rule.getClass() == testRuleClass) {
					Object cond = (Object) condition.invoke(rule);
					int constant = constantValue(cond);
					if (constant == 0) replacement = null;
					else if (constant == 1) replacement = (Object) followup.invoke(rule);
				}
				if (replacement != rule && out == null) {
					out = new ArrayList<>(rules.size());
					for (int j = 0; j < i; j++) out.add(rules.get(j));
				}
				if (out != null && replacement != null) out.add(replacement);
				if (replacement == null) dropped.increment();
				else if (replacement != rule) unwrapped.increment();
				else kept.increment();
			}
		} catch (Throwable t) {
			disable("pruning failed: " + t);
			return rules;
		}
		if (out == null) return rules;
		if (ThreadLocalRandom.current().nextInt(ORACLE_EVERY) == 0) PENDING_FULL.set(rules);
		return ImmutableList.copyOf(out);
	}

	/** For a sequence just built on this thread: its full rule list if it is checked, else null. */
	public static List<?> takeFullList() {
		List<?> full = PENDING_FULL.get();
		if (full != null) PENDING_FULL.remove();
		return full;
	}

	/** A checked sequence gave result: the full list must give the same. */
	public static void check(List<?> full, BlockState result, int x, int y, int z) {
		try {
			BlockState expected = null;
			for (Object rule : full) {
				expected = (BlockState) tryApply.invoke(rule, x, y, z);
				if (expected != null) break;
			}
			oracleChecks.increment();
			if (expected != result) {
				oracleMismatches.increment();
				if (oracleMismatches.sum() <= 5) {
					ExampleMod.LOGGER.warn("[surface-prune] MISMATCH at {} {} {}: pruned {}, full {}", x, y, z, result, expected);
				}
			}
		} catch (Throwable t) {
			disable("check failed: " + t);
		}
	}

	/**
	 * For LegacyBiomeFold: 0 if cond is the game's constant false condition,
	 * 1 for its constant true, -1 for anything else or when the surface
	 * rule types are not recognised.
	 */
	static int constantOf(Object cond) {
		if (disabledReason != null || (!resolved && !resolve())) return -1;
		try {
			return constantValue(cond);
		} catch (Throwable t) {
			return -1;
		}
	}

	/** 0 for the constant false, 1 for the constant true, -1 for anything else. */
	private static int constantValue(Object cond) throws Throwable {
		if (cond == constantFalse) return 0;
		if (cond == constantTrue) return 1;
		Class<?> c = cond.getClass();
		if (!c.getName().startsWith(CONSTANT_PREFIX) || c.getDeclaredFields().length != 0) return -1;
		// A non-capturing lambda of the biome test: one of the two constants, asked once.
		boolean value = (boolean) test.invoke(cond);
		synchronized (SurfaceRulePrune.class) {
			if (value) {
				if (constantTrue == null) constantTrue = cond;
				return cond == constantTrue ? 1 : -1;
			}
			if (constantFalse == null) constantFalse = cond;
			return cond == constantFalse ? 0 : -1;
		}
	}

	private static synchronized boolean resolve() {
		if (resolved) return true;
		if (disabledReason != null) return false;
		try {
			ClassLoader loader = SurfaceRules.class.getClassLoader();
			testRuleClass = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$TestRule", false, loader);
			Class<?> conditionClass = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$Condition", false, loader);
			Class<?> ruleClass = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$SurfaceRule", false, loader);
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			condition = lookup.unreflect(accessible(testRuleClass.getDeclaredMethod("condition")));
			followup = lookup.unreflect(accessible(testRuleClass.getDeclaredMethod("followup")));
			test = lookup.unreflect(accessible(conditionClass.getDeclaredMethod("test")));
			tryApply = lookup.unreflect(accessible(ruleClass.getDeclaredMethod("tryApply", int.class, int.class, int.class)));
			resolved = true;
			return true;
		} catch (Throwable t) {
			disable("surface rule types not recognised: " + t);
			return false;
		}
	}

	private static Method accessible(Method m) {
		m.setAccessible(true);
		return m;
	}

	private static void disable(String why) {
		if (disabledReason == null) {
			disabledReason = why;
			ExampleMod.LOGGER.warn("[surface-prune] off: {}", why);
		}
	}

	public static String status() {
		return String.format("[surface-prune] surface-prune=%s%s sequences=%d rules_dropped=%d unwrapped=%d kept=%d oracleChecks=%d oracleMismatches=%d",
				ENABLED ? "on" : "off", disabledReason == null ? "" : " (unavailable: " + disabledReason + ")",
				sequences.sum(), dropped.sum(), unwrapped.sum(), kept.sum(), oracleChecks.sum(), oracleMismatches.sum());
	}
}
