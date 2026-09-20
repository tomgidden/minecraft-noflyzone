package cx.gid.minecraft.noflyzone;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The one place that answers "should flight be refused for this entity, here?",
 * plus the player-facing messaging that goes with a refusal.
 *
 * Keeping the bypass rule and the message cooldown here rather than in the
 * mixins means all three enforcement points (glide, riptide, firework boost)
 * behave consistently, and the mixins stay thin.
 */
public final class NoFlyPolicy {
  /**
   * Damage per hit in {@link NoFlyMode#DAMAGE} mode, matching vanilla's void
   * damage. See {@link #applyDamage} for why the cadence differs.
   */
  private static final float DAMAGE_PER_HIT = 4.0f;

  /**
   * Horizontal speed cap in {@link NoFlyMode#ZERO_MOMENTUM} mode, in blocks per
   * tick -- vanilla's baseline walking speed, about 4.3 blocks/second.
   *
   * <p>Fixed rather than read from the player's {@code MOVEMENT_SPEED}
   * attribute: the cap is a property of the zone, not of the player, so speed
   * potions and enchantments do not raise it.
   */
  private static final double WALKING_SPEED = 0.1;

  /**
   * How far ahead to place a fleeing ghast's waypoint, in blocks.
   *
   * <p>Deliberately short. {@code GhastMoveControl} is built with
   * {@code careful = true}, and its {@code canReach} check sweeps the ghast's
   * bounding box along the <em>entire</em> offset to the target, testing every
   * block position it passes through. A waypoint fifty blocks away over real
   * terrain almost always fails that test, and the ghast simply does not move
   * -- which is exactly what a first attempt at this did. Vanilla's own wander
   * goal picks targets about sixteen blocks out for the same reason.
   *
   * <p>The target is therefore anchored just outside the zone edge and lifted
   * clear of terrain, rather than placed a fixed distance ahead of the ghast.
   */
  private static final double CLEARANCE = 4.0;

  /**
   * Slack allowed before a vehicle move counts as heading further in, in
   * blocks. Absorbs the jitter of ordinary flight so a player hovering near
   * the boundary is not fighting constant rejections.
   */
  private static final double DEPTH_TOLERANCE = 0.05;

  /**
   * Blocks to raise the target by on each attempt to find an unobstructed path.
   */
  private static final double LIFT_STEP = 4.0;

  /**
   * How many times to try lifting the target before giving up for this tick.
   */
  private static final int MAX_LIFT_STEPS = 8;

  /**
   * Minimum height above ground to aim a fleeing ghast, in blocks.
   *
   * <p>The waypoint is placed level with the ghast, or lifted to this height if
   * the ghast is flying low. Aiming <em>downward</em> was an early bug: the
   * reachability sweep runs from the ghast to the target, so a descending
   * target near ground level drags the swept box through terrain and the check
   * fails every time -- a ghast at Y=64 aiming two blocks down never moved at
   * all. Zones already shed altitude by pushing outward; they do not need to
   * aim at the floor to do it.
   */
  private static final double MIN_FLIGHT_HEIGHT = 6.0;

  /**
   * Speed modifier for the escape waypoint. Slightly above 1.0 so leaving is a
   * little more urgent than ordinary wandering, without looking panicked.
   */
  private static final double ESCAPE_SPEED = 1.2;

  /**
   * Elytra durability lost per damage tick.
   */
  private static final int ELYTRA_DAMAGE_PER_HIT = 1;

  /**
   * Last tick each player was shown a refusal message, for cooldown purposes.
   */
  private static final Map<UUID, Long> LAST_MESSAGE = new ConcurrentHashMap<>();

  /**
   * Last tick each player was damaged, for the damage-mode interval.
   */
  private static final Map<UUID, Long> LAST_DAMAGE = new ConcurrentHashMap<>();

  private NoFlyPolicy() {}

  /**
   * True if flight should be refused for this entity at its current position.
   *
   * Only players are subject to no-fly zones: a mob wearing an elytra is a
   * curiosity rather than the problem this mod exists to solve, and sparing
   * them keeps the per-tick cost off every entity in the world.
   *
   * <p>There are deliberately no exemptions. A no-fly zone applies to
   * everyone -- operators included -- because a rule with holes in it is a
   * weaker guarantee than the one this mod is meant to provide. Spectators are
   * the sole exception, and only because spectator movement is not elytra
   * flight at all: it never sets the gliding flag, so it never reaches here.
   */
  public static boolean shouldRefuse(Entity entity)
  {
    if (!(entity instanceof ServerPlayer player)) {
      return false;
    }
    if (player.level().isClientSide()) {
      return false;
    }
    return NoFlyZones.isInZone(player);
  }

