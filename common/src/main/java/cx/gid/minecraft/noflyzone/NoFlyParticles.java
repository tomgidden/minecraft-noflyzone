package cx.gid.minecraft.noflyzone;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

/**
 * The visible half of enforcement: what a refusal looks like, as distinct from
 * what it does.
 *
 * <p>Enforcement without a visual reads as a bug. A player whose elytra stops
 * working mid-flight has no way to tell a zone from lag, a broken wing or a
 * server hiccup, and the action-bar message only helps if they happen to be
 * looking at it. Particles put the cause in the same place as the effect.
 *
 * <p>Everything here is spawned server-side with
 * {@link ServerLevel#sendParticles}, so it reaches every client in range
 * without needing the mod installed. Other players seeing flak burst around
 * someone being shot down is most of the point.
 */
public final class NoFlyParticles {

    private NoFlyParticles() {}

    /**
     * How far from the player an airburst can go off, in blocks.
     *
     * <p>Fixed rather than configurable: the effect is anchored to the player's
     * own size, and the interesting knob is how many bursts there are, not how
     * far away they are. Far enough to read as "near misses", close enough that
     * they are obviously about this player.
     */
    private static final double BURST_RADIUS = 3.0;

    /** Vertical bias for airbursts, so they cluster below rather than above. */
    private static final double BURST_Y_BIAS = -0.5;

    /**
     * Spread of the small puff spawned at the player on a hit, in blocks. Kept
     * under the player's own width so it reads as damage to them rather than as
     * another near miss.
     */
    private static final double HIT_SPREAD = 0.3;

    /** Speed passed to sendParticles for burst particles. */
    private static final double BURST_SPEED = 0.02;

