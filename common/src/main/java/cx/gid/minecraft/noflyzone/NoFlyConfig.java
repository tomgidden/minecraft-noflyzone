package cx.gid.minecraft.noflyzone;

import net.minecraft.core.particles.SimpleParticleType;

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

    /** When true, damage-mode hits throw up flak particles around the player. */
    public final boolean particlesDamage;

    /** When true, a sparse tracer trail follows a player being shot at. */
    public final boolean particlesTrail;

    /** When true, each damage hit draws a line of fire from the beacon to the player. */
    public final boolean particlesTracer;

    /** When true, refusals in the non-damage modes show a particle puff. */
    public final boolean particlesRefused;

    /** Airbursts per damage hit. 0 disables the layer without disabling the rest. */
    public final int particleBurstCount;

    /** Particles in the puff at the player on a damage hit. */
    public final int particleHitCount;

    /** Particles per tracer emission. */
    public final int particleTrailCount;

    /** Particles in a non-damage refusal puff. */
    public final int particleRefusedCount;

    /** Ticks between tracer emissions; 1 is every tick. */
    public final int particleTrailIntervalTicks;

    /** Blocks between particles along the beacon-to-player line. */
    public final double particleTracerSpacing;

    /** Hard cap on particles in one shot, whatever the distance. */
    public final int particleTracerMaxCount;

    /** How far particles stray from the true line, in blocks. */
    public final double particleTracerJitter;

    public final SimpleParticleType particleBurstType;
    public final SimpleParticleType particleHitType;
    public final SimpleParticleType particleTrailType;
    public final SimpleParticleType particleRefusedType;
    public final SimpleParticleType particleTracerType;

    /** When true, the mod logs zone registration and enforcement diagnostics. */
    public final boolean debug;

    private NoFlyConfig(NoFlyMode mode, int extraRadius, int requiredTier, int damageIntervalTicks,
                        boolean blockRiptide, boolean blockFireworkBoost, boolean blockHappyGhast,
                        boolean actionBarMessages, int messageCooldownTicks,
                        boolean particlesDamage, boolean particlesTrail, boolean particlesRefused,
                        boolean particlesTracer,
                        int particleBurstCount, int particleHitCount, int particleTrailCount,
                        int particleRefusedCount, int particleTrailIntervalTicks,
                        double particleTracerSpacing, int particleTracerMaxCount, double particleTracerJitter,
                        SimpleParticleType particleBurstType, SimpleParticleType particleHitType,
                        SimpleParticleType particleTrailType, SimpleParticleType particleRefusedType,
                        SimpleParticleType particleTracerType,
                        boolean debug) {
        this.mode = mode;
        this.extraRadius = extraRadius;
        this.requiredTier = requiredTier;
        this.damageIntervalTicks = damageIntervalTicks;
        this.blockRiptide = blockRiptide;
        this.blockFireworkBoost = blockFireworkBoost;
        this.blockHappyGhast = blockHappyGhast;
        this.actionBarMessages = actionBarMessages;
        this.messageCooldownTicks = messageCooldownTicks;
        this.particlesDamage = particlesDamage;
        this.particlesTrail = particlesTrail;
        this.particlesRefused = particlesRefused;
        this.particlesTracer = particlesTracer;
        this.particleBurstCount = particleBurstCount;
        this.particleHitCount = particleHitCount;
        this.particleTrailCount = particleTrailCount;
        this.particleRefusedCount = particleRefusedCount;
        this.particleTrailIntervalTicks = particleTrailIntervalTicks;
        this.particleTracerSpacing = particleTracerSpacing;
        this.particleTracerMaxCount = particleTracerMaxCount;
        this.particleTracerJitter = particleTracerJitter;
        this.particleBurstType = particleBurstType;
        this.particleHitType = particleHitType;
        this.particleTrailType = particleTrailType;
        this.particleRefusedType = particleRefusedType;
        this.particleTracerType = particleTracerType;
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

    // Particles are on by default for damage mode -- being shot down is the
    // mode that most needs to look like something -- and for the quieter
    // refusal puff. Tracers are off: they are the most expensive layer and the
    // most likely to be thought noisy, so they are opt-in.
    private static final boolean DEFAULT_PARTICLES_DAMAGE = true;
    private static final boolean DEFAULT_PARTICLES_TRAIL = false;
    private static final boolean DEFAULT_PARTICLES_REFUSED = true;
    private static final int DEFAULT_PARTICLE_BURST_COUNT = 6;
    private static final int DEFAULT_PARTICLE_HIT_COUNT = 8;
    private static final int DEFAULT_PARTICLE_TRAIL_COUNT = 2;
    private static final int DEFAULT_PARTICLE_REFUSED_COUNT = 8;
    private static final int DEFAULT_PARTICLE_TRAIL_INTERVAL_TICKS = 3;
    private static final boolean DEFAULT_PARTICLES_TRACER = true;
    private static final double DEFAULT_PARTICLE_TRACER_SPACING = 1.5;
    private static final int DEFAULT_PARTICLE_TRACER_MAX_COUNT = 48;
    private static final double DEFAULT_PARTICLE_TRACER_JITTER = 0.35;

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
            current.actionBarMessages, current.messageCooldownTicks,
            current.particlesDamage, current.particlesTrail, current.particlesRefused,
            current.particlesTracer,
            current.particleBurstCount, current.particleHitCount, current.particleTrailCount,
            current.particleRefusedCount, current.particleTrailIntervalTicks,
            current.particleTracerSpacing, current.particleTracerMaxCount, current.particleTracerJitter,
            current.particleBurstType, current.particleHitType, current.particleTrailType,
            current.particleRefusedType, current.particleTracerType,
            current.debug);
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

        boolean particlesDamage = readBoolean(props, "particles_damage", DEFAULT_PARTICLES_DAMAGE);
        boolean particlesTrail = readBoolean(props, "particles_trail", DEFAULT_PARTICLES_TRAIL);
        boolean particlesRefused = readBoolean(props, "particles_refused", DEFAULT_PARTICLES_REFUSED);
        boolean particlesTracer = readBoolean(props, "particles_tracer", DEFAULT_PARTICLES_TRACER);

        // Upper bounds are deliberately modest. Each particle is a packet to
        // every client in range, so a hundred of them per hit per player is a
        // denial of service dressed as a setting.
        int particleBurstCount = readInt(props, "particle_burst_count", DEFAULT_PARTICLE_BURST_COUNT, 0, 64);
        int particleHitCount = readInt(props, "particle_hit_count", DEFAULT_PARTICLE_HIT_COUNT, 0, 64);
        int particleTrailCount = readInt(props, "particle_trail_count", DEFAULT_PARTICLE_TRAIL_COUNT, 0, 32);
        int particleRefusedCount = readInt(props, "particle_refused_count", DEFAULT_PARTICLE_REFUSED_COUNT, 0, 64);
        int particleTrailIntervalTicks = readInt(props, "particle_trail_interval_ticks",
            DEFAULT_PARTICLE_TRAIL_INTERVAL_TICKS, 1, 40);

        // Spacing has a floor: at 0 the step count would divide by zero, and
        // anything under half a block is denser than the cap can usefully pay
        // for over a long shot.
        double particleTracerSpacing = readDouble(props, "particle_tracer_spacing",
            DEFAULT_PARTICLE_TRACER_SPACING, 0.5, 16.0);
        int particleTracerMaxCount = readInt(props, "particle_tracer_max_count",
            DEFAULT_PARTICLE_TRACER_MAX_COUNT, 0, 256);
        double particleTracerJitter = readDouble(props, "particle_tracer_jitter",
            DEFAULT_PARTICLE_TRACER_JITTER, 0.0, 4.0);

        SimpleParticleType particleBurstType = NoFlyParticles.parseType(
            "particle_burst_type", props.getProperty("particle_burst_type"), NoFlyParticles.DEFAULT_BURST);
        SimpleParticleType particleHitType = NoFlyParticles.parseType(
            "particle_hit_type", props.getProperty("particle_hit_type"), NoFlyParticles.DEFAULT_HIT);
        SimpleParticleType particleTrailType = NoFlyParticles.parseType(
            "particle_trail_type", props.getProperty("particle_trail_type"), NoFlyParticles.DEFAULT_TRAIL);
        SimpleParticleType particleRefusedType = NoFlyParticles.parseType(
            "particle_refused_type", props.getProperty("particle_refused_type"), NoFlyParticles.DEFAULT_REFUSED);
        SimpleParticleType particleTracerType = NoFlyParticles.parseType(
            "particle_tracer_type", props.getProperty("particle_tracer_type"), NoFlyParticles.DEFAULT_TRACER);

        boolean debug = readBoolean(props, "debug", false);

        NoFlyConfig config = new NoFlyConfig(
            mode, extraRadius, requiredTier, damageIntervalTicks, blockRiptide, blockFireworkBoost,
            blockHappyGhast,
            actionBarMessages, messageCooldownTicks,
            particlesDamage, particlesTrail, particlesRefused, particlesTracer,
            particleBurstCount, particleHitCount, particleTrailCount, particleRefusedCount,
            particleTrailIntervalTicks,
            particleTracerSpacing, particleTracerMaxCount, particleTracerJitter,
            particleBurstType, particleHitType, particleTrailType, particleRefusedType,
            particleTracerType,
            debug
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

    private static double readDouble(Properties props, String key, double fallback, double min, double max) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (!Double.isFinite(value)) {
                NoFlyDebug.warn("config {}: '{}' is not a finite number, using {}", key, raw.trim(), fallback);
                return fallback;
            }
            double clamped = Math.max(min, Math.min(max, value));
            if (clamped != value) {
                NoFlyDebug.warn("config {}: {} is outside {}..{}, clamped to {}", key, value, min, max, clamped);
            }
            return clamped;
        } catch (NumberFormatException e) {
            NoFlyDebug.warn("config {}: '{}' is not a number, using {}", key, raw.trim(), fallback);
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
                props.setProperty("particles_damage", Boolean.toString(config.particlesDamage));
                props.setProperty("particles_trail", Boolean.toString(config.particlesTrail));
                props.setProperty("particles_refused", Boolean.toString(config.particlesRefused));
                props.setProperty("particles_tracer", Boolean.toString(config.particlesTracer));
                props.setProperty("particle_burst_count", Integer.toString(config.particleBurstCount));
                props.setProperty("particle_hit_count", Integer.toString(config.particleHitCount));
                props.setProperty("particle_trail_count", Integer.toString(config.particleTrailCount));
                props.setProperty("particle_refused_count", Integer.toString(config.particleRefusedCount));
                props.setProperty("particle_trail_interval_ticks", Integer.toString(config.particleTrailIntervalTicks));
                props.setProperty("particle_tracer_spacing", Double.toString(config.particleTracerSpacing));
                props.setProperty("particle_tracer_max_count", Integer.toString(config.particleTracerMaxCount));
                props.setProperty("particle_tracer_jitter", Double.toString(config.particleTracerJitter));
                props.setProperty("particle_burst_type", NoFlyParticles.nameOf(config.particleBurstType));
                props.setProperty("particle_hit_type", NoFlyParticles.nameOf(config.particleHitType));
                props.setProperty("particle_trail_type", NoFlyParticles.nameOf(config.particleTrailType));
                props.setProperty("particle_refused_type", NoFlyParticles.nameOf(config.particleRefusedType));
                props.setProperty("particle_tracer_type", NoFlyParticles.nameOf(config.particleTracerType));
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
                    + "\n"
                    + "Particles. Spawned server-side, so everyone nearby sees them, not just the\n"
                    + "player being stopped. The *_type settings take any simple particle id, e.g.\n"
                    + "minecraft:explosion, minecraft:smoke, minecraft:crit, minecraft:flame,\n"
                    + "minecraft:soul_fire_flame, minecraft:end_rod. Particles that need extra data\n"
                    + "(block, dust, item) can't be named here and fall back to the default.\n"
                    + "Any *_count of 0 turns that layer off on its own.\n"
                    + "\n"
                    + "particles_damage:        flak around a player taking damage-mode hits.\n"
                    + "particle_burst_count:    airbursts per hit, scattered around the player (0-64).\n"
                    + "particle_burst_type:     particle for those airbursts.\n"
                    + "particle_hit_count:      particles in the puff on the player itself (0-64).\n"
                    + "particle_hit_type:       particle for that puff.\n"
                    + "particles_tracer:        the round in flight: a line of particles from the\n"
                    + "                         beacon up to the player on each hit, so the shot\n"
                    + "                         visibly comes from the beacon.\n"
                    + "particle_tracer_type:    particle for that line.\n"
                    + "particle_tracer_spacing: blocks between particles along it (0.5-16). Lower is\n"
                    + "                         denser; the count is derived from the distance, so a\n"
                    + "                         near and a far shot look equally solid.\n"
                    + "particle_tracer_max_count:\n"
                    + "                         hard cap on particles in one shot (0-256), so a player\n"
                    + "                         300 blocks up doesn't cost a huge packet burst.\n"
                    + "particle_tracer_jitter:  how far particles stray from the true line (0-4), so it\n"
                    + "                         reads as rounds rather than as a laser.\n"
                    + "particles_trail:         a sparse wake behind the player marking where they have\n"
                    + "                         been, like smoke off a damaged aircraft. Off by default:\n"
                    + "                         it is the busiest layer.\n"
                    + "particle_trail_count:    particles per emission (0-32).\n"
                    + "particle_trail_type:     particle for the wake.\n"
                    + "particle_trail_interval_ticks:\n"
                    + "                         ticks between emissions (1-40).\n"
                    + "particles_refused:       a quieter puff when flight is refused in the\n"
                    + "                         non-damage modes.\n"
                    + "particle_refused_count:  particles in that puff (0-64).\n"
                    + "particle_refused_type:   particle for it.\n"
                    + "\n"
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
