package me.apika.apikaprobe.mixin;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import me.apika.apikaprobe.worldgen.LazyInterpolation;

import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * The interpolator's side of LazyInterpolation: a read of the value inside
 * the interpolation loop is worked out from the z pair and the chunk's z
 * delta, as updateForZ would have. The value field is read nowhere else.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseChunk$NoiseInterpolator")
public abstract class LazyInterpolatorMixin implements LazyInterpolation.Interpolator {
	@Shadow
	@Final
	NoiseChunk this$0;
	@Shadow
	private double valueZ0;
	@Shadow
	private double valueZ1;

	@Unique
	private double ferrite$savedZ0;
	@Unique
	private double ferrite$savedZ1;

	@Override
	public void ferrite$saveZ() {
		ferrite$savedZ0 = valueZ0;
		ferrite$savedZ1 = valueZ1;
	}

	@ModifyExpressionValue(method = "compute", require = 0,
			at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
					target = "Lnet/minecraft/world/level/levelgen/NoiseChunk$NoiseInterpolator;value:D"))
	private double ferrite$lazyValue(double stored) {
		LazyInterpolation.Chunk chunk = (LazyInterpolation.Chunk) this$0;
		int state = chunk.ferrite$zState();
		if (state == 0) return stored;
		double lazy = state == 1
				? Mth.lerp(chunk.ferrite$zDelta(), valueZ0, valueZ1)
				: Mth.lerp(chunk.ferrite$zDelta(), ferrite$savedZ0, ferrite$savedZ1);
		if (chunk.ferrite$checking()) {
			LazyInterpolation.check(lazy, stored);
			return stored;
		}
		return lazy;
	}
}
