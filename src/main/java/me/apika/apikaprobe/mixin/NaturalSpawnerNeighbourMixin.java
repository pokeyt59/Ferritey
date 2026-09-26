package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import me.apika.apikaprobe.compat.ChunkWaitGuards;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;

/**
 * ChunkWaitGuards.SPAWNING: a spawn attempt that crosses into a
 * neighbouring chunk not generated yet is rejected, as for a chunk where
 * spawning is not allowed, instead of generating it on the server thread.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerNeighbourMixin {
	@WrapOperation(method = "isRightDistanceToPlayerAndSpawnPoint", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;canSpawnEntitiesInChunk(Lnet/minecraft/world/level/ChunkPos;)Z"))
	private static boolean ferrite$generated(ServerLevel level, ChunkPos pos, Operation<Boolean> original) {
		boolean allowed = original.call(level, pos);
		if (!allowed || !ChunkWaitGuards.SPAWNING) return allowed;
		if (ChunkWaitGuards.generated(level, pos.x(), pos.z())) return true;
		ChunkWaitGuards.spawnRejected.increment();
		return false;
	}
}
