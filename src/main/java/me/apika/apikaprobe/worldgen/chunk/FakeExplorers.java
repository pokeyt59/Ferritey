package me.apika.apikaprobe.worldgen.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Players for the CI replica bench: real ServerPlayers, joined through
 * the player list like a client, on a connection with no socket
 * (FakeConnection), each moving in a straight line through fresh terrain
 * at a mode's speed.
 *
 * Unlike the ticket bench (ExploreBench) they bring what a real explorer
 * brings: player chunk loading at the server's view distance, chunks
 * built into packets and sent (the server paces sending by the client's
 * batch acknowledgements, answered here one tick later at a client's
 * usual rate), entity tracking, natural spawning around them, and mobs
 * ticking within the simulation distance.
 *
 * Modes and speeds: walk 5.6 b/s (sprinting), horse 10 b/s, elytra 33
 * b/s, boat 40 b/s (ice). Walkers and riders stay on the ground and wait
 * when the chunk ahead is not loaded yet, as a client does; elytra and
 * boat keep going at a fixed height and outrun loading if the server
 * falls behind. Players are in creative mode, so mobs leave them alone,
 * and nothing runs their movement physics (a connection's network tick
 * would); the bench sets their positions and tells the chunk map each
 * tick, as a movement packet does.
 *
 * Status per player: chunks sent (per second), chunks within 5 of the
 * player not yet sent ("holes", sampled each second: what a player sees
 * as missing terrain), seconds with a hole within 2, and seconds spent
 * waiting for terrain.
 *
 * /ferrite bench players add <mode> <x> <z> <heading>|clear|status.
 * Nothing runs unless the command is used.
 */
public final class FakeExplorers {
	private FakeExplorers() {}

	/** What a client usually asks for after a batch (its measured chunk processing rate). */
	private static final float CLIENT_CHUNKS_PER_TICK = 10f;

	enum Mode {
		WALK(5.6, true), HORSE(10.0, true), ELYTRA(33.0, false), BOAT(40.0, false);

		final double blocksPerSecond;
		final boolean grounded;

		Mode(double blocksPerSecond, boolean grounded) {
			this.blocksPerSecond = blocksPerSecond;
			this.grounded = grounded;
		}
	}

	private static final class Explorer {
		final ServerPlayer player;
		final FakeConnection connection;
		final Mode mode;
		final double dx;
		final double dz;
		final long startNanos = System.nanoTime();
		double x, y, z;
		double travelled;
		int pendingAcks;
		long chunksSent;
		final LongOpenHashSet sent = new LongOpenHashSet();
		long holeSamples;
		long holeSum;
		int holeMax;
		int secondsHoleNear;
		int secondsWaiting;
		int ticks;

		Explorer(ServerPlayer player, FakeConnection connection, Mode mode, double x, double y, double z, double headingDeg) {
			this.player = player;
			this.connection = connection;
			this.mode = mode;
			this.x = x;
			this.y = y;
			this.z = z;
			double rad = Math.toRadians(headingDeg);
			this.dx = -Math.sin(rad);
			this.dz = Math.cos(rad);
		}
	}

