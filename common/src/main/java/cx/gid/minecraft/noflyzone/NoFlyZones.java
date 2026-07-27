package cx.gid.minecraft.noflyzone;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of currently-active no-fly beacons, and the "is this position in a
 * zone" question that enforcement asks.
 *
 * Enforcement runs from {@code LivingEntity.canGlide()}, which is consulted
 * every tick for every gliding entity. Scanning loaded block entities at that
 * rate would be absurd, so beacons instead publish themselves here from their
 * own tick (vanilla re-evaluates a beacon every 80 ticks) and enforcement does
 * a handful of AABB tests against the result.
 *
 * A beacon that stops being a valid no-fly beacon -- broken, roofed over,
 * pyramid dismantled, effect changed -- simply stops re-publishing. Because
 * vanilla only reconsiders a beacon every 80 ticks, an entry is kept only until
 * {@link #EXPIRY_TICKS} pass without a refresh, after which it is dropped. That
 * makes deactivation self-healing without needing to hook every possible way a
 * beacon can stop working, at the cost of a zone outliving its beacon by up to
 * about six seconds.
 */
public final class NoFlyZones {

    /**
     * How long an unrefreshed zone survives, in game ticks. Vanilla's beacon
     * tick refreshes every 80; this allows a couple of missed refreshes before
     * expiry so a laggy server doesn't flicker zones off and on.
     */
    private static final long EXPIRY_TICKS = 200L;

    /** One active-zone map per dimension. */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Zone>> ZONES = new ConcurrentHashMap<>();

    private NoFlyZones() {}

    /**
     * A live no-fly zone: the volume it covers and when its beacon last said so.
     *
     * The bounds are computed once at publish time rather than per query,
     * because the query runs orders of magnitude more often than the refresh.
     */
    private record Zone(AABB bounds, long lastSeenTick) {}

    /**
     * Registers or refreshes the zone projected by a beacon.
     *
     * @param level  the beacon's level
     * @param pos    the beacon block's position
     * @param levels the beacon's pyramid tier, 1-4
     */
    public static void refresh(Level level, BlockPos pos, int levels) {
        if (level.isClientSide()) {
            return;
        }

        AABB bounds = boundsFor(level, pos, levels);
        Map<BlockPos, Zone> perLevel = ZONES.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
        Zone previous = perLevel.put(pos.immutable(), new Zone(bounds, level.getGameTime()));

        if (previous == null)
            NoFlyDebug.log("zone activated at {} (tier {}, radius {})", pos, levels, radiusFor(levels));
    }

    /**
     * The volume a beacon of the given tier covers.
     *
     * Mirrors vanilla's own beacon effect volume from
     * {@code BeaconBlockEntity.applyEffects}: a cube inflated by the effect
     * radius, then extended upward by the full world height. That upward
     * extension is exactly what a no-fly zone wants -- someone cruising at Y=300
     * over the beacon is precisely who the rule is aimed at -- so it is
     * reproduced rather than replaced.
     */
    private static AABB boundsFor(Level level, BlockPos pos, int levels) {
        double range = radiusFor(levels);
        return new AABB(pos).inflate(range).expandTowards(0.0, level.getHeight(), 0.0);
    }

    /**
     * Horizontal radius for a beacon tier: vanilla's {@code levels * 10 + 10}
     * (20/30/40/50), plus any configured extra.
     *
     * Vanilla has no accessor for this -- it is an inline expression in
     * {@code applyEffects} -- so the arithmetic is repeated here deliberately.
     */
    private static double radiusFor(int levels) {
        return levels * 10.0 + 10.0 + NoFlyConfig.get().extraRadius;
    }

    /** True if the given position lies inside any active no-fly zone. */
    public static boolean isInZone(Level level, double x, double y, double z) {
        if (level.isClientSide()) {
            return false;
        }

        Map<BlockPos, Zone> perLevel = ZONES.get(level.dimension());
        if (perLevel == null || perLevel.isEmpty()) {
            return false;
        }

        long now = level.getGameTime();
        boolean inZone = false;

        var iterator = perLevel.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Zone zone = entry.getValue();

            // Drop zones whose beacon has stopped refreshing them. Doing this
            // during the query keeps the map self-cleaning without a separate
            // sweep task, and the map is small enough that the cost is trivial.
            if (now - zone.lastSeenTick() > EXPIRY_TICKS) {
                iterator.remove();
                NoFlyDebug.log("zone expired at {}", entry.getKey());
                continue;
            }

            if (zone.bounds().contains(x, y, z)) {
                inZone = true;
                // Deliberately no early return: finishing the sweep lets the
                // expiry check above see every entry.
            }
        }

        return inZone;
    }

    /** True if the entity is standing/flying inside any active no-fly zone. */
    public static boolean isInZone(Entity entity) {
        return isInZone(entity.level(), entity.getX(), entity.getY(), entity.getZ());
    }

    /**
     * The bounds of an active zone containing the entity, or {@code null}.
     *
     * Where {@link #isInZone} only answers yes or no, this hands back the
     * volume itself, because steering something out of a zone needs to know
     * which way "out" is. See {@code NoFlyPolicy.steerOut}.
     *
     * If zones overlap, the first match wins. That is arbitrary but harmless:
     * leaving one zone moves the entity toward the edge of the others too, and
     * the next tick re-evaluates against whatever it is still inside.
     *
     * Deliberately does not run the expiry sweep. This is called
     * only for ghasts already known to be in a zone, so the sweep has just run
     * in {@link #isInZone}; repeating it here would be wasted work on a path
     * that ticks per entity.
     */
    public static AABB zoneContaining(Entity entity) {
        Map<BlockPos, Zone> perLevel = ZONES.get(entity.level().dimension());
        if (perLevel == null || perLevel.isEmpty()) {
            return null;
        }

        for (Zone zone : perLevel.values()) {
            if (zone.bounds().contains(entity.getX(), entity.getY(), entity.getZ())) {
                return zone.bounds();
            }
        }
        return null;
    }

    /**
     * Drops the zone projected by the beacon at {@code pos}, if any.
     *
     * Zones normally age out on their own once a beacon stops refreshing them,
     * but that takes several seconds. Turning a beacon off explicitly should feel
     * immediate, so the entry is removed at once.
     */
    public static void remove(Level level, BlockPos pos) {
        Map<BlockPos, Zone> perLevel = ZONES.get(level.dimension());

        if (perLevel != null && perLevel.remove(pos) != null)
            NoFlyDebug.log("zone removed at {}", pos);
    }

    /**
     * Forgets every zone in every dimension. Called on server shutdown so a
     * subsequent world load in the same JVM (singleplayer, or a server restart
     * without a process restart) doesn't inherit stale zones.
     */
    public static void clearAll() {
        ZONES.clear();
        NoFlyDebug.log("cleared all zones");
    }
}
