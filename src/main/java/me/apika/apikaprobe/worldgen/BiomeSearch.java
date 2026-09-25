package me.apika.apikaprobe.worldgen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Climate;

/**
 * Biolith's biome search, on flat arrays.
 *
 * Biolith replaces the climate tree's search with its own
 * (Climate.RTree.biolith$searchTreeGet): it finds the best and the
 * second-best biome, prunes against the second-best distance, and starts
 * from the two leaves the previous search on the thread found. With
 * Terralith that search was 11% of the worldgen workers' time on the CI
 * worldgen bench, nearly all of it filling chunk biomes, one search per
 * quart position.
 *
 * This is the same search: the same visiting order, the same strict
 * comparisons, the same warm start (Biolith's own thread-locals, read and
 * written here) and the same result object. Only the tree is laid out
 * flat: each node's parameter ranges in one long[], children as
 * contiguous index ranges, each leaf's biome as an int instead of a key
 * compared by its identifier, and no iterator per level descended.
 *
 * It runs only when the distance is the tree's own node distance and the
 * tree could be flattened; anything else goes to Biolith's search.
 *
 * Oracle: every ORACLE_EVERY-th search is run again by Biolith's own
 * code from the same warm-start state and compared: both leaves and both
 * distances.
 * Off: -Dferrite.worldgen.biomesearch=false or
 * /ferrite worldgen biome-search off.
 */
public final class BiomeSearch {
	private BiomeSearch() {}

	public static volatile boolean ENABLED = !"false".equals(System.getProperty("ferrite.worldgen.biomesearch"));

	private static final int ORACLE_EVERY = 64;
	private static final int DIMS = 7;

	public static final LongAdder searches = new LongAdder();
	public static final LongAdder fallbacks = new LongAdder();
	public static final LongAdder oracleChecks = new LongAdder();
	public static final LongAdder oracleMismatches = new LongAdder();
	private static final AtomicLong calls = new AtomicLong();
	private static volatile String disabledReason;

	/** Set while the oracle runs Biolith's own search, so the hook stands aside. */
	private static final ThreadLocal<Boolean> BYPASS = new ThreadLocal<>();

	// Biolith's pieces, found once.
	private static volatile boolean resolved;
	private static Field ultimateField;
	private static Field penultimateField;
	private static MethodHandle fittest2;
	private static MethodHandle fittest4;
	private static MethodHandle original;
	private static Field ultimateOut;
	private static Field ultimateDistanceOut;
	private static Field penultimateOut;
	private static Field penultimateDistanceOut;
	// Vanilla's tree internals (private), found once.
	private static Field rootField;
	private static Field childrenField;
	private static Field parameterSpaceField;
	private static Field valueField;

	/** Flattened trees, one per RTree instance (weak by identity). */
	private static final Map<Object, Flat> FLAT = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	public static boolean bypassed() {
		return BYPASS.get() != null;
	}

	/**
	 * The search's result, or null to let Biolith's search run. tree is the
	 * Climate.RTree, metric the distance Biolith was handed.
	 */
	public static Object search(Object tree, Climate.TargetPoint point, Climate.DistanceMetric<?> metric) {
		if (!ENABLED || disabledReason != null) return null;
		if (!resolved && !resolve(tree)) return null;
		Flat flat = flat(tree);
		if (flat == null || !flat.usesNodeDistance(metric)) {
			fallbacks.increment();
			return null;
		}
		searches.increment();
		try {
			@SuppressWarnings("unchecked")
			ThreadLocal<Object> ultTl = (ThreadLocal<Object>) ultimateField.get(tree);
			@SuppressWarnings("unchecked")
			ThreadLocal<Object> penTl = (ThreadLocal<Object>) penultimateField.get(tree);
			boolean check = calls.getAndIncrement() % ORACLE_EVERY == 0;
			Object savedUlt = check ? ultTl.get() : null;
			Object savedPen = check ? penTl.get() : null;
			Object result = flat.search(point.toParameterArray(), ultTl, penTl);
			if (check) checkAgainstBiolith(tree, point, metric, ultTl, penTl, savedUlt, savedPen, result);
			return result;
		} catch (Throwable t) {
			disable("search failed: " + t);
			return null;
		}
	}

