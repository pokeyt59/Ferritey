package me.apika.apikaprobe.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.server.MinecraftServer;

/**
 * Samples the server thread's stack while a tick runs long, and logs what
 * it was doing: a profiler that only looks at slow ticks.
 *
 * A daemon thread reads the server's tick counter every 10 ms. When the
 * counter has not moved for longer than the threshold (the gap between
 * ticks is at most 50 ms of waiting plus the tick itself), it samples the
 * server thread's stack every 10 ms until the next tick starts, then logs
 * one "[slow-tick]" line: how long the gap lasted and the most common
 * stacks. Unlike an execution-sample profiler, it also sees a server
 * thread that is waiting (for a chunk, a lock, the disk).
 *
 * /ferrite tickwatch <ms>|off|status. Off unless started.
 */
public final class SlowTickWatchdog {
	private SlowTickWatchdog() {}

	private static final int FRAMES = 14;
	private static Thread thread;
	private static volatile boolean running;
	private static volatile int thresholdMs;
	private static final List<String> recent = new ArrayList<>();
	private static long reports;

	public static synchronized void start(MinecraftServer server, int ms) {
		thresholdMs = ms;
		if (thread != null && thread.isAlive()) return;
		running = true;
		thread = new Thread(() -> loop(server), "Ferrite tick watchdog");
		thread.setDaemon(true);
		thread.start();
	}

	public static synchronized void stop() {
		running = false;
		thread = null;
	}

	public static synchronized String status() {
		StringBuilder sb = new StringBuilder(String.format("[tickwatch] %s, threshold %d ms, %d slow tick(s) logged",
				running ? "on" : "off", thresholdMs, reports));
		for (String r : recent) sb.append('\n').append(r);
		return sb.toString();
	}

	private static void loop(MinecraftServer server) {
		Thread serverThread = server.getRunningThread();
		int lastTick = server.getTickCount();
		long lastChange = System.nanoTime();
		Map<String, Integer> stacks = null;
		int samples = 0;
		while (running && server.isRunning()) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				return;
			}
			int tick = server.getTickCount();
			long now = System.nanoTime();
			if (tick != lastTick) {
				if (stacks != null) report(lastTick, (now - lastChange) / 1_000_000L, stacks, samples);
				stacks = null;
				samples = 0;
				lastTick = tick;
				lastChange = now;
				continue;
			}
			if ((now - lastChange) / 1_000_000L < thresholdMs) continue;
			if (stacks == null) stacks = new HashMap<>();
			stacks.merge(describe(serverThread.getStackTrace()), 1, Integer::sum);
			samples++;
		}
	}

	private static String describe(StackTraceElement[] stack) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(FRAMES, stack.length); i++) {
			if (i > 0) sb.append(" < ");
			String cls = stack[i].getClassName();
			sb.append(cls.substring(cls.lastIndexOf('.') + 1)).append('.').append(stack[i].getMethodName());
		}
		return sb.toString();
	}

	private static void report(int tick, long gapMs, Map<String, Integer> stacks, int samples) {
		List<Map.Entry<String, Integer>> top = new ArrayList<>(stacks.entrySet());
		top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		StringBuilder sb = new StringBuilder(String.format(
				"[slow-tick] tick %d: %d ms between ticks, %d samples;", tick, gapMs, samples));
		for (Map.Entry<String, Integer> e : top.subList(0, Math.min(3, top.size()))) {
			sb.append(" | ").append(e.getValue()).append("x ").append(e.getKey());
		}
		String line = sb.toString();
		ExampleMod.LOGGER.info(line);
		synchronized (SlowTickWatchdog.class) {
			reports++;
			recent.add(line);
			if (recent.size() > 5) recent.remove(0);
		}
	}
}
