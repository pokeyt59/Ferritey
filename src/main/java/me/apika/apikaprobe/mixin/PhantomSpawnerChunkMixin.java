package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import me.apika.apikaprobe.compat.ChunkWaitGuards;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.level.levelgen.PhantomSpawner;

/**
 * ChunkWaitGuards.SPAWNERS: every 60-120 s at night the phantom spawner
 * reads the difficulty at each player's position, then a block up to 10
 * blocks away. The difficulty lookup asks for the player's chunk without
 * loading it, but on the server thread that still waits for a chunk that
 * is scheduled and not generated yet, which a flying player's often is.
 * When a chunk this attempt could read is not generated, the player is
 * skipped until the next attempt: the answer is a peaceful difficulty,
 * whose isHarderThan check the spawner's roll never passes.
 */
@Mixin(PhantomSpawner.class)
public abstract class PhantomSpawnerChunkMixin {
	@WrapOperation(method = "tick", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getCurrentDifficultyAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/DifficultyInstance;"))
	private DifficultyInstance ferrite$generated(ServerLevel level, BlockPos pos, Operation<DifficultyInstance> original) {
		if (ChunkWaitGuards.SPAWNERS && !ChunkWaitGuards.generatedAround(level, pos.getX(), pos.getZ(), 10)) {
			ChunkWaitGuards.spawnerSkipped.increment();
			// Effective difficulty 0: isHarderThan(roll) is false for every roll.
			return new DifficultyInstance(Difficulty.PEACEFUL, 0L, 0L, 0f);
		}
		return original.call(level, pos);
	}
}
