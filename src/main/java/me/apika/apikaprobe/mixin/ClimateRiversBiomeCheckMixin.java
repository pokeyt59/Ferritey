package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import me.apika.apikaprobe.worldgen.LegacyBiomeFold;

/** LegacyBiomeFold's oracle: a condition left unfolded on purpose, checked at every block. */
@Pseudo
@Mixin(targets = "fuzs.climaterivers.common.world.level.levelgen.LegacyBiomeConditionSource$1")
public abstract class ClimateRiversBiomeCheckMixin implements LegacyBiomeFold.Checked {
	@Unique
	private int ferrite$expected = -1;

	@Override
	public void ferrite$expect(int constant) {
		ferrite$expected = constant;
	}

	@ModifyReturnValue(method = "compute()Z", at = @At("RETURN"), require = 0)
	private boolean ferrite$check(boolean result) {
		if (ferrite$expected >= 0) LegacyBiomeFold.check(ferrite$expected, result);
		return result;
	}
}
