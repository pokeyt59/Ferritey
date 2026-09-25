import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Text summary of JFR execution samples on the server thread.
 * Run with: java scripts/JfrHot.java bench.jfr
 * Prints the hottest methods by self and inclusive samples, the
 * Ferrite frames, and the commonest caller chains of the top self frames.
 */
public class JfrHot {
	private static final String THREAD = "Server thread";
	private static final int CHAIN_DEPTH = 8;

	public static void main(String[] args) throws Exception {
		Map<String, Integer> self = new HashMap<>();
		Map<String, Integer> incl = new HashMap<>();
		Map<String, Map<String, Integer>> chains = new HashMap<>();
		int total = 0;
		try (RecordingFile file = new RecordingFile(Path.of(args[0]))) {
			while (file.hasMoreEvents()) {
				RecordedEvent event = file.readEvent();
				if (!"jdk.ExecutionSample".equals(event.getEventType().getName())) continue;
				RecordedThread thread = event.getThread("sampledThread");
				if (thread == null || !THREAD.equals(thread.getJavaName())) continue;
				RecordedStackTrace stack = event.getStackTrace();
				if (stack == null || stack.getFrames().isEmpty()) continue;
				total++;
				List<RecordedFrame> frames = stack.getFrames();
				String top = name(frames.get(0));
				self.merge(top, 1, Integer::sum);
				Set<String> seen = new HashSet<>();
				for (RecordedFrame frame : frames) {
					String n = name(frame);
					if (seen.add(n)) incl.merge(n, 1, Integer::sum);
				}
				StringBuilder chain = new StringBuilder();
				for (int i = 1; i < Math.min(frames.size(), CHAIN_DEPTH); i++) {
					chain.append("\n            <- ").append(name(frames.get(i)));
				}
				chains.computeIfAbsent(top, k -> new HashMap<>()).merge(chain.toString(), 1, Integer::sum);
			}
		}
		System.out.println("server thread samples: " + total);
		if (total == 0) return;
		dump("self (top frame)", self, total, 45, n -> true);
		dump("inclusive", incl, total, 100, n -> true);
		dump("ferrite frames, inclusive", incl, total, 40, n -> n.startsWith("me.apika."));

		System.out.println("\n=== commonest callers of the top self frames ===");
		for (Map.Entry<String, Integer> e : sorted(self).subList(0, Math.min(15, self.size()))) {
			System.out.printf("%n%6.2f%%  %s%n", 100.0 * e.getValue() / total, e.getKey());
			List<Map.Entry<String, Integer>> paths = sorted(chains.get(e.getKey()));
			for (Map.Entry<String, Integer> p : paths.subList(0, Math.min(3, paths.size()))) {
				System.out.printf("    %5d samples%s%n", p.getValue(), p.getKey());
			}
		}
	}

	private static String name(RecordedFrame frame) {
		return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
	}

	private static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> map) {
		List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
		list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		return list;
	}

	private static void dump(String title, Map<String, Integer> map, int total, int limit,
			java.util.function.Predicate<String> keep) {
		System.out.println("\n=== " + title + " ===");
		int shown = 0;
		for (Map.Entry<String, Integer> e : sorted(map)) {
			if (!keep.test(e.getKey())) continue;
			System.out.printf("%6.2f%%  %6d  %s%n", 100.0 * e.getValue() / total, e.getValue(), e.getKey());
			if (++shown >= limit) break;
		}
	}
}