	private static void checkAgainstBiolith(Object tree, Climate.TargetPoint point, Climate.DistanceMetric<?> metric,
			ThreadLocal<Object> ultTl, ThreadLocal<Object> penTl, Object savedUlt, Object savedPen, Object mine)
			throws Throwable {
		Object myUlt = ultTl.get();
		Object myPen = penTl.get();
		ultTl.set(savedUlt);
		penTl.set(savedPen);
		Object theirs;
		BYPASS.set(Boolean.TRUE);
		try {
			theirs = original.invoke(tree, point, metric);
		} finally {
			BYPASS.remove();
		}
		oracleChecks.increment();
		boolean same = ultimateOut.get(mine) == ultimateOut.get(theirs)
				&& penultimateOut.get(mine) == penultimateOut.get(theirs)
				&& ultimateDistanceOut.getLong(mine) == ultimateDistanceOut.getLong(theirs)
				&& penultimateDistanceOut.getLong(mine) == penultimateDistanceOut.getLong(theirs)
				&& ultTl.get() == myUlt && penTl.get() == myPen;
		if (!same) {
			oracleMismatches.increment();
			if (oracleMismatches.sum() <= 5) {
				ExampleMod.LOGGER.warn("[biome-search] MISMATCH: flat {}/{} ({}/{}), Biolith {}/{} ({}/{})",
						ultimateOut.get(mine), penultimateOut.get(mine),
						ultimateDistanceOut.getLong(mine), penultimateDistanceOut.getLong(mine),
						ultimateOut.get(theirs), penultimateOut.get(theirs),
						ultimateDistanceOut.getLong(theirs), penultimateDistanceOut.getLong(theirs));
			}
		}
	}

