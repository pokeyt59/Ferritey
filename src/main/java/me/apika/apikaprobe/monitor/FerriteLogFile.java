package me.apika.apikaprobe.monitor;

import java.beans.PropertyChangeEvent;
import java.nio.file.Path;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingRandomAccessFileAppender;
import org.apache.logging.log4j.core.appender.rolling.CompositeTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.OnStartupTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.appender.rolling.TimeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.slf4j.LoggerFactory;

/**
 * Gives Ferrite a log file of its own, logs/ferrite.log, and keeps only its
 * warnings and errors in the main log (latest.log and the console).
 *
 * <p>Every Ferrite logger is named "ferrite". This adds a Log4j logger
 * config for that name with additivity off: all of its lines go to
 * ferrite.log, and the appenders the root logger writes to (console,
 * latest.log, the server GUI) get only WARN and up. No call site changes.
 * ferrite.log rolls over at each start, like latest.log, and by size.
 *
 * <p>On unless {@code -Dferrite.log.file=false}, {@code log-file=false} in
 * config/ferrite.properties, or {@code /ferrite log file off}. Stands down,
 * leaving everything in the main log, when the logging backend is not
 * Log4j or its configuration already has a "ferrite" logger.
 *
 * <p>Installed from FerriteMixinPlugin.onLoad, before any other Ferrite
 * code logs, so it touches no game classes. The Log4j types live in
 * {@link Routing}, loaded only when routing runs.
 */
public final class FerriteLogFile {
	private FerriteLogFile() {}

	/** {@code -Dferrite.log.file=false} keeps every Ferrite line in the main log. */
	public static final String PROPERTY = "ferrite.log.file";

	private static final String LOGGER_NAME = "ferrite";

	private static volatile boolean wanted;
	/** Why routing cannot run, or null. */
	private static volatile String unavailable;
	private static Path file;

	/** Called once at launch. */
	public static synchronized void install(Path gameDir, boolean on) {
		file = gameDir.resolve("logs").resolve("ferrite.log");
		wanted = on;
		if (!on) return;
		String result = apply(true);
		if (result == null) {
			// "Ferrite", not "ferrite": Log4j names are case-sensitive, so this
			// one line is not routed and reaches the main log.
			LoggerFactory.getLogger("Ferrite").info(
					"Ferrite logs to logs/ferrite.log; only its warnings and errors appear here (/ferrite log file off to change)");
		}
	}

	/** Re-applies the routing if a Log4j reconfiguration dropped it; called from mod init. */
	public static synchronized void ensureInstalled() {
		if (!wanted || unavailable != null) return;
		try {
			if (Routing.route(file) == Routing.ADDED) {
				LoggerFactory.getLogger(LOGGER_NAME).info("[log] routing to logs/ferrite.log re-applied");
			}
		} catch (Throwable t) {
			unavailable = describe(t);
		}
	}

	/** Returns null on success, else why the file is unavailable. */
	public static synchronized String setEnabled(boolean on) {
		wanted = on;
		return apply(on);
	}

	public static boolean wanted() {
		return wanted;
	}

	public static synchronized String status() {
		if (!wanted) return "[log] file=off: every Ferrite line goes to the main log";
		if (unavailable != null) {
			return "[log] file=unavailable (" + unavailable + "): every Ferrite line goes to the main log";
		}
		return "[log] file=on: " + file + " gets every Ferrite line; the main log gets its warnings and errors";
	}

	private static String apply(boolean on) {
		try {
			if (on) {
				if (Routing.route(file) == Routing.OWNED_ELSEWHERE) {
					unavailable = "the Log4j configuration already sets up a \"ferrite\" logger";
					LoggerFactory.getLogger(LOGGER_NAME).info("[log] {}; leaving it as configured", unavailable);
					return unavailable;
				}
			} else {
				Routing.unroute();
			}
			unavailable = null;
			return null;
		} catch (Throwable t) {
			unavailable = describe(t);
			LoggerFactory.getLogger(LOGGER_NAME).warn(
					"[log] logs/ferrite.log unavailable, Ferrite logs here instead: {}", unavailable);
			return unavailable;
		}
	}

	private static String describe(Throwable t) {
		return t.getMessage() != null ? t.getMessage() : t.toString();
	}

	/** Everything that touches Log4j core types. */
	private static final class Routing {
		static final int ADDED = 0, PRESENT = 1, OWNED_ELSEWHERE = 2;

