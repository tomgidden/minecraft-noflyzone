package cx.gid.minecraft.noflyzone.client;

import cx.gid.minecraft.noflyzone.NoFlyDebug;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.function.Consumer;

/**
 * Sends a payload to the server from the client.
 *
 * A one-method seam, because Fabric and NeoForge have incompatible client send
 * APIs and there is no common one without pulling in a cross-platform layer.
 * Each loader's client entrypoint installs its own implementation.
 */
public final class ClientPayloadSender {

    private static volatile Consumer<CustomPacketPayload> sender;

    private ClientPayloadSender() {}

    /** Installs the loader-specific send function. Called from each client entrypoint. */
    public static void setSender(Consumer<CustomPacketPayload> value) {
        sender = value;
    }

    /** Sends a payload, or does nothing if no sender is installed. */
    public static void send(CustomPacketPayload payload) {
        Consumer<CustomPacketPayload> local = sender;
        if (local == null) {
            NoFlyDebug.warn("no payload sender installed; {} not sent", payload.type().id());
            return;
        }
        try {
            local.accept(payload);
        } catch (Exception e) {
            NoFlyDebug.warn("failed to send {}: {}", payload.type().id(), e.toString());
        }
    }
}
