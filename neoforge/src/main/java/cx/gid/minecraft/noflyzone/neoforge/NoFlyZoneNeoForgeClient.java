package cx.gid.minecraft.noflyzone.neoforge;

import cx.gid.minecraft.noflyzone.Constants;
import cx.gid.minecraft.noflyzone.client.ClientNoFly;
import cx.gid.minecraft.noflyzone.client.ClientPayloadSender;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Client half: the No-Fly button in the beacon screen, and the HUD icon.
 *
 * Entirely optional. A player without this is subject to every no-fly zone
 * identically; they just cannot create one and see no icon.
 */
@EventBusSubscriber(modid = Constants.MOD_ID, value = Dist.CLIENT)
public final class NoFlyZoneNeoForgeClient {

    private NoFlyZoneNeoForgeClient() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // Registered in the client's own registry only; the id never crosses
            // the network. See ClientNoFlyEffect.
            ClientNoFly.register();
            ClientNoFly.installBeaconButton();

            // Explicit lambda rather than a method reference: sendToServer is
            // varargs, which makes the reference ambiguous.
            ClientPayloadSender.setSender(payload -> ClientPacketDistributor.sendToServer(payload));
        });

        Constants.LOGGER.info("{} (NeoForge client) initialized", Constants.MOD_NAME);
    }

    /** Applies or clears the local icon effect in response to a server update. */
    public static void handleInZone(boolean inZone) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            ClientNoFly.setInZone(minecraft.player, inZone);
        }
    }
}
