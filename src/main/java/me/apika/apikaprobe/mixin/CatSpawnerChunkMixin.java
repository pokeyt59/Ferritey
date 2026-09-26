package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import me.apika.apikaprobe.compat.ChunkWaitGuards;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.level.LevelReader;

/**
 * ChunkWaitGuards.SPAWNERS: once a minute the cat spawner tries a spot 8-24
 * blocks from a random player, after checking only that the chunks there
 * are scheduled. A spot whose chunk is not generated yet is now skipped
 * (as a spot that fails the placement check is) instead of generated on
 * the server thread.
 */
@Mixin(CatSpawner.class)
public abstract class CatSpawnerChunkMixin {
	@WrapOperation(method = "tick", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/SpawnPlacements;isSpawnPositionOk(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"))
	private boolean ferrite$generated(EntityType<?> type, LevelReader level, BlockPos pos, Operation<Boolean> original) {
		if (ChunkWaitGuards.SPAWNERS && level instanceof ServerLevel server
				&& !ChunkWaitGuards.generated(server, pos.getX() >> 4, pos.getZ() >> 4)) {
			ChunkWaitGuards.spawnerSkipped.increment();
			return false;
		}
		return original.call(type, level, pos);
	}
}
