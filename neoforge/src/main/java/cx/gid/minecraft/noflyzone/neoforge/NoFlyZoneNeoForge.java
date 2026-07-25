package cx.gid.minecraft.noflyzone.neoforge;

import cx.gid.minecraft.noflyzone.Constants;
import cx.gid.minecraft.noflyzone.ModPayloads;
import cx.gid.minecraft.noflyzone.NoFlyCommand;
import cx.gid.minecraft.noflyzone.NoFlyPolicy;
import cx.gid.minecraft.noflyzone.NoFlyZone;
import cx.gid.minecraft.noflyzone.SetZoneHandler;
import cx.gid.minecraft.noflyzone.ZoneStateNotifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(Constants.MOD_ID)
public class NoFlyZoneNeoForge {

    public NoFlyZoneNeoForge(IEventBus modEventBus) {
        modEventBus.addListener(this::registerPayloads);

        NoFlyZone.init();

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);

        Constants.LOGGER.info("{} (NeoForge) initialized", Constants.MOD_NAME);
    }

    /**
     * Registers both payload types and the server-side handler.
     *
     * {@code optional()} matters: without it NeoForge would refuse connections
     * from clients lacking these channels, which is precisely the unmodded
     * players this mod is designed to still affect.
     *
     * <p>Note nothing is registered into any game registry -- no
     * {@code MobEffect}, nothing. That is what keeps unmodded clients able to
     * connect; see {@link cx.gid.minecraft.noflyzone.NoFlyBeacon}.
     */
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(Constants.MOD_ID).optional();

        registrar.playToServer(
            ModPayloads.SET_ZONE_TYPE,
            ModPayloads.SetZonePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    SetZoneHandler.handle(player, payload.enabled());
                }
            }));

        registrar.playToClient(
            ModPayloads.IN_ZONE_TYPE,
            ModPayloads.InZonePayload.CODEC,
            (payload, context) -> context.enqueueWork(() -> {
                // Guarded so the client-only class is never loaded on a
                // dedicated server, where it would fail to link.
                if (FMLEnvironment.getDist().isClient()) {
                    NoFlyZoneNeoForgeClient.handleInZone(payload.inZone());
                }
            }));

        ZoneStateNotifier.setSender((player, inZone) -> {
            if (player.connection.hasChannel(ModPayloads.IN_ZONE_TYPE)) {
                PacketDistributor.sendToPlayer(player, new ModPayloads.InZonePayload(inZone));
            }
        });
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        NoFlyCommand.register(event.getDispatcher());
    }

    private void onServerStarting(ServerStartingEvent event) {
        NoFlyZone.onServerStarting(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        NoFlyZone.onServerStopping(event.getServer());
    }

    /** Drives the HUD icon; edge-triggered inside the notifier. */
    private void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            ZoneStateNotifier.update(player);
        }
    }

    private void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ZoneStateNotifier.forget(player);
            NoFlyPolicy.forget(player);
        }
    }
}
