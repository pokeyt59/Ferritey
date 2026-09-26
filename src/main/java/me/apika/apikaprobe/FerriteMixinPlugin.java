package me.apika.apikaprobe;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.fabricmc.loader.api.FabricLoader;

import me.apika.apikaprobe.monitor.FerriteLogFile;

public class FerriteMixinPlugin implements IMixinConfigPlugin {

	private static final Logger LOGGER = LoggerFactory.getLogger("ferrite");

	// Moonrise replaces ThreadedLevelLightEngine internals; these monitor-only
	// mixins lose their injection targets there, so skip them (issue #12).
	private static final Set<String> LIGHT_MONITOR_MIXINS = Set.of(
			"me.apika.apikaprobe.mixin.ThreadedLevelLightEngineMixin",
			"me.apika.apikaprobe.mixin.LightTimingMixin");

	// Timing-only hooks. They feed the periodic monitor reports and nothing
	// else, so lean mode (diagnostics off) skips them at load.
	private static final String P = "me.apika.apikaprobe.mixin.";
	private static final Set<String> TIMING_MIXINS = Set.of(
			P + "WorldTickMixin", P + "ServerWorldTickMixin", P + "EntityCategoryMixin",
			P + "MonsterBaseTickMixin", P + "MonsterMovementMixin", P + "MonsterMobTickMixin",
			P + "CrammingMixin", P + "BlockCollisionMixin", P + "NavigatorTickMixin",
			P + "EntityMoveMixin", P + "TravelMixin", P + "GravityMixin",
			P + "AdjustCollisionsMixin", P + "TickHandSwingMixin", P + "TickNewAiMixin",
			P + "ActiveTargetGoalMixin", P + "GoalSelectorMixin", P + "MoveControlMixin",
			P + "LookControlMixin", P + "ServerTickPhaseMixin",
			P + "ThreadedLevelLightEngineMixin", P + "LightTimingMixin",
			P + "ChunkDecoratorTimingMixin", P + "FerriteDispatcherProbeMixin",
			P + "AbstractConsecutiveExecutorDurationMixin", P + "ChunkStageTimingMixin",
			P + "RedstoneWireMixin", P + "RedstoneGateMixin", P + "DefaultRedstoneControllerMixin",
			P + "EntitySectionStorageMixin");

	// C2ME (priority 1100) rewrites the classes these hook: NoiseChunk and
	// its interpolators and caches, the density function visitor, the noise
	// router's RandomState, ImprovedNoise/PerlinNoise/BlendedNoise, the
	// noise generator, DataFixTypes, and the surface rule sequences (its
	// opts-dfc, opts-math, opts-natives-math, opts-allocs, chunk system and
	// threading modules). Mixin refuses to inject into a method a
	// higher-priority mixin replaced, so the server would not start: with
	// C2ME these stand down and C2ME's versions run. Accessors and invokers
	// stay; they only call.
	private static final Set<String> C2ME_OVERLAP_MIXINS = Set.of(
			P + "SurfaceSequencePruneMixin", P + "SurfaceSequenceCheckMixin",
			P + "ImprovedNoiseMathMixin", P + "PerlinWrapMixin",
			P + "LazyInterpolationChunkMixin", P + "LazyInterpolatorMixin",
			P + "NoiseChunkMappingMemoMixin", P + "RecursiveVisitorMemoMixin",
			P + "BaseHeightCacheMixin", P + "StructureFixTimingMixin",
			P + "AquiferRouteMixin", P + "BulkChunkDensityMixin", P + "CacheRouteCaptureMixin",
			P + "ChunkNoiseSamplerMixin", P + "NoiseConfigCaptureMixin");

	// Also carries the opt-in nav-cache path parity check.
	private static final String PATH_FINDER_MIXIN = P + "PathFinderMixin";

	// Hooks for the Rust physics port. Nothing sets PhysicsDispatcher.ENABLED
	// or PARITY_MODE, so they stay out unless -Dferrite.physics.hooks=true.
	private static final Set<String> PHYSICS_MIXINS = Set.of(
			P + "MovementRedirectMixin", P + "PhysicsPreTickMixin", P + "EntityAdjustInvoker");

