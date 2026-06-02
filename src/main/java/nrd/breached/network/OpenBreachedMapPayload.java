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
        List<Teammate> teammates,
        List<Landlock> landlocks,
        List<Bed> beds,
        List<DeathMarker> deathMarkers
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
        landlocks = List.copyOf(landlocks);
        beds = List.copyOf(beds);
        deathMarkers = List.copyOf(deathMarkers);
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
        buf.writeVarInt(payload.landlocks().size());
        for (Landlock landlock : payload.landlocks()) {
            buf.writeString(landlock.label());
            buf.writeInt(landlock.x());
            buf.writeInt(landlock.z());
            buf.writeInt(landlock.color());
        }
        buf.writeVarInt(payload.beds().size());
        for (Bed bed : payload.beds()) {
            buf.writeString(bed.label());
            buf.writeVarInt(bed.bedIndex());
            buf.writeInt(bed.x());
            buf.writeInt(bed.z());
            buf.writeInt(bed.color());
            buf.writeBoolean(bed.available());
            buf.writeVarInt(bed.cooldownRemainingTicks());
        }
        buf.writeVarInt(payload.deathMarkers().size());
        for (DeathMarker deathMarker : payload.deathMarkers()) {
            buf.writeString(deathMarker.label());
            buf.writeInt(deathMarker.x());
            buf.writeInt(deathMarker.z());
            buf.writeInt(deathMarker.color());
            buf.writeVarInt(deathMarker.remainingTicks());
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

        int landlockCount = buf.readVarInt();
        List<Landlock> landlocks = new ArrayList<>(landlockCount);
        for (int index = 0; index < landlockCount; index++) {
            landlocks.add(new Landlock(
                    buf.readString(64),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt()
            ));
        }

        int bedCount = buf.readVarInt();
        List<Bed> beds = new ArrayList<>(bedCount);
        for (int index = 0; index < bedCount; index++) {
            beds.add(new Bed(
                    buf.readString(64),
                    buf.readVarInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readBoolean(),
                    buf.readVarInt()
            ));
        }

        int deathMarkerCount = buf.readVarInt();
        List<DeathMarker> deathMarkers = new ArrayList<>(deathMarkerCount);
        for (int index = 0; index < deathMarkerCount; index++) {
            deathMarkers.add(new DeathMarker(
                    buf.readString(64),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readVarInt()
            ));
        }

        return new OpenBreachedMapPayload(borderSize, playerX, playerZ, terrainResolution, terrainColors, markers, teammates, landlocks, beds, deathMarkers);
    }

    public record Marker(String label, int x, int z, int color) {
    }

    public record Teammate(String name, int x, int z, int color) {
    }

    public record Landlock(String label, int x, int z, int color) {
    }

    public record Bed(String label, int bedIndex, int x, int z, int color, boolean available, int cooldownRemainingTicks) {
    }

    public record DeathMarker(String label, int x, int z, int color, int remainingTicks) {
    }
}
