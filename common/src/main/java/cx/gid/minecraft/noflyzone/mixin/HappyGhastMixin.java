package cx.gid.minecraft.noflyzone.mixin;

import cx.gid.minecraft.noflyzone.NoFlyGhast;
import cx.gid.minecraft.noflyzone.NoFlyGhastMoveControl;
import cx.gid.minecraft.noflyzone.NoFlyMode;
import cx.gid.minecraft.noflyzone.NoFlyPolicy;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Steers happy ghasts out of a no-fly zone, using their own flight AI.
 *
 * <h2>Why ghasts need their own treatment</h2>
 * A happy ghast carries up to four players and flies under its own power, so
 * none of the elytra enforcement touches it: {@code canGlide()} is never
 * consulted, and the rider is a passenger rather than the thing flying. Left
 * alone, a ghast is a straightforward way to carry a party straight through a
 * zone.
 *
 * <h2>How control is taken, and why that alone was not enough</h2>
 * The still timeout is the lever that removes a pilot: while it is set,
 * {@code getControllingPassenger()} stops returning the rider, so
 * {@code travelRidden} is never entered and rider input is never read. Vanilla
 * uses it when somebody stands on the ghast's back.
 *
 * <p>But {@code adultGhastSetup()} also passes {@code this::isOnStillTimeout} to
 * its {@code GhastMoveControl} as that control's {@code shouldBeStopped}
 * supplier, and {@code GhastMoveControl.tick()} answers a true there by calling
 * {@code stopInPlace()} and discarding any waypoint. The one flag is both "the
 * pilot is not driving" and "the AI should not move". Arming it to achieve the
 * first defeats the second, which is exactly what an earlier attempt did:
 * ghasts sat motionless inside zones and were never repelled.
 *
 * <p>{@link NoFlyGhastMoveControl} unties that by supplying a different
 * predicate -- vanilla's reasoning, except while the mod is steering. The pilot
 * still loses control; only the brake is released.
 *
 * <h2>Also worth knowing</h2>
 * An even earlier attempt overrode {@code isOnStillTimeout()} directly. That is
 * only the <em>reader</em>: the writer {@code setServerStillTimeout(int)} also
 * sends a {@code ClientboundEntityPositionSyncPacket} and calls
 * {@code syncStayStillFlag()} to update synched entity data. Overriding the
 * reader changed the answer on the server and nowhere else, so the client went
 * on predicting rider control and diverged further every tick, reconciling only
 * on dismount. Drive the setter, never the getter.
 *
 * @see NoFlyPolicy#applyGhastEffects
 */
@Mixin(HappyGhast.class)
public abstract class HappyGhastMixin implements NoFlyGhast {
  /**
   * Refuses to let anything hold a fleeing ghast still.
   *
   * <p>The still timeout is vanilla's "nobody is steering me" state, and it is
   * also the {@code GhastMoveControl}'s brake -- {@code shouldBeStopped} is
   * literally {@code this::isOnStillTimeout}. Inside a zone that brake must
   * never engage, or the ghast sits motionless exactly where it is least
   * wanted.
   *
   * <p>Six vanilla call sites arm it, the awkward one being
   * {@code scanPlayerAboveGhast()}: a player standing on the ghast's back
   * re-arms it every tick. Since a dismounting rider lands on the roof, an
   * ejected pilot would otherwise pin the ghast in place indefinitely.
   * Cancelling at the setter catches all six at once.
   *
   * <p>Cancelled rather than zeroed: the setter also sends a position sync
   * packet and writes synched entity data, and doing neither is what keeps an
   * unmodded client's view consistent -- as far as it is concerned the timeout
   * simply never started.
   */
  @Inject(method = "setServerStillTimeout", at = @At("HEAD"), cancellable = true)
  private void noflyzone$refuseStillTimeout(int ticks, CallbackInfo ci)
  {
    if (ticks > 0 && this.noflyzone$fleeing) {
      ci.cancel();
    }
  }

  /**
   * True while the mod is steering this ghast out of a zone.
   */
  @Unique
  private boolean noflyzone$fleeing;

  @Override
  public boolean noflyzone$isFleeing()
  {
    return this.noflyzone$fleeing;
  }

  /**
   * Swaps in a move control that the mod can steer while the still timeout is
   * armed.
   *
   * <p>Hooked in <em>two</em> places, because between them they are the only
   * ways a happy ghast acquires a move control:
   *
   * <ul>
   *   <li>the constructor, which assigns one directly -- this covers every
   *       ghast that spawns as an adult or is loaded from disk;</li>
   *   <li>{@code adultGhastSetup}, reached only from
   *       {@code ageBoundaryReached}, i.e. when a baby grows up.</li>
   * </ul>
   *
   * <p>Hooking only the latter was an early bug: it fires on an age
   * <em>transition</em>, so every already-adult ghast in an existing world kept
   * vanilla's control and sat motionless in zones, waypoint accepted and
   * ignored.
   *
   * <p>Babies keep their {@code FlyingMoveControl} until they grow: they are
   * brain-driven, have no {@code shouldBeStopped} supplier to conflict with,
   * and take a waypoint directly.
   */
  @Inject(method = "<init>", at = @At("TAIL"))
  private void noflyzone$installMoveControlOnSpawn(CallbackInfo ci)
  {
    noflyzone$installMoveControl(ci);
  }

