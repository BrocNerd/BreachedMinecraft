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
            96,
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
            BreachedStructureDefinition.SurfaceRequirement.ALLOW_WATER,
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
            64,
            BreachedStructureDefinition.PlacementMode.DISTRIBUTED_RING,
            0,
            0,
            0,
            -1,
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
            BreachedStructureDefinition.AirPlacementMode.PLACE_AIR,
            BreachedStructureDefinition.TerrainValidation.LENIENT,
            BreachedStructureDefinition.HeightSelection.ORIGIN_SURFACE,
            16,
            BlockMirror.NONE,
            BlockRotation.NONE,
            BreachedStructureDefinition.SurfaceRequirement.ALLOW_WATER,
            BreachedStructureDefinition.SupportMode.WATER_MARKER_PILLARS,
            Blocks.STONE_BRICKS,
            Blocks.REINFORCED_DEEPSLATE,
            96,
            BreachedStructureDefinition.PrePlacementCheck.NO_ACTIVE_NETHER_PORTAL_NEARBY
    );

    public static final BreachedStructureDefinition HORACE = new BreachedStructureDefinition(
            "horace.nbt",
            Identifier.of(Breached.MOD_ID, "horace"),
            World.OVERWORLD,
            1,
            64,
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
            96,
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

    public static final BreachedStructureDefinition BIG_BOAT = new BreachedStructureDefinition(
            "bigboat.nbt",
            Identifier.of(Breached.MOD_ID, "bigboat"),
            World.OVERWORLD,
            1,
            96,
            BreachedStructureDefinition.PlacementMode.CENTER_RADIUS,
            0,
            0,
            -8,
            -5,
            -19,
            350,
            1100,
            false,
            0x31A64F7D92C50B18L,
            48,
            500,
            200,
            false,
            false,
            false,
            false,
            false,
            true,
            false,
            true,
            false,
            15,
            BreachedStructureDefinition.SpawnImportance.REQUIRED,
            BreachedStructureDefinition.SpacingGroup.MAJOR,
            BreachedStructureDefinition.SpacingPolicy.MAJOR_ONLY,
            0,
            0,
            BreachedStructureDefinition.AirPlacementMode.IGNORE_AIR,
            BreachedStructureDefinition.TerrainValidation.STRICT,
            BreachedStructureDefinition.HeightSelection.MEDIAN_SURFACE,
            4,
            BlockMirror.NONE,
            BlockRotation.NONE,
            BreachedStructureDefinition.SurfaceRequirement.REQUIRE_WATER,
            BreachedStructureDefinition.SupportMode.NONE,
            Blocks.AIR,
            Blocks.AIR,
            0,
            BreachedStructureDefinition.PrePlacementCheck.NONE
    );

    public static final BreachedStructureDefinition ABANDONED_HUT = new BreachedStructureDefinition(
            "abandonedhut.nbt",
            Identifier.of(Breached.MOD_ID, "abandonedhut"),
            World.OVERWORLD,
            3,
            0,
            BreachedStructureDefinition.PlacementMode.RANDOM_WITHIN_BORDER,
            0,
            0,
            0,
            -1,
            0,
            64,
            1200,
            false,
            0x6E2D9B4C18F05A77L,
            0,
            96,
            48,
            true,
            true,
            true,
            true,
            false,
            true,
            false,
            false,
            true,
            1,
            BreachedStructureDefinition.SpawnImportance.OPTIONAL,
            BreachedStructureDefinition.SpacingGroup.MINOR,
            BreachedStructureDefinition.SpacingPolicy.SAME_GROUP,
            8,
            2,
            BreachedStructureDefinition.AirPlacementMode.IGNORE_AIR,
            BreachedStructureDefinition.TerrainValidation.MEDIUM,
            BreachedStructureDefinition.HeightSelection.MEDIAN_SURFACE,
            4,
            BlockMirror.NONE,
            BlockRotation.NONE,
            BreachedStructureDefinition.SurfaceRequirement.AVOID_WATER,
            BreachedStructureDefinition.SupportMode.WATER_SOLID_FOOTPRINT,
            Blocks.DIRT,
            Blocks.AIR,
            6,
            BreachedStructureDefinition.PrePlacementCheck.NONE
    );

    public static final BreachedStructureDefinition CADEN_BOAT = new BreachedStructureDefinition(
            "cadenboat.nbt",
            Identifier.of(Breached.MOD_ID, "cadenboat"),
            World.OVERWORLD,
            3,
            0,
            BreachedStructureDefinition.PlacementMode.RANDOM_WITHIN_BORDER,
            0,
            0,
            0,
            0,
            0,
            64,
            1200,
            false,
            0x2A91D64F7C03B5E8L,
            0,
            96,
            48,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            1,
            BreachedStructureDefinition.SpawnImportance.OPTIONAL,
            BreachedStructureDefinition.SpacingGroup.MINOR,
            BreachedStructureDefinition.SpacingPolicy.SAME_GROUP,
            0,
            0,
            BreachedStructureDefinition.AirPlacementMode.IGNORE_AIR,
            BreachedStructureDefinition.TerrainValidation.LENIENT,
            BreachedStructureDefinition.HeightSelection.MEDIAN_SURFACE,
            4,
            BlockMirror.NONE,
            BlockRotation.NONE,
            BreachedStructureDefinition.SurfaceRequirement.REQUIRE_WATER,
            BreachedStructureDefinition.SupportMode.NONE,
            Blocks.AIR,
            Blocks.AIR,
            0,
            BreachedStructureDefinition.PrePlacementCheck.NONE
    );

    public static final BreachedStructureDefinition CAVE_HUT = new BreachedStructureDefinition(
            "cavehut.nbt",
            Identifier.of(Breached.MOD_ID, "cavehut"),
            World.OVERWORLD,
            3,
            0,
            BreachedStructureDefinition.PlacementMode.RANDOM_WITHIN_BORDER,
            0,
            0,
            -6,
            -1,
            -6,
            64,
            1200,
            false,
            0x74C3A19D5E26B880L,
            0,
            96,
            48,
            true,
            true,
            true,
            true,
            false,
            true,
            false,
            false,
            true,
            1,
            BreachedStructureDefinition.SpawnImportance.OPTIONAL,
            BreachedStructureDefinition.SpacingGroup.MINOR,
            BreachedStructureDefinition.SpacingPolicy.SAME_GROUP,
            16,
            4,
            BreachedStructureDefinition.AirPlacementMode.IGNORE_AIR,
            BreachedStructureDefinition.TerrainValidation.MEDIUM,
            BreachedStructureDefinition.HeightSelection.MEDIAN_SURFACE,
            4,
            BlockMirror.NONE,
            BlockRotation.NONE,
            BreachedStructureDefinition.SurfaceRequirement.AVOID_WATER,
            BreachedStructureDefinition.SupportMode.WATER_SOLID_FOOTPRINT,
            Blocks.DIRT,
            Blocks.AIR,
            6,
            BreachedStructureDefinition.PrePlacementCheck.NONE
    );

    public static final BreachedStructureDefinition PUEBLO_1 = new BreachedStructureDefinition(
            "pueblo1.nbt",
            Identifier.of(Breached.MOD_ID, "pueblo1"),
            World.OVERWORLD,
            2,
            0,
            BreachedStructureDefinition.PlacementMode.RANDOM_WITHIN_BORDER,
            0,
            0,
            -3,
            -1,
            -3,
            64,
            1200,
            false,
            0x1D6E8A43C9B257F0L,
            0,
            96,
            48,
            true,
            true,
            true,
            true,
            false,
            true,
            false,
            false,
            true,
            1,
            BreachedStructureDefinition.SpawnImportance.OPTIONAL,
            BreachedStructureDefinition.SpacingGroup.MINOR,
            BreachedStructureDefinition.SpacingPolicy.SAME_GROUP,
            8,
            2,
            BreachedStructureDefinition.AirPlacementMode.IGNORE_AIR,
            BreachedStructureDefinition.TerrainValidation.MEDIUM,
            BreachedStructureDefinition.HeightSelection.MEDIAN_SURFACE,
            4,
            BlockMirror.NONE,
            BlockRotation.NONE,
            BreachedStructureDefinition.SurfaceRequirement.AVOID_WATER,
            BreachedStructureDefinition.SupportMode.WATER_SOLID_FOOTPRINT,
            Blocks.DIRT,
            Blocks.AIR,
            6,
            BreachedStructureDefinition.PrePlacementCheck.NONE
    );

    public static final BreachedStructureDefinition PUEBLO_2 = new BreachedStructureDefinition(
            "pueblo2.nbt",
            Identifier.of(Breached.MOD_ID, "pueblo2"),
            World.OVERWORLD,
            2,
            0,
            BreachedStructureDefinition.PlacementMode.RANDOM_WITHIN_BORDER,
            0,
            0,
            -5,
            -1,
            -3,
            64,
            1200,
            false,
            0x5A20F6C83E914D37L,
            0,
            96,
            48,
            true,
            true,
            true,
            true,
            false,
            true,
            false,
            false,
            true,
            1,
            BreachedStructureDefinition.SpawnImportance.OPTIONAL,
            BreachedStructureDefinition.SpacingGroup.MINOR,
            BreachedStructureDefinition.SpacingPolicy.SAME_GROUP,
            12,
            3,
            BreachedStructureDefinition.AirPlacementMode.IGNORE_AIR,
            BreachedStructureDefinition.TerrainValidation.MEDIUM,
            BreachedStructureDefinition.HeightSelection.MEDIAN_SURFACE,
            4,
            BlockMirror.NONE,
            BlockRotation.NONE,
            BreachedStructureDefinition.SurfaceRequirement.AVOID_WATER,
            BreachedStructureDefinition.SupportMode.WATER_SOLID_FOOTPRINT,
            Blocks.DIRT,
            Blocks.AIR,
            6,
            BreachedStructureDefinition.PrePlacementCheck.NONE
    );

    public static final BreachedStructureDefinition RAFT = new BreachedStructureDefinition(
            "raft.nbt",
            Identifier.of(Breached.MOD_ID, "raft"),
            World.OVERWORLD,
            2,
            0,
            BreachedStructureDefinition.PlacementMode.RANDOM_WITHIN_BORDER,
            0,
            0,
            -2,
            -1,
            -2,
            64,
            1200,
            false,
            0x0E941B73D5C862A4L,
            0,
            96,
            48,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            1,
            BreachedStructureDefinition.SpawnImportance.OPTIONAL,
            BreachedStructureDefinition.SpacingGroup.MINOR,
            BreachedStructureDefinition.SpacingPolicy.SAME_GROUP,
            0,
            0,
            BreachedStructureDefinition.AirPlacementMode.IGNORE_AIR,
            BreachedStructureDefinition.TerrainValidation.LENIENT,
            BreachedStructureDefinition.HeightSelection.MEDIAN_SURFACE,
            4,
            BlockMirror.NONE,
            BlockRotation.NONE,
            BreachedStructureDefinition.SurfaceRequirement.REQUIRE_WATER,
            BreachedStructureDefinition.SupportMode.NONE,
            Blocks.AIR,
            Blocks.AIR,
            0,
            BreachedStructureDefinition.PrePlacementCheck.NONE
    );

    public static final BreachedStructureDefinition WAYSTOP = new BreachedStructureDefinition(
            "waystop.nbt",
            Identifier.of(Breached.MOD_ID, "waystop"),
            World.OVERWORLD,
            3,
            0,
            BreachedStructureDefinition.PlacementMode.RANDOM_WITHIN_BORDER,
            0,
            0,
            -5,
            -1,
            -5,
            64,
            1200,
            false,
            0x69B51E28C407AD3FL,
            0,
            96,
            48,
            true,
            true,
            true,
            true,
            false,
            true,
            false,
            false,
            true,
            1,
            BreachedStructureDefinition.SpawnImportance.OPTIONAL,
            BreachedStructureDefinition.SpacingGroup.MINOR,
            BreachedStructureDefinition.SpacingPolicy.SAME_GROUP,
            16,
            4,
            BreachedStructureDefinition.AirPlacementMode.IGNORE_AIR,
            BreachedStructureDefinition.TerrainValidation.MEDIUM,
            BreachedStructureDefinition.HeightSelection.MEDIAN_SURFACE,
            4,
            BlockMirror.NONE,
            BlockRotation.NONE,
            BreachedStructureDefinition.SurfaceRequirement.AVOID_WATER,
            BreachedStructureDefinition.SupportMode.WATER_SOLID_FOOTPRINT,
            Blocks.DIRT,
            Blocks.AIR,
            6,
            BreachedStructureDefinition.PrePlacementCheck.NONE
    );

    public static final List<BreachedStructureDefinition> PLANNED_STRUCTURES = List.of(
            SWORD_STATUE,
            OFFICIAL_NETHER_PORTAL,
            HORACE,
            PINK_TREE,
            BIG_BOAT
    );

    public static final List<BreachedStructureDefinition> MINOR_POI_STRUCTURES = List.of(
            ABANDONED_HUT,
            CADEN_BOAT,
            CAVE_HUT,
            PUEBLO_1,
            PUEBLO_2,
            RAFT,
            WAYSTOP
    );

    public static final List<BreachedStructureDefinition> ALL_STRUCTURES = List.of(
            SWORD_STATUE,
            OFFICIAL_NETHER_PORTAL,
            HORACE,
            PINK_TREE,
            BIG_BOAT,
            ABANDONED_HUT,
            CADEN_BOAT,
            CAVE_HUT,
            PUEBLO_1,
            PUEBLO_2,
            RAFT,
            WAYSTOP
    );

    public static final List<BreachedStructureDefinition> PROTECTED_STRUCTURES = List.of(
            SWORD_STATUE,
            OFFICIAL_NETHER_PORTAL,
            BIG_BOAT
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

        if (preset.isPresent()
                && preset.get() == BreachedDimensionRules.BreachedPreset.SMALL
                && definition.structureId().equals(BIG_BOAT.structureId())) {
            return definition.withRadiusAndSpacing(150, 425, 300, 150);
        }

        if (preset.isPresent()
                && preset.get() == BreachedDimensionRules.BreachedPreset.SMALL
                && definition.structureId().equals(ABANDONED_HUT.structureId())) {
            return definition.withRadiusAndSpacing(48, 425, 96, 48);
        }

        if (preset.isPresent()
                && preset.get() == BreachedDimensionRules.BreachedPreset.SMALL
                && definition.structureId().equals(CADEN_BOAT.structureId())) {
            return definition.withCountPerWorld(3).withRadiusAndSpacing(48, 425, 96, 48);
        }

        if (preset.isPresent()
                && preset.get() == BreachedDimensionRules.BreachedPreset.SMALL
                && definition.spacingGroup() == BreachedStructureDefinition.SpacingGroup.MINOR) {
            return definition.withRadiusAndSpacing(48, 425, 96, 48);
        }

        return definition;
    }
}
