package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.entity.CrammingDispatcher;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * Intercepts LivingEntity.tickCramming() for Mob subclasses and
 * cancels the vanilla body when the Rust batched dispatcher handled
 * this mob for this tick.
 *
 * The first Mob tickCramming a level sees in a tick triggers that
 * level's batch; each call then applies its own cramming damage and
 * cancels, or runs vanilla if the batch did not take the mob.
 */
@Mixin(LivingEntity.class)
public abstract class CrammingCancelMixin {

	@Inject(method = "pushEntities()V", at = @At("HEAD"), cancellable = true)
	private void ferrite$onTickCramming(CallbackInfo ci) {
		if (!((Object) this instanceof Mob mob)) {
			return; // let vanilla handle non-mobs (players, etc.)
		}
		if (CrammingDispatcher.onTickCramming(mob)) {
			ci.cancel();
		}
	}
}
