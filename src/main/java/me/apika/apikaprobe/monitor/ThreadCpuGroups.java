package me.apika.apikaprobe.monitor;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * CPU time per group of threads since a reset, for benches that compare
 * what kinds of work a change costs (scripts/ci-geyser.sh: Bedrock
 * players through Geyser against Java players):
 *   - server: the server thread;
 *   - worldgen: the game's worldgen workers ("Worker-*") or C2ME's;
 *   - geyser: Geyser's, Floodgate's and RakNet's threads (Bedrock
 *     translation and its network), matched before network;
 *   - network: Netty event loops (the Java connections);
 *   - other: everything else (GC, JIT, logging, spark...).
 *
 * Each thread's CPU time is read every 5 s once measuring starts, and at
 * every status, so a thread that ends between two reads loses at most
 * 5 s. Status also lists the busiest threads, to check the grouping.
 *
 * /ferrite bench cpu reset|status. Nothing runs until the first reset.
 */
public final class ThreadCpuGroups {
	private ThreadCpuGroups() {}

	public enum Group { SERVER, WORLDGEN, GEYSER, NETWORK, OTHER }

	private static final Map<Long, Long> atReset = new HashMap<>();
	private static final Map<Long, Long> last = new HashMap<>();
	private static final Map<Long, String> names = new HashMap<>();
	private static long resetNanos;
	private static boolean registered;

	/** The group a thread belongs to, by its name. */
	public static Group classify(String name) {
		if (name.equals("Server thread")) return Group.SERVER;
		String lower = name.toLowerCase(Locale.ROOT);
		if (name.startsWith("Worker-") || lower.contains("c2me")) return Group.WORLDGEN;
		if (lower.contains("geyser") || lower.contains("floodgate") || lower.contains("raknet") || lower.contains("bedrock")) {
			return Group.GEYSER;
		}
		if (lower.contains("netty") || lower.contains("epoll") || lower.contains("nio") || lower.contains("server io")) {
			return Group.NETWORK;
		}
		return Group.OTHER;
	}

	public static synchronized String reset() {
		if (!registered) {
			registered = true;
			ServerTickEvents.END_SERVER_TICK.register(server -> {
				if (server.getTickCount() % 100 == 0) sampleNow();
			});
		}
		sample();
		atReset.clear();
		atReset.putAll(last);
		resetNanos = System.nanoTime();
		return "[cpu] reset";
	}

	private static synchronized void sampleNow() {
		sample();
	}

	private static void sample() {
		ThreadMXBean mx = ManagementFactory.getThreadMXBean();
		if (!mx.isThreadCpuTimeSupported()) return;
		for (ThreadInfo info : mx.getThreadInfo(mx.getAllThreadIds())) {
			if (info == null) continue;
			long cpu = mx.getThreadCpuTime(info.getThreadId());
			if (cpu < 0) continue;
			last.put(info.getThreadId(), cpu);
			names.put(info.getThreadId(), info.getThreadName());
		}
	}

	public static synchronized String status() {
		if (resetNanos == 0) return "[cpu] not measuring: /ferrite bench cpu reset";
		sample();
		double seconds = Math.max(1e-9, (System.nanoTime() - resetNanos) / 1e9);
		long[] groups = new long[Group.values().length];
		List<Map.Entry<Long, Long>> perThread = new ArrayList<>();
		for (Map.Entry<Long, Long> e : last.entrySet()) {
			long since = e.getValue() - atReset.getOrDefault(e.getKey(), 0L);
			if (since <= 0) continue;
			groups[classify(names.get(e.getKey())).ordinal()] += since;
			perThread.add(Map.entry(e.getKey(), since));
		}
		long total = 0;
		StringBuilder sb = new StringBuilder(String.format("[cpu] %.1f s:", seconds));
		for (Group g : Group.values()) {
			long ms = groups[g.ordinal()] / 1_000_000L;
			total += ms;
			sb.append(String.format(" %s=%d ms (%.1f ms/s)", g.name().toLowerCase(Locale.ROOT), ms, ms / seconds));
		}
		sb.append(String.format(" total=%d ms (%.1f ms/s)", total, total / seconds));
		perThread.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		sb.append(" | top:");
		for (Map.Entry<Long, Long> e : perThread.subList(0, Math.min(10, perThread.size()))) {
			String name = names.get(e.getKey());
			sb.append(String.format(" [%s] %s %d ms;", classify(name).name().toLowerCase(Locale.ROOT), name, e.getValue() / 1_000_000L));
		}
		return sb.toString();
	}
}
