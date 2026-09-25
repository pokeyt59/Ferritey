package me.apika.apikaprobe.worldgen.chunk;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * When the server thread has to wait for a chunk to be generated, the
 * chunks around it are asked for too, without waiting.
 *
 * A mod that reads blocks past the loaded area from the server thread
 * (Roguelike Dungeons building its rooms) makes the server thread wait
 * while the chunk it touched is generated, then touches the next one and
 * waits again: on the CI worldgen bench one dungeon waited 10 to 12 times
 * in a row, 70-75 ms each, for 1.6-1.9 s of frozen ticks. Moving those
 * chunks ahead in the task queue changed nothing; each wait is the
 * generation itself.
 *
 * So when the server thread blocks on a chunk that is not ready, its
 * eight neighbours get a short loading ticket (like a player's view: they
 * generate in the background and never tick; the ticket lapses after 80
 * ticks). Chunks that are generated anyway come sooner and in parallel;
 * what is generated does not change. Every wait is timed (status).
 *
 * Off: -Dferrite.chunk.syncprefetch=false or
 * /ferrite worldgen sync-prefetch off.
 */
public final class SyncLoadPrefetch {
	private SyncLoadPrefetch() {}

	public static volatile boolean ENABLED = !"false".equals(System.getProperty("ferrite.chunk.syncprefetch"));

	private static TicketType ticketType;
	private static volatile Class<?> chunkExecutorClass;

	/** Each thread's last chunk generation request, until it blocks or asks again. */
	private static final ThreadLocal<Request> LAST = ThreadLocal.withInitial(Request::new);

	private static final class Request {
		long pos;
		CompletableFuture<?> future;
		ServerLevel level;
	}

	/** Recently prefetched positions, so a run of waits nearby does not ask again. */
	private static final long[] RECENT = new long[64];
	private static int recentNext;

	private static final LongAdder waits = new LongAdder();
	private static final LongAdder waitNanos = new LongAdder();
	private static final AtomicLong maxWaitNanos = new AtomicLong();
	private static final LongAdder over50ms = new LongAdder();
	private static final LongAdder prefetched = new LongAdder();

	public static void register() {
		ticketType = Registry.register(
				BuiltInRegistries.TICKET_TYPE,
				Identifier.fromNamespaceAndPath(ExampleMod.MOD_ID, "sync_prefetch"),
				new TicketType(80L, TicketType.FLAG_LOADING));
	}

	/** A chunk holder was asked to generate (GenerationChunkHolder.scheduleChunkGenerationTask). */
	public static void requested(long pos, CompletableFuture<?> future, ServerLevel level) {
		Request r = LAST.get();
		r.pos = pos;
		r.future = future;
		r.level = level;
	}

	/**
	 * A thread is about to block in an executor's managedBlock. If that is
	 * the server's chunk executor (a blocking chunk request: the game's
	 * getChunk and Lithium's replacement both ask the holder last) and the
	 * chunk is not ready, the wait is timed and the neighbours asked for.
	 */
	public static void beforeBlock(Object executor) {
		Request r = LAST.get();
		CompletableFuture<?> future = r.future;
		if (future == null) return;
		r.future = null;
		ServerLevel level = r.level;
		r.level = null;
		if (future.isDone() || !isChunkExecutor(executor)) return;
		long start = System.nanoTime();
		future.whenComplete((res, err) -> {
			long nanos = System.nanoTime() - start;
			waits.increment();
			waitNanos.add(nanos);
			maxWaitNanos.accumulateAndGet(nanos, Math::max);
			if (nanos >= 50_000_000L) over50ms.increment();
		});
		if (ENABLED && ticketType != null && level != null) prefetchAround(level, r.pos);
	}

	private static void prefetchAround(ServerLevel level, long center) {
		ChunkPos c = ChunkPos.unpack(center);
		int cx = c.x();
		int cz = c.z();
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				long pos = ChunkPos.pack(cx + dx, cz + dz);
				if (recentlyAsked(pos)) continue;
				level.getChunkSource().addTicketAndLoadWithRadius(ticketType, new ChunkPos(cx + dx, cz + dz), 0);
				prefetched.increment();
			}
		}
	}

	/** Server thread only. */
	private static boolean recentlyAsked(long pos) {
		for (long p : RECENT) {
			if (p == pos) return true;
		}
		RECENT[recentNext] = pos;
		recentNext = (recentNext + 1) % RECENT.length;
		return false;
	}

	private static boolean isChunkExecutor(Object executor) {
		Class<?> c = chunkExecutorClass;
		if (c == null) {
			if (!executor.getClass().getName().equals("net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")) {
				return false;
			}
			chunkExecutorClass = c = executor.getClass();
		}
		return executor.getClass() == c;
	}

	public static String status() {
		long n = waits.sum();
		return String.format("[sync-prefetch] sync-prefetch=%s waits=%d mean_ms=%.1f max_ms=%.0f over_50ms=%d total_ms=%.0f neighbours_asked=%d",
				ENABLED ? "on" : "off", n, n == 0 ? 0.0 : waitNanos.sum() / 1e6 / n, maxWaitNanos.get() / 1e6,
				over50ms.sum(), waitNanos.sum() / 1e6, prefetched.sum());
	}

	public static void reset() {
		waits.reset();
		waitNanos.reset();
		maxWaitNanos.set(0);
		over50ms.reset();
		prefetched.reset();
	}
}
