package cx.gid.minecraft.noflyzone.client;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * The "No-Fly" effect, registered <em>on the client only</em>.
 *
 * <h2>Client-only, and that is the whole point</h2>
 * Registering this server-side would make Fabric mark {@code MOB_EFFECT} as
 * modded, force it into the login sync payload, and disconnect every client that
 * lacks the mod -- see {@link cx.gid.minecraft.noflyzone.NoFlyBeacon}. Since the
 * mod exists to affect unmodded players, the effect cannot exist on the server.
 *
 * <p>Registering it only on the client is safe because the id never crosses the
 * network in either direction. The beacon selection travels as
 * {@code noflyzone:set_zone} (a boolean) and the HUD state as
 * {@code noflyzone:in_zone} (a boolean); neither mentions a registry id. The
 * effect is applied locally, by the client, to itself.
 *
 * <p>The effect does nothing mechanically. It exists to give the beacon screen a
 * button to draw and the HUD an icon to show.
 */
public class ClientNoFlyEffect extends MobEffect {

    /**
     * Anvil grey, matching the icon. Drives the ambient effect particles, which
     * are purely local here anyway.
     */
    private static final int COLOUR = 0x494949;

    public ClientNoFlyEffect() {
        super(MobEffectCategory.NEUTRAL, COLOUR);
    }
}
