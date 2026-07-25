package cx.gid.minecraft.noflyzone;

/**
 * Duck-type interface mixed into {@code BeaconBlockEntity}, marking a beacon as
 * projecting a no-fly zone.
 *
 * <h2>Why the flag lives here and not in the beacon's effect slots</h2>
 * The obvious design -- register a "No-Fly" {@code MobEffect} and store it as the
 * beacon's primary or secondary power -- cannot work on a server-side mod. Fabric
 * API marks {@code MOB_EFFECT} as {@code MODDED} the moment any non-vanilla entry
 * is registered, which forces the registry into the login sync payload and
 * <em>disconnects every client that lacks the mod</em>:
 *
 * <pre>
 * Registry entry (noflyzone:no_fly) is missing from local registry
 *   (minecraft:mob_effect)
 * </pre>
 *
 * There is no supported opt-out ({@code RegistryAttribute.OPTIONAL} only covers a
 * wholly missing registry, not missing entries, and {@code removeAttribute}
 * throws outside a dev environment). Since the entire point of this mod is that
 * unmodded players are still subject to no-fly zones, the effect cannot exist
 * server-side at all.
 *
 * <p>So the state is kept as a plain boolean in the beacon's own NBT instead. It
 * is not a vanilla concept and nothing but this mod reads it, which also means a
 * world that has had the mod removed keeps a harmless orphan flag until the mod
 * returns.
 *
 * @see cx.gid.minecraft.noflyzone.mixin.BeaconBlockEntityMixin
 */
public interface NoFlyBeacon {

    /** NBT key the flag is stored under. Namespaced to avoid colliding with vanilla. */
    String TAG_NO_FLY = "noflyzone:no_fly";

    /** True if this beacon projects a no-fly zone. */
    boolean noflyzone$isNoFlyBeacon();

    /** Marks (or unmarks) this beacon as projecting a no-fly zone. */
    void noflyzone$setNoFlyBeacon(boolean value);
}
