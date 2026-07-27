package cx.gid.minecraft.noflyzone;

import cx.gid.minecraft.noflyzone.mixin.BeaconMenuAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.BeaconMenu;

/**
 * Applies a client's request to make a beacon a no-fly beacon.
 *
 * Because No-Fly cannot be a real {@code MobEffect} (see {@link NoFlyBeacon}),
 * the selection does not travel through {@code ServerboundSetBeaconPacket} and
 * therefore gets none of vanilla's validation -- not the open-menu check, not 
 * the tier check, not the payment. Everything vanilla would have done
 * has to be done here instead, against a payload a modified client can put
 * anything into.
 *
 * The payload carries only a boolean; the beacon is resolved from the menu the
 * player has open, so a client cannot name a block it isn't standing at. The
 * rules enforced then mirror vanilla's own:
 * 
 *   - the player must have a valid beacon menu open ({@code stillValid} covers
 *     reach and that the block is still a beacon),
 *   - the beacon must be a full tier-4 pyramid,
 *   - payment must be present, and is consumed exactly as vanilla consumes it.
 */
public final class SetZoneHandler {

    private SetZoneHandler() {}

    /**
     * Handles a {@code set_zone} request. Must be called on the server thread.
     *
     * Silently ignores anything that fails validation: a well-behaved client
     * cannot produce these, so the only sources are a modified client or a stale
     * packet, and neither deserves feedback.
     */
    public static void handle(ServerPlayer player, boolean enabled) {
        if (!(player.containerMenu instanceof BeaconMenu menu)) {
            NoFlyDebug.log("set_zone from {} with no beacon menu open", player.getGameProfile().name());
            return;
        }
        if (!menu.stillValid(player)) {
            NoFlyDebug.log("set_zone from {} with an invalid menu", player.getGameProfile().name());
            return;
        }

        // Checked here as well as inside ZoneMutation, because payment must not
        // be consumed for a request that is going to be refused anyway. The menu
        // is the client's view of the tier; ZoneMutation re-reads it from the
        // block entity, which is authoritative.
        if (enabled) {
            if (menu.getLevels() < ZoneMutation.requiredLevels()) {
                NoFlyDebug.log("set_zone from {} for tier-{} beacon (needs {})",
                    player.getGameProfile().name(), menu.getLevels(), ZoneMutation.requiredLevels());
                return;
            }
            if (!menu.hasPayment()) {
                NoFlyDebug.log("set_zone from {} with no payment", player.getGameProfile().name());
                return;
            }
        }

        // Resolve the actual block from the menu rather than trusting the client.
        ((BeaconMenuAccessor) menu).noflyzone$getAccess().execute((level, pos) -> {
            ZoneMutation.Result result = ZoneMutation.apply(level, pos, enabled);

            if (!result.ok()) {
                NoFlyDebug.log("set_zone from {} for beacon at {} refused: {}",
                    player.getGameProfile().name(), pos, result);
                return;
            }

            if (enabled) {
                // Consume the payment exactly as vanilla's updateEffects does,
                // and only once the change is known to have taken effect.
                menu.getSlot(0).remove(1);
            }

            NoFlyDebug.log("beacon at {} set no-fly={} by {}", pos, enabled, player.getGameProfile().name());
        });
    }
}
