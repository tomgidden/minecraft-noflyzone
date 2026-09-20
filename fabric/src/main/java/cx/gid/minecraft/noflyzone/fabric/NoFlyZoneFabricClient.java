package cx.gid.minecraft.noflyzone.fabric;

import cx.gid.minecraft.noflyzone.Constants;
import cx.gid.minecraft.noflyzone.ModPayloads;
import cx.gid.minecraft.noflyzone.client.ClientNoFly;
import cx.gid.minecraft.noflyzone.client.ClientPayloadSender;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Client half: the No-Fly button in the beacon screen, and the HUD icon.
 *
 * Entirely optional. A player without this is subject to every no-fly zone
 * identically; they just cannot create one and see no icon.
 */
public class NoFlyZoneFabricClient implements ClientModInitializer {
  @Override
  public void onInitializeClient()
  {
    // Registered in the client's own registry only. Safe because the id never
    // crosses the network: the selection travels as noflyzone:set_zone (a
    // boolean) and the HUD state as noflyzone:in_zone (a boolean).
    ClientNoFly.register();
    ClientNoFly.installBeaconButton();

    ClientPayloadSender.setSender(payload -> ClientPlayNetworking.send(payload));

    // Registering this receiver is also what declares the channel, which is
    // how the server knows this client can be sent zone-state updates.
    ClientPlayNetworking.registerGlobalReceiver(ModPayloads.IN_ZONE_TYPE, (payload, context) -> context.client().execute(() -> {
      if (context.player() != null) {
        ClientNoFly.setInZone(context.player(), payload.inZone());
      }
    }));

    Constants.LOGGER.info("{} (Fabric client) initialized", Constants.MOD_NAME);
  }
}
