package nrd.breached.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import nrd.breached.Breached;

import java.util.ArrayList;
import java.util.List;

public record OpenBreachedMapPayload(
        int borderSize,
        int playerX,
        int playerZ,
        int terrainResolution,
        byte[] terrainColors,
        List<Marker> markers,
        List<Teammate> teammates
) implements CustomPayload {
    public static final Id<OpenBreachedMapPayload> ID = new Id<>(Identifier.of(Breached.MOD_ID, "open_breached_map"));
    public static final PacketCodec<RegistryByteBuf, OpenBreachedMapPayload> CODEC = PacketCodec.ofStatic(
            OpenBreachedMapPayload::write,
            OpenBreachedMapPayload::read
    );

    public OpenBreachedMapPayload {
        borderSize = Math.max(1, borderSize);
        terrainResolution = Math.max(0, terrainResolution);
        terrainColors = terrainColors.clone();
        markers = List.copyOf(markers);
        teammates = List.copyOf(teammates);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }

    private static void write(RegistryByteBuf buf, OpenBreachedMapPayload payload) {
        buf.writeVarInt(payload.borderSize());
        buf.writeInt(payload.playerX());
        buf.writeInt(payload.playerZ());
        buf.writeVarInt(payload.terrainResolution());
        buf.writeByteArray(payload.terrainColors());
        buf.writeVarInt(payload.markers().size());
        for (Marker marker : payload.markers()) {
            buf.writeString(marker.label());
            buf.writeInt(marker.x());
            buf.writeInt(marker.z());
            buf.writeInt(marker.color());
        }
        buf.writeVarInt(payload.teammates().size());
        for (Teammate teammate : payload.teammates()) {
            buf.writeString(teammate.name());
            buf.writeInt(teammate.x());
            buf.writeInt(teammate.z());
            buf.writeInt(teammate.color());
        }
    }

    private static OpenBreachedMapPayload read(RegistryByteBuf buf) {
        int borderSize = buf.readVarInt();
        int playerX = buf.readInt();
        int playerZ = buf.readInt();
        int terrainResolution = buf.readVarInt();
        byte[] terrainColors = buf.readByteArray(1024 * 1024);
        int markerCount = buf.readVarInt();
        List<Marker> markers = new ArrayList<>(markerCount);
        for (int index = 0; index < markerCount; index++) {
            markers.add(new Marker(
                    buf.readString(64),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt()
            ));
        }

        int teammateCount = buf.readVarInt();
        List<Teammate> teammates = new ArrayList<>(teammateCount);
        for (int index = 0; index < teammateCount; index++) {
            teammates.add(new Teammate(
                    buf.readString(64),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt()
            ));
        }

        return new OpenBreachedMapPayload(borderSize, playerX, playerZ, terrainResolution, terrainColors, markers, teammates);
    }

    public record Marker(String label, int x, int z, int color) {
    }

    public record Teammate(String name, int x, int z, int color) {
    }
}
