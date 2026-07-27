package cx.gid.minecraft.noflyzone;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Tunable behaviour for no-fly zones, loaded from a plain
 * {@code config/noflyzone.properties} file.
 *
 * <p>There is deliberately no bypass setting. A no-fly zone applies to everyone,
 * operators included -- see {@link NoFlyPolicy#shouldRefuse}.
 *
 * <h2>Mutability</h2>
 * An instance is immutable, but the <em>current</em> instance can be replaced at
 * runtime by {@link #setMode}, which also writes the change back to disk so it
 * survives a restart. Everything else is read from the file at startup only.
 */
public final class NoFlyConfig {

    /** How a zone treats a player who flies into it. */
    public final NoFlyMode mode;

    /**
     * Extra blocks of horizontal radius beyond vanilla's beacon effect range
     * ({@code levels * 10 + 10}). 0 means "exactly the beacon's own range",
     * which is what makes the zone legible to players who already understand
     * beacons.
     */
    public final int extraRadius;

    /**
     * Pyramid tier a beacon needs before it can project a zone.
     *
     * <p>Note that lowering this only affects the {@code /noflyzone} command.
     * The beacon screen's No-Fly button is drawn in vanilla's tier-4 row and
     * cannot be moved, so the client-mod route always requires tier 4.
     */
    public final int requiredTier;

    /**
     * Ticks between damage hits in {@link NoFlyMode#DAMAGE} mode.
     *
     * <p>Each hit is a fixed 4.0 (vanilla's void-damage magnitude), so this is
     * the knob that decides how punishing the mode is. At the default of 20
     * (one hit per second) an unarmoured player survives a few seconds inside a
     * zone; lowering it toward vanilla's 10-tick invulnerability floor makes a
     * crossing progressively less survivable.
     */
    public final int damageIntervalTicks;

    /** When true, riptide tridents are also refused inside a zone. */
    public final boolean blockRiptide;

    /** When true, firework rockets can't be used to boost a glide inside a zone. */
    public final boolean blockFireworkBoost;

    /**
     * When true, happy ghasts are steered out of a zone rather than allowed to
     * carry riders through it.
     *
     * <p>Same reasoning as {@link #blockRiptide} and {@link #blockFireworkBoost}:
     * another way to get airborne that isn't elytra, and leaving it open would
     * make the zone's central promise false.
     */
    public final boolean blockHappyGhast;

    /** When true, an action-bar message explains each refusal. */
    public final boolean actionBarMessages;

    /**
     * Minimum ticks between action-bar messages to the same player. Enforcement
     * runs every tick, so without this a player held in a zone would have the
     * message rewritten 20 times a second.
     */
    public final int messageCooldownTicks;

    /** When true, the mod logs zone registration and enforcement diagnostics. */
    public final boolean debug;

    private NoFlyConfig(NoFlyMode mode, int extraRadius, int requiredTier, int damageIntervalTicks,
                        boolean blockRiptide, boolean blockFireworkBoost, boolean blockHappyGhast,
                        boolean actionBarMessages, int messageCooldownTicks, boolean debug) {
        this.mode = mode;
        this.extraRadius = extraRadius;
        this.requiredTier = requiredTier;
        this.damageIntervalTicks = damageIntervalTicks;
        this.blockRiptide = blockRiptide;
        this.blockFireworkBoost = blockFireworkBoost;
        this.blockHappyGhast = blockHappyGhast;
        this.actionBarMessages = actionBarMessages;
        this.messageCooldownTicks = messageCooldownTicks;
        this.debug = debug;
    }

    private static final NoFlyMode DEFAULT_MODE = NoFlyMode.ZERO_MOMENTUM;
    private static final int DEFAULT_EXTRA_RADIUS = 0;
    private static final int DEFAULT_REQUIRED_TIER = 4;
    private static final int DEFAULT_DAMAGE_INTERVAL_TICKS = 20;
    private static final boolean DEFAULT_BLOCK_RIPTIDE = true;
    private static final boolean DEFAULT_BLOCK_FIREWORK_BOOST = true;
    private static final boolean DEFAULT_BLOCK_HAPPY_GHAST = true;
    private static final boolean DEFAULT_ACTION_BAR_MESSAGES = true;
    private static final int DEFAULT_MESSAGE_COOLDOWN_TICKS = 40;

    private static volatile NoFlyConfig instance;

    /** Returns the loaded config, reading it from disk on first use. */
    public static NoFlyConfig get() {
        NoFlyConfig local = instance;
        if (local == null) {
            synchronized (NoFlyConfig.class) {
                local = instance;
                if (local == null) {
                    local = load();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * Changes the enforcement mode and persists it.
     *
     * <p>Called from {@code /noflyzone mode}. The write is best-effort: if it
     * fails the running server still honours the new mode, but the change will
     * not survive a restart, so the caller is told.
     *
     * @return true if the change was written to disk
     */
    public static synchronized boolean setMode(NoFlyMode mode) {
        NoFlyConfig current = get();
        instance = new NoFlyConfig(mode, current.extraRadius, current.requiredTier,
            current.damageIntervalTicks, current.blockRiptide, current.blockFireworkBoost,
            current.blockHappyGhast,
            current.actionBarMessages, current.messageCooldownTicks, current.debug);
        return write(configPath(), instance);
    }

    private static Path configPath() {
        return Paths.get("config", Constants.MOD_ID + ".properties");
    }

    private static NoFlyConfig load() {
        Path path = configPath();
        Properties props = new Properties();

        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException ignored) {
                // Fall back to defaults if the file is unreadable.
            }
        }

        NoFlyMode mode = readMode(props, "mode", DEFAULT_MODE);
        int extraRadius = readInt(props, "extra_radius", DEFAULT_EXTRA_RADIUS, 0, 256);
        int requiredTier = readInt(props, "required_tier", DEFAULT_REQUIRED_TIER, 1, 4);

        // Floor of 10 matches vanilla's invulnerability window: below that the
        // extra hits are simply swallowed, so allowing it would only mislead.
        int damageIntervalTicks = readInt(props, "damage_interval_ticks", DEFAULT_DAMAGE_INTERVAL_TICKS, 10, 200);

        boolean blockRiptide = readBoolean(props, "block_riptide", DEFAULT_BLOCK_RIPTIDE);
        boolean blockFireworkBoost = readBoolean(props, "block_firework_boost", DEFAULT_BLOCK_FIREWORK_BOOST);
        boolean blockHappyGhast = readBoolean(props, "block_happy_ghast", DEFAULT_BLOCK_HAPPY_GHAST);
        boolean actionBarMessages = readBoolean(props, "action_bar_messages", DEFAULT_ACTION_BAR_MESSAGES);
        int messageCooldownTicks = readInt(props, "message_cooldown_ticks", DEFAULT_MESSAGE_COOLDOWN_TICKS, 0, 1200);

        boolean debug = readBoolean(props, "debug", false);

        NoFlyConfig config = new NoFlyConfig(
            mode, extraRadius, requiredTier, damageIntervalTicks, blockRiptide, blockFireworkBoost,
            blockHappyGhast,
            actionBarMessages, messageCooldownTicks, debug
        );

        // Write the file on first run so operators have a documented, editable
        // copy with the values actually in effect.
        if (!Files.isRegularFile(path)) {
            write(path, config);
        }
        return config;
    }

    private static NoFlyMode readMode(Properties props, String key, NoFlyMode fallback) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        NoFlyMode parsed = NoFlyMode.parse(raw);
        if (parsed == null) {
            NoFlyDebug.warn("config {}: '{}' is not one of {}, using {}",
                key, raw.trim(), NoFlyMode.names(), fallback.configName());
            return fallback;
        }
        return parsed;
    }

    private static int readInt(Properties props, String key, int fallback, int min, int max) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            int clamped = Math.max(min, Math.min(max, value));
            if (clamped != value) {
                NoFlyDebug.warn("config {}: {} is outside {}..{}, clamped to {}", key, value, min, max, clamped);
            }
            return clamped;
        } catch (NumberFormatException e) {
            NoFlyDebug.warn("config {}: '{}' is not a whole number, using {}", key, raw.trim(), fallback);
            return fallback;
        }
    }

    private static boolean readBoolean(Properties props, String key, boolean fallback) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("true")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("false")) {
            return false;
        }
        // Boolean.parseBoolean silently maps anything non-"true" to false, which
        // would turn a typo into a surprise. Say so and keep the default instead.
        NoFlyDebug.warn("config {}: '{}' is not true or false, using {}", key, trimmed, fallback);
        return fallback;
    }

    /**
     * Writes the config out, creating it if absent and replacing it otherwise.
     *
     * <p>Note that {@code Properties.store} does not preserve comments, so any an
     * operator added by hand are lost when {@code /noflyzone mode} rewrites the
     * file. The documented header below is written every time, so the file stays
     * self-describing regardless.
     *
     * @return true on success
     */
    private static boolean write(Path path, NoFlyConfig config) {
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                Properties props = new Properties();
                props.setProperty("mode", config.mode.configName());
                props.setProperty("extra_radius", Integer.toString(config.extraRadius));
                props.setProperty("required_tier", Integer.toString(config.requiredTier));
                props.setProperty("damage_interval_ticks", Integer.toString(config.damageIntervalTicks));
                props.setProperty("block_riptide", Boolean.toString(config.blockRiptide));
                props.setProperty("block_firework_boost", Boolean.toString(config.blockFireworkBoost));
                props.setProperty("block_happy_ghast", Boolean.toString(config.blockHappyGhast));
                props.setProperty("action_bar_messages", Boolean.toString(config.actionBarMessages));
                props.setProperty("message_cooldown_ticks", Integer.toString(config.messageCooldownTicks));
                props.setProperty("debug", Boolean.toString(config.debug));
                props.store(out,
                    "No-Fly Zone settings.\n"
                    + "\n"
                    + "mode:                    how a zone treats a player who flies into it.\n"
                    + "                           zero-momentum  horizontal movement is stopped, but an existing\n"
                    + "                                          glide continues, so the player sinks and lands\n"
                    + "                                          safely. Merciful, and the default.\n"
                    + "                           no-glide       wings cut outright, exactly as when an elytra\n"
                    + "                                          breaks. Brutal, surprising, often fatal.\n"
                    + "                           damage         the player keeps flying and keeps control, but\n"
                    + "                                          takes steady damage as though being shot down.\n"
                    + "                                          Scary, but survivable with armour.\n"
                    + "                         Changeable in-game with /noflyzone mode <mode>.\n"
                    + "extra_radius:            extra blocks of radius beyond the beacon's own effect range\n"
                    + "                         (0 = exactly the vanilla beacon range: 20/30/40/50 by tier).\n"
                    + "required_tier:           pyramid tier a beacon needs to project a zone (1-4).\n"
                    + "                         Only affects /noflyzone: the beacon screen's No-Fly button sits\n"
                    + "                         in vanilla's tier-4 row and always requires tier 4.\n"
                    + "damage_interval_ticks:   ticks between hits in damage mode (10-200; 20 = one per second).\n"
                    + "                         Each hit is 4.0 damage, so this decides how punishing it is.\n"
                    + "block_riptide:           also refuse riptide tridents inside a zone.\n"
                    + "block_firework_boost:    also refuse firework rocket boosts inside a zone.\n"
                    + "block_happy_ghast:       steer happy ghasts out of a zone instead of letting them\n"
                    + "                         carry riders through it.\n"
                    + "action_bar_messages:     tell players why their elytra stopped working.\n"
                    + "message_cooldown_ticks:  minimum ticks between messages to the same player.\n"
                    + "debug:                   log zone registration and enforcement to the console."
                );
            }
            return true;
        } catch (IOException e) {
            NoFlyDebug.warn("could not write {}: {}", path, e.toString());
            return false;
        }
    }
}
