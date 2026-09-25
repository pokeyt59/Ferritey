package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseChunk;

/** NoiseChunk's block state at the current interpolation position and cell size, for the noise bench. */
@Mixin(NoiseChunk.class)
public interface NoiseChunkStateInvoker {
	@Invoker("getInterpolatedState")
	BlockState ferrite$interpolatedState();

	@Invoker("cellWidth")
	int ferrite$cellWidth();

	@Invoker("cellHeight")
	int ferrite$cellHeight();
}
