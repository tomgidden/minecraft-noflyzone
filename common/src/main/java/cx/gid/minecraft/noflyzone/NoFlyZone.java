package cx.gid.minecraft.noflyzone;

import net.minecraft.server.MinecraftServer;

/**
 * Common entrypoint.
 *
 * Note there is deliberately no content registration: the No-Fly option is not a
 * {@code MobEffect} and nothing modded enters any registry server-side, which is
 * what keeps unmodded clients able to connect. See {@link NoFlyBeacon}.
 */
public class NoFlyZone {

    public static void init() {
        // Nothing to register: the loader entrypoints announce startup, and the
        // zone state is rebuilt from the beacons themselves as they tick.
    }

    public static void onServerStarting(MinecraftServer server) {
        // Zones are rebuilt from the beacons themselves as they tick, so there
        // is nothing to load -- but a previous world in the same JVM may have
        // left entries behind.
        resetState();
    }

    public static void onServerStopping(MinecraftServer server) {
        resetState();
    }

    private static void resetState() {
        NoFlyZones.clearAll();
        NoFlyPolicy.forgetAll();
        ZoneStateNotifier.forgetAll();
    }
}
