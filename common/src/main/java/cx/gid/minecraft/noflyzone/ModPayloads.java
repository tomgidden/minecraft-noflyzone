package cx.gid.minecraft.noflyzone;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The mod's custom network payloads.
 *
 * <h2>Why the mod has its own protocol at all</h2>
 * The No-Fly option cannot be a real {@code MobEffect} -- see {@link NoFlyBeacon}
 * -- so it cannot travel through vanilla's {@code ServerboundSetBeaconPacket},
 * which carries effects as bare registry ids. A modded client picking it would
 * send an id the server cannot resolve, and the connection would drop with a
 * decoder exception. Hence {@link SetZonePayload}.
 *
 * <p>{@link InZonePayload} exists purely for the HUD icon: the server has no
 * effect to apply, so it instead tells modded clients when they enter or leave a
 * zone and lets them apply their own locally-registered effect.
 *
 * <p>Both are optional in the loader sense. A client that declares neither is a
 * vanilla client: it can't configure a beacon and won't see an icon, but it is
 * subject to every zone exactly like anyone else.
 */
public final class ModPayloads {

    private ModPayloads() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Constants.MOD_ID, path);
    }

    // ---------------------------------------------------------------- serverbound

    /** Channel a modded client uses to set or clear a beacon's no-fly flag. */
    public static final CustomPacketPayload.Type<SetZonePayload> SET_ZONE_TYPE =
        new CustomPacketPayload.Type<>(id("set_zone"));

    /**
     * "Make the beacon I currently have open a no-fly beacon" (or stop being one).
     *
     * Deliberately carries no position. The client screen has no way to know the
     * beacon's coordinates -- {@code ContainerLevelAccess} is server-side only --
     * and a client-supplied position would be untrustworthy anyway. The server
     * resolves the beacon from the player's own open menu instead, which it must
     * validate regardless since vanilla's checks are bypassed entirely.
     */
    public record SetZonePayload(boolean enabled) implements CustomPacketPayload {

        public static final StreamCodec<RegistryFriendlyByteBuf, SetZonePayload> CODEC =
            StreamCodec.composite(
                ByteBufCodecs.BOOL, SetZonePayload::enabled,
                SetZonePayload::new
            );

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return SET_ZONE_TYPE;
        }
    }

    // ---------------------------------------------------------------- clientbound

    /** Channel the server uses to tell a modded client it is in (or out of) a zone. */
    public static final CustomPacketPayload.Type<InZonePayload> IN_ZONE_TYPE =
        new CustomPacketPayload.Type<>(id("in_zone"));

    /**
     * "You are now inside / no longer inside a no-fly zone."
     *
     * Cosmetic only -- it drives the HUD icon. Enforcement never consults it, and
     * a client that ignores or never receives it is still fully grounded.
     */
    public record InZonePayload(boolean inZone) implements CustomPacketPayload {

        public static final StreamCodec<RegistryFriendlyByteBuf, InZonePayload> CODEC =
            StreamCodec.composite(
                ByteBufCodecs.BOOL, InZonePayload::inZone,
                InZonePayload::new
            );

        @Override
        public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
            return IN_ZONE_TYPE;
        }
    }
}
