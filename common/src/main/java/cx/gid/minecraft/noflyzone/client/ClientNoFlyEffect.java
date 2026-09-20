package cx.gid.minecraft.noflyzone.client;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * The "No-Fly" effect, registered on the client only.
 *
 * Registering this server-side would make Fabric mark {@code MOB_EFFECT} as
 * modded, force it into the login sync payload, and disconnect every client that
 * lacks the mod -- see {@link cx.gid.minecraft.noflyzone.NoFlyBeacon}. Since the
 * mod exists to affect unmodded players, the effect cannot exist on the server.
 *
 * The effect does nothing mechanically. It exists to give the beacon screen a
 * button to draw and the HUD an icon to show.
 */
public class ClientNoFlyEffect extends MobEffect {
  private static final int COLOUR = 0x494949;

  public ClientNoFlyEffect()
  {
    super(MobEffectCategory.NEUTRAL, COLOUR);
  }
}
