package cx.gid.minecraft.noflyzone;

import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.monster.Ghast;

/**
 * A {@code GhastMoveControl} that keeps flying while the mod is steering a ghast
 * out of a no-fly zone.
 *
 * <h2>The knot this unties</h2>
 * Vanilla builds a happy ghast's move control as
 * {@code new GhastMoveControl(ghast, true, ghast::isOnStillTimeout)}. That third
 * argument is {@code shouldBeStopped}: when it returns true,
 * {@code GhastMoveControl.tick()} calls {@code stopInPlace()} and sets
 * {@code Operation.WAIT}, discarding any waypoint.
 *
 * <p>The still timeout is therefore doing two jobs at once. It is the only
 * reliable way to take the controls off a pilot -- {@code
 * getControllingPassenger()} consults it, so while it is set the rider's input
 * is never read -- and it is simultaneously the AI's own brake. Arming it to
 * remove a pilot also stops the AI that is supposed to fly the ghast out, which
 * is why an earlier attempt left ghasts sitting motionless inside zones.
 *
 * <p>Since the supplier is a constructor argument rather than something baked
 * into the class, the fix is simply to pass a different one. This subclass
 * answers "should I stop?" with vanilla's own reasoning <em>unless</em> the mod
 * is actively steering, in which case the answer is no. The pilot still loses
 * control; only the brake is released.
 *
 * <p>Nothing is mixed into {@code GhastMoveControl} itself, deliberately: that
 * class is shared with hostile ghasts, and this concerns only happy ones.
 */
public class NoFlyGhastMoveControl extends Ghast.GhastMoveControl<HappyGhast> {
  private final HappyGhast ghast;

  public NoFlyGhastMoveControl(HappyGhast ghast)
  {
    // The supplier cannot reference `this` before super() completes, so it
    // closes over the ghast and re-reads the flag on each call instead.
    super(ghast, true, () -> ghast.isOnStillTimeout() && !NoFlyGhast.isFleeing(ghast));
    this.ghast = ghast;
  }

  /**
   * The ghast this control belongs to.
   */
  public HappyGhast ghast()
  {
    return this.ghast;
  }
}
