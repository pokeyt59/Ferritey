package me.apika.apikaprobe.worldgen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Predicate;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Climate Rivers' biome test in surface rules, folded per chunk as the
 * game folds its own.
 *
 * Climate Rivers adds surface rules with its own biome condition
 * (LegacyBiomeConditionSource): is the block's biome one of these. It
 * looks the biome up at every block (a fuzzy BiomeManager lookup). The
 * game's own biome test, since 26.1, first checks the chunk's possible
 * biomes (SurfaceRules.Context.possibleBiomes, every biome the lookup can
 * return in this chunk): if none is in its set it is the constant false,
 * if all are it is the constant true, and only otherwise a per-block test.
 * On the CI worldgen bench Climate Rivers' lookups were about a tenth of
 * the surface step, which runs one chunk at a time.
 *
 * This applies the game's rule to Climate Rivers' test: same possible
 * set, same answer per biome (its own predicate on each possible biome),
 * and the game's own two constant conditions, so SurfaceRulePrune then
 * drops or unwraps the rule like any other.
 *
 * Oracle: one fold in ORACLE_EVERY keeps Climate Rivers' per-block test,
 * and every block it answers is compared with the constant it would have
 * been. Off: -Dferrite.worldgen.biomefold=false or
 * /ferrite worldgen biome-fold off.
 */
public final class LegacyBiomeFold {
	private LegacyBiomeFold() {}

	public static volatile boolean ENABLED = !"false".equals(System.getProperty("ferrite.worldgen.biomefold"));

	private static final int ORACLE_EVERY = 64;

	/** Climate Rivers' per-block condition, tagged with the constant it was not folded to. */
	public interface Checked {
		void ferrite$expect(int constant);
	}

	public static final LongAdder applied = new LongAdder();
	public static final LongAdder foldedFalse = new LongAdder();
	public static final LongAdder foldedTrue = new LongAdder();
	public static final LongAdder kept = new LongAdder();
	public static final LongAdder oracleChecks = new LongAdder();
	public static final LongAdder oracleMismatches = new LongAdder();

	private static volatile String disabledReason;
	/** SurfaceRules.LazyCondition.context: the context Climate Rivers' condition was built for. */
	private static MethodHandle conditionContext;
	/** Written last by resolve(), so the handles above are visible once it is set. */
	private static volatile MethodHandle possibleBiomes;
	private static Constructor<?> gameBiomeTest;
	private static volatile Object constantFalse;
	private static volatile Object constantTrue;

	/**
	 * What Climate Rivers' apply(context) returns: original (its per-block
	 * condition for that context), or the game's constant for this chunk.
	 */
	@SuppressWarnings("unchecked")
	public static Object fold(Object original, Predicate<ResourceKey<Biome>> test) {
		if (!ENABLED || disabledReason != null || test == null || original == null) return original;
		try {
			if (possibleBiomes == null && !resolve(original)) return original;
			Object context = (Object) conditionContext.invoke(original);
			Set<Holder<Biome>> possible = (Set<Holder<Biome>>) (Object) possibleBiomes.invoke(context);
			applied.increment();
			if (possible == null || possible.isEmpty()) {
				kept.increment();
				return original;
			}
			boolean any = false;
			boolean all = true;
			for (Holder<Biome> biome : possible) {
				if (biome.is(test)) any = true;
				else all = false;
			}
			int constant = !any ? 0 : all ? 1 : -1;
			if (constant < 0) {
				kept.increment();
				return original;
			}
			Object folded = constant == 0 ? constantFalse(context) : constantTrue(context, possible);
			if (folded == null) {
				kept.increment();
				return original;
			}
			if (original instanceof Checked checked && ThreadLocalRandom.current().nextInt(ORACLE_EVERY) == 0) {
				checked.ferrite$expect(constant);
				kept.increment();
				return original;
			}
			(constant == 0 ? foldedFalse : foldedTrue).increment();
			return folded;
		} catch (Throwable t) {
			disable("folding failed: " + t);
			return original;
		}
	}

	/** A checked condition answered result where the fold would have answered constant. */
	public static void check(int constant, boolean result) {
		oracleChecks.increment();
		if (result != (constant == 1)) {
			oracleMismatches.increment();
			if (oracleMismatches.sum() <= 5) {
				ExampleMod.LOGGER.warn("[biome-fold] MISMATCH: folded {}, per-block test {}", constant == 1, result);
			}
		}
	}

	/** The game's constant false: its biome test over no biomes. */
	private static Object constantFalse(Object context) throws ReflectiveOperationException {
		Object c = constantFalse;
		if (c != null) return c;
		c = gameCondition(HolderSet.empty(), context);
		if (SurfaceRulePrune.constantOf(c) != 0) {
			disable("the game's biome test over no biomes is not its constant false: " + c.getClass().getName());
			return null;
		}
		constantFalse = c;
		return c;
	}

	/** The game's constant true: its biome test over exactly the possible biomes. */
	private static Object constantTrue(Object context, Set<Holder<Biome>> possible) throws ReflectiveOperationException {
		Object c = constantTrue;
		if (c != null) return c;
		c = gameCondition(HolderSet.direct(new ArrayList<>(possible)), context);
		if (SurfaceRulePrune.constantOf(c) != 1) {
			disable("the game's biome test over the possible biomes is not its constant true: " + c.getClass().getName());
			return null;
		}
		constantTrue = c;
		return c;
	}

	@SuppressWarnings("unchecked")
	private static Object gameCondition(HolderSet<Biome> biomes, Object context) throws ReflectiveOperationException {
		Object source = gameBiomeTest.newInstance(biomes);
		return ((Function<Object, Object>) source).apply(context);
	}

	private static synchronized boolean resolve(Object condition) {
		if (possibleBiomes != null) return true;
		if (disabledReason != null) return false;
		try {
			Field contextField = null;
			for (Class<?> c = condition.getClass(); c != null && contextField == null; c = c.getSuperclass()) {
				for (Field f : c.getDeclaredFields()) {
					if (f.getName().equals("context")) contextField = f;
				}
			}
			if (contextField == null) throw new NoSuchFieldException("context on " + condition.getClass().getName());
			contextField.setAccessible(true);
			Field field = contextField.getType().getDeclaredField("possibleBiomes");
			field.setAccessible(true);
			Class<?> source = Class.forName("net.minecraft.world.level.levelgen.SurfaceRules$BiomeConditionSource", false,
					SurfaceRules.class.getClassLoader());
			Constructor<?> ctor = source.getDeclaredConstructor(HolderSet.class);
			ctor.setAccessible(true);
			gameBiomeTest = ctor;
			conditionContext = MethodHandles.lookup().unreflectGetter(contextField);
			possibleBiomes = MethodHandles.lookup().unreflectGetter(field);
			return true;
		} catch (Throwable t) {
			disable("surface rule context not recognised: " + t);
			return false;
		}
	}

	private static void disable(String why) {
		if (disabledReason == null) {
			disabledReason = why;
			ExampleMod.LOGGER.warn("[biome-fold] off: {}", why);
		}
	}

	public static String status() {
		return String.format("[biome-fold] biome-fold=%s%s applied=%d folded_false=%d folded_true=%d kept=%d oracleChecks=%d oracleMismatches=%d",
				ENABLED ? "on" : "off", disabledReason == null ? "" : " (unavailable: " + disabledReason + ")",
				applied.sum(), foldedFalse.sum(), foldedTrue.sum(), kept.sum(), oracleChecks.sum(), oracleMismatches.sum());
	}
}
