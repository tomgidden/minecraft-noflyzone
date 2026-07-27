package cx.gid.minecraft.noflyzone;

import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Tells modded clients when they enter or leave a no-fly zone, so they can show
 * the HUD icon.
 *
 * Enforcement never consults any of this. A client that lacks the mod, ignores
 * the payload, or never receives it is grounded exactly the same -- the zone test
 * is done server-side against the player's position on every glide check. This
 * exists solely because the server has no {@code MobEffect} to apply (see
 * {@link NoFlyBeacon}), so the icon has to be driven explicitly.
 *
 * Only transitions are sent, not per-tick state. A player standing in a zone
 * generates one packet on entry and one on exit, which is what makes it
 * reasonable to run this from the server tick.
 */
public final class ZoneStateNotifier {

    /** Players currently believed to be inside a zone. */
    private static final Set<UUID> IN_ZONE = ConcurrentHashMap.newKeySet();

    /**
     * How the payload actually gets sent. Supplied per loader, since Fabric and
     * NeoForge have incompatible send APIs and there is no common one without
     * pulling in a cross-platform layer.
     */
    private static volatile BiConsumer<ServerPlayer, Boolean> sender;

    private ZoneStateNotifier() {}

    /** Installs the loader-specific send function. Called from each entrypoint. */
    public static void setSender(BiConsumer<ServerPlayer, Boolean> value) {
        sender = value;
    }

    /**
     * Re-evaluates a player's zone membership and notifies on a change.
     *
     * Cheap enough for the server tick: {@link NoFlyZones#isInZone} is a handful
     * of AABB tests against a small map, and nothing is sent unless the answer
     * differs from last tick.
     */
    public static void update(ServerPlayer player) {
        boolean nowInZone = NoFlyZones.isInZone(player);
        UUID id = player.getUUID();
        boolean wasInZone = IN_ZONE.contains(id);

        if (nowInZone == wasInZone) {
            return;
        }

        if (nowInZone) {
            IN_ZONE.add(id);
        } else {
            IN_ZONE.remove(id);
        }

        BiConsumer<ServerPlayer, Boolean> local = sender;
        if (local == null) {
            return;
        }
        try {
            local.accept(player, nowInZone);
        } catch (Exception e) {
            // A player mid-disconnect can make the send throw. The icon is
            // cosmetic, so failing to update it must never propagate.
            NoFlyDebug.warn("failed to send zone state to {}: {}",
                player.getGameProfile().name(), e.toString());
        }
    }

    /** Forgets a player on disconnect. */
    public static void forget(ServerPlayer player) {
        IN_ZONE.remove(player.getUUID());
    }

    /** Forgets every tracked player. */
    public static void forgetAll() {
        IN_ZONE.clear();
    }
}
