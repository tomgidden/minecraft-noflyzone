package cx.gid.minecraft.noflyzone.client;

import cx.gid.minecraft.noflyzone.Constants;
import cx.gid.minecraft.noflyzone.NoFlyDebug;
import cx.gid.minecraft.noflyzone.mixin.client.BeaconEffectListMixin;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * The client half: the No-Fly button in the beacon screen, and the HUD icon.
 *
 * All of this is optional. A player without the mod is subject to every no-fly
 * zone identically; they simply cannot create one and see no icon.
 */
public final class ClientNoFly {

    /** How long the locally-applied icon effect lasts before needing a refresh. */
    private static final int ICON_DURATION_TICKS = 20 * 30;

    public static final ResourceKey<MobEffect> NO_FLY_KEY =
        ResourceKey.create(Registries.MOB_EFFECT,
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "no_fly"));

    /** The client-local effect holder, assigned by {@link #register()}. */
    public static Holder<MobEffect> NO_FLY;

    private ClientNoFly() {}

    /**
     * Registers the effect in the client's own registry.
     *
     * Safe despite the server knowing nothing about it: the id never goes over
     * the wire. See {@link ClientNoFlyEffect}.
     */
    public static void register() {
        NO_FLY = Registry.registerForHolder(BuiltInRegistries.MOB_EFFECT, NO_FLY_KEY, new ClientNoFlyEffect());
    }

    /** True if the given holder is our client-local No-Fly effect. */
    public static boolean isNoFly(Holder<MobEffect> holder) {
        return holder != null && holder.is(NO_FLY_KEY);
    }

    /**
     * Appends No-Fly to the last beacon tier so the screen draws a button for it.
     *
     * Idempotent. Called after {@link #register()}, rather than from a class
     * initialiser, so the effect is guaranteed to exist by this point.
     */
    public static void installBeaconButton() {
        if (NO_FLY == null) {
            Constants.LOGGER.warn("No-Fly effect not registered; beacon menu button not added");
            return;
        }

        List<List<Holder<MobEffect>>> tiers = new ArrayList<>(BeaconEffectListMixin.noflyzone$getBeaconEffects());
        if (tiers.isEmpty()) {
            return;
        }

        int lastTier = tiers.size() - 1;
        List<Holder<MobEffect>> topRow = new ArrayList<>(tiers.get(lastTier));
        if (topRow.contains(NO_FLY)) {
            return;
        }

        topRow.add(NO_FLY);
        tiers.set(lastTier, List.copyOf(topRow));
        BeaconEffectListMixin.noflyzone$setBeaconEffects(List.copyOf(tiers));

        NoFlyDebug.log("added No-Fly to the beacon menu (tier {})", lastTier + 1);
    }

    /**
     * Shows or hides the HUD icon, in response to a {@code noflyzone:in_zone}
     * payload from the server.
     *
     * The effect is applied to the local player by the client itself, so no
     * effect id is ever sent or received.
     */
    public static void setInZone(net.minecraft.world.entity.player.Player player, boolean inZone) {
        if (NO_FLY == null) {
            return;
        }
        if (inZone) {
            player.addEffect(new MobEffectInstance(NO_FLY, ICON_DURATION_TICKS, 0, true, false, true));
        } else {
            player.removeEffect(NO_FLY);
        }
    }
}
