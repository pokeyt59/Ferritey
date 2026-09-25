package me.apika.apikaprobe.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.worldgen.chunk.BlockingLoadBoost;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Chunk generation requests, for BlockingLoadBoost to spot the one a thread then blocks on. */
@Mixin(GenerationChunkHolder.class)
public abstract class ChunkRequestTrackMixin {
	@Shadow
	public abstract ChunkPos getPos();

	@Inject(method = "scheduleChunkGenerationTask", at = @At("RETURN"), require = 0)
	private void ferrite$remember(ChunkStatus status, ChunkMap chunkMap,
			CallbackInfoReturnable<CompletableFuture<?>> cir) {
		BlockingLoadBoost.requested(getPos().pack(), cir.getReturnValue());
	}
}
