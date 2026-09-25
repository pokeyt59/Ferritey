package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.world.entity.Mob;

import me.apika.apikaprobe.entity.CrammingState;

/** Carries the per-mob cramming batch bookkeeping. */
@Mixin(Mob.class)
public abstract class CrammingStateMixin implements CrammingState {
	@Unique private long ferrite$pushTick = Long.MIN_VALUE;
	@Unique private long ferrite$batchTick = Long.MIN_VALUE;
	@Unique private int ferrite$crowded;

	@Override
	public long ferrite$pushTick() {
		return ferrite$pushTick;
	}

	@Override
	public void ferrite$setPushTick(long tick) {
		ferrite$pushTick = tick;
	}

	@Override
	public long ferrite$batchTick() {
		return ferrite$batchTick;
	}

	@Override
	public int ferrite$crowded() {
		return ferrite$crowded;
	}

	@Override
	public void ferrite$setBatched(long tick, int crowded) {
		ferrite$batchTick = tick;
		ferrite$crowded = crowded;
	}
}
