package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.apika.apikaprobe.worldgen.NoiseMath;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;

/** ImprovedNoise's corner sampling goes through NoiseMath (int permutation, flat gradients). */
@Mixin(ImprovedNoise.class)
public abstract class ImprovedNoiseMathMixin {
	@Shadow
	@Final
	private byte[] p;

	@Unique
	private int[] ferrite$perm;

	@Shadow
	private double sampleAndLerp(int x, int y, int z, double xr, double yr, double zr, double yrOriginal) {
		throw new AssertionError();
	}

	@Inject(method = "<init>(Lnet/minecraft/util/RandomSource;)V", at = @At("RETURN"), require = 0)
	private void ferrite$permutation(RandomSource random, CallbackInfo ci) {
		ferrite$perm = NoiseMath.permutation(p);
	}

	@Redirect(method = "noise(DDDDD)D", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/synth/ImprovedNoise;sampleAndLerp(IIIDDDD)D"))
	private double ferrite$sample(ImprovedNoise self, int x, int y, int z, double xr, double yr, double zr,
			double yrOriginal) {
		int[] perm = ferrite$perm;
		if (perm == null || !NoiseMath.sampling()) return sampleAndLerp(x, y, z, xr, yr, zr, yrOriginal);
		double value = NoiseMath.sampleAndLerp(perm, x, y, z, xr, yr, zr, yrOriginal);
		if (NoiseMath.verifying()) NoiseMath.compare(value, sampleAndLerp(x, y, z, xr, yr, zr, yrOriginal));
		return value;
	}
}
