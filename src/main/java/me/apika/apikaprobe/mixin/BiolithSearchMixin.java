package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.worldgen.BiomeSearch;

import net.minecraft.world.level.biome.Climate;

/**
 * Biolith's biome lookup (VanillaCompat.getBiome, its search of the
 * climate tree with the tree's own node distance) goes through
 * BiomeSearch, which runs the same search on flat arrays. Applies only
 * when Biolith is installed (@Pseudo, require = 0).
 */
@Pseudo
@Mixin(targets = "com.terraformersmc.biolith.impl.compat.VanillaCompat")
public abstract class BiolithSearchMixin {

	@Inject(method = "getBiome", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private static void ferrite$flatSearch(Climate.TargetPoint point, Climate.ParameterList<?> parameters,
			CallbackInfoReturnable<Object> cir) {
		if (BiomeSearch.bypassed()) return;
		Object result = BiomeSearch.search(parameters, point);
		if (result != null) cir.setReturnValue(result);
	}
}
