package me.apika.apikaprobe.worldgen;

import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.UnaryOperator;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Maps each node of the noise router once per NoiseChunk.
 *
 * Every NoiseChunk (one per chunk, one per structure height query) maps
 * the whole noise router through its wrap visitor. The router is a graph:
 * datapack functions such as Terralith's are referenced from many places,
 * and DensityFunction.mapAll walks it as a tree, rebuilding and wrapping
 * a shared subtree again at every reference (about 52,000 wrap calls per
 * NoiseChunk with the bench's mods). Each repeat ends in a wrap-map hit
 * that hashes the rebuilt subtree, so NoiseChunk construction came to
 * 14.7% of the worldgen workers' time, over half of it this mapping.
 *
 * The repeat is redundant. Mapping a node rebuilds it over its mapped
 * children and passes it to the NoiseChunk's wrap, which returns the one
 * instance it holds for equal nodes. On a second visit the children map
 * to the same instances as on the first (by induction), so the rebuilt
 * node equals the first one and wrap returns the first result. Here the
 * mapping is remembered by node identity and the repeat returns that
 * result directly. The NoiseChunk gets the same cache objects in the same
 * order, and wrap's side effects (registering interpolators and cell
 * caches) happen only on first visits, as before.
 *
 * Only the NoiseChunk's own wrap visitors are remembered: NoiseChunk
 * hands mapAll a Visitor that is this memo's, and the recursion checks
 * for it.
 *
 * Oracle: one NoiseChunk in ORACLE_EVERY maps every repeat anyway and
 * checks it gets the same instance back.
 * Off: -Dferrite.worldgen.mapmemo=false or /ferrite worldgen map-memo off.
 */
public final class MappingMemo {
	public static volatile boolean ENABLED = !"false".equals(System.getProperty("ferrite.worldgen.mapmemo"));

	private static final int ORACLE_EVERY = 64;
	private static final AtomicLong CREATED = new AtomicLong();
	public static final LongAdder repeats = new LongAdder();
	public static final LongAdder oracleChecks = new LongAdder();
	public static final LongAdder oracleMismatches = new LongAdder();

	private final IdentityHashMap<DensityFunction, DensityFunction> mapped = new IdentityHashMap<>();
	private final boolean checking;
	private long localRepeats;

	public MappingMemo() {
		checking = CREATED.getAndIncrement() % ORACLE_EVERY == 0;
	}

	public static long created() {
		return CREATED.get();
	}

	/** One step of mapAll's recursion: node through mapChildren and the wrap visitor. */
	public DensityFunction map(DensityFunction node, UnaryOperator<DensityFunction> step) {
		DensityFunction known = mapped.get(node);
		if (known == null) {
			DensityFunction result = step.apply(node);
			// A cycle would re-enter before this put; mapAll has none.
			mapped.put(node, result);
			return result;
		}
		localRepeats++;
		if ((localRepeats & 1023) == 0) {
			repeats.add(1024);
		}
		if (!checking) return known;
		DensityFunction again = step.apply(node);
		oracleChecks.increment();
		if (again != known) {
			oracleMismatches.increment();
			if (oracleMismatches.sum() <= 5) {
				ExampleMod.LOGGER.warn("[map-memo] MISMATCH on {}: remembered {}, mapped again to {}",
						node.getClass().getName(), known.getClass().getName(), again.getClass().getName());
			}
		}
		return again;
	}

	/**
	 * The visitor NoiseChunk hands mapAll: the NoiseChunk's own visitor,
	 * carrying the memo for the recursion to find.
	 */
	public static final class Visitor implements DensityFunction.Visitor {
		private final DensityFunction.Visitor delegate;
		private final MappingMemo memo;

		public Visitor(DensityFunction.Visitor delegate) {
			this.delegate = delegate;
			this.memo = new MappingMemo();
		}

		public DensityFunction.Visitor delegate() {
			return delegate;
		}

		public MappingMemo memo() {
			return memo;
		}

		@Override
		public DensityFunction apply(DensityFunction function) {
			return delegate.apply(function);
		}

		@Override
		public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder noise) {
			return delegate.visitNoise(noise);
		}
	}

	public static String status() {
		return String.format("[map-memo] map-memo=%s noiseChunks=%d repeatsSkipped>=%d oracleChecks=%d oracleMismatches=%d",
				ENABLED ? "on" : "off", CREATED.get(), repeats.sum(), oracleChecks.sum(), oracleMismatches.sum());
	}
}
