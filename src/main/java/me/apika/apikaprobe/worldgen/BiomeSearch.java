package me.apika.apikaprobe.worldgen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
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
 * written here) and the same result. Only the tree is laid out flat: each
 * node's parameter ranges in one long[], children as contiguous index
 * ranges, each leaf's biome as an int instead of a key compared by its
 * identifier, and no iterator object per level descended.
 *
 * It takes over Biolith's lookup (VanillaCompat.getBiome, which searches
 * with the tree's own node distance) when the tree could be flattened;
 * anything else goes to Biolith.
 *
 * Oracle: one lookup in ORACLE_EVERY is run again by Biolith's own code
 * from the same warm-start state and compared: both leaves, both
 * distances and the state left for the next search.
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
	private static volatile String disabledReason;

	/** Set while the oracle runs Biolith's own search, so the hook stands aside. */
	private static final ThreadLocal<Boolean> BYPASS = new ThreadLocal<>();

	// Biolith's and the tree's pieces (not public), found once.
	private static volatile boolean resolved;
	private static Class<?> subTreeClass;
	private static Class<?> leafClass;
	private static Field indexField;
	private static Field rootField;
	private static Field childrenField;
	private static Field parameterSpaceField;
	private static Field valueField;
	private static Field ultimateStateField;
	private static Field penultimateStateField;
	private static MethodHandle original;
	private static MethodHandle fittest2;
	private static MethodHandle fittest4;
	private static MethodHandle ultimateOut;
	private static MethodHandle ultimateDistanceOut;
	private static MethodHandle penultimateOut;
	private static MethodHandle penultimateDistanceOut;

	/** Flattened trees, by tree identity. */
	private static final Map<Object, Flat> FLAT = Collections.synchronizedMap(new WeakHashMap<>());

	public static boolean bypassed() {
		return BYPASS.get() != null;
	}

	/**
	 * Biolith's lookup result for this climate point, or null to let
	 * Biolith run. parameters is the Climate.ParameterList it searches.
	 */
	public static Object search(Climate.ParameterList<?> parameters, Climate.TargetPoint point) {
		if (!ENABLED || disabledReason != null) return null;
		if (!resolved && !resolve()) return null;
		Object tree;
		try {
			tree = indexField.get(parameters);
		} catch (IllegalAccessException e) {
			disable(e.toString());
			return null;
		}
		Flat flat = flat(tree);
		if (flat == null) {
			fallbacks.increment();
			return null;
		}
		searches.increment();
		long[] p = { point.temperature(), point.humidity(), point.continentalness(), point.erosion(),
				point.depth(), point.weirdness(), 0L };
		try {
			if (ThreadLocalRandom.current().nextInt(ORACLE_EVERY) != 0) return flat.search(p);
			Object savedUlt = flat.ultimateState.get();
			Object savedPen = flat.penultimateState.get();
			Object mine = flat.search(p);
			check(parameters, point, flat, savedUlt, savedPen, mine);
			return mine;
		} catch (Throwable t) {
			disable("search failed: " + t);
			return null;
		}
	}

	private static void check(Climate.ParameterList<?> parameters, Climate.TargetPoint point, Flat flat,
			Object savedUlt, Object savedPen, Object mine) throws Throwable {
		Object myUlt = flat.ultimateState.get();
		Object myPen = flat.penultimateState.get();
		flat.ultimateState.set(savedUlt);
		flat.penultimateState.set(savedPen);
		Object theirs;
		BYPASS.set(Boolean.TRUE);
		try {
			theirs = original.invoke(point, parameters);
		} finally {
			BYPASS.remove();
		}
		oracleChecks.increment();
		boolean same = (Object) ultimateOut.invoke(mine) == (Object) ultimateOut.invoke(theirs)
				&& (Object) penultimateOut.invoke(mine) == (Object) penultimateOut.invoke(theirs)
				&& (long) ultimateDistanceOut.invoke(mine) == (long) ultimateDistanceOut.invoke(theirs)
				&& (long) penultimateDistanceOut.invoke(mine) == (long) penultimateDistanceOut.invoke(theirs)
				&& flat.ultimateState.get() == myUlt && flat.penultimateState.get() == myPen;
		if (!same) {
			oracleMismatches.increment();
			if (oracleMismatches.sum() <= 5) {
				ExampleMod.LOGGER.warn("[biome-search] MISMATCH: flat {}, Biolith {}", mine, theirs);
			}
		}
	}

	private static synchronized boolean resolve() {
		if (resolved) return true;
		if (disabledReason != null) return false;
		try {
			ClassLoader loader = Climate.class.getClassLoader();
			Class<?> rtree = Class.forName("net.minecraft.world.level.biome.Climate$RTree", false, loader);
			Class<?> node = Class.forName("net.minecraft.world.level.biome.Climate$RTree$Node", false, loader);
			subTreeClass = Class.forName("net.minecraft.world.level.biome.Climate$RTree$SubTree", false, loader);
			leafClass = Class.forName("net.minecraft.world.level.biome.Climate$RTree$Leaf", false, loader);
			for (Field f : rtree.getDeclaredFields()) {
				if (f.getType() != ThreadLocal.class) continue;
				if (f.getName().contains("previousUltimateNode")) ultimateStateField = f;
				else if (f.getName().contains("previousPenultimateNode")) penultimateStateField = f;
			}
			if (ultimateStateField == null || penultimateStateField == null) {
				disable("Biolith's search state is not on Climate.RTree");
				return false;
			}
			ultimateStateField.setAccessible(true);
			penultimateStateField.setAccessible(true);
			indexField = field(Climate.ParameterList.class, "index");
			rootField = field(rtree, "root");
			childrenField = field(subTreeClass, "children");
			parameterSpaceField = field(node, "parameterSpace");
			valueField = field(leafClass, "value");

			MethodHandles.Lookup lookup = MethodHandles.lookup();
			Class<?> compat = Class.forName("com.terraformersmc.biolith.impl.compat.VanillaCompat", false, loader);
			Method getBiome = compat.getMethod("getBiome", Climate.TargetPoint.class, Climate.ParameterList.class);
			original = lookup.unreflect(getBiome);

			Class<?> nodes = Class.forName("com.terraformersmc.biolith.api.biome.BiolithFittestNodes", false, loader);
			Constructor<?> c2 = nodes.getConstructor(leafClass, long.class);
			Constructor<?> c4 = nodes.getConstructor(leafClass, long.class, leafClass, long.class);
			fittest2 = lookup.unreflectConstructor(c2);
			fittest4 = lookup.unreflectConstructor(c4);
			ultimateOut = lookup.unreflect(nodes.getMethod("ultimate"));
			ultimateDistanceOut = lookup.unreflect(nodes.getMethod("ultimateDistance"));
			penultimateOut = lookup.unreflect(nodes.getMethod("penultimate"));
			penultimateDistanceOut = lookup.unreflect(nodes.getMethod("penultimateDistance"));
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

	/** The tree last looked up: one per dimension, so nearly always a hit without a lock. */
	private static volatile Flat last;

	private static Flat flat(Object tree) {
		Flat l = last;
		if (l != null && l.tree.get() == tree) return l.valid ? l : null;
		Flat f = FLAT.get(tree);
		if (f == null) {
			synchronized (FLAT) {
				f = FLAT.get(tree);
				if (f == null) {
					f = Flat.build(tree);
					FLAT.put(tree, f);
				}
			}
		}
		last = f;
		return f.valid ? f : null;
	}

	/** The tree in arrays. Node 0 is the root; a node's children are contiguous. */
	private static final class Flat {
		boolean valid;
		/** The tree, weakly: FLAT's key must stay collectable. */
		WeakReference<Object> tree;
		/** min, max per dimension per node: node n, dimension i at 2 * (n * DIMS + i). */
		long[] bounds;
		/** First child and one past the last; firstChild is -1 for a leaf. */
		int[] firstChild;
		int[] endChild;
		/** The node objects, for results and the warm start. */
		Object[] nodes;
		/** Biome id per leaf: equal ids for equal biome identifiers. */
		int[] biome;
		int maxDepth;
		/** Leaf object to node index, for the warm start. */
		final Map<Object, Integer> leafIndex = new IdentityHashMap<>();
		/** Identifier to id; Biolith compares a missing leaf as biolith:null. */
		final Map<Identifier, Integer> ids = new HashMap<>();
		int nullId;
		ThreadLocal<Object> ultimateState;
		ThreadLocal<Object> penultimateState;
		@SuppressWarnings("unchecked")
		static Flat build(Object tree) {
			Flat f = new Flat();
			f.tree = new WeakReference<>(tree);
			try {
				f.ultimateState = (ThreadLocal<Object>) ultimateStateField.get(tree);
				f.penultimateState = (ThreadLocal<Object>) penultimateStateField.get(tree);
				List<Object> order = new ArrayList<>();
				List<Integer> depth = new ArrayList<>();
				List<int[]> ranges = new ArrayList<>();
				order.add(rootField.get(tree));
				depth.add(0);
				ranges.add(null);
				// Breadth first, so each node's children get consecutive indices.
				ArrayDeque<Integer> queue = new ArrayDeque<>();
				queue.add(0);
				while (!queue.isEmpty()) {
					int n = queue.poll();
					Object node = order.get(n);
					if (subTreeClass.isInstance(node)) {
						Object[] children = (Object[]) childrenField.get(node);
						if (children.length == 0) return f;
						int start = order.size();
						for (Object c : children) {
							order.add(c);
							depth.add(depth.get(n) + 1);
							ranges.add(null);
							queue.add(order.size() - 1);
						}
						ranges.set(n, new int[] { start, order.size() });
					} else if (!leafClass.isInstance(node)) {
						return f;
					}
				}
				int count = order.size();
				f.bounds = new long[count * DIMS * 2];
				f.firstChild = new int[count];
				f.endChild = new int[count];
				f.nodes = order.toArray();
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
					int[] r = ranges.get(n);
					if (r == null) {
						f.firstChild[n] = -1;
						f.endChild[n] = -1;
						// Biolith's comparison throws on a leaf without a biome key; leave those trees to it.
						if (!(valueField.get(node) instanceof Holder<?> holder)) return f;
						ResourceKey<?> key = holder.unwrapKey().orElse(null);
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
			Integer known = ids.get(id);
			if (known != null) return known;
			int next = ids.size();
			ids.put(id, next);
			return next;
		}

		/** Climate.RTree.Node.distance: the sum over dimensions of the squared distance to the range. */
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
		Object search(long[] p) throws Throwable {
			if (firstChild[0] < 0) {
				// A tree that is one leaf: Biolith returns it and leaves its state alone.
				return fittest2.invoke(nodes[0], distance(0, p));
			}
			int ult = indexOf(ultimateState.get());
			int pen = indexOf(penultimateState.get());
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
			Object u = ult >= 0 ? nodes[ult] : null;
			Object q = pen >= 0 ? nodes[pen] : null;
			ultimateState.set(u);
			penultimateState.set(q);
			return q == null ? fittest2.invoke(u, ultD) : fittest4.invoke(u, ultD, q, penD);
		}

		private int biomeOf(int n) {
			return n >= 0 ? biome[n] : nullId;
		}

		private int indexOf(Object leaf) {
			if (leaf == null) return -1;
			Integer i = leafIndex.get(leaf);
			if (i == null) throw new IllegalStateException("warm-start leaf is not in this tree");
			return i;
		}
	}

	public static String status() {
		return String.format("[biome-search] biome-search=%s%s searches=%d fallbacks=%d oracleChecks=%d oracleMismatches=%d",
				ENABLED ? "on" : "off", disabledReason == null ? "" : " (unavailable: " + disabledReason + ")",
				searches.sum(), fallbacks.sum(), oracleChecks.sum(), oracleMismatches.sum());
	}
}
