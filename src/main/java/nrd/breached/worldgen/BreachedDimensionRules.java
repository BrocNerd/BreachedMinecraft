package nrd.breached.worldgen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.ServerDynamicRegistryType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.dimension.NetherPortal;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.WorldPresets;
import nrd.breached.Breached;

import java.util.HashSet;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

public final class BreachedDimensionRules {
    private static final RegistryKey<WorldPreset> BREACHED_ISLAND_PRESET = RegistryKey.of(
            RegistryKeys.WORLD_PRESET,
            Identifier.of(Breached.MOD_ID, "breached_island")
    );
    private static final RegistryKey<ChunkGeneratorSettings> BREACHED_ISLAND_OVERWORLD_SETTINGS = RegistryKey.of(
            RegistryKeys.CHUNK_GENERATOR_SETTINGS,
            Identifier.of(Breached.MOD_ID, "island_overworld")
    );
    private static final double BORDER_CENTER = 0.0D;
    private static final double OVERWORLD_BORDER_SIZE = 2500.0D;
    private static final double NETHER_BORDER_SIZE = 1250.0D;
    private static final long OFFICIAL_PORTAL_SEED_SALT = 0x42D5A3B91F0C7E66L;
    private static final int PORTAL_MIN_RADIUS = 450;
    private static final int PORTAL_MAX_RADIUS = 1000;
    private static final int PORTAL_PLATFORM_RADIUS = 4;
    private static final int PORTAL_PROTECTION_RADIUS = 24;
    private static final int PORTAL_PROTECTION_RADIUS_SQUARED = PORTAL_PROTECTION_RADIUS * PORTAL_PROTECTION_RADIUS;
    private static final int BLOCK_UPDATE_FLAGS = 2;
    private static final Set<PendingPortalPlacement> PENDING_PORTAL_PLACEMENTS = new HashSet<>();

    private BreachedDimensionRules() {
    }

    public static void register() {
        ServerWorldEvents.LOAD.register(BreachedDimensionRules::applyForBreachedIsland);
        ServerChunkEvents.CHUNK_LOAD.register(BreachedDimensionRules::enqueueOfficialPortalStructures);
        ServerTickEvents.END_WORLD_TICK.register(BreachedDimensionRules::placePendingOfficialPortalStructures);
        registerOfficialPortalProtectionEvents();
    }

    private static void applyForBreachedIsland(MinecraftServer server, ServerWorld world) {
        if (!isBreachedIslandWorld(server)) {
            return;
        }

        if (world.getRegistryKey().equals(World.OVERWORLD)) {
            applyWorldBorder(world, OVERWORLD_BORDER_SIZE, "Overworld");
            logOfficialOverworldPortalCoordinates(world);
        } else if (world.getRegistryKey().equals(World.NETHER)) {
            applyWorldBorder(world, NETHER_BORDER_SIZE, "Nether");
        }
    }

    private static void applyWorldBorder(ServerWorld world, double size, String dimensionName) {
        WorldBorder border = world.getWorldBorder();
        border.setCenter(BORDER_CENTER, BORDER_CENTER);
        border.setSize(size);

        System.out.println("[Breached] Applied " + dimensionName + " world border for breached:breached_island: center 0,0 size " + (int) size + ".");
    }

    private static void logOfficialOverworldPortalCoordinates(ServerWorld world) {
        PortalCoordinate[] coordinates = generateOfficialOverworldPortalCoordinates(world.getSeed());

        for (int i = 0; i < coordinates.length; i++) {
            PortalCoordinate coordinate = coordinates[i];
            System.out.println("[Breached] Official Overworld Nether portal " + (i + 1) + " for breached:breached_island: x "
                    + coordinate.x() + ", z " + coordinate.z() + ".");
        }
    }

