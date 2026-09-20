package cx.gid.minecraft.noflyzone;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;

/**
 * {@code /noflyzone} -- operator control of no-fly beacons without a client mod.
 *
 * <h2>Why this exists</h2>
 * Selecting No-Fly in the beacon screen requires the client mod, because the
 * server cannot add a button to a screen it does not draw (see
 * {@link cx.gid.minecraft.noflyzone.mixin.client.BeaconEffectListMixin}). That
 * would otherwise mean at least one player on the server must run a modded
 * client just to create a zone.
 *
 * <p>This command removes that requirement. It performs exactly the state change
 * {@link SetZoneHandler} performs, but reached through operator permission
 * rather than through a beacon menu -- so a wholly unmodded playerbase can still
 * build and manage no-fly zones.
 *
 * <h2>Differences from the client-mod route</h2>
 * No payment is consumed. An operator is already trusted with {@code /give}, so
 * charging an ingot would be ceremony rather than a constraint. The tier-4
 * requirement <em>is</em> still enforced, because it is a game-balance rule
 * about what a beacon can do rather than an anti-cheat measure -- and because a
 * zone silently failing to appear on an under-built pyramid would be confusing.
 */
public final class NoFlyCommand {

    /**
     * Who may run this command.
     *
     * <p>26.x replaced integer permission levels with named permissions, so this
     * is the same check {@code /gamemode} and {@code /give} declare rather than a
     * hand-rolled level comparison -- which also means a server using a
     * permissions manager can grant it independently of op status.
     */
    private static final PermissionCheck PERMISSION_CHECK =
        new PermissionCheck.Require(Permissions.COMMANDS_GAMEMASTER);

    /** How far to look for a beacon when no radius is given. */
    private static final int DEFAULT_SEARCH_RADIUS = 16;

    /** Upper bound on the search radius, to keep the block-entity scan cheap. */
    private static final int MAX_SEARCH_RADIUS = 64;

