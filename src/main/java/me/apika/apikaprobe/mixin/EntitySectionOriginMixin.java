package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;

import me.apika.apikaprobe.spatial.SectionExtents;

/** Stamps each new entity section with its block origin for the query index. */
@Mixin(EntitySectionStorage.class)
public abstract class EntitySectionOriginMixin {

	@Inject(method = "createSection", at = @At("RETURN"))
	private void ferrite$stampOrigin(long sectionPos, CallbackInfoReturnable<EntitySection<?>> cir) {
		((SectionExtents) cir.getReturnValue()).ferrite$setOrigin(
				SectionPos.x(sectionPos) << 4,
				SectionPos.y(sectionPos) << 4,
				SectionPos.z(sectionPos) << 4);
	}
}
