package me.apika.apikaprobe.worldgen;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * NoiseChunk's wrap map, with each node hashed once.
 *
 * Every NoiseChunk (one per chunk, and one per height query from
 * structure placement: NoiseBasedChunkGenerator.iterateNoiseColumn) maps
 * the whole noise router through NoiseChunk.wrap, which dedupes equal
 * nodes in a HashMap so equal subtrees share one cache. Density functions
 * are records, and a record's hashCode hashes its whole subtree, so each
 * lookup re-hashes everything below it down to the nearest cache marker:
 * big splines and datapack trees (Terralith) are hashed once for every
 * ancestor.
 *
 * mapAll rebuilds the tree bottom-up and hands wrap each node after its
 * children, so a node's children are always earlier results of this map.
 * Here a record's hash is built from its components' hashes, memoized by
 * identity, so every object is hashed once per NoiseChunk. Keys still
 * compare with the functions' own equals, in the same direction as
 * HashMap, so the map dedupes exactly the nodes vanilla's does and the
 * NoiseChunk ends up with the same cache objects. The hash follows equals
 * because it mirrors the record equality: components by their own hash
 * (records recursively, lists element-wise) and anything else by its own
 * hashCode.
 *
 * Oracle: one NoiseChunk in ORACLE_EVERY also keeps vanilla's map and
 * checks every answer against it (oracleMismatches in
 * /ferrite worldgen wrap-index status).
 * On: -Dferrite.worldgen.wrapindex=true or /ferrite worldgen wrap-index on.
 */
public final class DensityWrapIndex {
	// Off until the CI worldgen bench's height queries show it winning on a
	// real router: its per-node cost (a memo lookup, reflective component
	// reads) only beats re-hashing where subtrees between cache markers
	// are deep.
	public static volatile boolean ENABLED = Boolean.getBoolean("ferrite.worldgen.wrapindex");

	private static final int ORACLE_EVERY = 64;
	private static final AtomicLong CREATED = new AtomicLong();
	public static final LongAdder oracleChecks = new LongAdder();
	public static final LongAdder oracleMismatches = new LongAdder();

	private static final MethodHandle[] NOT_RECORD = new MethodHandle[0];
	private static final ClassValue<MethodHandle[]> COMPONENTS = new ClassValue<>() {
		@Override
		protected MethodHandle[] computeValue(Class<?> type) {
			if (!type.isRecord()) return NOT_RECORD;
			try {
				RecordComponent[] components = type.getRecordComponents();
				MethodHandle[] getters = new MethodHandle[components.length];
				for (int i = 0; i < components.length; i++) {
					// The field, not the accessor: record equals reads the
					// fields, and an accessor may be overridden.
					Field field = type.getDeclaredField(components[i].getName());
					field.setAccessible(true);
					getters[i] = MethodHandles.lookup().unreflectGetter(field)
							.asType(MethodType.methodType(Object.class, Object.class));
				}
				return getters;
			} catch (ReflectiveOperationException | RuntimeException e) {
				// Unreadable: the record's own hashCode, which follows its equals.
				return NOT_RECORD;
			}
		}
	};

	/** How many NoiseChunks used the index; read by the status command. */
	public static long created() {
		return CREATED.get();
	}

	private final HashMap<Key, DensityFunction> map = new HashMap<>();
	private final IdentityHashMap<Object, Integer> hashes = new IdentityHashMap<>();
	/** Vanilla's map, in oracle NoiseChunks only. */
	private final HashMap<DensityFunction, DensityFunction> vanilla;

	public DensityWrapIndex() {
		vanilla = CREATED.getAndIncrement() % ORACLE_EVERY == 0 ? new HashMap<>() : null;
	}

	/** Map.computeIfAbsent for NoiseChunk.wrap. */
	public DensityFunction computeIfAbsent(DensityFunction function,
			Function<? super DensityFunction, ? extends DensityFunction> wrapNew) {
		Key key = new Key(function, hash(function));
		DensityFunction result = map.get(key);
		boolean hit = result != null;
		if (!hit) {
			result = wrapNew.apply(function);
			if (result != null) map.put(key, result);
		}
		if (vanilla != null) check(function, result, hit);
		return result;
	}

	private void check(DensityFunction function, DensityFunction result, boolean hit) {
		oracleChecks.increment();
		DensityFunction expected = vanilla.get(function);
		boolean ok = expected == null ? !hit : expected == result;
		if (expected == null && result != null) vanilla.put(function, result);
		if (!ok) {
			oracleMismatches.increment();
			if (oracleMismatches.sum() <= 5) {
				ExampleMod.LOGGER.warn("[wrap-index] MISMATCH on {}: index {} ({}), vanilla {}",
						function.getClass().getName(), result == null ? null : result.getClass().getName(),
						hit ? "reused" : "new", expected == null ? "new" : expected.getClass().getName());
			}
		}
	}

	private int hash(Object o) {
		if (o == null) return 0;
		MethodHandle[] getters = COMPONENTS.get(o.getClass());
		if (getters == NOT_RECORD) {
			if (o instanceof List<?> list) {
				int h = 1;
				for (Object e : list) h = 31 * h + hash(e);
				return h;
			}
			return o.hashCode();
		}
		Integer known = hashes.get(o);
		if (known != null) return known;
		int h = o.getClass().hashCode();
		try {
			for (MethodHandle getter : getters) h = 31 * h + hash((Object) getter.invokeExact(o));
		} catch (Throwable t) {
			throw new IllegalStateException("reading a record component of " + o.getClass().getName(), t);
		}
		hashes.put(o, h);
		return h;
	}

	/** A function with its precomputed hash; equal as the functions are. */
	private static final class Key {
		final DensityFunction function;
		final int hash;

		Key(DensityFunction function, int hash) {
			this.function = function;
			this.hash = hash;
		}

		@Override
		public int hashCode() {
			return hash;
		}

		@Override
		public boolean equals(Object o) {
			// HashMap asks the probe: probe.equals(stored), as vanilla's map
			// asks function.equals(storedFunction).
			return o instanceof Key other && (function == other.function || function.equals(other.function));
		}
	}
}
