package cx.gid.minecraft.noflyzone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight debug logging, gated behind a config flag so it can be turned on
 * to diagnose a zone that is not triggering (or one that is triggering when it
 * shouldn't) and left off in normal play without a rebuild.
 *
 * Enable by setting {@code debug=true} in {@code config/noflyzone.properties}
 * (see {@link NoFlyConfig}). All messages are logged at {@code INFO} under the
 * logger name {@code noflyzone} so they show up plainly in the server console.
 */
public final class NoFlyDebug {
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.MOD_ID);

    private NoFlyDebug() {}

    /** True if debug logging is switched on in the config. */
    public static boolean enabled() {
        return NoFlyConfig.get().debug;
    }

    /** Logs a formatted message at INFO if debug is enabled ({@code {}} placeholders). */
    public static void log(String format, Object... args) {
        if (enabled()) {
            LOGGER.info("[" + Constants.MOD_ID + "] " + format, args);
        }
    }

    /**
     * Logs a formatted message at WARN regardless of the debug flag, for
     * problems an operator needs to see (a malformed config value, say).
     *
     * Deliberately does not consult {@link #enabled()}: this is called from
     * {@link NoFlyConfig#load()} while the config is still being built, so
     * asking for the flag would re-enter the loader.
     */
    public static void warn(String format, Object... args) {
        LOGGER.warn("[" + Constants.MOD_ID + "] " + format, args);
    }
}
