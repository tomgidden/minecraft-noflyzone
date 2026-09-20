package cx.gid.minecraft.noflyzone.mixin;

import cx.gid.minecraft.noflyzone.NoFlyConfig;
import cx.gid.minecraft.noflyzone.NoFlyMessages;
import cx.gid.minecraft.noflyzone.NoFlyPolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks riptide launches inside a no-fly zone.
 *
 * Riptide is a genuinely separate mechanism from gliding: it never touches the
 * fall-flying flag, driving the player with {@code push()} plus
 * {@code startAutoSpinAttack()} on a different synched byte entirely. Blocking
 * elytra therefore does nothing to it, and a zone that grounded elytra but let
 * players riptide skyward would be an obvious hole in a rule that reads
 * "nothing gets you airborne here".
 *
 * <p>The refusal is at HEAD of {@code releaseUsing} rather than inside the
 * riptide branch, so the throw is never started. Returning false matches what
 * vanilla returns when a trident release does nothing, leaving the trident in
 * hand, undamaged and unthrown.
 *
 * <p>Only riptide is refused: an ordinary trident throw is not flight and is
 * left alone. That distinction is why this re-tests the spin-attack strength
 * rather than blanket-cancelling the release.
 */
@Mixin(TridentItem.class)
public abstract class TridentItemMixin {
  @Inject(method = "releaseUsing", at = @At("HEAD"), cancellable = true)
  private void noflyzone$refuseRiptideInZone(ItemStack itemStack, Level level, LivingEntity entity,
      int remainingTime, CallbackInfoReturnable<Boolean> cir)
  {
    if (!NoFlyConfig.get().blockRiptide) {
      return;
    }
    if (!(entity instanceof Player player)) {
      return;
    }
    if (!NoFlyPolicy.shouldRefuse(player)) {
      return;
    }

    // Mirror vanilla's own riptide gate: a trident only riptides when it has
    // spin-attack strength AND the player is in water or rain. Anything else
    // is a normal throw, which this mod has no business interfering with.
    float riptideStrength = EnchantmentHelper.getTridentSpinAttackStrength(itemStack, player);
    if (riptideStrength <= 0.0F || !player.isInWaterOrRain() || player.isPassenger()) {
      return;
    }

    NoFlyPolicy.notifyRefused((ServerPlayer) player, NoFlyMessages.RIPTIDE_DENIED);
    cir.setReturnValue(false);
  }
}
