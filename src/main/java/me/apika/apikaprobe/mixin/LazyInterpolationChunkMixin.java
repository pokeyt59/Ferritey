package me.apika.apikaprobe.mixin;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.apika.apikaprobe.worldgen.LazyInterpolation;

import net.minecraft.world.level.levelgen.NoiseChunk;

/**
 * NoiseChunk's side of LazyInterpolation: the per-block z step keeps its
 * delta and skips stepping the interpolators; an x step after a z step
 * saves each interpolator's z pair first. Both through the loops'
 * List.iterator() call, so nothing is allocated per block.
 */
@Mixin(NoiseChunk.class)
public abstract class LazyInterpolationChunkMixin implements LazyInterpolation.Chunk {
	@Shadow
	@Final
	private List<?> interpolators;

	@Unique
	private int ferrite$mode = LazyInterpolation.modeForNewChunk();
	@Unique
	private int ferrite$zState;
	@Unique
	private double ferrite$zDelta;

	@Redirect(method = "updateForZ", require = 0,
			at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
	private Iterator<?> ferrite$lazyZ(List<?> list, int blockZ, double delta) {
		if (ferrite$mode == 0) return list.iterator();
		ferrite$zDelta = delta;
		ferrite$zState = 1;
		// Checking: step them anyway, and compare every read with the stepped value.
		return ferrite$mode == 2 ? list.iterator() : Collections.emptyIterator();
	}

	@Redirect(method = "updateForX", require = 0,
			at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
	private Iterator<?> ferrite$saveBeforeX(List<?> list, int blockX, double delta) {
		if (ferrite$zState == 1) {
			for (int i = 0, n = interpolators.size(); i < n; i++) {
				((LazyInterpolation.Interpolator) interpolators.get(i)).ferrite$saveZ();
			}
			ferrite$zState = 2;
		}
		return list.iterator();
	}

	@Override
	public int ferrite$zState() {
		return ferrite$zState;
	}

	@Override
	public double ferrite$zDelta() {
		return ferrite$zDelta;
	}

	@Override
	public boolean ferrite$checking() {
		return ferrite$mode == 2;
	}
}
