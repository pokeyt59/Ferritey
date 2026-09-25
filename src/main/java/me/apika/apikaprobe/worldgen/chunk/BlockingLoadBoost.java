package me.apika.apikaprobe.worldgen.chunk;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;

/**
 * Chunks the server thread is waiting for go to the front of the chunk
 * task queue.
 *
 * When the server thread needs a chunk that is not loaded (a mod reading
 * blocks past the loaded area, a command, a teleport), it asks for it and
 * blocks until it is ready. Its generation waits in the same queue as
 * every other chunk: first in, first out within a ticket level, and the
 * synchronous worldgen steps (structure starts, surface, carvers,
 * features) of all chunks run one at a time. While players explore, that
 * queue holds the chunks around them, so the server thread waited behind
 * them: on the CI worldgen bench Roguelike Dungeons, which builds its
 * rooms on the server thread, froze ticks for 0.2-0.9 s this way.
 *
 * While the server thread waits for a chunk, queued tasks for chunks
 * within RADIUS of it (its own generation task and the ones that may hold
 * its neighbours) are moved to the front of the queue before the next
 * task is taken. Ticket levels are untouched, so no chunk loads further
 * or ticks because of this; only the order chunks are worked on changes,
 * as it already does with player movement.
 *
 * Every wait is timed, with the boost on or off (status).
 * Off: -Dferrite.chunk.syncboost=false or
 * /ferrite worldgen sync-load-boost off.
 */
public final class BlockingLoadBoost {
	private BlockingLoadBoost() {}

	public static volatile boolean ENABLED = !"false".equals(System.getProperty("ferrite.chunk.syncboost"));

	/** Chebyshev distance, in chunks, from a waited-for chunk to the tasks moved ahead. */
	private static final int RADIUS = 8;
	private static final long[] NONE = new long[0];

	/** Packed positions of the chunks the server thread is waiting for (copy on write). */
	private static volatile long[] waiting = NONE;

	private static final LongAdder waits = new LongAdder();
	private static final LongAdder waitNanos = new LongAdder();
	private static final AtomicLong maxWaitNanos = new AtomicLong();
	private static final LongAdder over50ms = new LongAdder();
	private static final LongAdder moved = new LongAdder();

	/** A chunk future the server thread asked for; tracked until it completes. */
	public static void track(long pos, CompletableFuture<?> future) {
		if (future.isDone()) return;
		long start = System.nanoTime();
		add(pos);
		future.whenComplete((r, t) -> {
			remove(pos);
			long nanos = System.nanoTime() - start;
			waits.increment();
			waitNanos.add(nanos);
			maxWaitNanos.accumulateAndGet(nanos, Math::max);
			if (nanos >= 50_000_000L) over50ms.increment();
		});
	}

	private static synchronized void add(long pos) {
		long[] w = waiting;
		long[] next = Arrays.copyOf(w, w.length + 1);
		next[w.length] = pos;
		waiting = next;
	}

	private static synchronized void remove(long pos) {
		long[] w = waiting;
		for (int i = 0; i < w.length; i++) {
			if (w[i] != pos) continue;
			long[] next = new long[w.length - 1];
			System.arraycopy(w, 0, next, 0, i);
			System.arraycopy(w, i + 1, next, i, w.length - i - 1);
			waiting = next;
			return;
		}
	}

	/**
	 * Before the queue gives out its next task: moves queued tasks near a
	 * waited-for chunk to the front of the top level. Runs on the queue's
	 * own executor, like every other queue operation.
	 */
	public static void beforePop(List<Long2ObjectLinkedOpenHashMap<List<Runnable>>> levels, int top) {
		long[] w = waiting;
		if (w.length == 0 || !ENABLED || top >= levels.size()) return;
		Long2ObjectLinkedOpenHashMap<List<Runnable>> front = levels.get(top);
		for (int level = top; level < levels.size(); level++) {
			Long2ObjectLinkedOpenHashMap<List<Runnable>> queue = levels.get(level);
			if (queue.isEmpty()) continue;
			long found = 0;
			boolean any = false;
			for (LongIterator it = queue.keySet().iterator(); it.hasNext();) {
				long key = it.nextLong();
				if (near(key, w)) {
					found = key;
					any = true;
					break;
				}
			}
			if (!any) continue;
			if (level == top) {
				front.getAndMoveToFirst(found);
			} else if (!front.containsKey(found)) {
				front.putAndMoveToFirst(found, queue.remove(found));
			} else {
				continue;
			}
			moved.increment();
			return;
		}
	}

	private static boolean near(long key, long[] centers) {
		int x = (int) key;
		int z = (int) (key >>> 32);
		for (long c : centers) {
			if (Math.abs(x - (int) c) <= RADIUS && Math.abs(z - (int) (c >>> 32)) <= RADIUS) return true;
		}
		return false;
	}

	public static String status() {
		long n = waits.sum();
		return String.format("[sync-load-boost] sync-load-boost=%s waits=%d mean_ms=%.1f max_ms=%.0f over_50ms=%d tasks_moved_ahead=%d",
				ENABLED ? "on" : "off", n, n == 0 ? 0.0 : waitNanos.sum() / 1e6 / n, maxWaitNanos.get() / 1e6,
				over50ms.sum(), moved.sum());
	}

	public static void reset() {
		waits.reset();
		waitNanos.reset();
		maxWaitNanos.set(0);
		over50ms.reset();
		moved.reset();
	}
}