	private static synchronized boolean resolve(Object tree) {
		if (resolved) return true;
		if (disabledReason != null) return false;
		try {
			Class<?> rtree = Climate.RTree.class;
			for (Field f : rtree.getDeclaredFields()) {
				if (f.getType() != ThreadLocal.class) continue;
				if (f.getName().contains("previousUltimateNode")) ultimateField = f;
				else if (f.getName().contains("previousPenultimateNode")) penultimateField = f;
			}
			if (ultimateField == null || penultimateField == null) {
				disable("Biolith's search state not found on Climate.RTree");
				return false;
			}
			ultimateField.setAccessible(true);
			penultimateField.setAccessible(true);
			Class<?> nodes = Class.forName("com.terraformersmc.biolith.api.biome.BiolithFittestNodes");
			MethodHandles.Lookup lookup = MethodHandles.lookup();
			Class<?> leaf = Climate.RTree.Leaf.class;
			fittest2 = lookup.findConstructor(nodes, MethodType.methodType(void.class, leaf, long.class));
			fittest4 = lookup.findConstructor(nodes,
					MethodType.methodType(void.class, leaf, long.class, leaf, long.class));
			ultimateOut = field(nodes, "ultimate");
			ultimateDistanceOut = field(nodes, "ultimateDistance");
			penultimateOut = field(nodes, "penultimate");
			penultimateDistanceOut = field(nodes, "penultimateDistance");
			original = lookup.findVirtual(rtree, "biolith$searchTreeGet",
					MethodType.methodType(nodes, Climate.TargetPoint.class, Climate.DistanceMetric.class));
			rootField = field(rtree, "root");
			childrenField = field(Climate.RTree.SubTree.class, "children");
			parameterSpaceField = field(Climate.RTree.Node.class, "parameterSpace");
			valueField = field(leaf, "value");
			resolved = true;
			ExampleMod.LOGGER.info("[biome-search] Biolith's biome search runs on flat arrays");
			return true;
		} catch (Throwable t) {
			disable("Biolith's search not recognised: " + t);
			return false;
		}
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	private static void disable(String why) {
		if (disabledReason == null) {
			disabledReason = why;
			ExampleMod.LOGGER.warn("[biome-search] off: {}", why);
		}
	}

	private static Flat flat(Object tree) {
		Flat f = FLAT.get(tree);
		if (f != null) return f.valid ? f : null;
		synchronized (FLAT) {
			f = FLAT.get(tree);
			if (f == null) {
				f = Flat.build(tree);
				FLAT.put(tree, f);
			}
		}
		return f.valid ? f : null;
	}

	/** The tree in arrays. Node 0 is the root; a node's children are contiguous. */
	private static final class Flat {
		boolean valid;
		/** min, max per dimension per node: node n, dimension i at 2 * (n * DIMS + i). */
		long[] bounds;
		/** First child and one past the last; firstChild < 0 for a leaf. */
		int[] firstChild;
		int[] endChild;
		/** The Leaf object per leaf node, for results and the warm start. */
		Object[] leaves;
		/** Biome id per leaf node: equal ids for equal biome identifiers. */
		int[] biome;
		int maxDepth;
		/** Leaf object to node index, for the warm start. */
		final Map<Object, Integer> leafIndex = new java.util.IdentityHashMap<>();
		/** Identifier to id, including Biolith's placeholder for "no leaf". */
		final Map<Identifier, Integer> ids = new HashMap<>();
		int nullId;
		/** The metric object seen to be node distance, checked once. */
		volatile Object nodeMetric;

		static Flat build(Object tree) {
			Flat f = new Flat();
			try {
				Object root = rootField.get(tree);
				List<Object> order = new ArrayList<>();
				List<Integer> depth = new ArrayList<>();
				order.add(root);
				depth.add(0);
				// Breadth-first, so each node's children get consecutive indices.
				ArrayDeque<Integer> queue = new ArrayDeque<>();
				queue.add(0);
				int[][] ranges = new int[1][];
				List<int[]> childRanges = new ArrayList<>();
				childRanges.add(null);
				while (!queue.isEmpty()) {
					int n = queue.poll();
					Object node = order.get(n);
					if (node instanceof Climate.RTree.SubTree<?>) {
						Object[] children = (Object[]) childrenField.get(node);
						if (children.length == 0) return f;
						int start = order.size();
						for (Object c : children) {
							order.add(c);
							depth.add(depth.get(n) + 1);
							childRanges.add(null);
							queue.add(order.size() - 1);
						}
						childRanges.set(n, new int[] { start, order.size() });
					} else if (!(node instanceof Climate.RTree.Leaf<?>)) {
						return f;
					}
				}
				int count = order.size();
				f.bounds = new long[count * DIMS * 2];
				f.firstChild = new int[count];
				f.endChild = new int[count];
				f.leaves = new Object[count];
				f.biome = new int[count];
				f.nullId = f.idOf(Identifier.fromNamespaceAndPath("biolith", "null"));
				for (int n = 0; n < count; n++) {
					Object node = order.get(n);
					Climate.Parameter[] space = (Climate.Parameter[]) parameterSpaceField.get(node);
					if (space.length != DIMS) return f;
					for (int i = 0; i < DIMS; i++) {
						f.bounds[2 * (n * DIMS + i)] = space[i].min();
						f.bounds[2 * (n * DIMS + i) + 1] = space[i].max();
					}
					int[] r = childRanges.get(n);
					if (r == null) {
						f.firstChild[n] = -1;
						f.endChild[n] = -1;
						f.leaves[n] = node;
						Object value = valueField.get(node);
						if (!(value instanceof Holder<?> holder)) return f;
						ResourceKey<?> key = holder.unwrapKey().orElse(null);
						// Biolith's comparison throws on a leaf without a key; leave that to it.
						if (key == null) return f;
						f.biome[n] = f.idOf(key.identifier());
						f.leafIndex.put(node, n);
					} else {
						f.firstChild[n] = r[0];
						f.endChild[n] = r[1];
					}
					f.maxDepth = Math.max(f.maxDepth, depth.get(n));
				}
				f.valid = true;
			} catch (Throwable t) {
				ExampleMod.LOGGER.warn("[biome-search] tree not flattened: {}", t.toString());
			}
			return f;
		}

		private int idOf(Identifier id) {
			return ids.computeIfAbsent(id, k -> ids.size());
		}

		/**
		 * Whether metric is the tree's node distance. Checked once per
		 * metric object: on the root and every leaf, against the flat
		 * distance, for a fixed set of targets.
		 */
		boolean usesNodeDistance(Climate.DistanceMetric<?> metric) {
			if (metric == nodeMetric) return true;
			if (!(metric != null && metric.getClass().isSynthetic() || metric != null)) return false;
			synchronized (this) {
				if (metric == nodeMetric) return true;
				@SuppressWarnings("unchecked")
				Climate.DistanceMetric<Object> m = (Climate.DistanceMetric<Object>) metric;
				long[][] targets = {
						{ 0, 0, 0, 0, 0, 0, 0 },
						{ 5000, -3000, 7000, -9000, 1000, 2500, 0 },
						{ -10000, 10000, -12000, 11000, -2000, -8000, 0 },
				};
				try {
					for (long[] t : targets) {
						for (int n = 0; n < leaves.length; n++) {
							Object node = n == 0 ? rootField.getDeclaringClass().cast(null) : null;
							// Every leaf, plus the root.
							if (leaves[n] == null && n != 0) continue;
							Object nodeObj = leaves[n] != null ? leaves[n] : null;
							if (nodeObj == null) continue;
							@SuppressWarnings({ "unchecked", "rawtypes" })
							long theirs = m.distance((Climate.RTree.Node) nodeObj, t);
							if (theirs != distance(n, t)) return false;
						}
					}
				} catch (Throwable e) {
					return false;
				}
				nodeMetric = metric;
				return true;
			}
		}

		long distance(int n, long[] p) {
			long[] b = bounds;
			int o = 2 * n * DIMS;
			long sum = 0L;
			for (int i = 0; i < DIMS; i++) {
				long v = p[i];
				long above = v - b[o + 2 * i + 1];
				long below = b[o + 2 * i] - v;
				long d = above > 0L ? above : Math.max(below, 0L);
				sum += d * d;
			}
			return sum;
		}

		/** Biolith's search, step for step, on the arrays. */
		Object search(long[] p, ThreadLocal<Object> ultTl, ThreadLocal<Object> penTl) throws Throwable {
			if (firstChild[0] < 0) {
				// A tree that is one leaf: Biolith returns it without touching its state.
				return fittest2.invoke(leaves[0], distance(0, p));
			}
			Object ultObj = ultTl.get();
			Object penObj = penTl.get();
			int ult = indexOf(ultObj);
			int pen = indexOf(penObj);
			if (ult == -2 || pen == -2) {
				// A leaf from another tree: its distance comes from Biolith's code.
				throw new IllegalStateException("warm start from another tree");
			}
			long ultD = ult >= 0 ? distance(ult, p) : Long.MAX_VALUE;
			long penD = pen >= 0 ? distance(pen, p) : Long.MAX_VALUE;
			if (ultD > penD) {
				int t = ult;
				ult = pen;
				pen = t;
				long td = ultD;
				ultD = penD;
				penD = td;
			}
			int[] next = new int[maxDepth + 1];
			int[] end = new int[maxDepth + 1];
			int depth = 0;
			next[0] = firstChild[0];
			end[0] = endChild[0];
			while (next[depth] < end[depth]) {
				int n = next[depth]++;
				long nd = distance(n, p);
				while (firstChild[n] >= 0 && penD > nd) {
					depth++;
					next[depth] = firstChild[n];
					end[depth] = endChild[n];
					n = next[depth]++;
					nd = distance(n, p);
				}
				if (firstChild[n] < 0 && penD > nd) {
					if (ultD > nd) {
						if (biome[n] != biomeOf(ult)) {
							penD = ultD;
							pen = ult;
						}
						ultD = nd;
						ult = n;
					} else if (biome[n] != biomeOf(ult)) {
						penD = nd;
						pen = n;
					}
				}
				while (depth > 0 && next[depth] >= end[depth]) depth--;
			}
			Object u = ult >= 0 ? leaves[ult] : null;
			Object q = pen >= 0 ? leaves[pen] : null;
			ultTl.set(u);
			penTl.set(q);
			return q == null ? fittest2.invoke(u, ultD) : fittest4.invoke(u, ultD, q, penD);
		}

		private int biomeOf(int n) {
			return n >= 0 ? biome[n] : nullId;
		}

		/** -1 for none, -2 for a leaf not in this tree. */
		private int indexOf(Object leaf) {
			if (leaf == null) return -1;
			Integer i = leafIndex.get(leaf);
			return i == null ? -2 : i;
		}
	}

	public static String status() {
		return String.format("[biome-search] biome-search=%s%s searches=%d fallbacks=%d oracleChecks=%d oracleMismatches=%d",
				ENABLED ? "on" : "off", disabledReason == null ? "" : " (unavailable: " + disabledReason + ")",
				searches.sum(), fallbacks.sum(), oracleChecks.sum(), oracleMismatches.sum());
	}
}
