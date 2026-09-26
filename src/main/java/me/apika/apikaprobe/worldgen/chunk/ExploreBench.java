package me.apika.apikaprobe.worldgen.chunk;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Chunk requests for the CI worldgen bench (scripts/worldgen-drive.py),
 * standing in for players exploring.
 *
 * A player's view loads chunks through asynchronous tickets: the server
 * thread never waits for generation. /forceload is no model for that. It
 * loads each chunk synchronously in the command, so the bench froze the
 * server thread for seconds and measured its own stalls. Here each chunk
 * gets a short-lived loading ticket through the same asynchronous call
 * as Ferrite's pregen ({@link ChunkForcer}), and the time from request to
 * loaded chunk is recorded.
 *
 * Status also reports the worldgen workers' CPU time, for CPU per chunk.
 *
 * /ferrite bench explore add|status|reset. Nothing runs unless the
 * command is used.
 */
public final class ExploreBench {
	private ExploreBench() {}

	private static TicketType ticketType;
	private static final AtomicLong requested = new AtomicLong();
	private static final AtomicLong done = new AtomicLong();
	private static final AtomicLong failed = new AtomicLong();
	private static final List<Long> latenciesMs = Collections.synchronizedList(new ArrayList<>());
	/** Last CPU time seen per worldgen worker thread; kept after a worker ends. */
	private static final Map<Long, Long> workerCpuNanos = new HashMap<>();

	public static void register() {
		// Same shape as ChunkForcer's ticket: loads to FULL, expires 80 ticks
		// after the chunk is loaded, never makes the chunk tick.
		ticketType = Registry.register(
				BuiltInRegistries.TICKET_TYPE,
				Identifier.fromNamespaceAndPath(ExampleMod.MOD_ID, "explore_bench"),
				new TicketType(80L, TicketType.FLAG_LOADING));
	}

	/** Requests every chunk in the rectangle; returns how many. Server thread. */
	public static int add(ServerLevel level, int x1, int z1, int x2, int z2) {
		if (ticketType == null) return 0;
		int n = 0;
		for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
			for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
				long start = System.nanoTime();
				requested.incrementAndGet();
				level.getChunkSource()
						.addTicketAndLoadWithRadius(ticketType, new ChunkPos(x, z), 0)
						.whenComplete((res, err) -> {
							if (err != null) {
								failed.incrementAndGet();
								return;
							}
							done.incrementAndGet();
							latenciesMs.add((System.nanoTime() - start) / 1_000_000L);
						});
				n++;
			}
		}
		return n;
	}

	/** One line the bench driver parses. */
	public static String status() {
		List<Long> lat;
		synchronized (latenciesMs) {
			lat = new ArrayList<>(latenciesMs);
		}
		Collections.sort(lat);
		long median = lat.isEmpty() ? 0 : lat.get(lat.size() / 2);
		long p90 = lat.isEmpty() ? 0 : lat.get((int) (0.9 * (lat.size() - 1)));
		long max = lat.isEmpty() ? 0 : lat.get(lat.size() - 1);
		return String.format("[explore-bench] requested=%d done=%d failed=%d latency_ms median=%d p90=%d max=%d worker_cpu_ms=%d",
				requested.get(), done.get(), failed.get(), median, p90, max, workerCpuMillis());
	}

	/**
	 * CPU time of the worldgen worker threads ("Worker-*", or C2ME's) since the JVM
	 * started, including workers the pool has since retired: the bench
	 * takes the difference over a phase, per chunk delivered.
	 */
	private static synchronized long workerCpuMillis() {
		ThreadMXBean mx = ManagementFactory.getThreadMXBean();
		if (!mx.isThreadCpuTimeSupported()) return -1;
		for (ThreadInfo info : mx.getThreadInfo(mx.getAllThreadIds())) {
			if (info == null) continue;
			String name = info.getThreadName();
			// The game's worldgen pool, or C2ME's when it replaces the chunk system.
			if (!name.startsWith("Worker-") && !name.toLowerCase(java.util.Locale.ROOT).contains("c2me")) continue;
			long cpu = mx.getThreadCpuTime(info.getThreadId());
			if (cpu > 0) workerCpuNanos.put(info.getThreadId(), cpu);
		}
		long total = 0;
		for (long cpu : workerCpuNanos.values()) total += cpu;
		return total / 1_000_000L;
	}

	public static void reset() {
		requested.set(0);
		done.set(0);
		failed.set(0);
		latenciesMs.clear();
	}
}
