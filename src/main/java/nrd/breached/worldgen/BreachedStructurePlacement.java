package nrd.breached.worldgen;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

public record BreachedStructurePlacement(
        Identifier structureId,
        String logName,
        BlockPos origin,
        Vec3i size,
        int surfaceY,
        int minSurfaceY,
        int maxSurfaceY,
        int heightRange
) {
}
