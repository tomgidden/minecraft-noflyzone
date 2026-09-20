package cx.gid.minecraft.noflyzone.fabric;

import cx.gid.minecraft.noflyzone.Constants;
import cx.gid.minecraft.noflyzone.ModPayloads;
import cx.gid.minecraft.noflyzone.NoFlyCommand;
import cx.gid.minecraft.noflyzone.NoFlyPolicy;
import cx.gid.minecraft.noflyzone.NoFlyZone;
import cx.gid.minecraft.noflyzone.SetZoneHandler;
import cx.gid.minecraft.noflyzone.ZoneStateNotifier;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class NoFlyZoneFabric implements ModInitializer {
  @Override
  public void onInitialize()
  {
    NoFlyZone.init();

    registerPayloads();
    registerLifecycleEvents();

    Constants.LOGGER.info("{} (Fabric) initialized", Constants.MOD_NAME);
  }

  /**
   * Registers both payload types and the server-side handler.
   *
   * Note nothing is registered into any game registry: no {@code MobEffect}, no
   * blocks, nothing. That is what keeps unmodded clients able to connect --
   * Fabric marks a registry {@code MODDED} the moment a non-vanilla entry
   * appears, which forces a login-time sync that disconnects clients missing
   * the entry.
   */
  private void registerPayloads()
  {
    PayloadTypeRegistry.serverboundPlay()
        .register(ModPayloads.SET_ZONE_TYPE, ModPayloads.SetZonePayload.CODEC);
    PayloadTypeRegistry.clientboundPlay()
        .register(ModPayloads.IN_ZONE_TYPE, ModPayloads.InZonePayload.CODEC);

    ServerPlayNetworking.registerGlobalReceiver(ModPayloads.SET_ZONE_TYPE, (payload, context) -> context.server().execute(() -> SetZoneHandler.handle(context.player(), payload.enabled())));

    // Only reaches clients that declared the channel, so a vanilla client is
    // never sent anything it cannot decode.
    ZoneStateNotifier.setSender((player, inZone) -> {
      if (ServerPlayNetworking.canSend(player, ModPayloads.IN_ZONE_TYPE)) {
        ServerPlayNetworking.send(player, new ModPayloads.InZonePayload(inZone));
      }
    });
  }

  private void registerLifecycleEvents()
  {
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, registryAccess, environment) -> NoFlyCommand.register(dispatcher));

    ServerLifecycleEvents.SERVER_STARTING.register(NoFlyZone::onServerStarting);
    ServerLifecycleEvents.SERVER_STOPPING.register(NoFlyZone::onServerStopping);

    // Drives the HUD icon. Edge-triggered inside the notifier, so this is a
    // cheap position test per player per tick and nothing more.
    ServerTickEvents.END_SERVER_TICK.register(server -> {
      for (var player : server.getPlayerList().getPlayers()) {
        ZoneStateNotifier.update(player);
      }
    });

    ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
      ZoneStateNotifier.forget(handler.getPlayer());
      NoFlyPolicy.forget(handler.getPlayer());
    });
  }
}
