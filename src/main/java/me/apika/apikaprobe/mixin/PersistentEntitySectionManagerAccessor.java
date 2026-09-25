package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

/** Reaches the section storage behind a PersistentEntitySectionManager. */
@Mixin(PersistentEntitySectionManager.class)
public interface PersistentEntitySectionManagerAccessor {
	@Accessor("sectionStorage")
	EntitySectionStorage<?> ferrite$sectionStorage();
}
