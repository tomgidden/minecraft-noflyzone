package cx.gid.minecraft.noflyzone.mixin;

import cx.gid.minecraft.noflyzone.NoFlyConfig;
import cx.gid.minecraft.noflyzone.NoFlyMessages;
import cx.gid.minecraft.noflyzone.NoFlyPolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops firework rockets being used to boost a glide inside a no-fly zone.
 *
 * Boosting is a distinct code path from gliding: {@code FireworkRocketItem.use}
 * checks {@code isFallFlying()} and spawns a rocket bound to the player, without
 * consulting {@code canGlide()} at all. In practice the glide flag is cut within
 * a tick of entering a zone, which makes this mostly redundant -- but "mostly"
 * is doing real work in that sentence, and a rocket consumed for a boost that
 * the next tick cancels is a bad outcome for the player.
 *
 * <p>Only the boost is refused. Right-clicking a rocket into the ground or a
 * block to launch it as a firework is not flight, so {@code useOn} is left
 * alone; this hook returns early for anyone who isn't currently gliding.
 */
@Mixin(FireworkRocketItem.class)
public abstract class FireworkRocketItemMixin {

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void noflyzone$refuseBoostInZone(Level level, Player player, InteractionHand hand,
                                             CallbackInfoReturnable<InteractionResult> cir) {
        if (!NoFlyConfig.get().blockFireworkBoost) {
            return;
        }
        // Not gliding means this is not a boost; vanilla will PASS anyway.
        if (!player.isFallFlying()) {
            return;
        }
        if (!NoFlyPolicy.shouldRefuse(player)) {
            return;
        }

        NoFlyPolicy.notifyRefused((ServerPlayer)player, NoFlyMessages.BOOST_DENIED);

        // PASS rather than FAIL: the rocket is not consumed and the hand is free
        // to do something else, which is what vanilla does for a non-gliding use.
        cir.setReturnValue(InteractionResult.PASS);
    }
}
