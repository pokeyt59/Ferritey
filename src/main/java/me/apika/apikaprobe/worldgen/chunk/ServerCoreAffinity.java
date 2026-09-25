package me.apika.apikaprobe.worldgen.chunk;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.stream.Stream;

import me.apika.apikaprobe.bridge.ExampleMod;

/**
 * Keeps the server thread on a physical CPU core of its own, away from
 * the worldgen workers. Linux only; off by default.
 *
 * On a CPU with hyperthreads, two busy threads on one physical core both
 * run slower. When players reach new terrain, the worldgen workers keep
 * cores busy, and a worker sharing the server thread's core slows every
 * tick. Here the server thread is pinned to the first allowed CPU, and
 * the workers ("Worker-*" threads) to the CPUs of the other physical
 * cores. Other threads are left alone. On the CI worldgen bench (2 cores
 * with 2 threads each, 9 new chunks a second), this cut the tick time
 * exploring added by about 60%, and every chunk was still delivered on
 * time. The trade is fewer cores for generation, so a backlog of new
 * chunks clears more slowly on a busy machine.
 *
 * Pool threads inherit the affinity of the thread that started them, so
 * a daemon re-applies the masks every two seconds while this is on.
 * Affinity is set with sched_setaffinity through java.lang.foreign.
 *
 * /ferrite worldgen isolate-server-core on|off|status (saved), or
 * -Dferrite.affinity.servercore=true.
 */
public final class ServerCoreAffinity {
	private ServerCoreAffinity() {}

	private static volatile boolean enabled;
	private static Thread daemon;
	private static MethodHandle setAffinity;
	private static String unsupported;
	private static BitSet allowed;
	private static int serverCpu = -1;
	private static BitSet workerCpus;
	private static volatile String lastPass = "not applied yet";

	public static synchronized boolean setEnabled(boolean on) {
		if (on && !prepare()) {
			enabled = false;
			return false;
		}
		enabled = on;
		if (on && daemon == null) {
			daemon = new Thread(ServerCoreAffinity::loop, "Ferrite server-core affinity");
			daemon.setDaemon(true);
			daemon.start();
		}
		if (!on && setAffinity != null) apply(false);
		return true;
	}

	public static boolean enabled() {
		return enabled;
	}

	public static synchronized String status() {
		if (unsupported != null) return "[server-core] unavailable: " + unsupported;
		if (serverCpu < 0) return "[server-core] off";
		return String.format("[server-core] %s: server thread -> cpu %d, worldgen workers -> cpus %s; %s",
				enabled ? "on" : "off", serverCpu, workerCpus, lastPass);
	}

	private static void loop() {
		while (true) {
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				return;
			}
			if (enabled) {
				synchronized (ServerCoreAffinity.class) {
					if (enabled) apply(true);
				}
			}
		}
	}

	/** Finds the CPUs and binds sched_setaffinity; false (with a reason) if it can't. */
	private static boolean prepare() {
		if (setAffinity != null) return true;
		if (unsupported != null) return false;
		if (!System.getProperty("os.name", "").toLowerCase().startsWith("linux")) {
			unsupported = "not Linux";
			return false;
		}
		try {
			allowed = allowedCpus();
			serverCpu = allowed.nextSetBit(0);
			BitSet serverCore = siblings(serverCpu);
			workerCpus = (BitSet) allowed.clone();
			workerCpus.andNot(serverCore);
			if (serverCpu < 0 || workerCpus.isEmpty()) {
				unsupported = "only one physical core available (cpus " + allowed + ")";
				serverCpu = -1;
				return false;
			}
			Linker linker = Linker.nativeLinker();
			setAffinity = linker.defaultLookup().find("sched_setaffinity")
					.map(address -> linker.downcallHandle(address, FunctionDescriptor.of(
							ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)))
					.orElse(null);
			if (setAffinity == null) {
				unsupported = "sched_setaffinity not found";
				return false;
			}
			ExampleMod.LOGGER.info("[server-core] server thread -> cpu {}, worldgen workers -> cpus {} (allowed {})",
					serverCpu, workerCpus, allowed);
			return true;
		} catch (Throwable t) {
			unsupported = t.toString();
			ExampleMod.LOGGER.warn("[server-core] unavailable: {}", unsupported);
			return false;
		}
	}

	/** Pins (or, with isolate false, releases) the server thread and the workers. */
	private static void apply(boolean isolate) {
		int server = 0, workers = 0, failed = 0;
		List<Path> tasks = new ArrayList<>();
		try (Stream<Path> list = Files.list(Path.of("/proc/self/task"))) {
			list.forEach(tasks::add);
		} catch (IOException e) {
			lastPass = "could not list threads: " + e.getMessage();
			return;
		}
		for (Path task : tasks) {
			String name;
			int tid;
			try {
				name = Files.readString(task.resolve("comm")).strip();
				tid = Integer.parseInt(task.getFileName().toString());
			} catch (IOException | NumberFormatException e) {
				continue;   // the thread ended meanwhile
			}
			BitSet mask;
			if (name.equals("Server thread")) {
				mask = isolate ? single(serverCpu) : allowed;
				server++;
			} else if (name.startsWith("Worker-")) {
				mask = isolate ? workerCpus : allowed;
				workers++;
			} else {
				continue;
			}
			if (!set(tid, mask)) failed++;
		}
		lastPass = String.format("last pass: server %d, workers %d, failed %d", server, workers, failed);
	}

	private static boolean set(int tid, BitSet cpus) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment mask = arena.allocate(128);   // cpu_set_t: 1024 bits
			for (int cpu = cpus.nextSetBit(0); cpu >= 0 && cpu < 1024; cpu = cpus.nextSetBit(cpu + 1)) {
				long offset = cpu / 8;
				mask.set(ValueLayout.JAVA_BYTE, offset, (byte) (mask.get(ValueLayout.JAVA_BYTE, offset) | (1 << (cpu % 8))));
			}
			return (int) setAffinity.invokeExact(tid, 128L, mask) == 0;
		} catch (Throwable t) {
			return false;
		}
	}

	private static BitSet single(int cpu) {
		BitSet b = new BitSet();
		b.set(cpu);
		return b;
	}

	/** The CPUs this process may run on (Cpus_allowed_list in /proc/self/status). */
	private static BitSet allowedCpus() throws IOException {
		for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
			if (line.startsWith("Cpus_allowed_list:")) return cpuList(line.substring(line.indexOf(':') + 1));
		}
		throw new IOException("no Cpus_allowed_list in /proc/self/status");
	}

	/** The CPUs sharing a physical core with cpu (itself included). */
	private static BitSet siblings(int cpu) throws IOException {
		Path path = Path.of("/sys/devices/system/cpu/cpu" + cpu + "/topology/thread_siblings_list");
		return Files.isReadable(path) ? cpuList(Files.readString(path)) : single(cpu);
	}

	static BitSet cpuList(String text) {
		BitSet cpus = new BitSet();
		for (String part : text.strip().split(",")) {
			if (part.isEmpty()) continue;
			int dash = part.indexOf('-');
			if (dash < 0) {
				cpus.set(Integer.parseInt(part.strip()));
			} else {
				cpus.set(Integer.parseInt(part.substring(0, dash).strip()),
						Integer.parseInt(part.substring(dash + 1).strip()) + 1);
			}
		}
		return cpus;
	}
}