  /**
   * The configured enforcement mode.
   */
  public static NoFlyMode mode()
  {
    return NoFlyConfig.get().mode;
  }

  /**
   * Per-tick effects for a player still gliding inside a zone.
   *
   * <p>Reached only from {@link cx.gid.minecraft.noflyzone.mixin.LivingEntityGlideTickMixin},
   * and only for the two modes that let a glide continue. {@code no-glide}
   * never gets here, because the glide has already been cut.
   */
  public static void applyInFlightEffects(ServerPlayer player)
  {
    switch (mode()) {
      case ZERO_MOMENTUM -> clampMomentum(player);
      case DAMAGE -> {
        applyDamage(player);
        // Per-tick rather than per-hit: a trail only reads as tracking
        // if it is continuous between hits. Rate-limited inside.
        NoFlyParticles.trail(player);
      }
      case NO_GLIDE -> { /* handled in canGlide; nothing to do per-tick */
      }
    }
  }

  /**
   * Caps horizontal movement at walking pace while leaving vertical alone, so
   * the player drifts down and lands safely.
   *
   * <p>Capped rather than stopped dead: an abrupt halt in mid-air reads as a
   * bug or as lag, whereas being slowed to a walk reads as resistance. The
   * player keeps steering and keeps moving, just no faster than they could on
   * foot -- which is the point, since elytra are a travel shortcut and this
   * mode's job is to take the shortcut away rather than to take away flight.
   *
   * <p>The cap is a fixed constant rather than the player's own
   * {@code MOVEMENT_SPEED} attribute, deliberately: the zone imposes the same
   * limit on everyone, so a speed potion cannot buy a faster crossing.
   *
   * <p>Vertical velocity is untouched: zeroing it would leave the player
   * hovering, and forcing it downward would be a fall by another name. Letting
   * elytra descent proceed normally is what makes this the merciful mode.
   *
   * <p>Runs after vanilla's fall-flying movement for the tick, so this is the
   * final word on velocity rather than something vanilla overwrites.
   */
  private static void clampMomentum(ServerPlayer player)
  {
    Vec3 movement = player.getDeltaMovement();

    double horizontalSq = movement.x * movement.x + movement.z * movement.z;
    if (horizontalSq <= WALKING_SPEED * WALKING_SPEED) {
      // Already at or below walking pace. Skip the setter so we don't mark
      // the entity dirty and resend velocity 20 times a second to someone
      // drifting gently downward.
      return;
    }

    double scale = WALKING_SPEED / Math.sqrt(horizontalSq);
    player.setDeltaMovement(movement.x * scale, movement.y, movement.z * scale);
    player.syncVelocity = true; // forces the velocity update to reach the client

    notifyRefused(player, NoFlyMessages.MOMENTUM_CUT);
  }

  /**
   * Damages a player who keeps flying inside a zone, as though being shot down.
   *
   * <p>The magnitude matches vanilla's void damage
   * ({@code LivingEntity.onBelowWorld} uses 4.0), but the cadence deliberately
   * does not. Void damage re-fires every tick and kills an unarmoured player in
   * about a quarter of a second, which would make a crossing unsurvivable
   * rather than merely frightening.
   *
   * <p>Rate is controlled by an explicit interval rather than by vanilla's
   * 10-tick invulnerability window. Relying on that window alone gave roughly
   * 8 HP/s, which played as too brutal; {@code damage_interval_ticks} makes the
   * spacing a tuning knob, and a hit spaced further apart still reads as being
   * shot at rather than as a steady drain.
   *
   * <p>{@code flyIntoWall} is used rather than {@code fellOutOfWorld} purely
   * for the death message: "experienced kinetic energy" reads plausibly for
   * being shot out of the sky, whereas "fell out of the world" is nonsense
   * 300 blocks up.
   */
  private static void applyDamage(ServerPlayer player)
  {
    if (!(player.level() instanceof ServerLevel serverLevel)) {
      return;
    }

    // The interval is the real governor; vanilla's invulnerability window
    // still applies underneath and simply never binds at these spacings.
    long now  = player.level().getGameTime();
    Long last = LAST_DAMAGE.get(player.getUUID());
    if (last != null && now - last < NoFlyConfig.get().damageIntervalTicks) {
      return;
    }
    LAST_DAMAGE.put(player.getUUID(), now);

    boolean hurt = player.hurtServer(serverLevel,
        player.damageSources().flyIntoWall(), DAMAGE_PER_HIT);

    // Only wear the elytra on ticks the player actually took damage, so the
    // two stay in step even if something else made the player invulnerable.
    if (hurt) {
      damageElytra(player);
      NoFlyParticles.flak(player);
      notifyRefused(player, NoFlyMessages.SHOT_DOWN);
    }
  }

