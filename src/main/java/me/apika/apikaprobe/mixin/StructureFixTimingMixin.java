package me.apika.apikaprobe.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.DataFixer;

import me.apika.apikaprobe.worldgen.StructureFixCache;
import me.apika.apikaprobe.worldgen.StructureFixTiming;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;

/**
 * Structure template upgrades go through StructureFixCache and are timed;
 * every other data type passes straight through.
 */
@Mixin(DataFixTypes.class)
public abstract class StructureFixTimingMixin {

	@WrapMethod(
			method = "update(Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/nbt/CompoundTag;II)"
					+ "Lnet/minecraft/nbt/CompoundTag;",
			require = 0)
	private CompoundTag ferrite$timeStructureFix(DataFixer fixer, CompoundTag tag, int from, int to,
			Operation<CompoundTag> original) {
		if ((Object) this != DataFixTypes.STRUCTURE) return original.call(fixer, tag, from, to);
		long start = System.nanoTime();
		try {
			return StructureFixCache.upgrade(tag, from, to, () -> original.call(fixer, tag, from, to));
		} finally {
			StructureFixTiming.record(System.nanoTime() - start);
		}
	}
}
