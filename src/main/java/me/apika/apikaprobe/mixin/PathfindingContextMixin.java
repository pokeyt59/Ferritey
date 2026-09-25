package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import me.apika.apikaprobe.ai.FabricPathTypeBypass;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.pathfinder.PathfindingContext;

/**
 * Runs PathfindingContext.getPathTypeFromState's vanilla body from HEAD,
 * ahead of Fabric API's path type hook, while FabricPathTypeBypass says
 * that hook cannot change the result (see there).
 */
@Mixin(PathfindingContext.class)
public abstract class PathfindingContextMixin {

	@Shadow @Final private BlockPos.MutableBlockPos mutablePos;
	@Shadow @Final private PathTypeCache cache;
	@Shadow @Final private CollisionGetter level;

	/** PathfindingContext.getPathTypeFromState (26.2), cached branch. */
	@Inject(method = "getPathTypeFromState(III)Lnet/minecraft/world/level/pathfinder/PathType;",
			at = @At("HEAD"), cancellable = true)
	private void ferrite$skipEmptyFabricHook(int x, int y, int z, CallbackInfoReturnable<PathType> cir) {
		// Without a cache (never on a server level) the method runs as is.
		if (cache == null || !FabricPathTypeBypass.active()) return;
		FabricPathTypeBypass.bypassed++;
		cir.setReturnValue(cache.getOrCompute(level, mutablePos.set(x, y, z)));
	}
}