  /**
   * Puts a little wear on the elytra each time the player is hit.
   *
   * <p>Small on purpose: a full six-second crossing costs around a dozen of an
   * elytra's 432 durability, so the wings are a real cost but not the reason
   * the crossing hurts. Breaking mid-zone would also convert the damage mode
   * into the no-glide mode by accident, which would defeat the point.
   */
  private static void damageElytra(ServerPlayer player)
  {
    ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
    if (!chest.isEmpty() && chest.has(DataComponents.GLIDER)) {
      chest.hurtAndBreak(ELYTRA_DAMAGE_PER_HIT, player, EquipmentSlot.CHEST);
    }
  }

  // ------------------------------------------------------------------ ghasts

  /**
   * Whether a happy ghast should be steered out of a zone.
   *
   * <p>Kept separate from {@link #shouldRefuse}, whose contract is documented
   * as player-only and deliberately narrow. A ghast is a vehicle rather than a
   * player, and the question asked of it is different: not "may this entity
   * fly?" but "should this entity be somewhere else?".
   *
   * <h2>The four states a ghast can be in</h2>
   * <ul>
   *   <li><b>Piloted</b> (harnessed, adult, player aboard) -- the case this
   *       exists for. Pushed out, with the pilot able to fight it briefly.</li>
   *   <li><b>Harnessed but unridden</b> -- pushed out too. It is a mount
   *       somebody parked, and leaving it hovering in the zone would let a
   *       player log out inside one and resume flying from within.</li>
   *   <li><b>Unharnessed adult</b> -- also pushed out. It cannot be ridden
   *       right now, but a harness is one right-click away, so exempting it
   *       would just move the exploit rather than close it. Being pushed also
   *       stops wild ghasts accumulating at the boundary.</li>
   *   <li><b>Baby</b> -- pushed out as well. It cannot carry anyone yet, but it
   *       grows into one that can, and exempting it would mean a no-fly zone
   *       was a fine place to raise flying mounts. Babies drive a
   *       {@code FlyingMoveControl} and a brain rather than goals, but that
   *       makes no difference here: the push writes velocity directly and
   *       never consults either.</li>
   * </ul>
   */
  public static boolean shouldRedirectGhast(HappyGhast ghast)
  {
    if (!NoFlyConfig.get().blockHappyGhast) {
      return false;
    }
    if (ghast.level().isClientSide()) {
      return false;
    }
    return NoFlyZones.isInZone(ghast);
  }

  /**
   * Steers a ghast out of a zone, and applies whatever else the mode calls for.
   *
   * <p>Called once per tick from the ghast's own tick, for a ghast already
   * known to be in a zone.
   */
  public static void applyGhastEffects(HappyGhast ghast)
  {
    NoFlyMode mode = mode();

    // NO_GLIDE keeps its character: everyone off. Isolated to this one branch
    // so it can be softened to match ZERO_MOMENTUM by deleting it.
    if (mode == NoFlyMode.NO_GLIDE) {
      if (ghast.isVehicle()) {
        notifyPassengers(ghast, NoFlyMessages.GHAST_REFUSED);
        ghast.ejectPassengers();
      }
      steerOut(ghast);
      return;
    }

    // DAMAGE leaves the pilot in control and does not steer -- the point of
    // that mode is that you may pass, at a price.
    if (mode == NoFlyMode.DAMAGE) {
      for (ServerPlayer passenger : playerPassengers(ghast)) {
        applyDamage(passenger);
      }
      return;
    }

    steerOut(ghast);
    notifyPassengers(ghast, NoFlyMessages.GHAST_REFUSED);
  }

