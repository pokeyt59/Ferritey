package me.apika.apikaprobe.mixin;

import java.util.function.BiFunction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import me.apika.apikaprobe.spatial.ClipAirSkip;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Hands vanilla's ray walk in clip a per-block function that answers air
 * itself (see ClipAirSkip). The walk and every non-air block stay vanilla.
 * require = 0: if another mod overwrites clip, the skip stands down (the
 * rays counter in /ferrite raycast air-skip status stays at 0) instead of
 * failing the boot.
 */
@Mixin(BlockGetter.class)
public interface BlockGetterClipMixin {

	@ModifyArg(
			method = "clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockGetter;traverseBlocks"
					+ "(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Ljava/lang/Object;"
					+ "Ljava/util/function/BiFunction;Ljava/util/function/Function;)Ljava/lang/Object;"),
			index = 3,
			require = 0)
	default BiFunction<ClipContext, BlockPos, BlockHitResult> ferrite$airSkip(
			BiFunction<ClipContext, BlockPos, BlockHitResult> perBlock) {
		return ClipAirSkip.wrap(this, perBlock);
	}
}
