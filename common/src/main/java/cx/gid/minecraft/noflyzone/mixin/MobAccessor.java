package cx.gid.minecraft.noflyzone.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Write access to {@code Mob.moveControl}.
 *
 * <p>The field is {@code protected} and would be reachable by a plain
 * {@code @Shadow} -- but only from a mixin targeting {@code Mob} itself.
 * {@code @Shadow} resolves against the target class's own declarations, not
 * inherited members, so shadowing it from a {@code HappyGhast} mixin fails at
 * apply time with "was not located in the target class".
 *
 * <p>Hence an accessor on the declaring class instead. Used by
 * {@link HappyGhastMixin} to swap in
 * {@link cx.gid.minecraft.noflyzone.NoFlyGhastMoveControl}.
 */
@Mixin(Mob.class)
public interface MobAccessor {
  @Accessor("moveControl")
  void noflyzone$setMoveControl(MoveControl value);
}
