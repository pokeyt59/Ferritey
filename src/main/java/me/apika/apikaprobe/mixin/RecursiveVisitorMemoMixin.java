package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import me.apika.apikaprobe.worldgen.MappingMemo;

import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * DensityFunction.mapAll (26.2) recurses through this local class: each
 * step is visitor.apply(node.mapChildren(this)). When the visitor is a
 * NoiseChunk's MappingMemo visitor, the step goes through the memo;
 * every other mapAll runs unchanged.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunction$1RecursiveVisitor")
public abstract class RecursiveVisitorMemoMixin {

	@Shadow
	@Final
	DensityFunction.Visitor val$visitor;

	@WrapMethod(
			method = "apply(Lnet/minecraft/world/level/levelgen/DensityFunction;)"
					+ "Lnet/minecraft/world/level/levelgen/DensityFunction;",
			require = 0)
	private DensityFunction ferrite$mapOnce(DensityFunction node, Operation<DensityFunction> original) {
		if (val$visitor instanceof MappingMemo.Visitor memo) {
			return memo.memo().map(node, n -> original.call(n));
		}
		return original.call(node);
	}
}
