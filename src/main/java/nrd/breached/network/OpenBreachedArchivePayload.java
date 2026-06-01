package nrd.breached.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import nrd.breached.Breached;

public record OpenBreachedArchivePayload() implements CustomPayload {
    public static final OpenBreachedArchivePayload INSTANCE = new OpenBreachedArchivePayload();
    public static final Id<OpenBreachedArchivePayload> ID = new Id<>(Identifier.of(Breached.MOD_ID, "open_breached_archive"));
    public static final PacketCodec<RegistryByteBuf, OpenBreachedArchivePayload> CODEC = PacketCodec.unit(INSTANCE);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
