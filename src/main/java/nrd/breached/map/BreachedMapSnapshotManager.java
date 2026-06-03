package nrd.breached.map;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

public final class BreachedMapSnapshotManager {
    private static final int TERRAIN_RESOLUTION = 512;
    private static final int CHUNKS_GENERATED_PER_TICK = 2;
    private static final int ACTIVE_GENERATION_TIMEOUT_TICKS = 20 * 60 * 5;
    private static final int UNKNOWN_TERRAIN_COLOR = 0xFF214057;
    private static final int OCEAN_TERRAIN_COLOR = 0xFF1E5F83;
    private static final Map<MinecraftServer, CachedTerrainSnapshot> CACHED_TERRAIN_SNAPSHOTS = new WeakHashMap<>();

    private BreachedMapSnapshotManager() {
    }

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(BreachedMapSnapshotManager::tickTerrainGeneration);
    }

    public static TerrainSnapshot getTerrainSnapshot(ServerWorld world, int borderSize) {
        MinecraftServer server = world.getServer();
        long worldTime = world.getTime();
        CachedTerrainSnapshot snapshot = CACHED_TERRAIN_SNAPSHOTS.get(server);
        if (snapshot == null || snapshot.borderSize() != borderSize) {
            snapshot = new CachedTerrainSnapshot(borderSize, worldTime);
            CACHED_TERRAIN_SNAPSHOTS.put(server, snapshot);
        }

        snapshot.markRequested(worldTime);
        if (!snapshot.complete()) {
            snapshot.sampleLoadedChunks(world);
        }
        return snapshot.toPayloadSnapshot();
    }

    private static void tickTerrainGeneration(ServerWorld world) {
        if (!world.getRegistryKey().equals(World.OVERWORLD)) {
            return;
        }

        CachedTerrainSnapshot snapshot = CACHED_TERRAIN_SNAPSHOTS.get(world.getServer());
        if (snapshot == null || snapshot.complete() || world.getTime() - snapshot.lastRequestedTime() > ACTIVE_GENERATION_TIMEOUT_TICKS) {
            return;
        }

        for (int index = 0; index < CHUNKS_GENERATED_PER_TICK && !snapshot.complete(); index++) {
            snapshot.sampleNextGeneratedChunk(world);
        }
    }

    private static int sampleTerrainColor(ServerWorld world, int worldX, int worldZ) {
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ);
        if (topY <= world.getBottomY()) {
            return UNKNOWN_TERRAIN_COLOR;
        }

        BlockPos pos = new BlockPos(worldX, topY - 1, worldZ);
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return UNKNOWN_TERRAIN_COLOR;
        }
        if (state.isOf(Blocks.WATER) || state.isOf(Blocks.KELP) || state.isOf(Blocks.SEAGRASS) || state.isOf(Blocks.TALL_SEAGRASS)) {
            return shadeColor(OCEAN_TERRAIN_COLOR, Math.min(0, topY - 62) * 2);
        }

        MapColor mapColor = state.getMapColor(world, pos);
        if (mapColor == MapColor.CLEAR) {
            return UNKNOWN_TERRAIN_COLOR;
        }

        int baseColor = 0xFF000000 | mapColor.color;
        return shadeColor(baseColor, Math.max(-28, Math.min(34, (topY - 64) / 2)));
    }

    private static int shadeColor(int color, int shade) {
        int red = clamp(((color >> 16) & 0xFF) + shade, 0, 255);
        int green = clamp(((color >> 8) & 0xFF) + shade, 0, 255);
        int blue = clamp((color & 0xFF) + shade, 0, 255);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static void writeRgb565(byte[] colors, int colorIndex, int argbColor) {
        int red = (argbColor >> 16) & 0xFF;
        int green = (argbColor >> 8) & 0xFF;
        int blue = argbColor & 0xFF;
        int rgb565 = (red >> 3) << 11 | (green >> 2) << 5 | blue >> 3;
        int byteIndex = colorIndex * 2;
        colors[byteIndex] = (byte) (rgb565 >> 8);
        colors[byteIndex + 1] = (byte) rgb565;
    }

    private static int getFallbackTerrainColor(int pixelX, int pixelZ) {
        double normalizedX = (pixelX + 0.5D) / TERRAIN_RESOLUTION * 2.0D - 1.0D;
        double normalizedZ = (pixelZ + 0.5D) / TERRAIN_RESOLUTION * 2.0D - 1.0D;
        double distance = Math.sqrt(normalizedX * normalizedX + normalizedZ * normalizedZ);
        int variation = Math.floorMod(pixelX * 17 + pixelZ * 31, 15) - 7;

        if (distance > 0.92D) {
            return shadeColor(OCEAN_TERRAIN_COLOR, -18 + variation);
        }
        if (distance > 0.78D) {
            return shadeColor(0xFFD8C56D, variation);
        }
        if (distance > 0.60D) {
            return shadeColor(0xFF567D3D, variation);
        }

        return shadeColor(0xFF4D8B4A, variation);
    }

    public record TerrainSnapshot(int resolution, byte[] colors) {
        public TerrainSnapshot {
            colors = colors.clone();
        }
    }

    private static final class CachedTerrainSnapshot {
        private final int borderSize;
        private final int halfBorder;
        private final int chunkMin;
        private final int chunkMax;
        private final byte[] colors = new byte[TERRAIN_RESOLUTION * TERRAIN_RESOLUTION * 2];
        private final boolean[] sampledPixels = new boolean[TERRAIN_RESOLUTION * TERRAIN_RESOLUTION];
        private int nextChunkX;
        private int nextChunkZ;
        private boolean complete;
        private long lastRequestedTime;

        private CachedTerrainSnapshot(int borderSize, long createdTime) {
            this.borderSize = Math.max(1, borderSize);
            this.halfBorder = this.borderSize / 2;
            this.chunkMin = Math.floorDiv(-halfBorder, 16);
            this.chunkMax = Math.floorDiv(halfBorder - 1, 16);
            this.nextChunkX = chunkMin;
            this.nextChunkZ = chunkMin;
            this.lastRequestedTime = createdTime;
            Arrays.fill(colors, (byte) 0);
            for (int pixelZ = 0; pixelZ < TERRAIN_RESOLUTION; pixelZ++) {
                for (int pixelX = 0; pixelX < TERRAIN_RESOLUTION; pixelX++) {
                    writeRgb565(colors, pixelZ * TERRAIN_RESOLUTION + pixelX, getFallbackTerrainColor(pixelX, pixelZ));
                }
            }
        }

        private int borderSize() {
            return borderSize;
        }

        private boolean complete() {
            return complete;
        }

        private long lastRequestedTime() {
            return lastRequestedTime;
        }

        private void markRequested(long worldTime) {
            lastRequestedTime = worldTime;
        }

        private TerrainSnapshot toPayloadSnapshot() {
            return new TerrainSnapshot(TERRAIN_RESOLUTION, colors);
        }

        private void sampleLoadedChunks(ServerWorld world) {
            for (int chunkZ = chunkMin; chunkZ <= chunkMax; chunkZ++) {
                for (int chunkX = chunkMin; chunkX <= chunkMax; chunkX++) {
                    if (world.isChunkLoaded(chunkX, chunkZ)) {
                        sampleChunkPixels(world, chunkX, chunkZ);
                    }
                }
            }
        }

        private void sampleNextGeneratedChunk(ServerWorld world) {
            if (complete) {
                return;
            }

            world.getChunk(nextChunkX, nextChunkZ);
            sampleChunkPixels(world, nextChunkX, nextChunkZ);
            advanceChunkCursor();
        }

        private void advanceChunkCursor() {
            if (nextChunkX < chunkMax) {
                nextChunkX++;
                return;
            }

            nextChunkX = chunkMin;
            if (nextChunkZ < chunkMax) {
                nextChunkZ++;
                return;
            }

            complete = true;
        }

        private void sampleChunkPixels(ServerWorld world, int chunkX, int chunkZ) {
            int pixelXStart = clamp(pixelStart(chunkX * 16) - 1, 0, TERRAIN_RESOLUTION - 1);
            int pixelXEnd = clamp(pixelEnd(chunkX * 16 + 15) + 1, 0, TERRAIN_RESOLUTION - 1);
            int pixelZStart = clamp(pixelStart(chunkZ * 16) - 1, 0, TERRAIN_RESOLUTION - 1);
            int pixelZEnd = clamp(pixelEnd(chunkZ * 16 + 15) + 1, 0, TERRAIN_RESOLUTION - 1);
            if (pixelXStart > pixelXEnd || pixelZStart > pixelZEnd) {
                return;
            }

            double blocksPerPixel = borderSize / (double) TERRAIN_RESOLUTION;
            for (int pixelZ = pixelZStart; pixelZ <= pixelZEnd; pixelZ++) {
                int worldZ = (int) Math.floor(-halfBorder + (pixelZ + 0.5D) * blocksPerPixel);
                for (int pixelX = pixelXStart; pixelX <= pixelXEnd; pixelX++) {
                    int colorIndex = pixelZ * TERRAIN_RESOLUTION + pixelX;
                    if (sampledPixels[colorIndex]) {
                        continue;
                    }

                    int worldX = (int) Math.floor(-halfBorder + (pixelX + 0.5D) * blocksPerPixel);
                    if (Math.floorDiv(worldX, 16) != chunkX || Math.floorDiv(worldZ, 16) != chunkZ) {
                        continue;
                    }

                    writeRgb565(colors, colorIndex, sampleTerrainColor(world, worldX, worldZ));
                    sampledPixels[colorIndex] = true;
                }
            }
        }

        private int pixelStart(int worldMin) {
            double blocksPerPixel = borderSize / (double) TERRAIN_RESOLUTION;
            return clamp((int) Math.ceil((worldMin + halfBorder) / blocksPerPixel - 0.5D), 0, TERRAIN_RESOLUTION - 1);
        }

        private int pixelEnd(int worldMax) {
            double blocksPerPixel = borderSize / (double) TERRAIN_RESOLUTION;
            return clamp((int) Math.floor((worldMax + halfBorder) / blocksPerPixel - 0.5D), 0, TERRAIN_RESOLUTION - 1);
        }
    }
}
