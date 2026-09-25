package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import me.apika.apikaprobe.spatial.ClipAirSkip;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Line-of-sight rays take ClipAirSkip's clip, which answers air blocks
 * without the shape clip. Other clip callers keep the original (vanilla's,
 * or Lithium's). require = 0: if another mod replaces hasLineOfSight, the
 * skip stands down (rays stays at 0 in /ferrite raycast air-skip status)
 * instead of failing the boot.
 */
@Mixin(LivingEntity.class)
public abstract class LineOfSightClipMixin {

	@WrapOperation(
			method = "hasLineOfSight",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;clip"
					+ "(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"),
			require = 0)
	private BlockHitResult ferrite$airSkipClip(Level level, ClipContext context,
			Operation<BlockHitResult> original) {
		BlockHitResult fast = ClipAirSkip.clip(level, context);
		if (fast == null) return original.call(level, context);
		if (ClipAirSkip.sample()) {
			BlockHitResult reference = original.call(level, context);
			ClipAirSkip.check(context, fast, reference);
			return reference;
		}
		return fast;
	}
}
