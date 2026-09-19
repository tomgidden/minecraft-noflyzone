package cx.gid.minecraft.noflyzone.mixin;

import cx.gid.minecraft.noflyzone.NoFlyPolicy;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuses client-driven vehicle movement deeper into a no-fly zone.
 *
 * <h2>Why enforcement has to happen here</h2>
 * A ridden happy ghast is <em>client-authoritative</em>. The client reads the
 * player's input, moves the ghast locally, and tells the server where it ended
 * up with a {@code ServerboundMoveVehiclePacket}. The server does run
 * {@code travelRidden} -> {@code getRiddenInput}, but {@code Player.xxa} and
 * {@code zza} are only populated for the local player on the client, so
 * server-side the input vector is always {@code (0, 0, 0)}.
 *
 * <p>That is worth stating plainly because it invalidates every server-side
 * attempt to intercept the <em>input</em>: zeroing a vector that is already
 * zero changes nothing, and the ghast keeps flying on the client's say-so.
 * Diagnostics showed exactly this -- 609 consecutive calls with
 * {@code in=(0.0, 0.0, 0.0)} while the ghast flew happily toward the beacon.
 *
 * <p>The authoritative moment is instead the packet itself. Cancelling the
 * handler leaves the server's position unchanged, and because the server never
 * acknowledges the move, vanilla's own correction sends the client back. That is
 * the same mechanism anti-cheat uses to reject illegal movement, so it is
 * well-trodden and needs no client mod.
 *
 * <h2>Only inward movement is refused</h2>
 * A player can always steer out or along the boundary; only the component of
 * travel that takes them deeper in is rejected. Refusing every move inside a
 * zone would trap anyone who wandered in, which is worse than letting them
 * leave under their own power.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {

    @Shadow
    public ServerPlayer player;


    @Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
    private void noflyzone$refuseInwardVehicleMove(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        Entity vehicle = this.player.getRootVehicle();
        if (vehicle == null || vehicle == this.player) {
            return;
        }

        if (!NoFlyPolicy.refusesVehicleMove(vehicle, packet.movingTo().position())) {
            return;
        }

        // Cancelling alone is not enough. The client is authoritative for a
        // ridden vehicle and will happily keep predicting forward, flying a
        // ghost the server never agreed to -- and then snapping violently back
        // the moment anything forces a resync, such as dismounting.
        //
        // So do what vanilla does when it rejects a move: pin the entity at the
        // position the server still believes in, and tell the client. The
        // correction arrives every tick the player pushes inward, which is what
        // makes the boundary feel like a wall rather than like lag.
        vehicle.absSnapTo(vehicle.getX(), vehicle.getY(), vehicle.getZ(),
            vehicle.getYRot(), vehicle.getXRot());
        // Sent via player.connection rather than a @Shadow of send(): that
        // method is declared on ServerCommonPacketListenerImpl, and @Shadow only
        // resolves members declared on the target class itself.
        this.player.connection.send(ClientboundMoveVehiclePacket.fromEntity(vehicle));

        ci.cancel();
    }
}
