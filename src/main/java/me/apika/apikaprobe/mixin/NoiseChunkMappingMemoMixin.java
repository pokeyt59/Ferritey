package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import me.apika.apikaprobe.worldgen.MappingMemo;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * NoiseChunk's router mappings get a MappingMemo visitor around its own
 * wrap visitor, so mapAll's recursion (RecursiveVisitorMemoMixin) maps
 * each shared node once. All of a NoiseChunk's mappings use `this::wrap`;
 * mappings through a visitor of another class get a memo of their own.
 * require = 0: without these hooks, mapping stays vanilla's.
 */
@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMappingMemoMixin {

	@Unique
	private MappingMemo.Visitor ferrite$memoVisitor;

	@Unique
	private DensityFunction.Visitor ferrite$remembering(DensityFunction.Visitor visitor) {
		if (!MappingMemo.ENABLED) return visitor;
		MappingMemo.Visitor memo = ferrite$memoVisitor;
		if (memo != null && memo.delegate().getClass() == visitor.getClass()) return memo;
		memo = new MappingMemo.Visitor(visitor);
		if (ferrite$memoVisitor == null) ferrite$memoVisitor = memo;
		return memo;
	}

	@WrapOperation(
			method = "<init>",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;"
					+ "mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)"
					+ "Lnet/minecraft/world/level/levelgen/NoiseRouter;"),
			require = 0)
	private NoiseRouter ferrite$rememberRouter(NoiseRouter router, DensityFunction.Visitor visitor,
			Operation<NoiseRouter> original) {
		return original.call(router, ferrite$remembering(visitor));
	}

	@WrapOperation(
			method = {"<init>", "cachedClimateSampler"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunction;"
					+ "mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)"
					+ "Lnet/minecraft/world/level/levelgen/DensityFunction;"),
			require = 0)
	private DensityFunction ferrite$rememberFunction(DensityFunction function, DensityFunction.Visitor visitor,
			Operation<DensityFunction> original) {
		return original.call(function, ferrite$remembering(visitor));
	}
}
