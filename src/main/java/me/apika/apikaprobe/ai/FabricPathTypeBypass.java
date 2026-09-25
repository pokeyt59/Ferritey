package me.apika.apikaprobe.ai;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

import me.apika.apikaprobe.bridge.ExampleMod;

/**
 * Lets PathfindingContext.getPathTypeFromState skip Fabric API's path type
 * hook while no mod has registered a path type.
 *
 * Fabric API (content-registries) hooks getPathTypeFromState, the lookup
 * behind every node a land pathfinder evaluates, ahead of vanilla's
 * PathTypeCache: it reads the block state and looks its block up in
 * LandPathTypeRegistry, and only a registered block changes the result.
 * With nothing registered it is a block lookup and a map lookup on every
 * call, cache hit or not. While the registry is empty (checked on every
 * call) and Fabric's hook is the only other one on the method (checked
 * once), PathfindingContextMixin runs vanilla's body and returns before
 * it. Kill switch -Dferrite.ai.pathtypebypass=false; /ferrite ai
 * pathtype-bypass on|off|status toggles it for A/B.
 */
public final class FabricPathTypeBypass {
	private FabricPathTypeBypass() {}

	public static volatile boolean ENABLED = !"false".equals(System.getProperty("ferrite.ai.pathtypebypass"));

	private static final String CONTEXT = "net/minecraft/world/level/pathfinder/PathfindingContext";
	private static final Set<String> KNOWN_MIXINS = Set.of(
			"net.fabricmc.fabric.mixin.content.registry.PathfindingContextMixin",
			"me.apika.apikaprobe.mixin.PathfindingContextMixin");

	// Server-thread counter, read by /ferrite ai pathtype-bypass status.
	public static long bypassed;

	private static Map<?, ?> registry;
	private static boolean usable;
	private static boolean resolved;
	private static String reason = "not checked yet";

	/** True when this call may skip Fabric's hook. */
	public static boolean active() {
		if (!ENABLED) return false;
		if (!resolved) resolve();
		return usable && registry.isEmpty();
	}

	public static String status() {
		if (!resolved) resolve();
		return usable
				? (registry.isEmpty() ? "usable (no registered path types)"
						: "standing down (" + registry.size() + " registered path types)")
				: "unavailable (" + reason + ")";
	}

	private static synchronized void resolve() {
		if (resolved) return;
		try {
			Class<?> cls = Class.forName("net.fabricmc.fabric.api.registry.LandPathTypeRegistry");
			Field field = cls.getDeclaredField("PATH_TYPES");
			field.setAccessible(true);
			registry = (Map<?, ?>) field.get(null);
			ClassInfo info = ClassInfo.forName(CONTEXT);
			if (info == null) {
				reason = "PathfindingContext not found";
			} else {
				String foreign = null;
				for (IMixinInfo mixin : info.getAppliedMixins()) {
					if (!KNOWN_MIXINS.contains(mixin.getClassName())) foreign = mixin.getClassName();
				}
				if (foreign != null) {
					reason = "another mixin on PathfindingContext: " + foreign;
				} else {
					usable = registry != null;
					reason = usable ? "ok" : "registry is null";
				}
			}
		} catch (ClassNotFoundException e) {
			reason = "Fabric API content registries not installed";
		} catch (ReflectiveOperationException | RuntimeException e) {
			reason = e.toString();
		}
		resolved = true;
		ExampleMod.LOGGER.info("[pathtype-bypass] {}", usable ? "usable" : "unavailable: " + reason);
	}
}
