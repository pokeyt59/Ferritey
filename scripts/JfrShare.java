import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Share of JFR execution samples inside named groups of packages, per
 * thread group (grouped as monitor/ThreadCpuGroups groups threads).
 * Run with: java scripts/JfrShare.java run.jfr name=prefix,prefix ...
 * A sample counts for a package group when any frame of its stack is
 * under one of the group's prefixes, so nested calls count once: an
 * upper bound on what that code cost, callees included. A prefix that
 * starts with "$" matches method names instead: mixin handlers merged
 * into game classes are named handler$...$<mod id>$..., so "$goml$"
 * counts GOML's hooks in vanilla code too.
 */
public class JfrShare {
	private static final String[] THREAD_GROUPS = { "server", "worldgen", "geyser", "network", "other", "all" };

	public static void main(String[] args) throws Exception {
		Map<String, String[]> packages = new LinkedHashMap<>();
		for (int i = 1; i < args.length; i++) {
			String[] kv = args[i].split("=", 2);
			packages.put(kv[0], kv[1].split(","));
		}
		Map<String, long[]> counts = new LinkedHashMap<>();
		for (String g : THREAD_GROUPS) counts.put(g, new long[packages.size() + 1]);
		try (RecordingFile file = new RecordingFile(Path.of(args[0]))) {
			while (file.hasMoreEvents()) {
				RecordedEvent event = file.readEvent();
				if (!"jdk.ExecutionSample".equals(event.getEventType().getName())) continue;
				RecordedThread thread = event.getThread("sampledThread");
				RecordedStackTrace stack = event.getStackTrace();
				if (stack == null) continue;
				List<String> types = new ArrayList<>();
				List<String> methods = new ArrayList<>();
				for (RecordedFrame frame : stack.getFrames()) {
					RecordedMethod method = frame.getMethod();
					if (method == null) continue;
					if (method.getType() != null) types.add(method.getType().getName());
					if (method.getName() != null) methods.add(method.getName());
				}
				String group = classify(thread == null || thread.getJavaName() == null ? "" : thread.getJavaName());
				for (String g : new String[] { group, "all" }) {
					long[] c = counts.get(g);
					c[0]++;
					int k = 1;
					for (String[] prefixes : packages.values()) {
						if (any(types, methods, prefixes)) c[k]++;
						k++;
					}
				}
			}
		}
		StringBuilder header = new StringBuilder(String.format("%-9s %8s", "threads", "samples"));
		for (String name : packages.keySet()) header.append(String.format("  %16s", name));
		System.out.println(header);
		for (Map.Entry<String, long[]> e : counts.entrySet()) {
			long[] c = e.getValue();
			if (c[0] == 0) continue;
			StringBuilder line = new StringBuilder(String.format("%-9s %8d", e.getKey(), c[0]));
			for (int k = 1; k < c.length; k++) {
				line.append(String.format("  %7d (%5.2f%%)", c[k], 100.0 * c[k] / c[0]));
			}
			System.out.println(line);
		}
	}

	private static boolean any(List<String> types, List<String> methods, String[] prefixes) {
		for (String p : prefixes) {
			if (p.startsWith("$")) {
				for (String m : methods) {
					if (m.contains(p)) return true;
				}
			} else {
				for (String t : types) {
					if (t.startsWith(p)) return true;
				}
			}
		}
		return false;
	}

	/** As monitor/ThreadCpuGroups.classify. */
	private static String classify(String name) {
		if (name.equals("Server thread")) return "server";
		String lower = name.toLowerCase(Locale.ROOT);
		if (name.startsWith("Worker-") || lower.contains("c2me")) return "worldgen";
		if (lower.contains("geyser") || lower.contains("floodgate") || lower.contains("raknet") || lower.contains("bedrock")) {
			return "geyser";
		}
		if (lower.contains("netty") || lower.contains("epoll") || lower.contains("nio") || lower.contains("server io")) {
			return "network";
		}
		return "other";
	}
}