	private static final List<Explorer> explorers = new ArrayList<>();
	private static boolean registered;
	private static int nextId;

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		ServerTickEvents.END_SERVER_TICK.register(FakeExplorers::tick);
	}

	/** Joins a player at x, z (block coordinates) heading headingDeg (0 = +z, 90 = -x, Minecraft yaw). */
	public static synchronized String add(MinecraftServer server, ServerLevel level, String modeName,
			double x, double z, double headingDeg) {
		Mode mode;
		try {
			mode = Mode.valueOf(modeName.toUpperCase(java.util.Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return "[bench-players] unknown mode " + modeName + " (walk, horse, elytra, boat)";
		}
		register();
		String name = "bench" + (nextId++);
		UUID id = UUIDUtil.createOfflinePlayerUUID(name);
		GameProfile profile = new GameProfile(id, name);
		ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
		Explorer[] holder = new Explorer[1];
		FakeConnection connection = new FakeConnection(packet -> {
			Explorer e = holder[0];
			if (e != null) onPacket(e, packet);
		});
		server.getPlayerList().placeNewPlayer(connection, player,
				new CommonListenerCookie(profile, 0, ClientInformation.createDefault(), false));
		player.setGameMode(GameType.CREATIVE);
		double y = mode == Mode.ELYTRA ? 200 : mode == Mode.BOAT ? level.getSeaLevel() : level.getSeaLevel() + 2;
		player.teleportTo(level, x, y, z, Set.of(), (float) headingDeg, 0f, false);
		// The chunk map follows a player on movement; start it at the destination.
		level.getChunkSource().move(player);
		Explorer e = new Explorer(player, connection, mode, x, y, z, headingDeg);
		holder[0] = e;
		explorers.add(e);
		return String.format("[bench-players] %s joined as %s at %.0f %.0f heading %.0f", name, mode.name().toLowerCase(java.util.Locale.ROOT), x, z, headingDeg);
	}

	/** Called for every packet the server sends to an explorer, on the thread that sends it. */
	private static void onPacket(Explorer e, Packet<?> packet) {
		if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
			synchronized (e) {
				e.chunksSent++;
				e.sent.add(new ChunkPos(chunk.getX(), chunk.getZ()).pack());
			}
		} else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
			synchronized (e) {
				e.sent.remove(forget.pos().pack());
			}
		} else if (packet instanceof ClientboundChunkBatchFinishedPacket) {
			synchronized (e) {
				e.pendingAcks++;
			}
		}
	}

	private static synchronized void tick(MinecraftServer server) {
		if (explorers.isEmpty()) return;
		for (Explorer e : explorers) {
			if (e.player.isRemoved()) continue;
			int acks;
			synchronized (e) {
				acks = e.pendingAcks;
				e.pendingAcks = 0;
			}
			// The client acknowledges each batch; a tick later stands in for the round trip.
			for (int i = 0; i < acks; i++) {
				e.player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(CLIENT_CHUNKS_PER_TICK));
			}
			move(e);
			if (++e.ticks % 20 == 0) sample(e);
		}
	}

	private static void move(Explorer e) {
		ServerLevel level = e.player.level();
		double step = e.mode.blocksPerSecond / 20.0;
		double nx = e.x + e.dx * step;
		double nz = e.z + e.dz * step;
		if (e.mode.grounded) {
			// A client cannot walk into terrain it has not received.
			LevelChunk ahead = level.getChunkSource().getChunkNow((int) Math.floor(nx) >> 4, (int) Math.floor(nz) >> 4);
			if (ahead == null || !e.sent.contains(ahead.getPos().pack())) {
				if (e.ticks % 20 == 0) e.secondsWaiting++;
				level.getChunkSource().move(e.player);
				return;
			}
			e.y = ahead.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(nx) & 15, (int) Math.floor(nz) & 15) + 1;
		}
		e.x = nx;
		e.z = nz;
		e.travelled += step;
		e.player.snapTo(e.x, e.y, e.z);
		level.getChunkSource().move(e.player);
	}

	private static void sample(Explorer e) {
		ChunkPos at = e.player.chunkPosition();
		int holes = 0;
		boolean near = false;
		synchronized (e) {
			for (int cx = -5; cx <= 5; cx++) {
				for (int cz = -5; cz <= 5; cz++) {
					if (!e.sent.contains(new ChunkPos(at.x() + cx, at.z() + cz).pack())) {
						holes++;
						if (Math.abs(cx) <= 2 && Math.abs(cz) <= 2) near = true;
					}
				}
			}
			e.holeSamples++;
			e.holeSum += holes;
			e.holeMax = Math.max(e.holeMax, holes);
			if (near) e.secondsHoleNear++;
		}
	}

	public static synchronized String clear(MinecraftServer server) {
		int n = explorers.size();
		for (Explorer e : explorers) {
			e.connection.close();
			if (!e.player.isRemoved()) server.getPlayerList().remove(e.player);
		}
		explorers.clear();
		return "[bench-players] removed " + n + " player(s)";
	}

	public static synchronized String status() {
		if (explorers.isEmpty()) return "[bench-players] none";
		StringBuilder sb = new StringBuilder();
		long total = 0;
		for (Explorer e : explorers) {
			double seconds = Math.max(1e-9, (System.nanoTime() - e.startNanos) / 1e9);
			synchronized (e) {
				total += e.chunksSent;
				if (sb.length() > 0) sb.append('\n');
				sb.append(String.format(
						"[bench-players] %s %s travelled=%.0f blocks chunks_sent=%d (%.1f/s) holes_r5 avg=%.1f max=%d seconds_hole_within_2=%d seconds_waiting=%d of %.0f s",
						e.player.getScoreboardName(), e.mode.name().toLowerCase(java.util.Locale.ROOT), e.travelled,
						e.chunksSent, e.chunksSent / seconds,
						e.holeSamples == 0 ? 0.0 : (double) e.holeSum / e.holeSamples, e.holeMax,
						e.secondsHoleNear, e.secondsWaiting, seconds));
			}
		}
		sb.append(String.format("%n[bench-players] total chunks_sent=%d", total));
		return sb.toString();
	}
}
