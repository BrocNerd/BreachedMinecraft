package nrd.breached.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import nrd.breached.Breached;

public record SelectRespawnBedPayload(int bedIndex) implements CustomPayload {
    public static final Id<SelectRespawnBedPayload> ID = new Id<>(Identifier.of(Breached.MOD_ID, "select_respawn_bed"));
    public static final PacketCodec<RegistryByteBuf, SelectRespawnBedPayload> CODEC = PacketCodec.ofStatic(
            SelectRespawnBedPayload::write,
            SelectRespawnBedPayload::read
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    private static void write(RegistryByteBuf buf, SelectRespawnBedPayload payload) {
        buf.writeVarInt(payload.bedIndex());
    }

    private static SelectRespawnBedPayload read(RegistryByteBuf buf) {
        return new SelectRespawnBedPayload(buf.readVarInt());
    }
}