  /**
   * Points the ghast's own flight AI at somewhere outside the zone.
   *
   * <p>Uses {@code MoveControl.setWantedPosition} rather than writing velocity,
   * so the ghast flies out under vanilla pathing -- it banks, avoids terrain and
   * generally behaves like a ghast that decided to leave. Writing
   * {@code deltaMovement} directly also works but looks like the mob is being
   * shoved, and fights the client's own prediction for anyone aboard.
   *
   * <p>{@code Ghast$RandomFloatAroundGoal.canUse()} returns false while
   * {@code hasWanted()} is true, so vanilla's wander goal steps aside for this
   * target rather than competing with it.
   *
   * <p>Aims just beyond the nearest zone face, lifted clear of terrain. The
   * target is anchored to the zone rather than to the ghast: an early version
   * placed it a fixed distance ahead of the ghast's current position, so the
   * waypoint moved with the ghast and it could never converge on anything.
   *
   * <p>Only the horizontal axes decide direction: zones are unbounded upward, so
   * "up" is never an escape, and diving out of the bottom would put the ghast in
   * the ground under the beacon. A small downward lean means a zone sheds
   * altitude as well as pushing outward.
   */
  /**
   * A unit vector pointing the shortest way out of the zone, or {@code null}.
   *
   * <p>Horizontal only: zones are unbounded upward, so there is no way out
   * through the top, and downward leads into the ground under the beacon.
   */
  public static Vec3 outwardDirection(HappyGhast ghast)
  {
    AABB zone = NoFlyZones.zoneContaining(ghast);
    if (zone == null) {
      return null;
    }

    double x         = ghast.getX();
    double z         = ghast.getZ();
    double westward  = x - zone.minX;
    double eastward  = zone.maxX - x;
    double northward = z - zone.minZ;
    double southward = zone.maxZ - z;
    double nearest   = Math.min(Math.min(westward, eastward), Math.min(northward, southward));

    if (nearest == westward) {
      return new Vec3(-1.0, 0.0, 0.0);
    }
    if (nearest == eastward) {
      return new Vec3(1.0, 0.0, 0.0);
    }
    if (nearest == northward) {
      return new Vec3(0.0, 0.0, -1.0);
    }
    return new Vec3(0.0, 0.0, 1.0);
  }

  /**
   * Whether a client-claimed vehicle position should be rejected.
   *
   * <p>Rejects only movement that takes the vehicle <em>deeper</em> into a
   * zone, measured as distance to the nearest way out. Moving outward or
   * along the boundary is always allowed, so a player who finds themselves
   * inside can always leave -- they simply cannot press further in.
   *
   * <p>Applies to any vehicle, not just ghasts: anything a player can ride
   * into a no-fly zone under client authority belongs here. In practice the
   * happy ghast is the only flying one, but the rule reads better as a general
   * statement about vehicles than as a ghast special case.
   */
  public static boolean refusesVehicleMove(Entity vehicle, Vec3 destination)
  {
    if (!(vehicle instanceof HappyGhast ghast)) {
      return false;
    }
    if (!shouldRedirectGhast(ghast) || mode() == NoFlyMode.DAMAGE) {
      return false;
    }

    AABB zone = NoFlyZones.zoneContaining(ghast);
    if (zone == null) {
      return false;
    }

    return depthInto(zone, destination.x, destination.z)
        > depthInto(zone, ghast.getX(), ghast.getZ()) + DEPTH_TOLERANCE;
  }

  /**
   * How far inside the zone a horizontal position is, in blocks.
   *
   * <p>Measured to the nearest horizontal face, so "deeper" means further from
   * the closest way out rather than closer to the beacon. Those differ in the
   * corners of the cubic zone, and the nearest-face reading is the one that
   * matches how a player would actually escape.
   */
  private static double depthInto(AABB zone, double x, double z)
  {
    return Math.min(
        Math.min(x - zone.minX, zone.maxX - x),
        Math.min(z - zone.minZ, zone.maxZ - z));
  }

