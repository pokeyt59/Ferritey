package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;

/** The level a ChunkMap belongs to, for SyncLoadPrefetch. */
@Mixin(ChunkMap.class)
public interface ChunkMapLevelAccessor {
	@Accessor("level")
	ServerLevel ferrite$level();
}
