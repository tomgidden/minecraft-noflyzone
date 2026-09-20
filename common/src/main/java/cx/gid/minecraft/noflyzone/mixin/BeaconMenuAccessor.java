package cx.gid.minecraft.noflyzone.mixin;

import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the beacon menu's {@code ContainerLevelAccess}, so the server can find
 * out <em>which</em> beacon a player has open.
 *
 * The mod's {@code set_zone} payload carries no position -- the client screen has
 * no way to know one, and a client-supplied position could not be trusted anyway
 * -- so the server resolves the block from the menu the player already has open.
 * {@code ContainerLevelAccess} is the only thing that holds that link, and
 * vanilla keeps it private.
 */
@Mixin(BeaconMenu.class)
public interface BeaconMenuAccessor {
  @Accessor("access")
  ContainerLevelAccess noflyzone$getAccess();
}
