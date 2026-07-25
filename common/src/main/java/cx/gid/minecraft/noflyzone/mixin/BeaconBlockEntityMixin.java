package cx.gid.minecraft.noflyzone.mixin;

import cx.gid.minecraft.noflyzone.NoFlyBeacon;
import cx.gid.minecraft.noflyzone.NoFlyZones;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes a beacon able to project a no-fly zone.
 *
 * <h2>Three jobs</h2>
 * <ul>
 *   <li>Hold the flag ({@link NoFlyBeacon}) and persist it in the beacon's NBT,
 *       so a no-fly beacon survives a world reload.</li>
 *   <li>Publish the zone from {@code applyEffects}, which vanilla only reaches
 *       for a beacon it considers fully active -- so pyramid tier and sky access
 *       are inherited for free, including deactivation when someone roofs it
 *       over.</li>
 *   <li>Suppress the beacon's normal potion effect while it is a no-fly beacon:
 *       one beacon, one job.</li>
 * </ul>
 *
 * <p>Note there is deliberately no interference with vanilla's effect selection,
 * validation or storage. The flag is entirely separate from
 * {@code primaryPower}/{@code secondaryPower} -- see {@link NoFlyBeacon} for why
 * a custom {@code MobEffect} is not usable here.
 */
@Mixin(BeaconBlockEntity.class)
public abstract class BeaconBlockEntityMixin implements NoFlyBeacon {

    @Unique
    private boolean noflyzone$noFly;

    @Override
    public boolean noflyzone$isNoFlyBeacon() {
        return this.noflyzone$noFly;
    }

    @Override
    public void noflyzone$setNoFlyBeacon(boolean value) {
        this.noflyzone$noFly = value;
    }

    /** Persists the flag. Only written when set, to keep vanilla beacons' NBT clean. */
    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void noflyzone$save(ValueOutput output, CallbackInfo ci) {
        if (this.noflyzone$noFly) {
            output.putBoolean(TAG_NO_FLY, true);
        }
    }

    /** Restores the flag on world load. */
    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void noflyzone$load(ValueInput input, CallbackInfo ci) {
        this.noflyzone$noFly = input.getBooleanOr(TAG_NO_FLY, false);
    }

    /**
     * Publishes the zone, and cancels the beacon's normal effect application.
     *
     * {@code applyEffects} is static, so the flag has to be read from the block
     * entity at {@code worldPosition} rather than from {@code this}. That lookup
     * is cheap -- the block entity is loaded by definition, since it is mid-tick
     * -- and it runs only every 80 ticks, on vanilla's own beacon cadence.
     */
    @Inject(method = "applyEffects", at = @At("HEAD"), cancellable = true)
    private static void noflyzone$projectZone(Level level, BlockPos worldPosition, int levels,
                                              Holder<MobEffect> primaryPower, Holder<MobEffect> secondaryPower,
                                              CallbackInfo ci) {
        if (level.isClientSide()) {
            return;
        }
        if (!(level.getBlockEntity(worldPosition) instanceof NoFlyBeacon beacon) || !beacon.noflyzone$isNoFlyBeacon()) {
            return;
        }

        NoFlyZones.refresh(level, worldPosition, levels);

        // Cancel so a no-fly beacon grants no potion effect. Whatever the player
        // selected in the beacon screen is simply not applied while the zone is up.
        ci.cancel();
    }
}