    private static void enqueueOfficialPortalStructures(ServerWorld world, WorldChunk chunk) {
        if (!world.getRegistryKey().equals(World.OVERWORLD)) {
            return;
        }

        PortalCoordinate[] coordinates = generateOfficialOverworldPortalCoordinates(world.getSeed());
        ChunkPos chunkPos = chunk.getPos();

        for (int i = 0; i < coordinates.length; i++) {
            PortalCoordinate coordinate = coordinates[i];
            if (chunkPos.x == Math.floorDiv(coordinate.x(), 16) && chunkPos.z == Math.floorDiv(coordinate.z(), 16)) {
                PENDING_PORTAL_PLACEMENTS.add(new PendingPortalPlacement(world.getSeed(), i + 1, coordinate));
            }
        }
    }

    private static void placePendingOfficialPortalStructures(ServerWorld world) {
        if (!world.getRegistryKey().equals(World.OVERWORLD) || PENDING_PORTAL_PLACEMENTS.isEmpty()) {
            return;
        }

        PortalCoordinate[] coordinates = generateOfficialOverworldPortalCoordinates(world.getSeed());
        for (int i = 0; i < coordinates.length; i++) {
            PendingPortalPlacement placement = new PendingPortalPlacement(world.getSeed(), i + 1, coordinates[i]);
            if (PENDING_PORTAL_PLACEMENTS.remove(placement)) {
                placeOfficialPortalStructure(world, placement.coordinate(), placement.portalNumber());
            }
        }
    }

    private static void placeOfficialPortalStructure(ServerWorld world, PortalCoordinate coordinate, int portalNumber) {
        if (!isBreachedIslandWorld(world.getServer()) || hasActivePortal(world, coordinate)) {
            return;
        }

        Integer frameBaseY = findPortalFrameBaseY(world, coordinate);
        if (frameBaseY == null) {
            int platformY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, coordinate.x(), coordinate.z());
            placePortalPlatform(world, coordinate, platformY);
            frameBaseY = platformY + 1;
            placePortalFrame(world, coordinate, frameBaseY);

            System.out.println("[Breached] Placed official Overworld Nether portal structure " + portalNumber
                    + " for breached:breached_island at x " + coordinate.x() + ", y " + platformY + ", z " + coordinate.z() + ".");
        }

