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
            BreachedStructureDefinition.PlacementMode.CENTER_RADIUS,
            0,
            0,
            0,
            1200,
            false,
            0x7C31B0E5A91D4F22L,
            72,
            128,
            true,
            true,
            true,
            true,
            false,
            true,
            false,
            true,
            false,
            0,
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
            BreachedStructureDefinition.PlacementMode.DISTRIBUTED_RING,
            0,
            0,
            450,
            1000,
            true,
            0x42D5A3B91F0C7E66L,
            24,
            700,
            true,
            false,
            true,
            true,
            false,
            false,
            false,
            true,
            false,
            10,
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
            BreachedStructureDefinition.PlacementMode.CENTER_RADIUS,
            0,
            0,
            300,
            900,
            false,
            0x19E6C4D8A73B52F1L,
            0,
            128,
            true,
            true,
            true,
            true,
            false,
            true,
            false,
            false,
            false,
            0,
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

    public static final List<BreachedStructureDefinition> PLANNED_STRUCTURES = List.of(
            SWORD_STATUE,
            OFFICIAL_NETHER_PORTAL,
            HORACE
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
                && definition.structureId().equals(OFFICIAL_NETHER_PORTAL.structureId())) {
            return definition.withRadiusAndSpacing(200, 250, 300);
        }

        return definition;
    }
}
