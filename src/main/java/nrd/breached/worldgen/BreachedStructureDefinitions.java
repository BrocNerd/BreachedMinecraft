package nrd.breached.worldgen;

import net.minecraft.block.Blocks;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import nrd.breached.Breached;

import java.util.List;
import java.util.Optional;

public final class BreachedStructureDefinitions {
    public static final BreachedStructureDefinition SWORD_STATUE = new BreachedStructureDefinition(
            "swordstatue.nbt",
            Identifier.of(Breached.MOD_ID, "swordstatue"),
            World.OVERWORLD,
            1,
            24,
            BreachedStructureDefinition.PlacementMode.CENTER_RADIUS,
            0,
            0,
            0,
            0,
            0,
            0,
            1200,
            false,
            0x7C31B0E5A91D4F22L,
            72,
            500,
            200,
            true,
            true,
            true,
            true,
            false,
            true,
            false,
            true,
            false,
            20,
            BreachedStructureDefinition.SpawnImportance.REQUIRED,
            BreachedStructureDefinition.SpacingGroup.MAJOR,
            BreachedStructureDefinition.SpacingPolicy.MAJOR_ONLY,
            0,
            0,
            BreachedStructureDefinition.AirPlacementMode.IGNORE_AIR,
            BreachedStructureDefinition.TerrainValidation.LENIENT,
            BreachedStructureDefinition.HeightSelection.MEDIAN_SURFACE,
            16,
            BlockMirror.NONE,
            BlockRotation.NONE,
            BreachedStructureDefinition.SurfaceRequirement.MOSTLY_WATER,
            BreachedStructureDefinition.SupportMode.WATER_MARKER_PILLARS,
            Blocks.DEEPSLATE_BRICKS,
            Blocks.REINFORCED_DEEPSLATE,
            96,
            BreachedStructureDefinition.PrePlacementCheck.NONE
    );

    public static final BreachedStructureDefinition OFFICIAL_NETHER_PORTAL = new BreachedStructureDefinition(
            "portal.nbt",
            Identifier.of(Breached.MOD_ID, "portal"),
            World.OVERWORLD,
            2,
            24,
            BreachedStructureDefinition.PlacementMode.DISTRIBUTED_RING,
            0,
            0,
            0,
            0,
            0,
            450,
            1000,
            true,
            0x42D5A3B91F0C7E66L,
            24,
            700,
            300,
            true,
            false,
            true,
            true,
            false,
            false,
            false,
            true,
            false,
            30,
            BreachedStructureDefinition.SpawnImportance.REQUIRED,
            BreachedStructureDefinition.SpacingGroup.MAJOR,
            BreachedStructureDefinition.SpacingPolicy.MAJOR_ONLY,
            24,
            12,
            BreachedStructureDefinition.AirPlacementMode.IGNORE_AIR,
            BreachedStructureDefinition.TerrainValidation.LENIENT,
            BreachedStructureDefinition.HeightSelection.ORIGIN_SURFACE,
            16,
            BlockMirror.NONE,
            BlockRotation.NONE,
            BreachedStructureDefinition.SurfaceRequirement.ALLOW_WATER,
            BreachedStructureDefinition.SupportMode.NONE,
            Blocks.AIR,
            Blocks.AIR,
            0,
            BreachedStructureDefinition.PrePlacementCheck.NO_ACTIVE_NETHER_PORTAL_NEARBY
    );

