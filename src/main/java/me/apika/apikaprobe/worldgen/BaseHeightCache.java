package me.apika.apikaprobe.worldgen;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

import me.apika.apikaprobe.bridge.ExampleMod;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Remembers NoiseBasedChunkGenerator.getBaseHeight answers.
 *
 * Structure placement asks the generator for terrain heights: at every
 * structure start, and for jigsaw pieces that follow the terrain, often
 * for the same columns again. Each query builds a whole NoiseChunk for
 * one column, mapping the full noise router (Terralith's expands to tens
 * of thousands of nodes): about 6 ms on the CI bench's CPU.
 *
 * The answer depends only on the generator (its settings and fluid
 * picker), the RandomState, the level's minimum Y and height, the column
 * and the heightmap type: the query reads noise, never the world. Entries
 * are keyed on all of them (generator and RandomState by identity), so a
 * hit returns exactly what the query would compute. The table is
 * direct-mapped and fixed-size; a colliding query replaces the entry.
 * Entries are immutable, so threads share the table without locks.
 *
 * Oracle: every ORACLE_EVERY-th hit, the first included, is computed
 * anyway and compared.
 * Off: -Dferrite.worldgen.heightcache=false or /ferrite worldgen height-cache off.
 */
public final class BaseHeightCache {
	private BaseHeightCache() {}

	public static volatile boolean ENABLED = !"false".equals(System.getProperty("ferrite.worldgen.heightcache"));

	private static final int SIZE = 1 << 14;
	private static final int ORACLE_EVERY = 64;
	private static final Entry[] TABLE = new Entry[SIZE];

	public static final LongAdder queries = new LongAdder();
	public static final AtomicLong hits = new AtomicLong();
	public static final LongAdder oracleChecks = new LongAdder();
	public static final LongAdder oracleMismatches = new LongAdder();

	private record Entry(Object generator, RandomState random, int minY, int height,
			int x, int z, int type, int value) {}

	/** getBaseHeight through the cache; compute runs the generator's own query. */
	public static int getBaseHeight(Object generator, int x, int z, Heightmap.Types type,
			LevelHeightAccessor level, RandomState random, IntSupplier compute) {
		if (!ENABLED) return compute.getAsInt();
		queries.increment();
		int minY = level.getMinY();
		int height = level.getHeight();
		int t = type.ordinal();
		int slot = slot(x, z, t);
		Entry e = TABLE[slot];
		if (e != null && e.generator == generator && e.random == random && e.minY == minY
				&& e.height == height && e.x == x && e.z == z && e.type == t) {
			if (hits.getAndIncrement() % ORACLE_EVERY != 0) return e.value;
			int actual = compute.getAsInt();
			oracleChecks.increment();
			if (actual != e.value) {
				oracleMismatches.increment();
				if (oracleMismatches.sum() <= 5) {
					ExampleMod.LOGGER.warn("[height-cache] MISMATCH at {} {} {}: cached {}, computed {}",
							x, z, type, e.value, actual);
				}
			}
			return actual;
		}
		int value = compute.getAsInt();
		TABLE[slot] = new Entry(generator, random, minY, height, x, z, t, value);
		return value;
	}

	private static int slot(int x, int z, int type) {
		long h = (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ (type * 0x165667B19E3779F9L);
		h ^= h >>> 29;
		return (int) h & (SIZE - 1);
	}

	public static String status() {
		long q = queries.sum();
		long h = hits.get();
		return String.format("[height-cache] height-cache=%s queries=%d hits=%d (%.1f%%) oracleChecks=%d oracleMismatches=%d",
				ENABLED ? "on" : "off", q, h, q == 0 ? 0.0 : 100.0 * h / q,
				oracleChecks.sum(), oracleMismatches.sum());
	}
}
