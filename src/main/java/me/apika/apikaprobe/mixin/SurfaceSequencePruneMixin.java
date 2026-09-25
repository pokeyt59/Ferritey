package me.apika.apikaprobe.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import me.apika.apikaprobe.worldgen.SurfaceRulePrune;

/** A surface rule sequence is built from SurfaceRulePrune's list. */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$SequenceRuleSource")
public abstract class SurfaceSequencePruneMixin {

	@ModifyArg(method = "apply(Lnet/minecraft/world/level/levelgen/SurfaceRules$Context;)Lnet/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule;",
			require = 0, at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/levelgen/SurfaceRules$SequenceRule;<init>(Ljava/util/List;)V"))
	private List<?> ferrite$prune(List<?> rules) {
		return SurfaceRulePrune.prune(rules);
	}
}
