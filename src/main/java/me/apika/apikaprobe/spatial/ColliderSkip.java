package me.apika.apikaprobe.spatial;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

/**
 * Caller-side skip for getEntityCollisions: the walk only ever accepts
 * hard-collidable entities (boat/shulker/happy-ghast family), so when a
 * level holds none of those, the correct result is the empty list and the
 * per-entity walk is skipped outright (LOCAL_DESIGN "caller-side query
 * avoidance": 87% of farm queries are this shape).
 *
 * Count is per-level and type-based (conservative overcount: a dead
 * shulker still counts). A level that does hold hard colliders (one parked
 * boat is enough) is then checked per entity section: each section counts
 * its hard-collider members on add/remove, and the skip holds when every
 * section the vanilla walk could visit counts zero. Skip requires BOTH the count at zero AND a
 * source whose canCollideWith is not widened (boats and minecarts also
 * accept pushables, so they always walk). Modded sources that widen
 * canCollideWith are the residual risk; the oracle samples eligible
 * queries, runs the vanilla walk, and logs any non-empty result.
 */
public final class ColliderSkip {
	private ColliderSkip() {}

	/** Master switch: default on since 0.7.2; kill with -Dferrite.entityquery.colliderskip=false. */
	public static final boolean ENABLED = !"false".equals(System.getProperty("ferrite.entityquery.colliderskip"));

	/** Oracle: sample 1 in N eligible queries with the vanilla walk; 0 disables. */
	public static final int ORACLE_RATE = Integer.getInteger("ferrite.entityquery.colliderskip.oracle", 16);

	/**
	 * Per-section check in levels that hold hard colliders. Kill switch
	 * -Dferrite.entityquery.collidersections=false (then any hard collider
	 * in the level disables the skip there); /ferrite entityquery
	 * collider-sections toggles it for A/B.
	 */
	public static volatile boolean PER_SECTION = !"false".equals(System.getProperty("ferrite.entityquery.collidersections"));

	private static final Map<ServerLevel, AtomicInteger> COUNTS = new ConcurrentHashMap<>();

	// Server-thread counters, drained by EntityQueryMonitor's 5 s report.
	public static long eligible;
	public static long skipped;
	public static long oracleWalks;
	public static long oracleNonEmpty;
	public static int sampleCounter;

	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (isHardCollider(entity)) COUNTS.computeIfAbsent(level, l -> new AtomicInteger()).incrementAndGet();
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (isHardCollider(entity)) {
				AtomicInteger c = COUNTS.get(level);
				if (c != null) c.decrementAndGet();
			}
		});
		ServerLevelEvents.UNLOAD.register((server, level) -> COUNTS.remove(level));
	}

	/** Types whose canBeCollidedWith can ever return true (26.2 override set). */
	public static boolean isHardCollider(Object entity) {
		return entity instanceof AbstractBoat || entity instanceof Shulker || entity instanceof HappyGhast;
	}

	/** Sources whose canCollideWith accepts more than hard colliders. */
	public static boolean isWideningSource(Entity source) {
		return source instanceof AbstractBoat || source instanceof AbstractMinecart;
	}

	public static boolean levelHasHardColliders(ServerLevel level) {
		AtomicInteger c = COUNTS.get(level);
		return c != null && c.get() > 0;
	}

	/**
	 * Whether a hard collider could be found by the vanilla entity walk for
	 * this box. The section range is a superset of the one
	 * EntitySectionStorage walks (it pads the box by 2 blocks on X/Z and
	 * reaches 4 below), so a zero here means the walk would find none.
	 */
	public static boolean regionHasHardColliders(ServerLevel level, AABB box) {
		if (!levelHasHardColliders(level)) return false;
		if (!PER_SECTION) return true;
		EntitySectionStorage<?> storage = ((me.apika.apikaprobe.mixin.PersistentEntitySectionManagerAccessor)
				((me.apika.apikaprobe.mixin.ServerLevelEntityManagerAccessor) level).ferrite$entityManager())
				.ferrite$sectionStorage();
		int minX = SectionPos.posToSectionCoord(box.minX - 3.0);
		int minY = SectionPos.posToSectionCoord(box.minY - 5.0);
		int minZ = SectionPos.posToSectionCoord(box.minZ - 3.0);
		int maxX = SectionPos.posToSectionCoord(box.maxX + 3.0);
		int maxY = SectionPos.posToSectionCoord(box.maxY + 1.0);
		int maxZ = SectionPos.posToSectionCoord(box.maxZ + 3.0);
		if ((long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > 64) {
			return true; // unusually large box: not worth walking the sections
		}
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = minY; y <= maxY; y++) {
					EntitySection<?> section = storage.getSection(SectionPos.asLong(x, y, z));
					if (section != null && ((SectionExtents) section).ferrite$hardColliders() > 0) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public static int count(ServerLevel level) {
		AtomicInteger c = COUNTS.get(level);
		return c == null ? 0 : c.get();
	}
}
