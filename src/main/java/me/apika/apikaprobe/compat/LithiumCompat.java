package me.apika.apikaprobe.compat;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import me.apika.apikaprobe.bridge.ExampleMod;

/**
 * Detects Lithium features that overlap Ferrite's. Checks whether Lithium's
 * mixin actually landed on the class, so a feature switched off in
 * lithium.properties reads as absent.
 */
public final class LithiumCompat {
	private LithiumCompat() {}

	private static final String SLEEPING_BLOCK_ENTITY =
			"net.caffeinemc.mods.lithium.common.block.entity.SleepingBlockEntity";

	/**
	 * Lithium already puts unlit furnaces with no cooking progress to sleep,
	 * which covers everything Ferrite's furnace ticker gate does.
	 */
	public static final boolean FURNACE_SLEEPING = detectFurnaceSleeping();

	private static boolean detectFurnaceSleeping() {
		if (!FabricLoader.getInstance().isModLoaded("lithium")) return false;
		boolean sleeping;
		try {
			Class<?> iface = Class.forName(SLEEPING_BLOCK_ENTITY, false,
					AbstractFurnaceBlockEntity.class.getClassLoader());
			sleeping = iface.isAssignableFrom(AbstractFurnaceBlockEntity.class);
		} catch (ClassNotFoundException | LinkageError e) {
			sleeping = false;
		}
		if (sleeping) {
			ExampleMod.LOGGER.info("[compat] Lithium furnace sleeping is active; Ferrite's furnace ticker gate stands down");
		}
		return sleeping;
	}
}
