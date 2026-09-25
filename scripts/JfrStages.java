import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Where chunk generation spends CPU: JFR execution samples of every
 * thread, grouped by thread (server thread, worldgen workers, IO, other)
 * and by worldgen stage.
 * Run with: java scripts/JfrStages.java worldgen.jfr
 *
 * A sample's stage is the outermost frame that names one (so biome
 * lookups made while placing structures count as structure starts); a
 * sample with none falls back to the generic chunk system, then "other".
 * Also prints each group's hottest self frames.
 */
public class JfrStages {

	/** Stage name -> substrings of "Class.method" that mark it, most specific stages first. */
	private static final Map<String, String[]> STAGES = new LinkedHashMap<>();
	static {
		STAGES.put("structure starts", new String[] {"ChunkGenerator.createStructures"});
		STAGES.put("structure refs", new String[] {"ChunkGenerator.createReferences"});
		STAGES.put("biomes", new String[] {".createBiomes", ".fillBiomesFromNoise"});
		STAGES.put("noise", new String[] {"NoiseBasedChunkGenerator.fillFromNoise", "NoiseBasedChunkGenerator.doFill"});
		STAGES.put("surface", new String[] {".buildSurface"});
		STAGES.put("carvers", new String[] {".applyCarvers"});
		STAGES.put("features", new String[] {".applyBiomeDecoration"});
		STAGES.put("light", new String[] {"LightEngine", "light.", "starlight", "scalablelux"});
		STAGES.put("chunk io", new String[] {"RegionFile", "IOWorker", "SerializableChunkData", "ChunkSerializer", "NbtIo"});
		STAGES.put("entity ticking", new String[] {"EntityTickList.forEach"});
	}
	private static final String[] CHUNK_SYSTEM = {"ChunkMap.", "ChunkHolder.", "ChunkStep", "ServerChunkCache.", "ChunkTaskDispatcher", "ChunkResult"};

	public static void main(String[] args) throws Exception {
		Map<String, Map<String, Integer>> table = new TreeMap<>();
		Map<String, Integer> groupTotals = new TreeMap<>();
		Map<String, Map<String, Integer>> selfByGroup = new TreeMap<>();
		try (RecordingFile file = new RecordingFile(Path.of(args[0]))) {
			while (file.hasMoreEvents()) {
				RecordedEvent event = file.readEvent();
				if (!"jdk.ExecutionSample".equals(event.getEventType().getName())) continue;
				RecordedStackTrace stack = event.getStackTrace();
				if (stack == null || stack.getFrames().isEmpty()) continue;
				RecordedThread thread = event.getThread("sampledThread");
				String group = group(thread == null ? "?" : String.valueOf(thread.getJavaName()));
				List<RecordedFrame> frames = stack.getFrames();
				String stage = stage(frames);
				table.computeIfAbsent(stage, k -> new TreeMap<>()).merge(group, 1, Integer::sum);
				groupTotals.merge(group, 1, Integer::sum);
				selfByGroup.computeIfAbsent(group, k -> new TreeMap<>()).merge(name(frames.get(0)), 1, Integer::sum);
			}
		}
		List<String> groups = new ArrayList<>(groupTotals.keySet());
		System.out.printf("%-18s", "stage \\ threads");
		for (String g : groups) System.out.printf(" %20s", g);
		System.out.println();
		List<String> order = new ArrayList<>(STAGES.keySet());
		order.add("chunk system");
		order.add("other");
		for (String stage : order) {
			Map<String, Integer> row = table.get(stage);
			if (row == null) continue;
			System.out.printf("%-18s", stage);
			for (String g : groups) {
				int n = row.getOrDefault(g, 0);
				System.out.printf(" %11d (%5.1f%%)", n, 100.0 * n / groupTotals.get(g));
			}
			System.out.println();
		}
		System.out.printf("%-18s", "total samples");
		for (String g : groups) System.out.printf(" %20d", groupTotals.get(g));
		System.out.println();

		for (String g : groups) {
			if (g.equals("other threads")) continue;
			System.out.println("\n=== " + g + ": top self frames ===");
			List<Map.Entry<String, Integer>> list = new ArrayList<>(selfByGroup.get(g).entrySet());
			list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
			for (Map.Entry<String, Integer> e : list.subList(0, Math.min(15, list.size()))) {
				System.out.printf("%6.2f%%  %6d  %s%n", 100.0 * e.getValue() / groupTotals.get(g), e.getValue(), e.getKey());
			}
		}
	}

	private static String group(String thread) {
		if (thread.startsWith("Server thread")) return "server thread";
		if (thread.startsWith("Worker-")) return "worldgen workers";
		if (thread.contains("IO-Worker") || thread.contains("IOWorker")) return "io";
		return "other threads";
	}

	private static String stage(List<RecordedFrame> frames) {
		// Frames are top-first; walk from the thread's root outwards in.
		for (int i = frames.size() - 1; i >= 0; i--) {
			String n = name(frames.get(i));
			for (Map.Entry<String, String[]> s : STAGES.entrySet()) {
				for (String marker : s.getValue()) {
					if (n.contains(marker)) return s.getKey();
				}
			}
		}
		for (RecordedFrame frame : frames) {
			String n = name(frame);
			for (String marker : CHUNK_SYSTEM) {
				if (n.contains(marker)) return "chunk system";
			}
		}
		return "other";
	}

	private static String name(RecordedFrame frame) {
		return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
	}
}
