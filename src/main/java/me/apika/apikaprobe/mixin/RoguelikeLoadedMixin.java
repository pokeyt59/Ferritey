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
 * checks hasChunk (scheduled) and then getChunk, which generates a
 * scheduled chunk on the spot. A chunk not generated yet now counts as
 * not loaded, so the room waits for it. Applies only with Roguelike
 * Dungeons installed.
 */
@Pseudo
@Mixin(targets = "com.greymerk.roguelike.editor.WorldEditor")
public abstract class RoguelikeLoadedMixin {
	@WrapOperation(method = "surroundingChunksLoaded", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;hasChunk(II)Z"))
	private boolean ferrite$generated(Level level, int chunkX, int chunkZ, Operation<Boolean> original) {
		boolean has = original.call(level, chunkX, chunkZ);
		if (!has || !ChunkWaitGuards.ROGUELIKE || !(level instanceof ServerLevel server)) return has;
		if (ChunkWaitGuards.generated(server, chunkX, chunkZ)) return true;
		ChunkWaitGuards.roguelikeDeferred.increment();
		return false;
	}
}
