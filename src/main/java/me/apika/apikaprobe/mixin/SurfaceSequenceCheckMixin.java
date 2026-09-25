package me.apika.apikaprobe.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

import me.apika.apikaprobe.worldgen.SurfaceRulePrune;

import net.minecraft.world.level.block.state.BlockState;

/** SurfaceRulePrune's oracle: a checked sequence's results against its full rule list. */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRule")
public abstract class SurfaceSequenceCheckMixin {
	@Unique
	private List<?> ferrite$fullRules;

	@Inject(method = "<init>", at = @At("RETURN"), require = 0)
	private void ferrite$takeFull(List<?> rules, CallbackInfo ci) {
		ferrite$fullRules = SurfaceRulePrune.takeFullList();
	}

	@ModifyReturnValue(method = "tryApply", at = @At("RETURN"), require = 0)
	private BlockState ferrite$check(BlockState result, @Local(argsOnly = true, ordinal = 0) int x,
			@Local(argsOnly = true, ordinal = 1) int y, @Local(argsOnly = true, ordinal = 2) int z) {
		List<?> full = ferrite$fullRules;
		if (full != null) SurfaceRulePrune.check(full, result, x, y, z);
		return result;
	}
}
