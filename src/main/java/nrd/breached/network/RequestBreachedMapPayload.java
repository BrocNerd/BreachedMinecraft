package nrd.breached.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import nrd.breached.Breached;

public record RequestBreachedMapPayload() implements CustomPayload {
    public static final RequestBreachedMapPayload INSTANCE = new RequestBreachedMapPayload();
    public static final Id<RequestBreachedMapPayload> ID = new Id<>(Identifier.of(Breached.MOD_ID, "request_breached_map"));
    public static final PacketCodec<RegistryByteBuf, RequestBreachedMapPayload> CODEC = PacketCodec.unit(INSTANCE);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
