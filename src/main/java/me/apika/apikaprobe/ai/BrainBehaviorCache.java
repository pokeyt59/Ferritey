package me.apika.apikaprobe.ai;

import java.util.Map;
import java.util.Set;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.schedule.Activity;

/**
 * Flat copy of a Brain's behavior table, for the two loops that walk it
 * every tick (see BrainBehaviorCacheMixin).
 *
 * Brain keeps its behaviors as priority (TreeMap) -> activity (HashMap) ->
 * behaviors (LinkedHashSet). startEachNonRunningBehavior walks all of it
 * to try every stopped behavior of an active activity, and
 * getRunningBehaviors walks all of it again to collect the running ones.
 * The table only changes in Brain.addActivity and removeAllBehaviors, so
 * a copy in plain arrays, in the same iteration order and rebuilt after
 * either call, walks the same behaviors in the same order. Which
 * activities are active, and each behavior's status, are still read live
 * at the same points as vanilla, so a behavior that switches activity
 * while the loop runs has the same effect.
 *
 * The oracle rebuilds the copy on 1 in N uses and logs any difference
 * from the cached one. Kill switch -Dferrite.ai.braincache=false;
 * /ferrite ai brain-cache on|off|status toggles it for A/B.
 */
public final class BrainBehaviorCache {

	public static volatile boolean ENABLED = !"false".equals(System.getProperty("ferrite.ai.braincache"));

	/** Oracle: rebuild and compare 1 in N uses; 0 disables. */
	public static final int ORACLE_RATE = Integer.getInteger("ferrite.ai.braincache.oracle", 1024);

	// Server-thread counters, read by /ferrite ai brain-cache status.
	public static long builds;
	public static long uses;
	public static long oracleChecks;
	public static long oracleMismatches;
	private static int sampleCounter;

	/** Activity of each (priority, activity) group, in vanilla iteration order. */
	public final Activity[] activities;
	/** Behaviors of each group, in their set's order. */
	public final BehaviorControl<?>[][] behaviors;

	private BrainBehaviorCache(Activity[] activities, BehaviorControl<?>[][] behaviors) {
		this.activities = activities;
		this.behaviors = behaviors;
	}

	public static BrainBehaviorCache build(
			Map<Integer, ? extends Map<Activity, ? extends Set<? extends BehaviorControl<?>>>> table) {
		builds++;
		int groups = 0;
		for (Map<Activity, ? extends Set<? extends BehaviorControl<?>>> byActivity : table.values()) {
			groups += byActivity.size();
		}
		Activity[] activities = new Activity[groups];
		BehaviorControl<?>[][] behaviors = new BehaviorControl<?>[groups][];
		int i = 0;
		for (Map<Activity, ? extends Set<? extends BehaviorControl<?>>> byActivity : table.values()) {
			for (Map.Entry<Activity, ? extends Set<? extends BehaviorControl<?>>> e : byActivity.entrySet()) {
				activities[i] = e.getKey();
				behaviors[i] = e.getValue().toArray(new BehaviorControl<?>[0]);
				i++;
			}
		}
		return new BrainBehaviorCache(activities, behaviors);
	}

	/**
	 * Returns the cache to use: {@code cached} when present, else a new
	 * build. On sampled uses, rebuilds and compares; a difference is logged
	 * and the fresh copy returned.
	 */
	public static BrainBehaviorCache use(BrainBehaviorCache cached,
			Map<Integer, ? extends Map<Activity, ? extends Set<? extends BehaviorControl<?>>>> table) {
		uses++;
		if (cached == null) return build(table);
		if (ORACLE_RATE > 0 && ++sampleCounter % ORACLE_RATE == 0) {
			oracleChecks++;
			BrainBehaviorCache fresh = build(table);
			if (!fresh.sameAs(cached)) {
				oracleMismatches++;
				ExampleMod.LOGGER.warn("[brain-cache] MISMATCH: cached behavior table differs from the brain's ({} vs {} groups)",
						cached.activities.length, fresh.activities.length);
			}
			return fresh;
		}
		return cached;
	}

	private boolean sameAs(BrainBehaviorCache other) {
		if (activities.length != other.activities.length) return false;
		for (int g = 0; g < activities.length; g++) {
			if (activities[g] != other.activities[g]) return false;
			BehaviorControl<?>[] a = behaviors[g];
			BehaviorControl<?>[] b = other.behaviors[g];
			if (a.length != b.length) return false;
			for (int k = 0; k < a.length; k++) {
				if (a[k] != b[k]) return false;
			}
		}
		return true;
	}
}
