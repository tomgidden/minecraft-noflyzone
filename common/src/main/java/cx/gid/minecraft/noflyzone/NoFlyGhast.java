package cx.gid.minecraft.noflyzone;

import net.minecraft.world.entity.animal.happyghast.HappyGhast;

/**
 * Duck-type interface mixed into {@code HappyGhast}, marking one that the mod is
 * currently steering out of a no-fly zone.
 *
 * <p>Read by {@link NoFlyGhastMoveControl} to decide whether the still timeout
 * should brake the ghast, and by the mixin to decide whether to arm that timeout
 * in the first place. Purely transient: it is recomputed every tick and never
 * saved, so a ghast that leaves a zone -- or a world where the mod is removed --
 * is immediately an ordinary ghast again.
 *
 * <p>Lives in the root package rather than {@code mixin} because every class in
 * the mixin package is fed to the mixin transformer, and a plain interface with
 * no {@code @Mixin} annotation fails that transformation. {@link NoFlyBeacon} is
 * here for the same reason.
 */
public interface NoFlyGhast {

    /** True if the mod is currently steering this ghast out of a zone. */
    boolean noflyzone$isFleeing();

    /** Convenience for callers holding a plain {@code HappyGhast}. */
    static boolean isFleeing(HappyGhast ghast) {
        return ghast instanceof NoFlyGhast flagged && flagged.noflyzone$isFleeing();
    }
}