  private static void steerOut(HappyGhast ghast)
  {
    AABB zone = NoFlyZones.zoneContaining(ghast);
    if (zone == null) {
      return;
    }

    double x = ghast.getX();
    double z = ghast.getZ();

    // Distance to each horizontal face; the smallest is the nearest way out.
    double westward  = x - zone.minX;
    double eastward  = zone.maxX - x;
    double northward = z - zone.minZ;
    double southward = zone.maxZ - z;

    double nearest = Math.min(Math.min(westward, eastward), Math.min(northward, southward));

    // Anchored to the zone edge, not to the ghast. Aiming a fixed distance
    // ahead of the ghast's *current* position was an early bug: the target
    // moved with the ghast, so a ghast drifting the wrong way was chased by
    // its own waypoint and never converged on anything.
    double targetX = x;
    double targetZ = z;
    if (nearest == westward) {
      targetX = zone.minX - CLEARANCE;
    }
    else if (nearest == eastward) {
      targetX = zone.maxX + CLEARANCE;
    }
    else if (nearest == northward) {
      targetZ = zone.minZ - CLEARANCE;
    }
    else {
      targetZ = zone.maxZ + CLEARANCE;
    }

    // Never aim into the ground: the reachability sweep runs from the ghast
    // to the target, so a low target drags the swept box through terrain and
    // the move control refuses to act on it. Climb over obstructions rather
    // than sitting still in front of them.
    double groundY = ghast.level().getHeightmapPos(
                                      Heightmap.Types.MOTION_BLOCKING,
                                      BlockPos.containing(targetX, ghast.getY(), targetZ))
                         .getY();
    double targetY = Math.max(ghast.getY(), groundY + MIN_FLIGHT_HEIGHT);

    // If the way there is blocked, aim higher until it is not. A ghast is a
    // flying mob with no pathfinding of its own to route around obstacles,
    // so lifting the target is the only way out of a blocked sweep.
    for (int lift = 0; lift < MAX_LIFT_STEPS && !pathIsClear(ghast, targetX, targetY, targetZ); lift++) {
      targetY += LIFT_STEP;
    }

    ghast.getMoveControl().setWantedPosition(targetX, targetY, targetZ, ESCAPE_SPEED);

    reportGhast(ghast, targetX, targetY, targetZ);
  }

  /**
   * Logs everything that decides whether a ghast actually flies out, once a
   * second per ghast while {@code debug=true}.
   *
   * <p>Every field here has been the culprit at least once: the move control
   * being vanilla's rather than ours (so the still timeout brakes it), the
   * waypoint being accepted but ignored, or the ghast being held by a pilot
   * the mod thought it had displaced.
   */
  private static void reportGhast(HappyGhast ghast, double tx, double ty, double tz)
  {
    if (!NoFlyDebug.enabled() || ghast.tickCount % 20 != 0) {
      return;
    }

    var control   = ghast.getMoveControl();
    Vec3 movement = ghast.getDeltaMovement();

    NoFlyDebug.log(
        "ghast {} at ({}, {}, {}) baby={} vehicle={} harness={} stillTimeout={} "
            + "control={} hasWanted={} wanted=({}, {}, {}) target=({}, {}, {}) "
            + "speed={} vel=({}, {}, {}) pilot={} dist={} clearPath={}",
        ghast.getId(),
        fmt(ghast.getX()), fmt(ghast.getY()), fmt(ghast.getZ()),
        ghast.isBaby(),
        ghast.isVehicle(),
        ghast.isWearingBodyArmor(),
        ghast.isOnStillTimeout(),
        control.getClass().getSimpleName(),
        control.hasWanted(),
        fmt(control.getWantedX()), fmt(control.getWantedY()), fmt(control.getWantedZ()),
        fmt(tx), fmt(ty), fmt(tz),
        fmt(control.getSpeedModifier()),
        fmt(movement.x), fmt(movement.y), fmt(movement.z),
        ghast.getControllingPassenger() == null
            ? "none"
            : ghast.getControllingPassenger().getName().getString(),
        fmt(Math.sqrt((tx - ghast.getX()) * (tx - ghast.getX())
            + (ty - ghast.getY()) * (ty - ghast.getY())
            + (tz - ghast.getZ()) * (tz - ghast.getZ()))),
        pathIsClear(ghast, tx, ty, tz));
  }

