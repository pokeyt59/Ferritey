package me.apika.apikaprobe.mixin;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import me.apika.apikaprobe.ai.BrainBehaviorCache;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.schedule.Activity;

/**
 * Walks Brain's behavior table through a flat copy (BrainBehaviorCache)
 * in startEachNonRunningBehavior and getRunningBehaviors. The copy is
 * dropped whenever the table changes (addActivity, removeAllBehaviors,
 * the only writers in 26.2) and rebuilt on next use.
 */
@Mixin(Brain.class)
public abstract class BrainBehaviorCacheMixin<E extends LivingEntity> {

	@Shadow @Final private Map<Integer, Map<Activity, Set<BehaviorControl<? super E>>>> availableBehaviorsByPriority;
	@Shadow @Final private Set<Activity> activeActivities;

	@Unique private BrainBehaviorCache ferrite$cache;

	@Inject(method = "addActivity(Lnet/minecraft/world/entity/schedule/Activity;Lcom/google/common/collect/ImmutableList;Ljava/util/Set;Ljava/util/Set;)V",
			at = @At("HEAD"))
	private void ferrite$dropOnAdd(CallbackInfo ci) {
		ferrite$cache = null;
	}

	@Inject(method = "removeAllBehaviors", at = @At("HEAD"))
	private void ferrite$dropOnRemove(CallbackInfo ci) {
		ferrite$cache = null;
	}

	@Unique
	private BrainBehaviorCache ferrite$table() {
		BrainBehaviorCache cache = BrainBehaviorCache.use(ferrite$cache, availableBehaviorsByPriority);
		ferrite$cache = cache;
		return cache;
	}

	/** Brain.startEachNonRunningBehavior (26.2) over the flat copy. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Inject(method = "startEachNonRunningBehavior", at = @At("HEAD"), cancellable = true)
	private void ferrite$startEachNonRunning(ServerLevel level, E entity, CallbackInfo ci) {
		if (!BrainBehaviorCache.ENABLED) return;
		ci.cancel();
		long time = level.getGameTime();
		BrainBehaviorCache table = ferrite$table();
		Activity[] activities = table.activities;
		BehaviorControl[][] behaviors = table.behaviors;
		for (int g = 0; g < activities.length; g++) {
			if (!activeActivities.contains(activities[g])) continue;
			for (BehaviorControl behavior : behaviors[g]) {
				if (behavior.getStatus() == Behavior.Status.STOPPED) {
					behavior.tryStart(level, entity, time);
				}
			}
		}
	}

	/** Brain.getRunningBehaviors (26.2) over the flat copy. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Inject(method = "getRunningBehaviors", at = @At("HEAD"), cancellable = true)
	private void ferrite$getRunning(CallbackInfoReturnable<List<BehaviorControl<? super E>>> cir) {
		if (!BrainBehaviorCache.ENABLED) return;
		List running = new ObjectArrayList();
		for (BehaviorControl[] group : ferrite$table().behaviors) {
			for (BehaviorControl behavior : group) {
				if (behavior.getStatus() == Behavior.Status.RUNNING) running.add(behavior);
			}
		}
		cir.setReturnValue(running);
	}
}
