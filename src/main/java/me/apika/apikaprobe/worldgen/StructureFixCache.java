package me.apika.apikaprobe.worldgen;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Keeps upgraded structure templates on disk between server starts.
 *
 * Mods ship their structure templates saved by older game versions, and
 * the game upgrades each one through DataFixerUpper the first time it
 * loads after every start. On the CI worldgen bench that was 1,020
 * templates and 43 s of worldgen CPU in the first minutes after a start
 * (27 s during startup alone, up to 3 s for one template while the fixers
 * warm up), competing with ticks right when players begin exploring.
 *
 * An upgrade is a pure function of the template, its version, the target
 * version and the fixers, which come from the game and the mods. The
 * cache key is a SHA-256 of the template's bytes and both versions, in a
 * directory named after a hash of every loaded mod and its version (the
 * game included), so any mod change starts a new cache and the old ones
 * are deleted. A hit reads the upgraded template back; NBT round-trips
 * exactly. Files are written to a temporary name and moved into place.
 *
 * Oracle: every ORACLE_EVERY-th hit, the first included, upgrades the
 * template anyway and compares; a mismatch deletes the entry and keeps the
 * fresh upgrade.
 *
 * .ferrite/structure-dfu in the server directory.
 * Off: -Dferrite.worldgen.structurecache=false or
 * /ferrite worldgen structure-dfu cache off.
 */
public final class StructureFixCache {
	private StructureFixCache() {}

	public static volatile boolean ENABLED = !"false".equals(System.getProperty("ferrite.worldgen.structurecache"));

	private static final int ORACLE_EVERY = 32;
	private static final AtomicLong hits = new AtomicLong();
	private static final AtomicLong misses = new AtomicLong();
	private static final AtomicLong failures = new AtomicLong();
	private static final AtomicLong oracleChecks = new AtomicLong();
	private static final AtomicLong oracleMismatches = new AtomicLong();

	private static volatile Path dir;
	private static volatile boolean unavailable;

	/**
	 * The upgraded template: from the cache, or from upgrade (the game's
	 * own upgrade), which is then stored.
	 */
	public static CompoundTag upgrade(CompoundTag template, int from, int to, Supplier<CompoundTag> upgrade) {
		Path directory = ENABLED ? directory() : null;
		if (directory == null) return upgrade.get();
		Path file;
		try {
			file = directory.resolve(key(template, from, to) + ".nbt");
		} catch (IOException | RuntimeException e) {
			failures.incrementAndGet();
			return upgrade.get();
		}
		if (Files.isRegularFile(file)) {
			CompoundTag cached;
			try {
				cached = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
			} catch (IOException | RuntimeException e) {
				cached = null;
				failures.incrementAndGet();
			}
			if (cached != null) {
				if (hits.getAndIncrement() % ORACLE_EVERY != 0) return cached;
				CompoundTag fresh = upgrade.get();
				oracleChecks.incrementAndGet();
				if (!fresh.equals(cached)) {
					oracleMismatches.incrementAndGet();
					ExampleMod.LOGGER.warn("[structure-dfu] cached upgrade differs from a fresh one; dropping {}", file);
					try {
						Files.deleteIfExists(file);
					} catch (IOException ignored) {
						// The next miss overwrites it.
					}
				}
				return fresh;
			}
		}
		misses.incrementAndGet();
		CompoundTag upgraded = upgrade.get();
		try {
			Path tmp = Files.createTempFile(directory, "upgrade", ".tmp");
			NbtIo.writeCompressed(upgraded, tmp);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException | RuntimeException e) {
			failures.incrementAndGet();
		}
		return upgraded;
	}

	private static String key(CompoundTag template, int from, int to) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(16384);
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(from);
			out.writeInt(to);
			NbtIo.write(template, out);
		}
		return HexFormat.of().formatHex(sha256().digest(bytes.toByteArray()));
	}

	/** The cache directory for this exact set of mods, created on first use. */
	private static Path directory() {
		Path d = dir;
		if (d != null || unavailable) return d;
		synchronized (StructureFixCache.class) {
			if (dir != null || unavailable) return dir;
			try {
				List<String> mods = FabricLoader.getInstance().getAllMods().stream()
						.map(ModContainer::getMetadata)
						.map(m -> m.getId() + "@" + m.getVersion().getFriendlyString())
						.sorted()
						.toList();
				String identity = HexFormat.of().formatHex(
						sha256().digest(String.join("\n", mods).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
				Path root = FabricLoader.getInstance().getGameDir().resolve(".ferrite").resolve("structure-dfu");
				Path current = root.resolve(identity);
				Files.createDirectories(current);
				// Caches for other mod sets can never hit again.
				try (Stream<Path> old = Files.list(root)) {
					for (Path other : old.filter(p -> !p.equals(current)).toList()) deleteTree(other);
				}
				dir = current;
				ExampleMod.LOGGER.info("[structure-dfu] upgraded structure templates are cached in {}", current);
			} catch (IOException | RuntimeException e) {
				unavailable = true;
				ExampleMod.LOGGER.warn("[structure-dfu] cache unavailable: {}", e.toString());
			}
			return dir;
		}
	}

	private static void deleteTree(Path path) throws IOException {
		try (Stream<Path> walk = Files.walk(path)) {
			for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) Files.deleteIfExists(p);
		}
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String status() {
		return String.format("[structure-dfu] cache=%s hits=%d misses=%d failures=%d oracleChecks=%d oracleMismatches=%d%s",
				ENABLED ? "on" : "off", hits.get(), misses.get(), failures.get(),
				oracleChecks.get(), oracleMismatches.get(), dir == null ? "" : " dir=" + dir);
	}
}