  /**
   * Approximates {@code GhastMoveControl.canReach}: is every block the ghast
   * would sweep through on its way to the target free of collision?
   *
   * <p>Diagnostic only. The move control's own check is the authority; this
   * exists so a log line can say <em>why</em> a stationary ghast is stationary,
   * rather than leaving "waypoint accepted, velocity zero" to be guessed at.
   */
  private static boolean pathIsClear(HappyGhast ghast, double tx, double ty, double tz)
  {
    AABB swept = ghast.getBoundingBox()
                     .move(tx - ghast.getX(), ty - ghast.getY(), tz - ghast.getZ())
                     .minmax(ghast.getBoundingBox())
                     .inflate(1.0);
    for (BlockPos pos : BlockPos.betweenClosed(swept)) {
      if (!ghast.level().getBlockState(pos).getCollisionShape(ghast.level(), pos).isEmpty()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Two decimal places, so a log line stays readable.
   */
  private static String fmt(double value)
  {
    return String.format(java.util.Locale.ROOT, "%.2f", value);
  }

  /**
   * Every player riding this ghast, controlling or not.
   */
  private static List<ServerPlayer> playerPassengers(HappyGhast ghast)
  {
    List<ServerPlayer> players = new ArrayList<>(ghast.getPassengers().size());
    for (Entity passenger : ghast.getPassengers()) {
      if (passenger instanceof ServerPlayer player) {
        players.add(player);
      }
    }
    return players;
  }

  /**
   * Tells everyone aboard why the ghast has stopped listening.
   *
   * <p>All passengers, not just the pilot: they are all being denied travel.
   * The pilot is in any case no longer the "controlling" passenger by the time
   * this runs -- that is exactly what dropping control means -- so the
   * passenger list is the only reliable source.
   */
  private static void notifyPassengers(HappyGhast ghast, String translationKey)
  {
    for (ServerPlayer passenger : playerPassengers(ghast)) {
      notifyRefused(passenger, translationKey);
    }
  }

  /**
   * Announces a refusal: the action-bar message and the particle puff that go
   * with being stopped, both subject to the configured cooldown.
   *
   * Enforcement fires every tick while a player is held in a zone, so without
   * the cooldown the message would be rewritten 20 times a second -- visually
   * identical, but it would suppress any other action-bar text the server
   * wants to show -- and the particles would be a packet storm.
   *
   * <p>The cooldown is shared deliberately, so the two stay in step and a
   * player sees one coherent "you were stopped" event rather than a message
   * and a puff on separate rhythms. Either can be switched off on its own
   * ({@code action_bar_messages}, {@code particles_refused}) without
   * affecting the other; the cooldown is checked first so turning the message
   * off does not silently change the particle rate.
   */
  public static void notifyRefused(ServerPlayer player, String translationKey)
  {
    NoFlyConfig config = NoFlyConfig.get();
    if (!config.actionBarMessages && !config.particlesRefused) {
      return;
    }

    long now  = player.level().getGameTime();
    Long last = LAST_MESSAGE.get(player.getUUID());
    if (last != null && now - last < config.messageCooldownTicks) {
      return;
    }
    LAST_MESSAGE.put(player.getUUID(), now);

    // Logged here rather than at each call site so every refusal is recorded
    // on the same cooldown as the message -- enforcement runs every tick, and
    // an ungated log line would flood the console.
    NoFlyDebug.log("refused {} for {} at {}",
        translationKey, player.getGameProfile().name(), player.blockPosition());

    if (config.actionBarMessages) {
      player.sendOverlayMessage(NoFlyMessages.of(player, translationKey).withStyle(ChatFormatting.RED));
    }

    // Only the non-damage modes get the quiet puff. Damage mode has its own,
    // louder visual fired from applyDamage on the damage cadence, and
    // showing both would read as two different things happening.
    if (config.particlesRefused && mode() != NoFlyMode.DAMAGE) {
      NoFlyParticles.refused(player);
    }
  }

  /**
   * Drops a player's message cooldown state when they disconnect.
   */
  public static void forget(ServerPlayer player)
  {
    LAST_MESSAGE.remove(player.getUUID());
    LAST_DAMAGE.remove(player.getUUID());
  }

  /**
   * Drops all message cooldown state.
   */
  public static void forgetAll()
  {
    LAST_MESSAGE.clear();
    LAST_DAMAGE.clear();
  }
}
