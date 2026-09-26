package me.apika.apikaprobe.mixin;

import java.util.function.Predicate;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.worldgen.LegacyBiomeFold;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

/**
 * LegacyBiomeFold: Climate Rivers' biome test becomes the game's constant
 * condition in chunks where its answer cannot vary. Applies only with
 * Climate Rivers installed.
 */
@Pseudo
@Mixin(targets = "fuzs.climaterivers.common.world.level.levelgen.LegacyBiomeConditionSource")
public abstract class ClimateRiversBiomeFoldMixin {
	@Shadow
	@Final
	private Predicate<ResourceKey<Biome>> biomeNameTest;

	// Takes only the callback: the context's type is not public; the fold
	// reads it from the condition apply built.
	@Inject(method = "apply(Lnet/minecraft/world/level/levelgen/SurfaceRules$Context;)Lnet/minecraft/world/level/levelgen/SurfaceRules$Condition;",
			at = @At("RETURN"), cancellable = true, require = 0)
	private void ferrite$fold(CallbackInfoReturnable<Object> cir) {
		Object built = cir.getReturnValue();
		Object folded = LegacyBiomeFold.fold(built, biomeNameTest);
		if (folded != built) cir.setReturnValue(folded);
	}
}
