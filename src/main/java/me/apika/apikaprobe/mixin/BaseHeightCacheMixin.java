package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import me.apika.apikaprobe.worldgen.BaseHeightCache;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Height queries go through BaseHeightCache. require = 0: if the method
 * cannot be wrapped, queries keep the generator's own path (queries stays
 * at 0 in /ferrite worldgen height-cache status).
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class BaseHeightCacheMixin {

	@WrapMethod(method = "getBaseHeight", require = 0)
	private int ferrite$cachedBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level,
			RandomState random, Operation<Integer> original) {
		return BaseHeightCache.getBaseHeight(this, x, z, type, level, random,
				() -> original.call(x, z, type, level, random));
	}
}
