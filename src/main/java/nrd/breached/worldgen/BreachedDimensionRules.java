package nrd.breached.worldgen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class BreachedDimensionRules {
    private static final double BORDER_CENTER = 0.0D;
    private static final int PORTAL_STRUCTURE_X_OFFSET = 0;
    private static final int PORTAL_STRUCTURE_Z_OFFSET = 0;
    private static final int PORTAL_ACTIVE_CHECK_RADIUS = 16;
    private static final Set<PendingPortalPlacement> PENDING_PORTAL_PLACEMENTS = new HashSet<>();
    private static final PresetRules STANDARD_BREACHED_ISLAND = new PresetRules(
            RegistryKey.of(RegistryKeys.WORLD_PRESET, Identifier.of(Breached.MOD_ID, "breached_island")),
            RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS, Identifier.of(Breached.MOD_ID, "island_overworld")),
            "breached:breached_island",
            2500.0D,
            1250.0D,
            450,
            1000
    );
    private static final PresetRules SMALL_BREACHED_ISLAND = new PresetRules(
            RegistryKey.of(RegistryKeys.WORLD_PRESET, Identifier.of(Breached.MOD_ID, "small_breached_island")),
            RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS, Identifier.of(Breached.MOD_ID, "small_island_overworld")),
            "breached:small_breached_island",
            1000.0D,
            500.0D,
            200,
            250
    );
    private static final PresetRules[] BREACHED_PRESETS = {
            STANDARD_BREACHED_ISLAND,
            SMALL_BREACHED_ISLAND
    };

    private BreachedDimensionRules() {
    }

    public static void register() {
        ServerWorldEvents.LOAD.register(BreachedDimensionRules::applyForBreachedIsland);
        ServerChunkEvents.CHUNK_LOAD.register(BreachedDimensionRules::enqueueOfficialPortalStructures);
        ServerTickEvents.END_WORLD_TICK.register(BreachedDimensionRules::placePendingOfficialPortalStructures);
        registerOfficialPortalProtectionEvents();
        CentralSpawnPoiManager.register();
    }

    private static void applyForBreachedIsland(MinecraftServer server, ServerWorld world) {
        Optional<PresetRules> rules = getPresetRules(server);
        if (rules.isEmpty()) {
            return;
        }

        if (world.getRegistryKey().equals(World.OVERWORLD)) {
            applyWorldBorder(world, rules.get().overworldBorderSize(), "Overworld", rules.get());
            logOfficialOverworldPortalCoordinates(world, rules.get());
        } else if (world.getRegistryKey().equals(World.NETHER)) {
            applyWorldBorder(world, rules.get().netherBorderSize(), "Nether", rules.get());
        }
    }

    private static void applyWorldBorder(ServerWorld world, double size, String dimensionName, PresetRules rules) {
        WorldBorder border = world.getWorldBorder();
        border.setCenter(BORDER_CENTER, BORDER_CENTER);
        border.setSize(size);

        System.out.println("[Breached] Applied " + dimensionName + " world border for " + rules.presetId() + ": center 0,0 size " + (int) size + ".");
    }

    private static void logOfficialOverworldPortalCoordinates(ServerWorld world, PresetRules rules) {
        PortalCoordinate[] coordinates = generateOfficialOverworldPortalCoordinates(world.getSeed(), rules);

        for (int i = 0; i < coordinates.length; i++) {
            PortalCoordinate coordinate = coordinates[i];
            System.out.println("[Breached] Official Overworld Nether portal " + (i + 1) + " for " + rules.presetId() + ": x "
                    + coordinate.x() + ", z " + coordinate.z() + ".");
        }
    }

    private static void enqueueOfficialPortalStructures(ServerWorld world, WorldChunk chunk) {
        if (!world.getRegistryKey().equals(World.OVERWORLD)) {
            return;
        }

        Optional<PresetRules> rules = getPresetRules(world.getServer());
        if (rules.isEmpty()) {
            return;
        }

        PortalCoordinate[] coordinates = generateOfficialOverworldPortalCoordinates(world.getSeed(), rules.get());
        ChunkPos chunkPos = chunk.getPos();

        for (int i = 0; i < coordinates.length; i++) {
            PortalCoordinate coordinate = coordinates[i];
            if (chunkPos.x == Math.floorDiv(coordinate.x(), 16) && chunkPos.z == Math.floorDiv(coordinate.z(), 16)) {
                PENDING_PORTAL_PLACEMENTS.add(new PendingPortalPlacement(world.getSeed(), rules.get().presetId(), i + 1, coordinate));
            }
        }
    }

    private static void placePendingOfficialPortalStructures(ServerWorld world) {
        if (!world.getRegistryKey().equals(World.OVERWORLD) || PENDING_PORTAL_PLACEMENTS.isEmpty()) {
            return;
        }

        Optional<PresetRules> rules = getPresetRules(world.getServer());
        if (rules.isEmpty()) {
            return;
        }

        PortalCoordinate[] coordinates = generateOfficialOverworldPortalCoordinates(world.getSeed(), rules.get());
        for (int i = 0; i < coordinates.length; i++) {
            PendingPortalPlacement placement = new PendingPortalPlacement(world.getSeed(), rules.get().presetId(), i + 1, coordinates[i]);
            if (PENDING_PORTAL_PLACEMENTS.remove(placement)) {
                placeOfficialPortalStructure(world, placement.coordinate(), placement.portalNumber(), rules.get());
            }
        }
    }

    private static void placeOfficialPortalStructure(ServerWorld world, PortalCoordinate coordinate, int portalNumber, PresetRules rules) {
        if (hasActivePortal(world, coordinate)) {
            return;
        }

        int x = coordinate.x() + PORTAL_STRUCTURE_X_OFFSET;
        int z = coordinate.z() + PORTAL_STRUCTURE_Z_OFFSET;
        BreachedStructureDefinition definition = BreachedStructureSpawnManager.OFFICIAL_NETHER_PORTAL.withRadius(
                rules.portalMinRadius(),
                rules.portalMaxRadius()
        );
        Optional<BreachedStructurePlacement> placement = BreachedStructureSpawnManager.placeRadiusCandidate(
                world,
                definition,
                new BreachedStructureSpawnManager.RadiusCandidate(
                        portalNumber,
                        x,
                        z,
                        coordinate.radius(),
                        coordinate.angle()
                )
        );

        placement.ifPresent(structurePlacement -> System.out.println("[Breached] Registered official Overworld Nether portal structure "
                + portalNumber
                + " for " + rules.presetId()
                + " at x " + x
                + ", y " + structurePlacement.origin().getY()
                + ", z " + z + "."));
    }

    private static boolean hasActivePortal(ServerWorld world, PortalCoordinate coordinate) {
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, coordinate.x(), coordinate.z());
        int minY = Math.max(world.getBottomY(), topY - 32);
        int maxY = Math.min(world.getTopYInclusive(), topY + 32);

        for (int xOffset = -PORTAL_ACTIVE_CHECK_RADIUS; xOffset <= PORTAL_ACTIVE_CHECK_RADIUS; xOffset++) {
            for (int zOffset = -PORTAL_ACTIVE_CHECK_RADIUS; zOffset <= PORTAL_ACTIVE_CHECK_RADIUS; zOffset++) {
                for (int y = minY; y <= maxY; y++) {
                    if (world.getBlockState(new BlockPos(coordinate.x() + xOffset, y, coordinate.z() + zOffset)).isOf(Blocks.NETHER_PORTAL)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static boolean shouldBlockNetherPortalCreation(World world, BlockPos pos) {
        return world instanceof ServerWorld serverWorld
                && getPresetRules(serverWorld.getServer()).isPresent()
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
                || getPresetRules(serverWorld.getServer()).isEmpty()) {
            return false;
        }

        PresetRules rules = getPresetRules(serverWorld.getServer()).get();
        BreachedStructureDefinition definition = BreachedStructureSpawnManager.OFFICIAL_NETHER_PORTAL.withRadius(
                rules.portalMinRadius(),
                rules.portalMaxRadius()
        );
        for (PortalCoordinate coordinate : generateOfficialOverworldPortalCoordinates(serverWorld.getSeed(), rules)) {
            if (BreachedStructureSpawnManager.isInsideProtectionRadius(definition, coordinate.x(), coordinate.z(), pos)) {
                return true;
            }
        }

        return false;
    }

    private static PortalCoordinate[] generateOfficialOverworldPortalCoordinates(long worldSeed, PresetRules rules) {
        BreachedStructureDefinition definition = BreachedStructureSpawnManager.OFFICIAL_NETHER_PORTAL.withRadius(
                rules.portalMinRadius(),
                rules.portalMaxRadius()
        );
        List<BreachedStructureSpawnManager.RadiusCandidate> candidates = BreachedStructureSpawnManager.generateRadiusCandidates(worldSeed, definition);

        return candidates.stream()
                .map(candidate -> new PortalCoordinate(candidate.x(), candidate.z(), candidate.radius(), candidate.angle()))
                .toArray(PortalCoordinate[]::new);
    }

    public static boolean isBreachedIslandWorld(MinecraftServer server) {
        return getPresetRules(server).isPresent();
    }

    private static Optional<PresetRules> getPresetRules(MinecraftServer server) {
        Registry<DimensionOptions> dimensions = server.getCombinedDynamicRegistries()
                .get(ServerDynamicRegistryType.DIMENSIONS)
                .getOrThrow(RegistryKeys.DIMENSION);
        DimensionOptionsRegistryHolder holder = new DimensionOptionsRegistryHolder(dimensions);
        Optional<RegistryKey<WorldPreset>> preset = WorldPresets.getWorldPreset(holder);

        if (preset.isPresent()) {
            for (PresetRules rules : BREACHED_PRESETS) {
                if (rules.presetKey().equals(preset.get())) {
                    return Optional.of(rules);
                }
            }
        }

        for (PresetRules rules : BREACHED_PRESETS) {
            if (hasBreachedIslandOverworldSettings(dimensions, rules)) {
                return Optional.of(rules);
            }
        }

        return Optional.empty();
    }

    private static boolean hasBreachedIslandOverworldSettings(Registry<DimensionOptions> dimensions, PresetRules rules) {
        return dimensions.getOptionalValue(DimensionOptions.OVERWORLD)
                .map(DimensionOptions::chunkGenerator)
                .filter(NoiseChunkGenerator.class::isInstance)
                .map(NoiseChunkGenerator.class::cast)
                .filter(generator -> generator.matchesSettings(rules.overworldSettings()))
                .isPresent();
    }

    private record PresetRules(
            RegistryKey<WorldPreset> presetKey,
            RegistryKey<ChunkGeneratorSettings> overworldSettings,
            String presetId,
            double overworldBorderSize,
            double netherBorderSize,
            int portalMinRadius,
            int portalMaxRadius
    ) {
    }

    private record PortalCoordinate(int x, int z, int radius, double angle) {
    }

    private record PendingPortalPlacement(long worldSeed, String presetId, int portalNumber, PortalCoordinate coordinate) {
    }
}
