package nrd.breached.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import nrd.breached.Breached;

import java.util.ArrayList;
import java.util.List;

public record ReinforcementOutlinePayload(List<Entry> entries) implements CustomPayload {
    public static final Id<ReinforcementOutlinePayload> ID = new Id<>(Identifier.of(Breached.MOD_ID, "reinforcement_outline"));
    public static final PacketCodec<RegistryByteBuf, ReinforcementOutlinePayload> CODEC = PacketCodec.ofStatic(
            ReinforcementOutlinePayload::write,
            ReinforcementOutlinePayload::read
    );

    public ReinforcementOutlinePayload {
        entries = List.copyOf(entries);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static ReinforcementOutlinePayload empty() {
        return new ReinforcementOutlinePayload(List.of());
    }

    private static void write(RegistryByteBuf buf, ReinforcementOutlinePayload payload) {
        buf.writeVarInt(payload.entries().size());
        for (Entry entry : payload.entries()) {
            buf.writeBlockPos(entry.pos());
            buf.writeVarInt(entry.tierLevel());
        }
    }

    private static ReinforcementOutlinePayload read(RegistryByteBuf buf) {
        int entryCount = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(entryCount);
        for (int index = 0; index < entryCount; index++) {
            entries.add(new Entry(buf.readBlockPos(), buf.readVarInt()));
        }

        return new ReinforcementOutlinePayload(entries);
    }

    public record Entry(BlockPos pos, int tierLevel) {
    }
}
