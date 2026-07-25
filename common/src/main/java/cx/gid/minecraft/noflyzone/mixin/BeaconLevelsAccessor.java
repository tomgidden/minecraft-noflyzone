package cx.gid.minecraft.noflyzone.mixin;

import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to {@code BeaconBlockEntity.levels}, the pyramid tier.
 *
 * <p>Vanilla exposes this to the beacon <em>menu</em> via a
 * {@code ContainerData}, but the block entity itself keeps the field private
 * with no getter. {@link cx.gid.minecraft.noflyzone.NoFlyCommand} needs it
 * without a menu open, since it works on the nearest beacon rather than one the
 * player is looking at.
 *
 * <p>The value is maintained by vanilla's own beacon tick, so reading it here
 * gives exactly the tier the beacon screen would show.
 */
@Mixin(BeaconBlockEntity.class)
public interface BeaconLevelsAccessor {

    @Accessor("levels")
    int noflyzone$getLevels();
}
