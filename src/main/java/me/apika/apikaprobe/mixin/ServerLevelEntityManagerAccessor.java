package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

/** Reaches the level's entity section manager for the per-section collider check. */
@Mixin(ServerLevel.class)
public interface ServerLevelEntityManagerAccessor {
	@Accessor("entityManager")
	PersistentEntitySectionManager<Entity> ferrite$entityManager();
}
