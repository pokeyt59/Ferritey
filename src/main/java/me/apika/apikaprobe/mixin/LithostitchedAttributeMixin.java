package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import me.apika.apikaprobe.compat.ChunkWaitGuards;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * ChunkWaitGuards.LITHOSTITCHED: Lithostitched's structure attribute check
 * skips a player whose chunk is not generated yet, and checks again 5
 * ticks later. Applies only with Lithostitched installed.
 */
@Pseudo
@Mixin(targets = "dev.worldgen.lithostitched.worldgen.structure.StructureAttributeHandler")
public abstract class LithostitchedAttributeMixin {
	@WrapOperation(method = "tick(Lnet/minecraft/server/level/ServerLevel;)V", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isLoaded(Lnet/minecraft/core/BlockPos;)Z"))
	private static boolean ferrite$generated(ServerLevel level, BlockPos pos, Operation<Boolean> original) {
		boolean loaded = original.call(level, pos);
		if (!loaded || !ChunkWaitGuards.LITHOSTITCHED) return loaded;
		if (ChunkWaitGuards.generated(level, pos.getX() >> 4, pos.getZ() >> 4)) return true;
		ChunkWaitGuards.lithostitchedDeferred.increment();
		return false;
	}
}