    /**
     * Fired when a player takes a damage-mode hit: flak.
     *
     * <p>Four independent layers, each separately switchable, because they
     * read very differently and taste varies:
     *
     * <ul>
     *   <li><b>tracers</b> -- a line of particles from the beacon up to the
     *       player: the round itself, visible in flight, so the shot comes
     *       from somewhere. See {@link #tracerFrom}.</li>
     *   <li><b>airbursts</b> -- puffs at random points in a sphere around the
     *       player, like shells detonating nearby. This is the one that makes
     *       it look like anti-aircraft fire rather than like the player is on
     *       fire.</li>
     *   <li><b>hit puff</b> -- a small burst at the player themselves, tying
     *       the damage to the spectacle.</li>
     *   <li><b>trail</b> -- a sparse wake along the player's own flight path,
     *       marking where they have been. Spawned per-tick elsewhere; see
     *       {@link #trail}.</li>
     * </ul>
     */
    public static void flak(ServerPlayer player) {
        NoFlyConfig config = NoFlyConfig.get();
        if (!config.particlesDamage) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        Vec3 at = player.position();

        if (config.particlesTracer) {
            tracerFrom(level, player, config);
        }

        if (config.particleBurstCount > 0) {
            ParticleOptions burst = config.particleBurstType;
            for (int i = 0; i < config.particleBurstCount; i++) {
                // Rejection-free random point in a box, biased downward. A true
                // sphere would be tidier but the difference is invisible once
                // the puffs have drifted, and this avoids a sqrt per particle
                // on what can be a per-second-per-player path.
                double dx = (player.getRandom().nextDouble() - 0.5) * 2 * BURST_RADIUS;
                double dy = (player.getRandom().nextDouble() - 0.5) * 2 * BURST_RADIUS + BURST_Y_BIAS;
                double dz = (player.getRandom().nextDouble() - 0.5) * 2 * BURST_RADIUS;

                // count=0 with a non-zero speed makes vanilla treat the xyz
                // offsets as a velocity vector for a single particle, which is
                // what gives EXPLOSION its outward puff. count=1 would pin it
                // in place instead.
                level.sendParticles(burst, at.x + dx, at.y + dy, at.z + dz, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }

        if (config.particleHitCount > 0) {
            level.sendParticles(config.particleHitType,
                at.x, at.y + player.getBbHeight() * 0.5, at.z,
                config.particleHitCount, HIT_SPREAD, HIT_SPREAD, HIT_SPREAD, BURST_SPEED);
        }
    }

    /**
     * A tracer: a line of particles from the beacon up to the player, the round
     * made visible in flight.
     *
     * <p>This is what makes the beacon legibly the thing doing the shooting.
     * Without it the flak appears from nowhere and the player has to infer the
     * source; with it, the cause is drawn in the sky.
     *
     * <p>Spacing is fixed and the count derived from the distance, rather than
     * a fixed count stretched over whatever the range happens to be -- so a
     * shot from 20 blocks and one from 200 have the same visual density rather
     * than the far one being a dotted line. The cap then bounds the cost: a
     * player 300 blocks up would otherwise be a thousand-particle packet burst
     * every hit.
     *
     * <p>Fired on the damage cadence, not per tick. A continuous beam would
     * read as a laser; discrete shots on the damage interval read as firing.
     */
    private static void tracerFrom(ServerLevel level, ServerPlayer player, NoFlyConfig config) {
        var beacon = NoFlyZones.beaconContaining(player);
        if (beacon == null) {
            // In a zone whose beacon has just aged out, or steered out between
            // the damage tick and now. The flak still fires; only the line
            // showing where it came from is skipped.
            return;
        }

        // From the top face of the beacon block rather than its centre, so the
        // line starts where the beam visibly does.
        Vec3 from = new Vec3(beacon.getX() + 0.5, beacon.getY() + 1.0, beacon.getZ() + 0.5);
        Vec3 to = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0);

        Vec3 delta = to.subtract(from);
        double distance = delta.length();
        if (distance < 1.0e-3) {
            return;
        }

        int steps = (int) Math.min(config.particleTracerMaxCount, distance / config.particleTracerSpacing);
        if (steps <= 0) {
            return;
        }

        Vec3 step = delta.scale(1.0 / steps);
        for (int i = 1; i <= steps; i++) {
            // Jittered off the true line so it reads as a burst of rounds
            // rather than as a ruler-straight beam.
            double jitter = config.particleTracerJitter;
            double jx = (player.getRandom().nextDouble() - 0.5) * 2 * jitter;
            double jy = (player.getRandom().nextDouble() - 0.5) * 2 * jitter;
            double jz = (player.getRandom().nextDouble() - 0.5) * 2 * jitter;

            Vec3 at = from.add(step.scale(i));
            level.sendParticles(config.particleTracerType,
                at.x + jx, at.y + jy, at.z + jz, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /**
     * A sparse wake behind the player, spawned per tick.
     *
     * <p>Distinct from the tracer, which is the shot arriving: this marks where
     * the player has been, like smoke off a damaged aircraft. It runs on a
     * different cadence for that reason -- flak and its tracer fire on each
     * damage hit (once a second by default), whereas a wake only reads as one
     * if it is close to continuous. Rate-limited by
     * {@code particle_trail_interval_ticks} so "continuous" does not mean
     * twenty packets a second per player.
     */
    public static void trail(ServerPlayer player) {
        NoFlyConfig config = NoFlyConfig.get();
        if (!config.particlesTrail || config.particleTrailCount <= 0) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (player.level().getGameTime() % config.particleTrailIntervalTicks != 0) {
            return;
        }

        // Behind the player, along their own motion: a round that just missed.
        Vec3 at = player.position();
        Vec3 motion = player.getDeltaMovement();
        Vec3 behind = at.subtract(motion.scale(4.0));

        level.sendParticles(config.particleTrailType,
            behind.x, behind.y + player.getBbHeight() * 0.5, behind.z,
            config.particleTrailCount, 0.4, 0.4, 0.4, 0.0);
    }

    /**
     * Fired when flight is refused in a non-damage mode.
     *
     * <p>Quieter than {@link #flak} by design. Nothing violent is happening to
     * the player -- they are being stopped, not shot -- so the default reads as
     * a barrier being pushed against rather than as an explosion.
     *
     * <p>Rate-limiting is the caller's job: this is reached through
     * {@code notifyRefused}, which already has a cooldown, so a player held
     * against a zone edge is not emitting particles every tick.
     */
    public static void refused(ServerPlayer player) {
        NoFlyConfig config = NoFlyConfig.get();
        if (!config.particlesRefused || config.particleRefusedCount <= 0) {
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        Vec3 at = player.position();
        level.sendParticles(config.particleRefusedType,
            at.x, at.y + player.getBbHeight() * 0.5, at.z,
            config.particleRefusedCount, 0.5, 0.5, 0.5, 0.01);
    }

    // ------------------------------------------------------------------ config

    /**
     * Resolves a particle id from the config file.
     *
     * <p>Only {@link SimpleParticleType} is accepted. Particles such as
     * {@code block} or {@code dust} carry extra data (a block state, a colour)
     * that a bare id cannot supply, and handing one to {@code sendParticles}
     * without it throws at spawn time -- which would surface as a crash mid-
     * flight rather than as a config error at startup. Rejecting them here
     * turns that into a warning and a working default.
     *
     * @return the configured particle, or {@code fallback} if the id is
     *         missing, unknown, or not a simple particle
     */
    public static SimpleParticleType parseType(String key, String raw, SimpleParticleType fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }

        Identifier id = Identifier.tryParse(raw.trim());
        if (id == null) {
            NoFlyDebug.warn("config {}: '{}' is not a valid particle id, using {}",
                key, raw.trim(), nameOf(fallback));
            return fallback;
        }

        Optional<SimpleParticleType> found = BuiltInRegistries.PARTICLE_TYPE.get(id)
            .map(holder -> holder.value())
            .filter(type -> type instanceof SimpleParticleType)
            .map(type -> (SimpleParticleType) type);

        if (found.isEmpty()) {
            NoFlyDebug.warn("config {}: '{}' is not a known simple particle, using {}. "
                    + "Particles needing extra data (block, dust, item) can't be named here.",
                key, id, nameOf(fallback));
            return fallback;
        }
        return found.get();
    }

    /**
     * Every particle id that can legally be named in the config.
     *
     * <p>Filtered to {@link SimpleParticleType} for the same reason
     * {@link #parseType} rejects the rest: a particle carrying extra data
     * cannot be spawned from a bare id, so offering one as a completion would
     * be offering a value that is then refused.
     *
     * <p>Computed on each call rather than cached. It is reached only from
     * tab-completion, where the cost is irrelevant and a stale list after a
     * datapack reload would not be.
     */
    public static List<String> simpleParticleIds() {
        return BuiltInRegistries.PARTICLE_TYPE.entrySet().stream()
            .filter(entry -> entry.getValue() instanceof SimpleParticleType)
            .map(entry -> entry.getKey().identifier().toString())
            .sorted()
            .toList();
    }

    /** The registry id of a particle, for messages and for writing the config back. */
    public static String nameOf(ParticleOptions particle) {
        return BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType()).toString();
    }

    /** Defaults, named here so the config and its documentation agree. */
    public static final SimpleParticleType DEFAULT_BURST = ParticleTypes.EXPLOSION;
    public static final SimpleParticleType DEFAULT_HIT = ParticleTypes.SMOKE;
    public static final SimpleParticleType DEFAULT_TRAIL = ParticleTypes.CRIT;
    public static final SimpleParticleType DEFAULT_REFUSED = ParticleTypes.CLOUD;
    public static final SimpleParticleType DEFAULT_TRACER = ParticleTypes.FLAME;
}
