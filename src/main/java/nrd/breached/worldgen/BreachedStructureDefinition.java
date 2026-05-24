package nrd.breached.worldgen;

import net.minecraft.util.Identifier;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.block.Block;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

public record BreachedStructureDefinition(
        String logName,
        Identifier structureId,
        RegistryKey<World> requiredDimension,
        int countPerWorld,
        PlacementMode placementMode,
        int centerX,
        int centerZ,
        int minRadius,
        int maxRadius,
        boolean roughlyOpposed,
        long seedSalt,
        int protectionRadius,
        int spacingFromOtherBreachedStructures,
        boolean avoidTrees,
        boolean avoidWater,
        boolean avoidSteepSlopes,
        boolean avoidBuriedPlacement,
        boolean allowUnderground,
        boolean needsFlatGround,
        boolean canClearBlocks,
        boolean protectedStructure,
        boolean waitForPlayers,
        int priority,
        TerrainValidation terrainValidation,
        HeightSelection heightSelection,
        int sampleStep,
        BlockMirror mirror,
        BlockRotation rotation,
        SurfaceRequirement surfaceRequirement,
        SupportMode supportMode,
        Block supportBlock,
        Block supportMarkerBlock,
        int supportMaxDepth
) {
    public enum TerrainValidation {
        LENIENT,
        MEDIUM,
        STRICT
    }

    public enum HeightSelection {
        ORIGIN_SURFACE,
        MEDIAN_SURFACE
    }

    public enum PlacementMode {
        CENTER_RADIUS,
        RING_RADIUS,
        DISTRIBUTED_RING,
        RANDOM_WITHIN_BORDER,
        CAVE
    }

    public enum SurfaceRequirement {
        ALLOW_WATER,
        AVOID_WATER,
        REQUIRE_WATER,
        MOSTLY_WATER
    }

    public enum SupportMode {
        NONE,
        WATER_SOLID_FOOTPRINT,
        WATER_MARKER_PILLARS
    }

    public int protectionRadiusSquared() {
        return protectionRadius * protectionRadius;
    }

    public BreachedStructureDefinition withRadius(int minRadius, int maxRadius) {
        return new BreachedStructureDefinition(
                logName,
                structureId,
                requiredDimension,
                countPerWorld,
                placementMode,
                centerX,
                centerZ,
                minRadius,
                maxRadius,
                roughlyOpposed,
                seedSalt,
                protectionRadius,
                spacingFromOtherBreachedStructures,
                avoidTrees,
                avoidWater,
                avoidSteepSlopes,
                avoidBuriedPlacement,
                allowUnderground,
                needsFlatGround,
                canClearBlocks,
                protectedStructure,
                waitForPlayers,
                priority,
                terrainValidation,
                heightSelection,
                sampleStep,
                mirror,
                rotation,
                surfaceRequirement,
                supportMode,
                supportBlock,
                supportMarkerBlock,
                supportMaxDepth
        );
    }
}
