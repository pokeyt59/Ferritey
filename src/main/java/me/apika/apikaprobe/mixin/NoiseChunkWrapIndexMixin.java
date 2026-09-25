package me.apika.apikaprobe.mixin;

import java.util.Map;
import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import me.apika.apikaprobe.worldgen.DensityWrapIndex;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * NoiseChunk.wrap looks nodes up in DensityWrapIndex instead of vanilla's
 * HashMap, which re-hashes each node's whole subtree. The switch is read
 * once per NoiseChunk, so one NoiseChunk never mixes the two maps.
 * require = 0: if another mod replaces wrap, the index stands down
 * (created stays at 0 in /ferrite worldgen wrap-index status) instead of
 * failing the boot.
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkWrapIndexMixin {

	@Unique
	private DensityWrapIndex ferrite$wrapIndex;
	@Unique
	private boolean ferrite$wrapIndexChosen;

	@WrapOperation(
			method = "wrap",
			at = @At(value = "INVOKE",
					target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"),
			require = 0)
	@SuppressWarnings("unchecked")
	private Object ferrite$indexedWrap(Map<Object, Object> wrapped, Object function,
			Function<Object, Object> wrapNew, Operation<Object> original) {
		if (!ferrite$wrapIndexChosen) {
			ferrite$wrapIndexChosen = true;
			if (DensityWrapIndex.ENABLED) ferrite$wrapIndex = new DensityWrapIndex();
		}
		if (ferrite$wrapIndex == null) return original.call(wrapped, function, wrapNew);
		return ferrite$wrapIndex.computeIfAbsent((DensityFunction) function,
				(Function<? super DensityFunction, ? extends DensityFunction>) (Function<?, ?>) wrapNew);
	}
}
