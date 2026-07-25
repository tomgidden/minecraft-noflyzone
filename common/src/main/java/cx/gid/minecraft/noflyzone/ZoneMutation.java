package cx.gid.minecraft.noflyzone;

import cx.gid.minecraft.noflyzone.mixin.BeaconLevelsAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;

/**
 * The one place a beacon's no-fly flag is validated and changed.
 *
 * <h2>Why this is separate</h2>
 * Two routes reach this state change: a modded client confirming No-Fly in the
 * beacon screen ({@link SetZoneHandler}) and an operator running
 * {@code /noflyzone} ({@link NoFlyCommand}). They authenticate differently -- one
 * by having a valid beacon menu open, the other by permission level -- but the
 * rules about what makes a beacon eligible, and the mutation itself, must not
 * drift apart between them.
 *
 * <p>So the callers own their <em>authorisation</em>, and this class owns
 * <em>eligibility and mutation</em>. The split is deliberate: whether you are
 * allowed to ask is a property of the route, whereas whether the beacon can
 * comply is a property of the beacon.
 */
public final class ZoneMutation {

    /**
     * Pyramid tier required to project a zone.
     *
     * <p>Defaults to 4, matching the tier vanilla requires for a secondary
     * beacon effect -- the row No-Fly is offered in. Lowering it via
     * {@code required_tier} only affects {@link NoFlyCommand}, since the beacon
     * screen's button placement is vanilla's to decide.
     */
    public static int requiredLevels() {
        return NoFlyConfig.get().requiredTier;
    }

    private ZoneMutation() {}

    /** Why a requested change was refused, or {@link #OK} if it was applied. */
    public enum Result {
        /** Applied. */
        OK,
        /** The block is no longer a beacon. */
        NOT_A_BEACON,
        /** The pyramid is too small to project a zone. */
        INSUFFICIENT_TIER,
        /** The beacon is already in the requested state. */
        UNCHANGED;

        public boolean ok() {
            return this == OK;
        }
    }

    /** The beacon's pyramid tier, as vanilla's own beacon tick computed it. */
    public static int levelsOf(BeaconBlockEntity beacon) {
        return ((BeaconLevelsAccessor) beacon).noflyzone$getLevels();
    }

    /** Whether the beacon currently projects a no-fly zone. */
    public static boolean isNoFly(BeaconBlockEntity beacon) {
        return ((NoFlyBeacon) beacon).noflyzone$isNoFlyBeacon();
    }

    /**
     * Validates and applies a no-fly flag change to the beacon at {@code pos}.
     *
     * <p>Callers must have already established that the actor is permitted to
     * make the request. Everything about whether the <em>beacon</em> can comply
     * is decided here.
     *
     * <p>Turning a zone off removes it from {@link NoFlyZones} at once rather
     * than letting it age out, so deactivation feels immediate. Turning one on
     * needs no such step: the beacon publishes itself on its next tick.
     *
     * @return {@link Result#OK} if the flag changed, otherwise the reason it did not
     */
    public static Result apply(Level level, BlockPos pos, boolean enabled) {
        if (!(level.getBlockEntity(pos) instanceof BeaconBlockEntity beacon)) {
            return Result.NOT_A_BEACON;
        }

        if (enabled && levelsOf(beacon) < requiredLevels()) {
            return Result.INSUFFICIENT_TIER;
        }

        if (isNoFly(beacon) == enabled) {
            return Result.UNCHANGED;
        }

        ((NoFlyBeacon) beacon).noflyzone$setNoFlyBeacon(enabled);
        beacon.setChanged();

        if (!enabled) {
            NoFlyZones.remove(level, pos);
        }

        return Result.OK;
    }
}