        placePortalBlocks(world, coordinate, frameBaseY);
    }

    private static boolean hasActivePortal(ServerWorld world, PortalCoordinate coordinate) {
        Integer frameBaseY = findPortalFrameBaseY(world, coordinate);
        if (frameBaseY == null) {
            return false;
        }

        for (int yOffset = 1; yOffset <= 3; yOffset++) {
            if (!world.getBlockState(new BlockPos(coordinate.x(), frameBaseY + yOffset, coordinate.z())).isOf(Blocks.NETHER_PORTAL)
                    || !world.getBlockState(new BlockPos(coordinate.x() + 1, frameBaseY + yOffset, coordinate.z())).isOf(Blocks.NETHER_PORTAL)) {
                return false;
            }
        }

        return true;
    }

    private static Integer findPortalFrameBaseY(ServerWorld world, PortalCoordinate coordinate) {
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, coordinate.x(), coordinate.z());
        int minY = Math.max(world.getBottomY(), topY - 16);

        for (int y = topY + 1; y >= minY; y--) {
            if (isPortalFrameBase(world, coordinate, y)) {
                return y;
            }
        }

        return null;
    }

    private static boolean isPortalFrameBase(ServerWorld world, PortalCoordinate coordinate, int baseY) {
        for (int xOffset = -1; xOffset <= 2; xOffset++) {
            if (!world.getBlockState(new BlockPos(coordinate.x() + xOffset, baseY, coordinate.z())).isOf(Blocks.OBSIDIAN)
                    || !world.getBlockState(new BlockPos(coordinate.x() + xOffset, baseY + 4, coordinate.z())).isOf(Blocks.OBSIDIAN)) {
                return false;
            }
        }

        for (int yOffset = 1; yOffset <= 3; yOffset++) {
            if (!world.getBlockState(new BlockPos(coordinate.x() - 1, baseY + yOffset, coordinate.z())).isOf(Blocks.OBSIDIAN)
                    || !world.getBlockState(new BlockPos(coordinate.x() + 2, baseY + yOffset, coordinate.z())).isOf(Blocks.OBSIDIAN)) {
                return false;
            }
        }

        return true;
    }

    private static void placePortalPlatform(ServerWorld world, PortalCoordinate coordinate, int y) {
        for (int xOffset = -PORTAL_PLATFORM_RADIUS; xOffset <= PORTAL_PLATFORM_RADIUS; xOffset++) {
            for (int zOffset = -PORTAL_PLATFORM_RADIUS; zOffset <= PORTAL_PLATFORM_RADIUS; zOffset++) {
                world.setBlockState(
                        new BlockPos(coordinate.x() + xOffset, y, coordinate.z() + zOffset),
                        Blocks.STONE_BRICKS.getDefaultState(),
                        BLOCK_UPDATE_FLAGS
                );
            }
        }
    }

    private static void placePortalFrame(ServerWorld world, PortalCoordinate coordinate, int baseY) {
        for (int xOffset = -1; xOffset <= 2; xOffset++) {
            world.setBlockState(new BlockPos(coordinate.x() + xOffset, baseY, coordinate.z()), Blocks.OBSIDIAN.getDefaultState(), BLOCK_UPDATE_FLAGS);
            world.setBlockState(new BlockPos(coordinate.x() + xOffset, baseY + 4, coordinate.z()), Blocks.OBSIDIAN.getDefaultState(), BLOCK_UPDATE_FLAGS);
        }

        for (int yOffset = 1; yOffset <= 3; yOffset++) {
            world.setBlockState(new BlockPos(coordinate.x() - 1, baseY + yOffset, coordinate.z()), Blocks.OBSIDIAN.getDefaultState(), BLOCK_UPDATE_FLAGS);
            world.setBlockState(new BlockPos(coordinate.x() + 2, baseY + yOffset, coordinate.z()), Blocks.OBSIDIAN.getDefaultState(), BLOCK_UPDATE_FLAGS);
            world.setBlockState(new BlockPos(coordinate.x(), baseY + yOffset, coordinate.z()), Blocks.AIR.getDefaultState(), BLOCK_UPDATE_FLAGS);
            world.setBlockState(new BlockPos(coordinate.x() + 1, baseY + yOffset, coordinate.z()), Blocks.AIR.getDefaultState(), BLOCK_UPDATE_FLAGS);
        }
    }

    private static void placePortalBlocks(ServerWorld world, PortalCoordinate coordinate, int frameBaseY) {
        for (int yOffset = 1; yOffset <= 3; yOffset++) {
            world.setBlockState(
                    new BlockPos(coordinate.x(), frameBaseY + yOffset, coordinate.z()),
                    Blocks.NETHER_PORTAL.getDefaultState().with(NetherPortalBlock.AXIS, Direction.Axis.X),
                    BLOCK_UPDATE_FLAGS
            );
            world.setBlockState(
                    new BlockPos(coordinate.x() + 1, frameBaseY + yOffset, coordinate.z()),
                    Blocks.NETHER_PORTAL.getDefaultState().with(NetherPortalBlock.AXIS, Direction.Axis.X),
                    BLOCK_UPDATE_FLAGS
            );
        }
    }

    public static boolean shouldBlockNetherPortalCreation(World world, BlockPos pos) {
        return world instanceof ServerWorld serverWorld
                && isBreachedIslandWorld(serverWorld.getServer())
                && isOverworldOrNether(world)
                && (NetherPortal.getNewPortal(world, pos, Direction.Axis.X).isPresent()
                || NetherPortal.getNewPortal(world, pos, Direction.Axis.Z).isPresent());
    }

    private static boolean isOverworldOrNether(World world) {
        return world.getRegistryKey().equals(World.OVERWORLD) || world.getRegistryKey().equals(World.NETHER);
    }

    private static void registerOfficialPortalProtectionEvents() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!shouldProtectOfficialPortalSite(world, player, pos)) {
                return true;
            }

            player.sendMessage(Text.literal("Official breach sites cannot be modified."), false);
            return false;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient() || canBypassOfficialPortalProtection(player) || !(player.getStackInHand(hand).getItem() instanceof BlockItem)) {
                return ActionResult.PASS;
            }

            ItemPlacementContext placementContext = new ItemPlacementContext(player, hand, player.getStackInHand(hand), hitResult);
            if (!placementContext.canPlace() || !isInsideOfficialPortalProtectionRadius(world, placementContext.getBlockPos())) {
                return ActionResult.PASS;
            }

            player.sendMessage(Text.literal("Official breach sites cannot be modified."), false);
            return ActionResult.FAIL;
        });
    }

    private static boolean shouldProtectOfficialPortalSite(World world, PlayerEntity player, BlockPos pos) {
        return !world.isClient()
                && !canBypassOfficialPortalProtection(player)
                && isInsideOfficialPortalProtectionRadius(world, pos);
    }

    private static boolean canBypassOfficialPortalProtection(PlayerEntity player) {
        return player.isCreative() || player.isCreativeLevelTwoOp();
    }

    private static boolean isInsideOfficialPortalProtectionRadius(World world, BlockPos pos) {
        if (!(world instanceof ServerWorld serverWorld)
                || !world.getRegistryKey().equals(World.OVERWORLD)
                || !isBreachedIslandWorld(serverWorld.getServer())) {
            return false;
        }

        for (PortalCoordinate coordinate : generateOfficialOverworldPortalCoordinates(serverWorld.getSeed())) {
            long xDistance = pos.getX() - coordinate.x();
            long zDistance = pos.getZ() - coordinate.z();
            if (xDistance * xDistance + zDistance * zDistance <= PORTAL_PROTECTION_RADIUS_SQUARED) {
                return true;
            }
        }

        return false;
    }

    private static PortalCoordinate[] generateOfficialOverworldPortalCoordinates(long worldSeed) {
        Random random = new Random(worldSeed ^ OFFICIAL_PORTAL_SEED_SALT);
        double firstAngle = random.nextDouble() * Math.TAU;
        double secondAngle = firstAngle + Math.PI + ((random.nextDouble() - 0.5D) * 0.7D);

        return new PortalCoordinate[] {
                generatePortalCoordinate(random, firstAngle),
                generatePortalCoordinate(random, secondAngle)
        };
    }

    private static PortalCoordinate generatePortalCoordinate(Random random, double angle) {
        int radius = PORTAL_MIN_RADIUS + random.nextInt(PORTAL_MAX_RADIUS - PORTAL_MIN_RADIUS + 1);
        int x = toChunkCenterCoordinate(Math.cos(angle) * radius);
        int z = toChunkCenterCoordinate(Math.sin(angle) * radius);
        return new PortalCoordinate(x, z);
    }

    private static int toChunkCenterCoordinate(double coordinate) {
        return Math.floorDiv((int) Math.round(coordinate), 16) * 16 + 8;
    }

    public static boolean isBreachedIslandWorld(MinecraftServer server) {
        Registry<DimensionOptions> dimensions = server.getCombinedDynamicRegistries()
                .get(ServerDynamicRegistryType.DIMENSIONS)
                .getOrThrow(RegistryKeys.DIMENSION);
        DimensionOptionsRegistryHolder holder = new DimensionOptionsRegistryHolder(dimensions);
        Optional<RegistryKey<WorldPreset>> preset = WorldPresets.getWorldPreset(holder);
        return preset.filter(BREACHED_ISLAND_PRESET::equals).isPresent()
                || hasBreachedIslandOverworldSettings(dimensions);
    }

    private static boolean hasBreachedIslandOverworldSettings(Registry<DimensionOptions> dimensions) {
        return dimensions.getOptionalValue(DimensionOptions.OVERWORLD)
                .map(DimensionOptions::chunkGenerator)
                .filter(NoiseChunkGenerator.class::isInstance)
                .map(NoiseChunkGenerator.class::cast)
                .filter(generator -> generator.matchesSettings(BREACHED_ISLAND_OVERWORLD_SETTINGS))
                .isPresent();
    }

    private record PortalCoordinate(int x, int z) {
    }

    private record PendingPortalPlacement(long worldSeed, int portalNumber, PortalCoordinate coordinate) {
    }
}
