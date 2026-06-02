package nrd.breached.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import nrd.breached.Breached;

public record RequestTownhallRespawnPayload() implements CustomPayload {
    public static final RequestTownhallRespawnPayload INSTANCE = new RequestTownhallRespawnPayload();
    public static final Id<RequestTownhallRespawnPayload> ID = new Id<>(Identifier.of(Breached.MOD_ID, "request_townhall_respawn"));
    public static final PacketCodec<RegistryByteBuf, RequestTownhallRespawnPayload> CODEC = PacketCodec.unit(INSTANCE);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