    private NoFlyCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("noflyzone")
                .requires(source -> PERMISSION_CHECK.check(source.permissions()))
                .then(radiusAware("on", true))
                .then(radiusAware("off", false))
                .then(radiusAware("status", null))
                .then(modeSubcommand())
                .then(reloadSubcommand())
        );
    }

    /**
     * {@code /noflyzone mode [<mode>]} -- reports or sets the server-wide
     * enforcement mode.
     *
     * <p>Server-wide rather than per-beacon, deliberately: the mode is a
     * statement about how this server treats flight, not a property of any one
     * beacon.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> modeSubcommand() {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("mode")
            .executes(ctx -> reportMode(ctx.getSource()));

        // A literal per mode rather than a string argument, so tab-completion
        // offers the valid values and a typo is rejected by Brigadier itself.
        for (NoFlyMode mode : NoFlyMode.values()) {
            node = node.then(Commands.literal(mode.configName())
                .executes(ctx -> setMode(ctx.getSource(), mode)));
        }
        return node;
    }

    /**
     * {@code /noflyzone reload} -- re-reads the config file from disk.
     *
     * <p>Settings are otherwise read at startup only, which makes tuning
     * anything -- particle counts especially -- a restart per attempt. This
     * makes the file the live source of truth on demand.
     *
     * <p>Reports the mode afterwards, because that is the setting most likely
     * to have been changed by hand and the one with the most visible
     * consequences; a silent success would leave the operator wondering
     * whether the file was actually picked up.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> reloadSubcommand() {
        return Commands.literal("reload")
            .executes(ctx -> reload(ctx.getSource()));
    }

    private static int reload(CommandSourceStack source) {
        NoFlyConfig reloaded = NoFlyConfig.reload();

        source.sendSuccess(() -> msg(source, "noflyzone.command.reloaded",
            reloaded.mode.configName()), true);

        NoFlyDebug.log("config reloaded by {}", source.getTextName());
        return 1;
    }

    /**
     * A message in the recipient's own language.
     *
     * <p>Command output can go to a player, the console, or a command block. Only
     * the first has a language, so the rest fall back to English -- which is what
     * a server log wants anyway.
     */
    private static Component msg(CommandSourceStack source, String key, Object... args) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return NoFlyMessages.of(player, key, args);
        }
        return NoFlyMessages.ofDefault(key, args);
    }

    private static int reportMode(CommandSourceStack source) {
        NoFlyMode mode = NoFlyConfig.get().mode;
        source.sendSuccess(() -> msg(source, "noflyzone.command.mode_is",
            mode.configName(),
            msg(source, mode.descriptionKey())), false);
        return 1;
    }

    private static int setMode(CommandSourceStack source, NoFlyMode mode) {
        if (NoFlyConfig.get().mode == mode) {
            source.sendFailure(msg(source, "noflyzone.command.mode_unchanged",
                mode.configName()));
            return 0;
        }

        boolean persisted = NoFlyConfig.setMode(mode);

        source.sendSuccess(() -> msg(source, "noflyzone.command.mode_set",
            mode.configName(),
            msg(source, mode.descriptionKey())), true);

        // The mode is live either way; say so plainly if it will not survive a
        // restart rather than letting the operator find out later.
        if (!persisted) {
            source.sendFailure(msg(source, "noflyzone.command.mode_not_saved"));
        }

        NoFlyDebug.log("mode set to {} by {}", mode.configName(), source.getTextName());
        return 1;
    }

    /**
     * Builds a subcommand in both its bare and explicit-radius forms.
     *
     * @param enabled the flag to set, or {@code null} for a read-only query
     */
    private static LiteralArgumentBuilder<CommandSourceStack> radiusAware(String name, Boolean enabled) {
        return Commands.literal(name)
            .executes(ctx -> run(ctx.getSource(), DEFAULT_SEARCH_RADIUS, enabled))
            .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_SEARCH_RADIUS))
                .executes(ctx -> run(ctx.getSource(),
                    IntegerArgumentType.getInteger(ctx, "radius"), enabled)));
    }

    /**
     * Finds the nearest beacon and applies (or reports) the no-fly flag.
     *
     * @param enabled the flag to set, or {@code null} to only report
     * @return 1 on success, 0 on any failure, per Brigadier convention
     */
    private static int run(CommandSourceStack source, int radius, Boolean enabled) {
        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());

        BlockPos found = findNearestBeacon(level, origin, radius);
        if (found == null) {
            source.sendFailure(msg(source, "noflyzone.command.no_beacon", radius));
            return 0;
        }

        if (!(level.getBlockEntity(found) instanceof BeaconBlockEntity beacon)) {
            // Cannot normally happen -- findNearestBeacon only returns positions
            // it has already resolved -- but the world could change underneath us.
            source.sendFailure(msg(source, "noflyzone.command.no_beacon", radius));
            return 0;
        }

        if (enabled == null) {
            boolean on = ZoneMutation.isNoFly(beacon);
            source.sendSuccess(() -> msg(source,
                on ? "noflyzone.command.status_on" : "noflyzone.command.status_off",
                found.getX(), found.getY(), found.getZ()), false);
            return 1;
        }

        // Eligibility and the mutation itself belong to ZoneMutation, shared with
        // the client-mod route. This command's own contribution is the permission
        // check in register() and turning the outcome into a message.
        ZoneMutation.Result result = ZoneMutation.apply(level, found, enabled);

        switch (result) {
            case OK -> {
                NoFlyDebug.log("beacon at {} set no-fly={} by command from {}",
                    found, enabled, source.getTextName());
                source.sendSuccess(() -> msg(source,
                    enabled ? "noflyzone.command.enabled" : "noflyzone.command.disabled",
                    found.getX(), found.getY(), found.getZ()), true);
                return 1;
            }
            case INSUFFICIENT_TIER -> source.sendFailure(msg(source, "noflyzone.command.needs_tier",
                ZoneMutation.requiredLevels(), ZoneMutation.levelsOf(beacon)));
            case UNCHANGED -> source.sendFailure(msg(source,
                enabled ? "noflyzone.command.already_on" : "noflyzone.command.already_off",
                found.getX(), found.getY(), found.getZ()));
            case NOT_A_BEACON -> source.sendFailure(
                msg(source, "noflyzone.command.no_beacon", radius));
        }

        return 0;
    }

    /**
     * The nearest beacon block entity within {@code radius} blocks of
     * {@code origin}, or {@code null}.
     *
     * <p>Scans the block entities of the chunks the search cube touches, rather
     * than every block position in it: a radius-16 cube is ~35k positions but
     * typically a handful of block entities. Only loaded chunks are considered,
     * which is not a practical limitation given the caller is standing there.
     *
     * <p>Distance is measured to the beacon block itself, squared, to avoid
     * needless square roots.
     */
    private static BlockPos findNearestBeacon(ServerLevel level, BlockPos origin, int radius) {
        BlockPos best = null;
        double bestDistanceSq = Double.MAX_VALUE;

        int minChunkX = (origin.getX() - radius) >> 4;
        int maxChunkX = (origin.getX() + radius) >> 4;
        int minChunkZ = (origin.getZ() - radius) >> 4;
        int maxChunkZ = (origin.getZ() + radius) >> 4;
        double radiusSq = (double) radius * radius;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                var chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }

                for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                    if (!(level.getBlockEntity(pos) instanceof BeaconBlockEntity)) {
                        continue;
                    }
                    double distanceSq = pos.distSqr(origin);
                    if (distanceSq <= radiusSq && distanceSq < bestDistanceSq) {
                        bestDistanceSq = distanceSq;
                        best = pos.immutable();
                    }
                }
            }
        }

        return best;
    }
}
