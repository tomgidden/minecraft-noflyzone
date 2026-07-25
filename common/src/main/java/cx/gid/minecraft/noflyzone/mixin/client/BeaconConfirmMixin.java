package cx.gid.minecraft.noflyzone.mixin.client;

import cx.gid.minecraft.noflyzone.ModPayloads;
import cx.gid.minecraft.noflyzone.client.ClientNoFly;
import cx.gid.minecraft.noflyzone.client.ClientPayloadSender;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundSetBeaconPacket;
import net.minecraft.world.effect.MobEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sends the mod's own payload when No-Fly is confirmed, instead of the vanilla
 * beacon packet.
 *
 * <h2>Why the vanilla packet cannot carry this</h2>
 * {@code ServerboundSetBeaconPacket} encodes effects as bare registry ids, and No
 * Fly exists only in the <em>client's</em> registry -- it cannot be registered
 * server-side without Fabric forcing a registry sync that disconnects unmodded
 * clients (see {@link cx.gid.minecraft.noflyzone.NoFlyBeacon}). Sending it would
 * hand the server an unresolvable id and drop the connection with a decoder
 * exception.
 *
 * <p>So when the selection includes No-Fly the vanilla send is replaced with
 * {@code noflyzone:set_zone}. Any other selection is passed through untouched and
 * behaves exactly as vanilla. The {@code closeContainer()} that follows the send
 * is unaffected either way, since only the send itself is redirected.
 *
 * <p>Client-side only: {@code BeaconScreen} does not exist on a dedicated server.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.inventory.BeaconScreen$BeaconConfirmButton")
public abstract class BeaconConfirmMixin {

    @Redirect(
        method = "onPress",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private void noflyzone$sendZoneInstead(ClientPacketListener connection, Packet<?> packet) {
        if (packet instanceof ServerboundSetBeaconPacket beaconPacket && noflyzone$wantsNoFly(beaconPacket)) {
            ClientPayloadSender.send(new ModPayloads.SetZonePayload(true));
            return;
        }
        connection.send(packet);
    }

    /**
     * True if the outgoing selection asks for a no-fly zone.
     *
     * Reads the packet rather than the screen's fields, so this needs no access
     * to the outer class. Either slot counts: No-Fly sits in the tier-4 row, so
     * the screen offers it as a secondary alongside a primary of the player's
     * choosing.
     */
    private static boolean noflyzone$wantsNoFly(ServerboundSetBeaconPacket packet) {
        return noflyzone$isNoFly(packet.primary().orElse(null))
            || noflyzone$isNoFly(packet.secondary().orElse(null));
    }

    private static boolean noflyzone$isNoFly(Holder<MobEffect> holder) {
        return ClientNoFly.isNoFly(holder);
    }
}
