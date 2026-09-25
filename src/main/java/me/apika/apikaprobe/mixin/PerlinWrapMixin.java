package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.apika.apikaprobe.worldgen.NoiseMath;

import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

/** Octave coordinate wrapping in Perlin and blended noise goes through NoiseMath.wrap. */
@Mixin({ PerlinNoise.class, BlendedNoise.class })
public abstract class PerlinWrapMixin {

	@Redirect(method = { "getValue(DDDDD)D", "compute" }, require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/synth/PerlinNoise;wrap(D)D"))
	private double ferrite$wrap(double d) {
		return NoiseMath.wrap(d);
	}
}
