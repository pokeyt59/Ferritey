package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import me.apika.apikaprobe.compat.ChunkWaitGuards;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * ChunkWaitGuards.ROGUELIKE: Roguelike Dungeons' surroundingChunksLoaded
 * checks the 3x3 chunks around a room with hasChunk (scheduled), then
 * getChunk, which generates a scheduled chunk on the spot. Now each of
 * those chunks counts as loaded only once it and its eight neighbours are
 * generated, so the room waits until the 5x5 chunks around it exist.
 * Rooms also write blocks past the 3x3 (the players bench caught a room
 * fill reading a block entity there), and that generated the chunk too.
 * Applies only with Roguelike Dungeons installed.
 */
@Pseudo
@Mixin(targets = "com.greymerk.roguelike.editor.WorldEditor")
public abstract class RoguelikeLoadedMixin {
	@WrapOperation(method = "surroundingChunksLoaded", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;hasChunk(II)Z"))
	private boolean ferrite$generated(Level level, int chunkX, int chunkZ, Operation<Boolean> original) {
		boolean has = original.call(level, chunkX, chunkZ);
		if (!has || !ChunkWaitGuards.ROGUELIKE || !(level instanceof ServerLevel server)) return has;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (!ChunkWaitGuards.generated(server, chunkX + dx, chunkZ + dz)) {
					ChunkWaitGuards.roguelikeDeferred.increment();
					return false;
				}
			}
		}
		return true;
	}
}
