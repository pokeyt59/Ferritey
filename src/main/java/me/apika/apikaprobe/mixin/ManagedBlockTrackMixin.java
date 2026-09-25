package me.apika.apikaprobe.mixin;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.worldgen.chunk.BlockingLoadBoost;

import net.minecraft.util.thread.BlockableEventLoop;

/** A thread blocking in an executor: BlockingLoadBoost checks whether it waits for a chunk. */
@Mixin(BlockableEventLoop.class)
public abstract class ManagedBlockTrackMixin {

	@Inject(method = "managedBlock", at = @At("HEAD"), require = 0)
	private void ferrite$beforeBlock(BooleanSupplier condition, CallbackInfo ci) {
		BlockingLoadBoost.beforeBlock(this);
	}
}
