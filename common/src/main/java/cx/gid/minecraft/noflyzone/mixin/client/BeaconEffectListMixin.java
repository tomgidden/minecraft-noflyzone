package cx.gid.minecraft.noflyzone.mixin.client;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Read/write access to {@code BeaconBlockEntity.BEACON_EFFECTS}, the list the
 * beacon screen builds its buttons from.
 *
 * <h2>Client-side only, and necessarily so</h2>
 * {@code BeaconScreen.init()} reads this static from the <em>client's own</em>
 * JVM. A server-side mixin mutates the server's copy and a remote client never
 * hears about it, so extending the beacon menu genuinely cannot be done from the
 * server. This mixin is declared in the config's {@code client} list and must
 * never load on a dedicated server.
 *
 * <p>Note the failure mode that hides: mutating the list server-side
 * <em>appears</em> to work in singleplayer, where client and server share one
 * JVM, then does nothing at all on a real server.
 *
 * <p>Vanilla builds this list with {@code List.of} in the class initialiser, so
 * there is nothing mutable to append to -- hence {@code @Mutable} and a
 * wholesale replacement.
 *
 * <p>Only the menu list is touched, and only on the client. Vanilla's own
 * validation is left alone entirely: the No-Fly selection never travels through
 * {@code ServerboundSetBeaconPacket}, so there is nothing for the server to
 * validate. See {@link cx.gid.minecraft.noflyzone.mixin.client.BeaconConfirmMixin}.
 *
 * @see cx.gid.minecraft.noflyzone.client.ClientNoFly#installBeaconButton()
 */
@Mixin(BeaconBlockEntity.class)
public interface BeaconEffectListMixin {

    @Accessor("BEACON_EFFECTS")
    static List<List<Holder<MobEffect>>> noflyzone$getBeaconEffects() {
        throw new AssertionError("mixin accessor not applied");
    }

    @Accessor("BEACON_EFFECTS") @Mutable @Final
    static void noflyzone$setBeaconEffects(List<List<Holder<MobEffect>>> value) {
        throw new AssertionError("mixin accessor not applied");
    }
}
