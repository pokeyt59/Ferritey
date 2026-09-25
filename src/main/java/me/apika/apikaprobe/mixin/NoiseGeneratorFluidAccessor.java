package me.apika.apikaprobe.mixin;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** The generator's fluid picker (sea and lava levels), for the noise bench. */
@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseGeneratorFluidAccessor {
	@Accessor("globalFluidPicker")
	Supplier<Aquifer.FluidPicker> ferrite$globalFluidPicker();
}
