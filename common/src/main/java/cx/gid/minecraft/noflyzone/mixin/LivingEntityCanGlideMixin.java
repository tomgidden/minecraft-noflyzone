package cx.gid.minecraft.noflyzone.mixin;

import cx.gid.minecraft.noflyzone.NoFlyMessages;
import cx.gid.minecraft.noflyzone.NoFlyMode;
import cx.gid.minecraft.noflyzone.NoFlyPolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses gliding inside a zone, for the modes that stop the glide.
 *
 * <p>{@code LivingEntity.canGlide()} is consulted from two places, which
 * together are exactly the two things this mod needs to control:
 *
 * <ul>
 *   <li>{@code Player.tryToStartFallFlying()} -- the takeoff attempt, reached
 *       when the client sends {@code START_FALL_FLYING}. Returning false here
 *       means the player never leaves the ground.</li>
 *   <li>{@code LivingEntity.updateFallFlying()} -- run every tick while gliding.
 *       Returning false here makes vanilla clear the fall-flying flag, which is
 *       the "wings cut" behaviour for someone who glided in from outside.</li>
 * </ul>
 *
 * <h2>What each mode does here</h2>
 * <ul>
 *   <li>{@link NoFlyMode#NO_GLIDE} refuses both: takeoff is denied and an
 *       existing glide is cut, so the player falls.</li>
 *   <li>{@link NoFlyMode#ZERO_MOMENTUM} refuses <em>takeoff only</em>. An
 *       existing glide must keep working, because the mode's whole point is that
 *       the player sinks gently and lands rather than dropping. The velocity
 *       clamp lives in {@link LivingEntityGlideTickMixin}.</li>
 *   <li>{@link NoFlyMode#DAMAGE} refuses nothing here -- the player keeps flying
 *       and keeps control. Damage is applied in
 *       {@link LivingEntityGlideTickMixin}.</li>
 * </ul>
 *
 * <p>Because vanilla already handles both refusal paths, no manual packet or
 * resync is needed. {@code handlePlayerCommand} does
 * {@code if (!tryToStartFallFlying()) stopFallFlying();} on the takeoff side,
 * and {@code updateFallFlying} does {@code setSharedFlag(7, false)} on the
 * in-flight side -- and {@code stopFallFlying()}'s deliberate set-true-then-false
 * is what forces the synched-data item dirty so the client actually hears about
 * it. Reimplementing any of that here would risk the classic desync where the
 * server thinks the flag is already false, sends nothing, and the client keeps
 * flying.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityCanGlideMixin {
  @Inject(method = "canGlide", at = @At("HEAD"), cancellable = true)
  private void noflyzone$refuseGlideInZone(CallbackInfoReturnable<Boolean> cir)
  {
    LivingEntity self = (LivingEntity) (Object) this;

    // shouldRefuse already filters to server-side ServerPlayers, so this
    // stays a single cheap call on a hot path.
    if (!NoFlyPolicy.shouldRefuse(self)) {
      return;
    }

    ServerPlayer player = (ServerPlayer) self;
    NoFlyMode mode      = NoFlyPolicy.mode();

    // DAMAGE leaves flight alone entirely; the player is punished, not grounded.
    if (mode == NoFlyMode.DAMAGE) {
      return;
    }

    // canGlide is polled for players who merely own an elytra, not only for
    // those actually flying, so the two cases are distinguished for the
    // message. notifyRefused applies its own cooldown, so a player
    // repeatedly retrying inside a zone is not spammed.
    boolean flying = player.isFallFlying();

    if (mode == NoFlyMode.ZERO_MOMENTUM && flying) {
      // Deliberately permitted: cutting the glide here is exactly what this
      // mode exists to avoid. The player is slowed to a stop and descends,
      // which LivingEntityGlideTickMixin takes care of.
      return;
    }

    NoFlyPolicy.notifyRefused(player,
        flying ? NoFlyMessages.GLIDE_CUT : NoFlyMessages.TAKEOFF_DENIED);

    cir.setReturnValue(false);
  }
}
