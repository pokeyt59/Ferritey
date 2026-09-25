package me.apika.apikaprobe.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.worldgen.chunk.BlockingLoadBoost;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Chunks the server thread asks for and does not have yet: tracked by
 * BlockingLoadBoost until ready. getChunkFutureMainThread is the server
 * thread's own chunk request, behind getChunk's blocking wait.
 */
@Mixin(ServerChunkCache.class)
public abstract class SyncChunkLoadTrackMixin {

	@Inject(method = "getChunkFutureMainThread", at = @At("RETURN"), require = 0)
	private void ferrite$track(int x, int z, ChunkStatus status, boolean load,
			CallbackInfoReturnable<CompletableFuture<?>> cir) {
		if (load) BlockingLoadBoost.track(ChunkPos.pack(x, z), cir.getReturnValue());
	}
}