	// Walkability cache hooks: getPathTypeFromState runs for every node a
	// pathfinder expands, setBlock for every block change. The cache flag is
	// read once at boot (NavigationCacheBridge.WALK_CACHE_ENABLED), so with
	// it off the hooks can only return; leave them out.
	private static final Set<String> NAV_CACHE_MIXINS = Set.of(
			P + "WalkNodeEvaluatorMixin", P + "LevelSetBlockMixin");

	/** System property the mod reads to learn which mode the plugin chose. */
	public static final String DIAGNOSTICS_PROPERTY = "ferrite.diagnostics.effective";

	private boolean moonrise;
	private boolean c2me;
	private boolean loggedC2me;
	private boolean logged;
	private boolean diagnostics;
	private boolean navParity;
	private boolean physicsHooks;
	private boolean navCache;

	@Override
	public void onLoad(String mixinPackage) {
		FabricLoader loader = FabricLoader.getInstance();
		Path savedConfig = loader.getConfigDir().resolve("ferrite.properties");
		// Ferrite's own log file, before anything else here logs.
		String logFile = System.getProperty(FerriteLogFile.PROPERTY);
		if (logFile == null || logFile.isEmpty()) logFile = readSaved(savedConfig, "log-file");
		FerriteLogFile.install(loader.getGameDir(), logFile == null || Boolean.parseBoolean(logFile));
		this.moonrise = loader.isModLoaded("moonrise");
		this.c2me = loader.isModLoaded("c2me");
		this.navParity = Boolean.parseBoolean(System.getProperty("ferrite.nav.parity", "false"));
		this.physicsHooks = Boolean.getBoolean("ferrite.physics.hooks");
		// Same parse as NavigationCacheBridge.WALK_CACHE_ENABLED.
		this.navCache = Boolean.parseBoolean(System.getProperty("ferrite.nav.cache", "false"));

		String reason;
		String prop = System.getProperty("ferrite.diagnostics");
		String saved = readSaved(savedConfig, "diagnostics");
		if (prop != null && !prop.isEmpty()) {
			diagnostics = Boolean.parseBoolean(prop);
			reason = "-Dferrite.diagnostics";
		} else if (saved != null) {
			diagnostics = Boolean.parseBoolean(saved);
			reason = "config/ferrite.properties";
		} else {
			// spark already profiles the server, so the timing hooks are
			// redundant weight on every entity tick.
			diagnostics = !loader.isModLoaded("spark");
			reason = diagnostics ? "default" : "spark detected";
		}
		System.setProperty(DIAGNOSTICS_PROPERTY, Boolean.toString(diagnostics));
		if (!diagnostics) {
			LOGGER.info("[ferrite] lean mode ({}): {} timing mixins skipped; -Dferrite.diagnostics=true restores them",
					reason, TIMING_MIXINS.size() + (navParity ? 0 : 1));
		}
	}

	/** Plain Properties read: FerriteConfig pulls in game classes, too early here. */
	private static String readSaved(Path file, String key) {
		if (!Files.isRegularFile(file)) return null;
		Properties props = new Properties();
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			props.load(in);
		} catch (IOException | IllegalArgumentException e) {
			return null;
		}
		return props.getProperty(key);
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!physicsHooks && PHYSICS_MIXINS.contains(mixinClassName)) {
			return false;
		}
		if (!navCache && NAV_CACHE_MIXINS.contains(mixinClassName)) {
			return false;
		}
		if (!diagnostics && (TIMING_MIXINS.contains(mixinClassName)
				|| (!navParity && PATH_FINDER_MIXIN.equals(mixinClassName)))) {
			return false;
		}
		if (c2me && C2ME_OVERLAP_MIXINS.contains(mixinClassName)) {
			if (!loggedC2me) {
				LOGGER.info("[ferrite] C2ME detected: {} worldgen hooks stand down (noise sampling, lazy interpolation, "
						+ "mapping memo, height cache, structure fix cache, surface pruning); C2ME rewrites the same code",
						C2ME_OVERLAP_MIXINS.size());
				loggedC2me = true;
			}
			return false;
		}
		if (moonrise && LIGHT_MONITOR_MIXINS.contains(mixinClassName)) {
			if (!logged) {
				LOGGER.info("[ferrite] Moonrise detected: light monitor mixins disabled ([light] shows no data)");
				logged = true;
			}
			return false;
		}
		return true;
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
