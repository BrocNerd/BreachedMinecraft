package nrd.breached.worldgen;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class BreachedStructureSupportGenerator {
    private static final int BLOCK_UPDATE_FLAGS = 2;

    private BreachedStructureSupportGenerator() {
    }

    public static List<BlockPos> generate(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructurePlacement placement
    ) {
        return switch (definition.supportMode()) {
            case NONE -> List.of();
            case WATER_SOLID_FOOTPRINT -> generateWaterSolidFootprint(world, definition, placement);
            case WATER_MARKER_PILLARS -> generateWaterMarkerPillars(world, definition, placement);
        };
    }

    private static List<BlockPos> generateWaterSolidFootprint(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructurePlacement placement
    ) {
        if (definition.supportBlock().equals(Blocks.AIR) || definition.supportMaxDepth() <= 0) {
            return List.of();
        }

        BlockState supportState = definition.supportBlock().getDefaultState();
        BlockPos origin = placement.origin();
        int minY = Math.max(world.getBottomY(), origin.getY() - definition.supportMaxDepth());
        List<BlockPos> placedPositions = new ArrayList<>();
        int placedBlocks = 0;

        for (int x = origin.getX(); x < origin.getX() + placement.size().getX(); x++) {
            for (int z = origin.getZ(); z < origin.getZ() + placement.size().getZ(); z++) {
                for (int y = origin.getY() - 1; y >= minY; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState currentState = world.getBlockState(pos);
                    if (!canReplaceWithStructureSupport(currentState)) {
                        break;
                    }

                    world.setBlockState(pos, supportState, BLOCK_UPDATE_FLAGS);
                    placedPositions.add(pos.toImmutable());
                    placedBlocks++;
                }
            }
        }

        System.out.println("[Breached] Generated water solid footprint support for " + placement.logName()
                + " using " + definition.supportBlock()
                + " down to max depth " + definition.supportMaxDepth()
                + "; placed " + placedBlocks + " blocks.");
        return placedPositions;
    }

    private static List<BlockPos> generateWaterMarkerPillars(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructurePlacement placement
    ) {
        if (definition.supportBlock().equals(Blocks.AIR)
                || definition.supportMarkerBlock().equals(Blocks.AIR)
                || definition.supportMaxDepth() <= 0) {
            return List.of();
        }

        BlockState supportState = definition.supportBlock().getDefaultState();
        BlockPos origin = placement.origin();
        List<BlockPos> placedPositions = new ArrayList<>();
        int markerCount = 0;
        int placedBlocks = 0;

        for (int x = origin.getX(); x < origin.getX() + placement.size().getX(); x++) {
            for (int y = origin.getY(); y < origin.getY() + placement.size().getY(); y++) {
                for (int z = origin.getZ(); z < origin.getZ() + placement.size().getZ(); z++) {
                    BlockPos markerPos = new BlockPos(x, y, z);
                    if (!world.getBlockState(markerPos).isOf(definition.supportMarkerBlock())) {
                        continue;
                    }

                    markerCount++;
                    world.setBlockState(markerPos, supportState, BLOCK_UPDATE_FLAGS);
                    placedPositions.add(markerPos.toImmutable());
                    placedBlocks++;
                    placedBlocks += generatePillarBelow(world, markerPos.down(), supportState, definition.supportMaxDepth(), placedPositions);
                }
            }
        }

        System.out.println("[Breached] Generated water marker pillar support for " + placement.logName()
                + " using marker " + definition.supportMarkerBlock()
                + " and support " + definition.supportBlock()
                + "; found " + markerCount
                + " markers and placed " + placedBlocks + " blocks.");
        return placedPositions;
    }

    private static int generatePillarBelow(
            ServerWorld world,
            BlockPos startPos,
            BlockState supportState,
            int maxDepth,
            List<BlockPos> placedPositions
    ) {
        int minY = Math.max(world.getBottomY(), startPos.getY() - maxDepth + 1);
        int placedBlocks = 0;

        for (int y = startPos.getY(); y >= minY; y--) {
            BlockPos pos = new BlockPos(startPos.getX(), y, startPos.getZ());
            BlockState currentState = world.getBlockState(pos);
            if (!canReplaceWithStructureSupport(currentState)) {
                break;
            }

            world.setBlockState(pos, supportState, BLOCK_UPDATE_FLAGS);
            placedPositions.add(pos.toImmutable());
            placedBlocks++;
        }

        return placedBlocks;
    }

    private static boolean canReplaceWithStructureSupport(BlockState state) {
        return state.isOf(Blocks.WATER)
                || state.isAir()
                || isNaturalSupportObstruction(state);
    }

    private static boolean isNaturalSupportObstruction(BlockState state) {
        return state.isIn(BlockTags.LOGS)
                || state.isIn(BlockTags.LEAVES)
                || state.isIn(BlockTags.FLOWERS)
                || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.SHORT_GRASS)
                || state.isOf(Blocks.FERN)
                || state.isOf(Blocks.LARGE_FERN)
                || state.isOf(Blocks.VINE)
                || state.isOf(Blocks.SNOW);
    }
}
