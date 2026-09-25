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
		// No leading dots: the work often runs in a lambda, whose frame is
		// "Class.lambda$fillFromNoise$4" rather than "Class.fillFromNoise".
		STAGES.put("structure starts", new String[] {"createStructures"});
		STAGES.put("structure refs", new String[] {"createReferences"});
		STAGES.put("biomes", new String[] {"createBiomes", "fillBiomesFromNoise"});
		STAGES.put("noise", new String[] {"fillFromNoise", "NoiseBasedChunkGenerator.doFill"});
		STAGES.put("surface", new String[] {"buildSurface"});
		STAGES.put("carvers", new String[] {"applyCarvers"});
		STAGES.put("features", new String[] {"applyBiomeDecoration"});
		STAGES.put("light", new String[] {"LightEngine", "light.", "starlight", "scalablelux"});
		STAGES.put("chunk io", new String[] {"RegionFile", "IOWorker", "SerializableChunkData", "ChunkSerializer", "NbtIo"});
		STAGES.put("entity ticking", new String[] {"EntityTickList.forEach"});
	}
	/**
	 * Frames whose inclusive share of the workers' samples is reported:
	 * a sample counts once for each of these on its stack.
	 */
	private static final String[] INCLUSIVE = {
		"NoiseChunk.<init>", "NoiseChunk.forChunk", "NoiseBasedChunkGenerator.iterateNoiseColumn",
		"NoiseBasedChunkGenerator.getBaseHeight", "NoiseBasedChunkGenerator.getBaseColumn",
		"NoiseChunk.wrap", "NoiseChunk.cachedClimateSampler", "Climate$RTree", "Climate$Sampler.sample",
		"BiomeManager.getBiome", "SurfaceSystem.buildSurface", "Beardifier.compute", "Aquifer",
		"JigsawPlacement", "JigsawStructure", "StructureTemplate", "PlacedFeature.place",
	};
	/** Frames whose samples are broken down by self frame. */
	private static final String[] INSIDE = {"NoiseChunk.<init>", "Climate$RTree", "JigsawStructure", "SurfaceSystem.buildSurface"};
	private static final String[] CHUNK_SYSTEM = {"ChunkMap.", "ChunkHolder.", "ChunkStep", "ServerChunkCache.", "ChunkTaskDispatcher", "ChunkResult"};

	public static void main(String[] args) throws Exception {
		Map<String, Map<String, Integer>> table = new TreeMap<>();
		Map<String, Integer> groupTotals = new TreeMap<>();
		Map<String, Map<String, Integer>> selfByGroup = new TreeMap<>();
		// Worker self frame -> caller chain -> samples, to tell apart the
		// callers of generic frames such as Objects.hashCode.
		Map<String, Map<String, Integer>> workerCallers = new TreeMap<>();
		Map<String, Integer> workerInclusive = new LinkedHashMap<>();
		Map<String, Map<String, Integer>> insideSelf = new LinkedHashMap<>();
		for (String marker : INSIDE) insideSelf.put(marker, new TreeMap<>());
		for (String marker : INCLUSIVE) workerInclusive.put(marker, 0);
		Map<String, Integer> otherEntries = new TreeMap<>();
		List<String> gcPauses = new ArrayList<>();
		List<long[]> gcNanos = new ArrayList<>();
		List<String> stalls = new ArrayList<>();
		List<long[]> serverTimes = new ArrayList<>();   // {nanos, index into serverWhat}
		List<String> serverWhat = new ArrayList<>();
		try (RecordingFile file = new RecordingFile(Path.of(args[0]))) {
			while (file.hasMoreEvents()) {
				RecordedEvent event = file.readEvent();
				String type = event.getEventType().getName();
				if (type.equals("jdk.GarbageCollection")) {
					long ns = event.getDuration().toNanos();
					gcNanos.add(new long[] {ns, gcPauses.size()});
					gcPauses.add(String.format("%8.1f ms  %s (%s), longest pause %.1f ms",
							ns / 1e6, event.getString("name"), event.getString("cause"),
							event.getDuration("longestPause").toNanos() / 1e6));
					continue;
				}
				if (type.equals("jdk.ThreadPark") || type.equals("jdk.JavaMonitorEnter")
						|| type.equals("jdk.JavaMonitorWait") || type.equals("jdk.ThreadSleep")) {
					RecordedThread t = event.getThread();
					long ms = event.getDuration().toMillis();
					if (t != null && "Server thread".equals(t.getJavaName()) && ms >= 50) {
						StringBuilder sb = new StringBuilder(String.format("%6d ms  %s", ms, type));
						RecordedStackTrace st = event.getStackTrace();
						if (st != null) {
							List<RecordedFrame> fr = st.getFrames();
							for (int i = 0; i < Math.min(14, fr.size()); i++) sb.append("\n            at ").append(name(fr.get(i)));
						}
						stalls.add(sb.toString());
					}
					continue;
				}
				if (!"jdk.ExecutionSample".equals(type)) continue;
				RecordedStackTrace stack = event.getStackTrace();
				if (stack == null || stack.getFrames().isEmpty()) continue;
				RecordedThread thread = event.getThread("sampledThread");
				String group = group(thread == null ? "?" : String.valueOf(thread.getJavaName()));
				List<RecordedFrame> frames = stack.getFrames();
				String stage = stage(frames);
				table.computeIfAbsent(stage, k -> new TreeMap<>()).merge(group, 1, Integer::sum);
				if (stage.equals("other")) otherEntries.merge(group + "  " + entry(frames), 1, Integer::sum);
				if (group.equals("server thread")) {
					long t = event.getStartTime().getEpochSecond() * 1_000_000_000L + event.getStartTime().getNano();
					serverTimes.add(new long[] {t, serverWhat.size()});
					serverWhat.add(stage + "  " + inner(frames));
				}
				groupTotals.merge(group, 1, Integer::sum);
				selfByGroup.computeIfAbsent(group, k -> new TreeMap<>()).merge(name(frames.get(0)), 1, Integer::sum);
				if (group.equals("worldgen workers")) {
					workerCallers.computeIfAbsent(name(frames.get(0)), k -> new TreeMap<>())
							.merge(callers(frames), 1, Integer::sum);
					for (String marker : INSIDE) {
						for (RecordedFrame f : frames) {
							if (name(f).contains(marker)) {
								insideSelf.get(marker).merge(name(frames.get(0)), 1, Integer::sum);
								break;
							}
						}
					}
					for (String marker : INCLUSIVE) {
						for (RecordedFrame f : frames) {
							if (name(f).contains(marker)) {
								workerInclusive.merge(marker, 1, Integer::sum);
								break;
							}
						}
					}
				}
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

		System.out.println("\n=== \"other\": where those samples enter Minecraft (outermost game frame) ===");
		List<Map.Entry<String, Integer>> oe = new ArrayList<>(otherEntries.entrySet());
		oe.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		for (Map.Entry<String, Integer> e : oe.subList(0, Math.min(15, oe.size()))) {
			System.out.printf("%8d  %s%n", e.getValue(), e.getKey());
		}

		// The busiest second of the server thread: a long tick shows as a
		// dense run of samples; what ran in it is the likely cause.
		serverTimes.sort((a, b) -> Long.compare(a[0], b[0]));
		int best = 0, bestStart = 0;
		for (int i = 0, j = 0; i < serverTimes.size(); i++) {
			while (serverTimes.get(i)[0] - serverTimes.get(j)[0] > 1_000_000_000L) j++;
			if (i - j + 1 > best) { best = i - j + 1; bestStart = j; }
		}
		System.out.printf("%n=== server thread's busiest 1 s window: %d samples ===%n", best);
		Map<String, Integer> inWindow = new TreeMap<>();
		for (int k = bestStart; k < bestStart + best; k++) {
			inWindow.merge(serverWhat.get((int) serverTimes.get(k)[1]), 1, Integer::sum);
		}
		List<Map.Entry<String, Integer>> iw = new ArrayList<>(inWindow.entrySet());
		iw.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		for (Map.Entry<String, Integer> e : iw.subList(0, Math.min(12, iw.size()))) {
			System.out.printf("%6d  %s%n", e.getValue(), e.getKey());
		}

		System.out.println("\n=== server thread blocked >= 50 ms (park, monitor, sleep) ===");
		for (String st : stalls.subList(0, Math.min(12, stalls.size()))) System.out.println(st);
		System.out.println(stalls.size() + " such events");

		System.out.println("\n=== longest garbage collections ===");
		gcNanos.sort((a, b) -> Long.compare(b[0], a[0]));
		for (long[] g : gcNanos.subList(0, Math.min(6, gcNanos.size()))) System.out.println(gcPauses.get((int) g[1]));
		System.out.println(gcNanos.size() + " collections");

		for (String g : groups) {
			if (g.equals("other threads")) continue;
			System.out.println("\n=== " + g + ": top self frames ===");
			List<Map.Entry<String, Integer>> list = new ArrayList<>(selfByGroup.get(g).entrySet());
			list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
			for (Map.Entry<String, Integer> e : list.subList(0, Math.min(15, list.size()))) {
				System.out.printf("%6.2f%%  %6d  %s%n", 100.0 * e.getValue() / groupTotals.get(g), e.getValue(), e.getKey());
			}
		}

		Integer workerTotal = groupTotals.get("worldgen workers");
		if (workerTotal != null) {
			System.out.println("\n=== worldgen workers: samples with the frame on the stack ===");
			for (Map.Entry<String, Integer> e : workerInclusive.entrySet()) {
				System.out.printf("%6.2f%%  %6d  %s%n", 100.0 * e.getValue() / workerTotal, e.getValue(), e.getKey());
			}
		}

		for (Map.Entry<String, Map<String, Integer>> in : insideSelf.entrySet()) {
			int total = 0;
			for (int n : in.getValue().values()) total += n;
			if (total == 0) continue;
			System.out.printf("%n=== worldgen workers under %s (%d samples): top self frames ===%n", in.getKey(), total);
			List<Map.Entry<String, Integer>> list = new ArrayList<>(in.getValue().entrySet());
			list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
			for (Map.Entry<String, Integer> e : list.subList(0, Math.min(10, list.size()))) {
				System.out.printf("%6.2f%%  %6d  %s%n", 100.0 * e.getValue() / total, e.getValue(), e.getKey());
			}
		}

		// Where the workers' hottest frames are called from.
		Map<String, Integer> workerSelf = selfByGroup.get("worldgen workers");
		if (workerSelf != null) {
			System.out.println("\n=== worldgen workers: callers of the top self frames ===");
			List<Map.Entry<String, Integer>> top = new ArrayList<>(workerSelf.entrySet());
			top.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
			for (Map.Entry<String, Integer> e : top.subList(0, Math.min(12, top.size()))) {
				System.out.printf("%6d  %s%n", e.getValue(), e.getKey());
				List<Map.Entry<String, Integer>> chains = new ArrayList<>(workerCallers.get(e.getKey()).entrySet());
				chains.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
				for (Map.Entry<String, Integer> c : chains.subList(0, Math.min(3, chains.size()))) {
					System.out.printf("        %6d  <- %s%n", c.getValue(), c.getKey());
				}
			}
		}
	}

	/**
	 * The callers above a sample's top frame, skipping method handle
	 * plumbing and frames already listed (recursion), and
	 * stopping after five distinct frames.
	 */
	private static String callers(List<RecordedFrame> frames) {
		StringBuilder sb = new StringBuilder();
		java.util.Set<String> seen = new java.util.HashSet<>();
		seen.add(name(frames.get(0)));
		int found = 0;
		for (int i = 1; i < frames.size() && found < 5; i++) {
			String n = name(frames.get(i));
			if (n.startsWith("java.lang.invoke.") || !seen.add(n)) continue;
			if (found > 0) sb.append(" <- ");
			sb.append(n.substring(n.lastIndexOf('.', n.lastIndexOf('.') - 1) + 1));
			found++;
		}
		return sb.toString();
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

	/** The two innermost game or mod frames, for "what was it doing". */
	private static String inner(List<RecordedFrame> frames) {
		StringBuilder sb = new StringBuilder();
		int found = 0;
		for (RecordedFrame f : frames) {
			String n = name(f);
			if (n.startsWith("java.") || n.startsWith("jdk.") || n.startsWith("it.unimi.")) continue;
			if (found > 0) sb.append(" <- ");
			sb.append(n);
			if (++found == 3) break;
		}
		return sb.toString();
	}

	/** Outermost frame from the game or a mod, skipping JDK and library frames. */
	private static String entry(List<RecordedFrame> frames) {
		for (int i = frames.size() - 1; i >= 0; i--) {
			String n = name(frames.get(i));
			if (n.startsWith("java.") || n.startsWith("jdk.") || n.startsWith("sun.")
					|| n.startsWith("com.google.") || n.startsWith("it.unimi.")
					|| n.startsWith("com.mojang.datafixers.")) continue;
			// Skip generic executor plumbing to reach the task itself.
			if (n.contains("Executor") || n.contains("TaskScheduler") || n.contains("ProcessorMailbox")
					|| n.contains("Util.") || n.contains("CompletableFuture")) continue;
			return n;
		}
		return "?";
	}

	private static String name(RecordedFrame frame) {
		return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
	}
}
