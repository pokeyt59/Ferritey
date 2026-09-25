package me.apika.apikaprobe.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import me.apika.apikaprobe.worldgen.chunk.BlockingLoadBoost;

/** Before the chunk task queue gives out a task: BlockingLoadBoost may move one ahead. */
@Mixin(targets = "net.minecraft.server.level.ChunkTaskPriorityQueue")
public abstract class ChunkTaskQueueBoostMixin {
	@Shadow
	@Final
	private List<Long2ObjectLinkedOpenHashMap<List<Runnable>>> queuesPerPriority;
	@Shadow
	private volatile int topPriorityQueueIndex;

	@Inject(method = "pop", at = @At("HEAD"), require = 0)
	private void ferrite$moveWaitedAhead(CallbackInfoReturnable<?> cir) {
		BlockingLoadBoost.beforePop(queuesPerPriority, topPriorityQueueIndex);
	}
}