  @Inject(method = "adultGhastSetup", at = @At("TAIL"))
  private void noflyzone$installMoveControl(CallbackInfo ci)
  {
    HappyGhast self = (HappyGhast) (Object) this;

    // Server only. The move control exists to steer riderless ghasts out of
    // zones, which is a server-side decision; the client has no zone data and
    // never needs it. Running on the client also means running inside the
    // entity constructor during ClientboundAddEntityPacket handling, where
    // the entity has no id yet -- touching getId() there throws
    // "Tried to access entity ID before ID assignment" and drops the client
    // with a protocol error.
    if (self.level().isClientSide()) {
      return;
    }

    // Babies use a FlyingMoveControl and a brain; leave theirs alone. They
    // pick ours up via adultGhastSetup when they grow.
    if (self.isBaby()) {
      return;
    }

    // Assigned through MobAccessor rather than @Shadow: the field is declared
    // on Mob, and @Shadow only resolves fields declared on the target class
    // itself, not ones inherited from a superclass.
    ((MobAccessor) self).noflyzone$setMoveControl(new NoFlyGhastMoveControl(self));
  }

  /**
   * Re-evaluates the zone once per tick and applies whatever the mode calls
   * for: a waypoint out, damage to passengers, or ejection.
   *
   * <p>Injected at {@code HEAD}. {@code HappyGhast.tick()} calls
   * {@code super.tick()} as its very first act, and all of the movement --
   * {@code aiStep}, {@code travelRidden}, the {@code getControllingPassenger()}
   * check that decides who is steering -- happens inside that call. A
   * {@code TAIL} injection would set the flag after the tick it was meant to
   * govern, leaving the rider in control for a tick while the server steered
   * elsewhere.
   */
  /**
   * Strips the inward component from a rider's steering while in a zone.
   *
   * <p>Piloted ghasts need a different lever from unpiloted ones. While a
   * player is controlling, {@code travelRidden} takes over movement entirely
   * and the {@code MoveControl} is never consulted -- so the AI waypoint that
   * herds a riderless ghast out is simply ignored, and an earlier build
   * announced "you cannot fly in here" while letting the pilot fly on
   * regardless.
   *
   * <p>The alternative, taking control away via the still timeout, is what the
   * rest of this class exists to avoid: it is also the move control's brake.
   *
   * <p>So the rider keeps flying and keeps control -- they simply cannot steer
   * deeper in. Pushing outward or along the boundary works normally, which
   * makes leaving easy and entering impossible without ever fighting the
   * player's input for control. Vanilla applies this vector in the ghast's own
   * frame, so the zone's outward direction is rotated into that frame before
   * the component is removed.
   */
  @Inject(method = "getRiddenInput", at = @At("RETURN"), cancellable = true)
  private void noflyzone$refuseInwardSteering(Player player, Vec3 input,
      CallbackInfoReturnable<Vec3> cir)
  {
    HappyGhast self = (HappyGhast) (Object) this;

    if (!this.noflyzone$fleeing) {
      return;
    }

    Vec3 outward = NoFlyPolicy.outwardDirection(self);
    if (outward == null) {
      return;
    }

    Vec3 steering = cir.getReturnValue();

    // getRiddenInput is expressed relative to the ghast's facing: x is
    // strafe, z is forward. Convert to world space to compare against the
    // zone's outward normal, strip any inward part, then convert back.
    float yaw     = self.getYRot() * ((float) Math.PI / 180.0F);
    double sin    = Math.sin(yaw);
    double cos    = Math.cos(yaw);
    double worldX = steering.x * cos - steering.z * sin;
    double worldZ = steering.z * cos + steering.x * sin;

    double inwardness = worldX * outward.x + worldZ * outward.z;
    if (inwardness >= 0.0) {
      return; // already heading out, or moving along the boundary
    }

    worldX -= inwardness * outward.x;
    worldZ -= inwardness * outward.z;

    cir.setReturnValue(new Vec3(
        worldX * cos + worldZ * sin,
        steering.y,
        worldZ * cos - worldX * sin));
  }

  @Inject(method = "tick", at = @At("HEAD"))
  private void noflyzone$redirectFromZone(CallbackInfo ci)
  {
    HappyGhast self = (HappyGhast) (Object) this;

    boolean shouldFlee = NoFlyPolicy.shouldRedirectGhast(self)
        && NoFlyPolicy.mode() != NoFlyMode.DAMAGE;
    this.noflyzone$fleeing = shouldFlee;

    if (!NoFlyPolicy.shouldRedirectGhast(self)) {
      return;
    }

    NoFlyPolicy.applyGhastEffects(self);
  }
}