    public static final BreachedStructureDefinition HORACE = new BreachedStructureDefinition(
            "horace.nbt",
            Identifier.of(Breached.MOD_ID, "horace"),
            World.OVERWORLD,
            1,
            24,
            BreachedStructureDefinition.PlacementMode.CENTER_RADIUS,
            0,
            0,
            0,
            0,
            0,
            300,
            900,
            false,
            0x19E6C4D8A73B52F1L,
            0,
            400,
            150,
            true,
            true,
            true,
            true,
            false,
            true,
            false,
            false,
            false,
            10,
            BreachedStructureDefinition.SpawnImportance.REQUIRED,
            BreachedStructureDefinition.SpacingGroup.MAJOR,
            BreachedStructureDefinition.SpacingPolicy.MAJOR_ONLY,
            16,
            8,
            BreachedStructureDefinition.AirPlacementMode.IGNORE_AIR,
            BreachedStructureDefinition.TerrainValidation.LENIENT,
            BreachedStructureDefinition.HeightSelection.MEDIAN_SURFACE,
            4,
            BlockMirror.NONE,
            BlockRotation.NONE,
            BreachedStructureDefinition.SurfaceRequirement.AVOID_WATER,
            BreachedStructureDefinition.SupportMode.NONE,
            Blocks.AIR,
            Blocks.AIR,
            0,
            BreachedStructureDefinition.PrePlacementCheck.NONE
    );

    public static final BreachedStructureDefinition PINK_TREE = new BreachedStructureDefinition(
            "pinktree.nbt",
            Identifier.of(Breached.MOD_ID, "pinktree"),
            World.OVERWORLD,
            1,
            24,
            BreachedStructureDefinition.PlacementMode.CENTER_RADIUS,
            0,
            0,
            -35,
            -13,
            -15,
            300,
            1000,
            false,
            0x5B7F2D19C8A643E0L,
            0,
            400,
            150,
            true,
            false,
            true,
            true,
            false,
            false,
            false,
            false,
            false,
            5,
            BreachedStructureDefinition.SpawnImportance.REQUIRED,
            BreachedStructureDefinition.SpacingGroup.MAJOR,
            BreachedStructureDefinition.SpacingPolicy.MAJOR_ONLY,
            8,
            4,
            BreachedStructureDefinition.AirPlacementMode.IGNORE_AIR,
            BreachedStructureDefinition.TerrainValidation.LENIENT,
            BreachedStructureDefinition.HeightSelection.MEDIAN_SURFACE,
            8,
            BlockMirror.NONE,
            BlockRotation.NONE,
            BreachedStructureDefinition.SurfaceRequirement.MOSTLY_WATER,
            BreachedStructureDefinition.SupportMode.WATER_MARKER_PILLARS,
            Blocks.SPRUCE_LOG,
            Blocks.REINFORCED_DEEPSLATE,
            96,
            BreachedStructureDefinition.PrePlacementCheck.NONE
    );

    public static final List<BreachedStructureDefinition> PLANNED_STRUCTURES = List.of(
            SWORD_STATUE,
            OFFICIAL_NETHER_PORTAL,
            HORACE,
            PINK_TREE
    );

    public static final List<BreachedStructureDefinition> PROTECTED_STRUCTURES = List.of(
            SWORD_STATUE,
            OFFICIAL_NETHER_PORTAL
    );

    private BreachedStructureDefinitions() {
    }

    public static String key(BreachedStructureDefinition definition) {
        return definition.structureId().toString();
    }

    public static BreachedStructureDefinition applyPresetOverrides(
            BreachedStructureDefinition definition,
            Optional<BreachedDimensionRules.BreachedPreset> preset
    ) {
        if (preset.isPresent()
                && preset.get() == BreachedDimensionRules.BreachedPreset.SMALL
                && definition.structureId().equals(HORACE.structureId())) {
            return definition.withRadius(150, 425);
        }

        if (preset.isPresent()
                && preset.get() == BreachedDimensionRules.BreachedPreset.SMALL
                && definition.structureId().equals(PINK_TREE.structureId())) {
            return definition.withRadiusAndSpacing(150, 425, 300, 150);
        }

        if (preset.isPresent()
                && preset.get() == BreachedDimensionRules.BreachedPreset.SMALL
                && definition.structureId().equals(SWORD_STATUE.structureId())) {
            return definition.withRadiusAndSpacing(100, 425, 300, 150);
        }

        if (preset.isPresent()
                && preset.get() == BreachedDimensionRules.BreachedPreset.SMALL
                && definition.structureId().equals(OFFICIAL_NETHER_PORTAL.structureId())) {
            return definition.withRadiusAndSpacing(200, 250, 300, 150);
        }

        return definition;
    }
}
