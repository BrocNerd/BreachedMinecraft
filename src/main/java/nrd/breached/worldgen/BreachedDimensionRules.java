package nrd.breached.worldgen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.ServerDynamicRegistryType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.WorldProperties;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.dimension.NetherPortal;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.WorldPreset;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.ServerWorldProperties;
import nrd.breached.Breached;

import java.util.Optional;

public final class BreachedDimensionRules {
    private static final double BORDER_CENTER = 0.0D;
    private static final int SPAWN_SEARCH_RADIUS = 96;
    private static final int SPAWN_SEARCH_STEP = 8;
    private static final PresetRules STANDARD_BREACHED_ISLAND = new PresetRules(
            BreachedPreset.STANDARD,
            RegistryKey.of(RegistryKeys.WORLD_PRESET, Identifier.of(Breached.MOD_ID, "breached_island")),
            RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS, Identifier.of(Breached.MOD_ID, "island_overworld")),
            "breached:breached_island",
            2500.0D,
            1250.0D
    );
    private static final PresetRules SMALL_BREACHED_ISLAND = new PresetRules(
            BreachedPreset.SMALL,
            RegistryKey.of(RegistryKeys.WORLD_PRESET, Identifier.of(Breached.MOD_ID, "small_breached_island")),
            RegistryKey.of(RegistryKeys.CHUNK_GENERATOR_SETTINGS, Identifier.of(Breached.MOD_ID, "small_island_overworld")),
            "breached:small_breached_island",
            1000.0D,
            500.0D
    );
    private static final PresetRules[] BREACHED_PRESETS = {
            STANDARD_BREACHED_ISLAND,
            SMALL_BREACHED_ISLAND
    };

    private BreachedDimensionRules() {
    }

    public static void register() {
        ServerWorldEvents.LOAD.register(BreachedDimensionRules::applyForBreachedIsland);
        BreachedStructurePlacementManager.register();
    }

    private static void applyForBreachedIsland(MinecraftServer server, ServerWorld world) {
        Optional<PresetRules> rules = getPresetRules(server);
        if (rules.isEmpty()) {
            return;
        }

        if (world.getRegistryKey().equals(World.OVERWORLD)) {
            applyWorldBorder(world, rules.get().overworldBorderSize(), "Overworld", rules.get());
            applyWorldSpawn(world, rules.get());
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

    private static void applyWorldSpawn(ServerWorld world, PresetRules rules) {
        BlockPos spawnPos = findSafeSpawnPos(world);
        ((ServerWorldProperties) world.getLevelProperties()).setSpawnPoint(new WorldProperties.SpawnPoint(
                GlobalPos.create(world.getRegistryKey(), spawnPos),
                0.0F,
                0.0F
        ));
        System.out.println("[Breached] Applied Overworld spawn for " + rules.presetId()
                + ": x " + spawnPos.getX()
                + ", y " + spawnPos.getY()
                + ", z " + spawnPos.getZ() + ".");
    }

    private static BlockPos findSafeSpawnPos(ServerWorld world) {
        BlockPos fallback = getSurfaceSpawnPos(world, 0, 0);
        if (isSafeSpawnPos(world, fallback)) {
            return fallback;
        }

        for (int radius = SPAWN_SEARCH_STEP; radius <= SPAWN_SEARCH_RADIUS; radius += SPAWN_SEARCH_STEP) {
            for (int x = -radius; x <= radius; x += SPAWN_SEARCH_STEP) {
                for (int z = -radius; z <= radius; z += SPAWN_SEARCH_STEP) {
                    if (Math.abs(x) != radius && Math.abs(z) != radius) {
                        continue;
                    }

                    BlockPos candidate = getSurfaceSpawnPos(world, x, z);
                    if (isSafeSpawnPos(world, candidate)) {
                        return candidate;
                    }
                }
            }
        }

        return fallback;
    }

    private static BlockPos getSurfaceSpawnPos(ServerWorld world, int x, int z) {
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    private static boolean isSafeSpawnPos(ServerWorld world, BlockPos pos) {
        BlockState below = world.getBlockState(pos.down());
        return !below.isAir()
                && !below.isOf(Blocks.WATER)
                && !below.isOf(Blocks.LAVA)
                && world.getBlockState(pos).isAir()
                && world.getBlockState(pos.up()).isAir();
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

    public static boolean isBreachedIslandWorld(MinecraftServer server) {
        return getPresetRules(server).isPresent();
    }

    public static Optional<BreachedPreset> getBreachedPreset(MinecraftServer server) {
        return getPresetRules(server).map(PresetRules::preset);
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

    public enum BreachedPreset {
        STANDARD,
        SMALL
    }

    private record PresetRules(
            BreachedPreset preset,
            RegistryKey<WorldPreset> presetKey,
            RegistryKey<ChunkGeneratorSettings> overworldSettings,
            String presetId,
            double overworldBorderSize,
            double netherBorderSize
    ) {
    }
}
