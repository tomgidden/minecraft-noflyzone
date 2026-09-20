package cx.gid.minecraft.noflyzone.mixin;

import cx.gid.minecraft.noflyzone.NoFlyPolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-tick enforcement for the modes that let a player keep gliding.
 *
 * <p>{@code LivingEntity.updateFallFlying()} runs once per tick for every
 * gliding entity, which is exactly the cadence both surviving-flight modes need:
 * {@code zero-momentum} clamps velocity, {@code damage} applies damage. The
 * mode that <em>stops</em> the glide ({@code no-glide}) is handled entirely in
 * {@link LivingEntityCanGlideMixin} and does nothing here.
 *
 * <p>Injected at {@code TAIL} so vanilla's own fall-flying movement has already
 * been applied for this tick. Clamping before it would simply be overwritten.
 *
 * @see NoFlyPolicy#applyInFlightEffects
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityGlideTickMixin {
  @Inject(method = "updateFallFlying", at = @At("TAIL"))
  private void noflyzone$enforceInFlight(CallbackInfo ci)
  {
    LivingEntity self = (LivingEntity) (Object) this;

    if (!NoFlyPolicy.shouldRefuse(self)) {
      return;
    }

    ServerPlayer player = (ServerPlayer) self;

    // Only act on a player actually gliding: canGlide is consulted for
    // anyone merely holding an elytra, and this hook can be reached in the
    // tick where the flag has just been cleared.
    if (!player.isFallFlying()) {
      return;
    }

    NoFlyPolicy.applyInFlightEffects(player);
  }
}
