package nrd.breached.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import nrd.breached.Breached;

import java.util.ArrayList;
import java.util.List;

public record LandlockClaimOutlinePayload(List<Entry> entries) implements CustomPayload {
    public static final Id<LandlockClaimOutlinePayload> ID = new Id<>(Identifier.of(Breached.MOD_ID, "landlock_claim_outline"));
    public static final PacketCodec<RegistryByteBuf, LandlockClaimOutlinePayload> CODEC = PacketCodec.ofStatic(
            LandlockClaimOutlinePayload::write,
            LandlockClaimOutlinePayload::read
    );

    public LandlockClaimOutlinePayload {
        entries = List.copyOf(entries);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    public static LandlockClaimOutlinePayload empty() {
        return new LandlockClaimOutlinePayload(List.of());
    }

    private static void write(RegistryByteBuf buf, LandlockClaimOutlinePayload payload) {
        buf.writeVarInt(payload.entries().size());
        for (Entry entry : payload.entries()) {
            buf.writeBlockPos(entry.claimCenter());
            buf.writeBoolean(entry.authorized());
            buf.writeBoolean(entry.lockdown());
            buf.writeBoolean(entry.decayed());
        }
    }

    private static LandlockClaimOutlinePayload read(RegistryByteBuf buf) {
        int entryCount = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(entryCount);
        for (int index = 0; index < entryCount; index++) {
            entries.add(new Entry(buf.readBlockPos(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
        }

        return new LandlockClaimOutlinePayload(entries);
    }

    public record Entry(BlockPos claimCenter, boolean authorized, boolean lockdown, boolean decayed) {
    }
}