		private static final String APPENDER_NAME = "FerriteFile";
		/** The game's latest.log pattern. */
		private static final String PATTERN = "[%d{HH:mm:ss}] [%t/%level]: %msg{nolookups}%n";

		private static LoggerContext context;
		private static Path file;

		private static LoggerContext context() {
			if (context == null) {
				Object ctx = LogManager.getContext(FerriteLogFile.class.getClassLoader(), false);
				if (!(ctx instanceof LoggerContext lc)) {
					throw new IllegalStateException("the logging backend is "
							+ ctx.getClass().getName() + ", not Log4j");
				}
				// A new configuration (a Log4j reconfigure) drops the routing;
				// add it back. updateLoggers() fires this too, with the same
				// configuration on both sides.
				lc.addPropertyChangeListener(Routing::onConfigChange);
				context = lc;
			}
			return context;
		}

		private static void onConfigChange(PropertyChangeEvent e) {
			if (!LoggerContext.PROPERTY_CONFIG.equals(e.getPropertyName())
					|| e.getOldValue() == e.getNewValue() || !wanted || file == null) return;
			try {
				route(file);
			} catch (Throwable t) {
				unavailable = describe(t);
			}
		}

		static synchronized int route(Path target) {
			file = target;
			LoggerContext ctx = context();
			Configuration config = ctx.getConfiguration();
			LoggerConfig existing = config.getLoggers().get(LOGGER_NAME);
			if (existing != null) {
				return existing.getAppenders().containsKey(APPENDER_NAME) ? PRESENT : OWNED_ELSEWHERE;
			}
			Appender fileAppender = config.getAppender(APPENDER_NAME);
			if (fileAppender == null) {
				fileAppender = build(config, target);
				fileAppender.start();
				config.addAppender(fileAppender);
			}
			LoggerConfig ferrite = new LoggerConfig(LOGGER_NAME, Level.INFO, false);
			ferrite.addAppender(fileAppender, null, null);
			// The main log: the root logger's appenders, at WARN or the
			// appender's own level if that is stricter.
			LoggerConfig root = config.getRootLogger();
			for (Map.Entry<String, Appender> e : root.getAppenders().entrySet()) {
				AppenderRef ref = null;
				for (AppenderRef r : root.getAppenderRefs()) {
					if (r.getRef().equals(e.getKey())) ref = r;
				}
				Level level = Level.WARN;
				if (ref != null && ref.getLevel() != null && ref.getLevel().isMoreSpecificThan(Level.WARN)) {
					level = ref.getLevel();
				}
				ferrite.addAppender(e.getValue(), level, ref == null ? null : ref.getFilter());
			}
			config.addLogger(LOGGER_NAME, ferrite);
			ctx.updateLoggers();
			return ADDED;
		}

		/** Back to the main log; the file appender stays open for a later route(). */
		static synchronized void unroute() {
			if (context == null) return;
			Configuration config = context.getConfiguration();
			LoggerConfig existing = config.getLoggers().get(LOGGER_NAME);
			if (existing != null && existing.getAppenders().containsKey(APPENDER_NAME)) {
				config.removeLogger(LOGGER_NAME);
				context.updateLoggers();
			}
		}

		private static Appender build(Configuration config, Path target) {
			Path dir = target.toAbsolutePath().getParent();
			String name = target.getFileName().toString();
			String stem = name.endsWith(".log") ? name.substring(0, name.length() - 4) : name;
			// Forward slashes: Log4j reads a file pattern, and Windows takes them.
			String fileName = target.toAbsolutePath().toString().replace('\\', '/');
			String filePattern = dir.toString().replace('\\', '/') + "/" + stem + "-%d{yyyy-MM-dd}-%i.log.gz";
			return RollingRandomAccessFileAppender.newBuilder()
					.withFileName(fileName)
					.withFilePattern(filePattern)
					.withPolicy(CompositeTriggeringPolicy.createPolicy(
							OnStartupTriggeringPolicy.createPolicy(1),
							TimeBasedTriggeringPolicy.newBuilder().build(),
							SizeBasedTriggeringPolicy.createPolicy("20MB")))
					.withStrategy(DefaultRolloverStrategy.newBuilder()
							.withMax("10")
							.withConfig(config)
							.build())
					.setName(APPENDER_NAME)
					.setLayout(PatternLayout.newBuilder()
							.withConfiguration(config)
							.withPattern(PATTERN)
							.build())
					.setConfiguration(config)
					.build();
		}
	}
}
