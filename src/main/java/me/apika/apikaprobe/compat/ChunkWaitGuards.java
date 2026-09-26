package me.apika.apikaprobe.compat;

import java.util.concurrent.atomic.LongAdder;

import net.minecraft.server.level.ServerLevel;

/**
 * Stops some callers on the server thread from generating a chunk while
 * they wait for it. Each of these asks for a chunk the server has
 * scheduled but not generated yet; the game then generates it on the
 * spot and the server thread waits, behind everything else queued. On the
 * CI replica of a 2-core server (scripts/ci-players-bench.sh) these were
 * the freezes while players explored: 1-5 s each, and 17-58 s for the
 * first player to land in fresh terrain.
 *
 * With a guard on, the caller treats the chunk as not there yet and
 * comes back later, as it does for a chunk no player is near:
 *   - lithostitched: Lithostitched checks every 5 ticks which structure
 *     each player stands in (structure attributes). It skips players whose
 *     chunk is not loaded, but "loaded" there means scheduled; now it also
 *     skips a player whose chunk is not generated yet.
 *   - spawners: once a minute the cat spawner tries a spot 8-24 blocks
 *     from a random player, having checked only that its chunks are
 *     scheduled; a spot whose chunk is not generated yet is now skipped.
 *   - roguelike: Roguelike Dungeons builds rooms whose surrounding chunks
 *     are loaded; its check loaded them. It now counts only generated ones.
 * Only timing changes: the check, spawn or room happens once the chunk
 * exists. Toggles: /ferrite compat <name>|all on|off|status,
 * -Dferrite.compat.<name>=false.
 */
public final class ChunkWaitGuards {
	private ChunkWaitGuards() {}

	public static volatile boolean LITHOSTITCHED = on("lithostitched");
	public static volatile boolean SPAWNERS = on("spawners");
	public static volatile boolean ROGUELIKE = on("roguelike");

	public static final LongAdder lithostitchedDeferred = new LongAdder();
	public static final LongAdder spawnerSkipped = new LongAdder();
	public static final LongAdder roguelikeDeferred = new LongAdder();

	private static boolean on(String name) {
		return !"false".equals(System.getProperty("ferrite.compat." + name));
	}

	/** Whether the chunk is generated and loaded, without loading or generating it. */
	public static boolean generated(ServerLevel level, int chunkX, int chunkZ) {
		return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
	}

	/** Sets one guard, or all; returns false for an unknown name. */
	public static boolean set(String name, boolean on) {
		switch (name) {
			case "lithostitched" -> LITHOSTITCHED = on;
			case "spawners" -> SPAWNERS = on;
			case "roguelike" -> ROGUELIKE = on;
			case "all" -> {
				LITHOSTITCHED = on;
				SPAWNERS = on;
				ROGUELIKE = on;
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	public static String status() {
		return String.format("[compat] lithostitched=%s (deferred %d) spawners=%s (skipped %d) roguelike=%s (deferred %d)",
				LITHOSTITCHED ? "on" : "off", lithostitchedDeferred.sum(),
				SPAWNERS ? "on" : "off", spawnerSkipped.sum(),
				ROGUELIKE ? "on" : "off", roguelikeDeferred.sum());
	}
}
