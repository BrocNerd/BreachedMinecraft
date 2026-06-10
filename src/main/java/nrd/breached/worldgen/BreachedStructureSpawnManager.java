package nrd.breached.worldgen;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.StairShape;
import net.minecraft.inventory.LootableInventory;
import net.minecraft.loot.LootTable;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.processor.BlockIgnoreStructureProcessor;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class BreachedStructureSpawnManager {
    public static final BreachedStructureDefinition CENTRAL_SPAWN = BreachedStructureDefinitions.SWORD_STATUE;
    public static final BreachedStructureDefinition OFFICIAL_NETHER_PORTAL = BreachedStructureDefinitions.OFFICIAL_NETHER_PORTAL;
    private static final int BLOCK_UPDATE_FLAGS = 2;
    private static final int TOWNHALL_STAIRCASE_MAX_EXTENSION = 128;
    private static final int TOWNHALL_WATER_LANDING_LENGTH = 3;
    private static final int TOWNHALL_WATER_CATCH_RADIUS = 1;
    private static final int TOWNHALL_WATER_FREEZE_LIGHT_LEVEL = 15;
    private static final int TOWNHALL_UNDERFLOOR_LIGHT_GRID_RADIUS = 1;
    private static final int TOWNHALL_UNDERFLOOR_LIGHT_SPACING = 12;
    private static final int TOWNHALL_UNDERFLOOR_LIGHT_SEARCH_DEPTH = 24;
    private static final List<Block> LOOTABLE_TEMPLATE_BLOCKS = List.of(
            Blocks.CHEST,
            Blocks.TRAPPED_CHEST,
            Blocks.BARREL,
            Blocks.DISPENSER,
            Blocks.DROPPER,
            Blocks.HOPPER,
            Blocks.SHULKER_BOX,
            Blocks.WHITE_SHULKER_BOX,
            Blocks.LIGHT_GRAY_SHULKER_BOX,
            Blocks.GRAY_SHULKER_BOX,
            Blocks.BLACK_SHULKER_BOX,
            Blocks.BROWN_SHULKER_BOX,
            Blocks.RED_SHULKER_BOX,
            Blocks.ORANGE_SHULKER_BOX,
            Blocks.YELLOW_SHULKER_BOX,
            Blocks.LIME_SHULKER_BOX,
            Blocks.GREEN_SHULKER_BOX,
            Blocks.CYAN_SHULKER_BOX,
            Blocks.LIGHT_BLUE_SHULKER_BOX,
            Blocks.BLUE_SHULKER_BOX,
            Blocks.PURPLE_SHULKER_BOX,
            Blocks.MAGENTA_SHULKER_BOX,
            Blocks.PINK_SHULKER_BOX
    );

    private BreachedStructureSpawnManager() {
    }

    public static Optional<StructureTemplate> loadTemplate(ServerWorld world, BreachedStructureDefinition definition) {
        Optional<StructureTemplate> template = world.getStructureTemplateManager().getTemplate(definition.structureId());
        if (template.isEmpty()) {
            System.out.println("[Breached] Structure template " + definition.structureId() + " for " + definition.logName() + " was not found.");
        }

        return template;
    }

    public static BreachedStructureSite evaluateSite(
            ServerWorld world,
            BreachedStructureDefinition definition,
            StructureTemplate template,
            int originX,
            int originZ,
            int distanceScore
    ) {
        return evaluateSite(world, definition, getRotatedSize(template.getSize(), definition.rotation()), originX, originZ, distanceScore);
    }

    public static List<RadiusCandidate> generateRadiusCandidates(
            long worldSeed,
            BreachedStructureDefinition definition
    ) {
        return generateRadiusCandidates(
                worldSeed,
                definition.seedSalt(),
                definition.centerX(),
                definition.centerZ(),
                definition.minRadius(),
                definition.maxRadius(),
                definition.countPerWorld(),
                definition.roughlyOpposed()
        );
    }

    public static List<RadiusCandidate> generateRadiusCandidates(
            long worldSeed,
            long seedSalt,
            int centerX,
            int centerZ,
            int minRadius,
            int maxRadius,
            int count,
            boolean roughlyOpposed
    ) {
        java.util.Random random = new java.util.Random(worldSeed ^ seedSalt);
        List<RadiusCandidate> candidates = new ArrayList<>();
        double firstAngle = random.nextDouble() * Math.TAU;
        double secondAngle = firstAngle + Math.PI + ((random.nextDouble() - 0.5D) * 0.7D);

        for (int i = 0; i < count; i++) {
            double angle;
            if (i == 0) {
                angle = firstAngle;
            } else if (i == 1 && roughlyOpposed) {
                angle = secondAngle;
            } else {
                angle = random.nextDouble() * Math.TAU;
            }

            int radius = minRadius + random.nextInt(maxRadius - minRadius + 1);
            int x = toChunkCenterCoordinate(centerX + Math.cos(angle) * radius);
            int z = toChunkCenterCoordinate(centerZ + Math.sin(angle) * radius);
            candidates.add(new RadiusCandidate(i + 1, x, z, radius, angle));
        }

        return candidates;
    }

    public static Optional<BreachedStructurePlacement> placeRadiusCandidate(
            ServerWorld world,
            BreachedStructureDefinition definition,
            RadiusCandidate candidate
    ) {
        if (!world.getRegistryKey().equals(definition.requiredDimension())) {
            return Optional.empty();
        }

        Optional<StructureTemplate> template = loadTemplate(world, definition);
        if (template.isEmpty()) {
            System.out.println("[Breached] " + definition.logName() + " placement will retry after the missing structure is available.");
            return Optional.empty();
        }

        BreachedStructureSite site = evaluateSite(world, definition, template.get(), candidate.x(), candidate.z(), candidate.radius());
        if (!site.accepted()) {
            System.out.println("[Breached] Rejected " + definition.logName() + " candidate " + candidate.index()
                    + " at x " + candidate.x() + ", z " + candidate.z()
                    + ": " + site.rejectionReason() + ".");
            return Optional.empty();
        }

        System.out.println("[Breached] Accepted " + definition.logName() + " candidate " + candidate.index()
                + " at x " + candidate.x() + ", z " + candidate.z()
                + " radius " + candidate.radius()
                + " surface y " + site.surfaceY()
                + " sampled range " + site.minSurfaceY() + "-" + site.maxSurfaceY()
                + " height range " + site.heightRange() + ".");
        return Optional.of(place(
                world,
                definition,
                template.get(),
                candidate.x(),
                site.surfaceY(),
                candidate.z(),
                definition.mirror(),
                definition.rotation(),
                site
        ));
    }

    public static int getProtectedCenterX(BreachedStructurePlacement placement) {
        return placement.origin().getX() + placement.size().getX() / 2;
    }

    public static int getProtectedCenterZ(BreachedStructurePlacement placement) {
        return placement.origin().getZ() + placement.size().getZ() / 2;
    }

    public static BreachedStructureSite evaluateSite(
            ServerWorld world,
            BreachedStructureDefinition definition,
            Vec3i footprintSize,
            int originX,
            int originZ,
            int distanceScore
    ) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int liquidSamples = 0;
        List<Integer> surfaceHeights = new ArrayList<>();

        List<Integer> xSamples = createSamples(originX, originX + footprintSize.getX() - 1, definition.sampleStep());
        List<Integer> zSamples = createSamples(originZ, originZ + footprintSize.getZ() - 1, definition.sampleStep());
        for (int x : xSamples) {
            for (int z : zSamples) {
                int surfaceY = getSurfaceY(world, definition, x, z);
                BlockState surfaceState = world.getBlockState(new BlockPos(x, surfaceY - 1, z));
                if (surfaceState.isOf(Blocks.WATER) || surfaceState.isOf(Blocks.LAVA)) {
                    liquidSamples++;
                }

                minY = Math.min(minY, surfaceY);
                maxY = Math.max(maxY, surfaceY);
                surfaceHeights.add(surfaceY);
            }
        }

        Optional<String> surfaceRejectionReason = getSurfaceRejectionReason(definition, liquidSamples, surfaceHeights.size());
        if (surfaceRejectionReason.isPresent()) {
            return new BreachedStructureSite(originX, originZ, 0, minY, maxY, maxY - minY, Integer.MAX_VALUE, surfaceRejectionReason.get());
        }

        int heightRange = maxY - minY;
        int maxAllowedHeightRange = getMaxAllowedHeightRange(definition);
        if (maxAllowedHeightRange >= 0 && heightRange > maxAllowedHeightRange) {
            return new BreachedStructureSite(originX, originZ, 0, minY, maxY, heightRange, Integer.MAX_VALUE,
                    "height range " + heightRange + " exceeded max " + maxAllowedHeightRange);
        }

        int score = heightRange * 100_000 + distanceScore;
        int surfaceY = switch (definition.heightSelection()) {
            case ORIGIN_SURFACE -> getSurfaceY(world, definition, originX, originZ);
            case MEDIAN_SURFACE -> getMedianSurfaceY(surfaceHeights);
            case HIGHEST_SURFACE -> maxY;
        };

        return new BreachedStructureSite(originX, originZ, surfaceY, minY, maxY, heightRange, score, null);
    }

    public static BreachedStructurePlacement place(
            ServerWorld world,
            BreachedStructureDefinition definition,
            StructureTemplate template,
            int x,
            int y,
            int z,
            BlockMirror mirror,
            BlockRotation rotation,
            BreachedStructureSite site
    ) {
        BlockPos pos = new BlockPos(x, y, z);
        ChunkPos chunkPos = new ChunkPos(pos);
        world.getChunk(chunkPos.x, chunkPos.z);

        StructurePlacementData placementData = createPlacementData(definition, mirror, rotation);

        template.place(world, pos, pos, placementData, Random.create(world.getSeed()), BLOCK_UPDATE_FLAGS);
        NbtCompound templateNbt = template.writeNbt(new NbtCompound());
        List<BlockPos> extensionBlocks = new ArrayList<>();
        removePlacedTemplateWaterlogging(world, templateNbt, pos, placementData);
        applyJigsawAirMarkers(world, templateNbt, pos, placementData);
        extensionBlocks.addAll(applyTownhallStaircasePlaceholders(world, definition, template, pos, placementData));
        extensionBlocks.addAll(applyTownhallWaterPlaceholders(world, definition, template, pos, placementData));
        extensionBlocks.addAll(applyTownhallUnderfloorSpawnLights(world, definition, template, pos));
        applyTemplateLootTables(world, template, pos, placementData);

        BreachedStructurePlacement placement = new BreachedStructurePlacement(
                definition.structureId(),
                definition.logName(),
                pos,
                getRotatedSize(template.getSize(), rotation),
                site.surfaceY(),
                site.minSurfaceY(),
                site.maxSurfaceY(),
                site.heightRange(),
                extensionBlocks
        );
        logPlacement(placement, mirror, rotation);
        return placement;
    }

    public static List<TemplateLootContainer> getTemplateLootContainers(
            ServerWorld world,
            BreachedStructureDefinition definition,
            StructureTemplate template,
            BlockPos pos,
            BlockMirror mirror,
            BlockRotation rotation
    ) {
        return getTemplateLootContainers(world, template, pos, createPlacementData(definition, mirror, rotation));
    }

    private static StructurePlacementData createPlacementData(
            BreachedStructureDefinition definition,
            BlockMirror mirror,
            BlockRotation rotation
    ) {
        StructurePlacementData placementData = new StructurePlacementData()
                .setMirror(mirror)
                .setRotation(rotation)
                .setIgnoreEntities(false)
                .setUpdateNeighbors(true);
        if (definition.airPlacementMode() == BreachedStructureDefinition.AirPlacementMode.IGNORE_AIR) {
            placementData.addProcessor(BlockIgnoreStructureProcessor.IGNORE_AIR);
        }

        return placementData;
    }

    public static Vec3i getRotatedSize(Vec3i size, BlockRotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90, COUNTERCLOCKWISE_90 -> new Vec3i(size.getZ(), size.getY(), size.getX());
            case NONE, CLOCKWISE_180 -> size;
        };
    }

    public static List<TemplatePlacedBlock> getTemplatePlacedBlocks(
            ServerWorld world,
            BreachedStructureDefinition definition,
            StructureTemplate template,
            BlockPos pos,
            BlockMirror mirror,
            BlockRotation rotation
    ) {
        return getTemplateBlocks(world, definition, template, pos, mirror, rotation, false);
    }

    public static List<TemplatePlacedBlock> getTemplateFootprintBlocks(
            ServerWorld world,
            BreachedStructureDefinition definition,
            StructureTemplate template,
            BlockPos pos,
            BlockMirror mirror,
            BlockRotation rotation
    ) {
        return getTemplateBlocks(world, definition, template, pos, mirror, rotation, true);
    }

    public static List<TemplatePlacedBlock> getTemplateJigsawAirBlocks(
            ServerWorld world,
            BreachedStructureDefinition definition,
            StructureTemplate template,
            BlockPos pos,
            BlockMirror mirror,
            BlockRotation rotation
    ) {
        StructurePlacementData placementData = createPlacementData(definition, mirror, rotation);
        NbtCompound templateNbt = template.writeNbt(new NbtCompound());
        Set<Integer> jigsawStateIds = getJigsawStateIds(templateNbt);
        if (jigsawStateIds.isEmpty()) {
            return List.of();
        }

        List<TemplatePlacedBlock> airBlocks = new ArrayList<>();
        NbtList blocks = templateNbt.getListOrEmpty("blocks");
        for (int index = 0; index < blocks.size(); index++) {
            NbtCompound blockNbt = blocks.getCompoundOrEmpty(index);
            if (!jigsawStateIds.contains(blockNbt.getInt("state", -1))) {
                continue;
            }

            NbtList blockPos = blockNbt.getListOrEmpty("pos");
            BlockPos relativePos = new BlockPos(
                    blockPos.getInt(0, 0),
                    blockPos.getInt(1, 0),
                    blockPos.getInt(2, 0)
            );
            BlockPos worldPos = StructureTemplate.transform(placementData, relativePos).add(pos);
            airBlocks.add(new TemplatePlacedBlock(worldPos, Blocks.AIR.getDefaultState()));
        }

        return airBlocks;
    }

    private static List<TemplatePlacedBlock> getTemplateBlocks(
            ServerWorld world,
            BreachedStructureDefinition definition,
            StructureTemplate template,
            BlockPos pos,
            BlockMirror mirror,
            BlockRotation rotation,
            boolean includeAir
    ) {
        StructurePlacementData placementData = createPlacementData(definition, mirror, rotation);
        NbtCompound templateNbt = template.writeNbt(new NbtCompound());
        List<BlockState> palette = getPrimaryPaletteBlockStates(world, templateNbt);
        Set<Integer> jigsawStateIds = getJigsawStateIds(templateNbt);
        List<TemplatePlacedBlock> placedBlocks = new ArrayList<>();

        NbtList blocks = templateNbt.getListOrEmpty("blocks");
        for (int index = 0; index < blocks.size(); index++) {
            NbtCompound blockNbt = blocks.getCompoundOrEmpty(index);
            int stateIndex = blockNbt.getInt("state", -1);
            if (stateIndex < 0 || stateIndex >= palette.size()) {
                continue;
            }

            BlockState state = jigsawStateIds.contains(stateIndex)
                    ? Blocks.AIR.getDefaultState()
                    : normalizePlacedTemplateState(palette.get(stateIndex), mirror, rotation);
            if (!includeAir && state.isAir()) {
                continue;
            }

            NbtList blockPos = blockNbt.getListOrEmpty("pos");
            BlockPos relativePos = new BlockPos(
                    blockPos.getInt(0, 0),
                    blockPos.getInt(1, 0),
                    blockPos.getInt(2, 0)
            );
            BlockPos worldPos = StructureTemplate.transform(placementData, relativePos).add(pos);
            placedBlocks.add(new TemplatePlacedBlock(worldPos, state));
        }

        return placedBlocks;
    }

    public static int getSurfaceY(ServerWorld world, int x, int z) {
        world.getChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private static int getSurfaceY(ServerWorld world, BreachedStructureDefinition definition, int x, int z) {
        int surfaceY = getSurfaceY(world, x, z);
        if (!shouldIgnoreTreeSurface(definition)) {
            return surfaceY;
        }

        while (surfaceY > world.getBottomY() + 1
                && world.getBlockState(new BlockPos(x, surfaceY - 1, z)).isIn(BlockTags.LOGS)) {
            surfaceY--;
        }

        return surfaceY;
    }

    private static boolean shouldIgnoreTreeSurface(BreachedStructureDefinition definition) {
        return BreachedStructureDefinitions.isForestTolerantGroundMinorPoi(definition);
    }

    private static Optional<String> getSurfaceRejectionReason(
            BreachedStructureDefinition definition,
            int liquidSamples,
            int sampleCount
    ) {
        return switch (definition.surfaceRequirement()) {
            case ALLOW_WATER -> Optional.empty();
            case AVOID_WATER -> liquidSamples > 0
                    ? Optional.of("rejected " + liquidSamples + " liquid surface samples")
                    : Optional.empty();
            case REQUIRE_WATER -> liquidSamples < sampleCount
                    ? Optional.of("required water at every surface sample but found " + liquidSamples + " of " + sampleCount)
                    : Optional.empty();
            case MOSTLY_WATER -> liquidSamples * 2 < sampleCount
                    ? Optional.of("required mostly water but found " + liquidSamples + " liquid surface samples of " + sampleCount)
                    : Optional.empty();
        };
    }

    private static int getMedianSurfaceY(List<Integer> surfaceHeights) {
        surfaceHeights.sort(Integer::compareTo);
        return surfaceHeights.get(surfaceHeights.size() / 2);
    }

    private static int getMaxAllowedHeightRange(BreachedStructureDefinition definition) {
        if (!definition.needsFlatGround()) {
            return -1;
        }

        return switch (definition.terrainValidation()) {
            case LENIENT -> -1;
            case MEDIUM -> 4;
            case STRICT -> 2;
        };
    }

    private static List<Integer> createSamples(int start, int end, int sampleStep) {
        int step = Math.max(1, sampleStep);
        List<Integer> samples = new ArrayList<>();
        for (int value = start; value <= end; value += step) {
            samples.add(value);
        }

        if (samples.get(samples.size() - 1) != end) {
            samples.add(end);
        }

        return samples;
    }

    private static int toChunkCenterCoordinate(double coordinate) {
        return Math.floorDiv((int) Math.round(coordinate), 16) * 16 + 8;
    }

    private static void applyJigsawAirMarkers(
            ServerWorld world,
            NbtCompound templateNbt,
            BlockPos pos,
            StructurePlacementData placementData
    ) {
        Set<Integer> jigsawStateIds = getJigsawStateIds(templateNbt);
        if (jigsawStateIds.isEmpty()) {
            return;
        }

        int clearedBlocks = 0;
        NbtList blocks = templateNbt.getListOrEmpty("blocks");
        for (int index = 0; index < blocks.size(); index++) {
            NbtCompound blockNbt = blocks.getCompoundOrEmpty(index);
            if (!jigsawStateIds.contains(blockNbt.getInt("state", -1))) {
                continue;
            }

            NbtList markerPos = blockNbt.getListOrEmpty("pos");
            BlockPos relativePos = new BlockPos(
                    markerPos.getInt(0, 0),
                    markerPos.getInt(1, 0),
                    markerPos.getInt(2, 0)
            );
            BlockPos markerWorldPos = StructureTemplate.transform(placementData, relativePos).add(pos);
            world.setBlockState(markerWorldPos, Blocks.AIR.getDefaultState(), BLOCK_UPDATE_FLAGS);
            clearedBlocks++;
        }

        if (clearedBlocks > 0) {
            System.out.println("[Breached] Cleared " + clearedBlocks + " jigsaw air markers.");
        }
    }

    private static void removePlacedTemplateWaterlogging(
            ServerWorld world,
            NbtCompound templateNbt,
            BlockPos pos,
            StructurePlacementData placementData
    ) {
        int fixedBlocks = 0;
        NbtList blocks = templateNbt.getListOrEmpty("blocks");
        for (int index = 0; index < blocks.size(); index++) {
            NbtCompound blockNbt = blocks.getCompoundOrEmpty(index);
            NbtList blockPos = blockNbt.getListOrEmpty("pos");
            BlockPos relativePos = new BlockPos(
                    blockPos.getInt(0, 0),
                    blockPos.getInt(1, 0),
                    blockPos.getInt(2, 0)
            );
            BlockPos worldPos = StructureTemplate.transform(placementData, relativePos).add(pos);
            BlockState state = world.getBlockState(worldPos);
            if (state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED)) {
                world.setBlockState(worldPos, state.with(Properties.WATERLOGGED, false), BLOCK_UPDATE_FLAGS);
                fixedBlocks++;
            }
        }

        if (fixedBlocks > 0) {
            System.out.println("[Breached] Removed waterlogging from " + fixedBlocks + " placed structure blocks.");
        }
    }

    private static Set<Integer> getJigsawStateIds(NbtCompound templateNbt) {
        Set<Integer> stateIds = new HashSet<>();
        NbtList palette = getPrimaryPalette(templateNbt);
        for (int index = 0; index < palette.size(); index++) {
            NbtCompound stateNbt = palette.getCompoundOrEmpty(index);
            if ("minecraft:jigsaw".equals(stateNbt.getString("Name", ""))) {
                stateIds.add(index);
            }
        }

        return stateIds;
    }

    private static List<BlockPos> applyTownhallStaircasePlaceholders(
            ServerWorld world,
            BreachedStructureDefinition definition,
            StructureTemplate template,
            BlockPos pos,
            StructurePlacementData placementData
    ) {
        if (!definition.structureId().equals(BreachedStructureDefinitions.TOWNHALL.structureId())) {
            return List.of();
        }

        List<StructureTemplate.StructureBlockInfo> placeholders = template.getInfosForBlock(
                pos,
                placementData,
                Blocks.RESIN_BRICK_STAIRS,
                true
        );
        if (placeholders.isEmpty()) {
            return List.of();
        }

        List<BlockPos> extensionBlocks = new ArrayList<>();
        Set<BlockPos> placeholderPositions = new HashSet<>();
        for (StructureTemplate.StructureBlockInfo placeholder : placeholders) {
            placeholderPositions.add(placeholder.pos().toImmutable());
        }

        int replacedStairs = 0;
        for (StructureTemplate.StructureBlockInfo placeholder : placeholders) {
            BlockPos stairPos = placeholder.pos();
            BlockState placeholderState = world.getBlockState(stairPos);
            if (!placeholderState.isOf(Blocks.RESIN_BRICK_STAIRS)
                    || !placeholderState.contains(Properties.HORIZONTAL_FACING)) {
                continue;
            }

            world.setBlockState(stairPos, createTownhallQuartzStairState(placeholderState), BLOCK_UPDATE_FLAGS);
            replacedStairs++;
        }

        int extendedStairs = 0;
        for (StructureTemplate.StructureBlockInfo placeholder : placeholders) {
            BlockPos stairPos = placeholder.pos();
            BlockState stairState = world.getBlockState(stairPos);
            if (!stairState.isOf(Blocks.QUARTZ_STAIRS)
                    || !stairState.contains(Properties.HORIZONTAL_FACING)) {
                continue;
            }

            Direction descentDirection = getTownhallStairDescentDirection(stairState);
            if (placeholderPositions.contains(stairPos.offset(descentDirection).down())) {
                continue;
            }

            List<BlockPos> placedStairs = extendTownhallStaircase(world, stairPos, stairState, descentDirection);
            extensionBlocks.addAll(placedStairs);
            extendedStairs += placedStairs.size();
        }

        if (replacedStairs > 0 || extendedStairs > 0) {
            System.out.println("[Breached] Built Town Hall quartz staircase from "
                    + replacedStairs + " resin stair placeholders and "
                    + extendedStairs + " extension blocks.");
        }
        return extensionBlocks;
    }

    private static BlockState createTownhallQuartzStairState(BlockState placeholderState) {
        BlockState quartzState = Blocks.QUARTZ_STAIRS.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, placeholderState.get(Properties.HORIZONTAL_FACING))
                .with(Properties.BLOCK_HALF, BlockHalf.BOTTOM)
                .with(Properties.STAIR_SHAPE, StairShape.STRAIGHT);
        if (quartzState.contains(Properties.WATERLOGGED)) {
            quartzState = quartzState.with(Properties.WATERLOGGED, false);
        }

        return quartzState;
    }

    private static Direction getTownhallStairDescentDirection(BlockState stairState) {
        return stairState.get(Properties.HORIZONTAL_FACING).getOpposite();
    }

    private static List<BlockPos> extendTownhallStaircase(
            ServerWorld world,
            BlockPos topStairPos,
            BlockState stairState,
            Direction descentDirection
    ) {
        List<BlockPos> placedStairs = new ArrayList<>();
        BlockPos cursor = topStairPos.offset(descentDirection).down();
        for (int step = 0; step < TOWNHALL_STAIRCASE_MAX_EXTENSION && cursor.getY() > world.getBottomY(); step++) {
            world.getChunk(Math.floorDiv(cursor.getX(), 16), Math.floorDiv(cursor.getZ(), 16));
            BlockState targetState = world.getBlockState(cursor);
            if (isTownhallStairWater(targetState)) {
                placedStairs.addAll(placeTownhallWaterLanding(world, cursor, descentDirection));
                break;
            }

            if (!canReplaceWithTownhallStair(world, cursor, targetState)) {
                break;
            }

            world.setBlockState(cursor, stairState, BLOCK_UPDATE_FLAGS);
            placedStairs.add(cursor.toImmutable());
            clearTownhallStairHeadroom(world, cursor.up());

            BlockPos belowPos = cursor.down();
            BlockState belowState = world.getBlockState(belowPos);
            if (isTownhallStairGround(world, belowPos, belowState)) {
                break;
            }

            cursor = cursor.offset(descentDirection).down();
        }

        return placedStairs;
    }

    private static List<BlockPos> placeTownhallWaterLanding(ServerWorld world, BlockPos startPos, Direction direction) {
        List<BlockPos> placedBlocks = new ArrayList<>();
        for (int offset = 0; offset < TOWNHALL_WATER_LANDING_LENGTH; offset++) {
            BlockPos landingPos = startPos.offset(direction, offset);
            world.getChunk(Math.floorDiv(landingPos.getX(), 16), Math.floorDiv(landingPos.getZ(), 16));
            BlockState landingState = world.getBlockState(landingPos);
            if (!isTownhallStairWater(landingState) || world.getBlockEntity(landingPos) != null) {
                break;
            }

            world.setBlockState(landingPos, Blocks.QUARTZ_BLOCK.getDefaultState(), BLOCK_UPDATE_FLAGS);
            placedBlocks.add(landingPos.toImmutable());
            clearTownhallStairHeadroom(world, landingPos.up());
        }

        return placedBlocks;
    }

    private static boolean isTownhallStairWater(BlockState state) {
        return state.getFluidState().isIn(FluidTags.WATER);
    }

    private static boolean canReplaceWithTownhallStair(ServerWorld world, BlockPos pos, BlockState state) {
        return world.getBlockEntity(pos) == null
                && (state.isAir()
                || !state.getFluidState().isEmpty()
                || state.getCollisionShape(world, pos).isEmpty()
                || state.isOf(Blocks.RESIN_BRICK_STAIRS)
                || state.isOf(Blocks.QUARTZ_STAIRS));
    }

    private static void clearTownhallStairHeadroom(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (world.getBlockEntity(pos) == null
                && (state.isAir()
                || !state.getFluidState().isEmpty()
                || state.getCollisionShape(world, pos).isEmpty())) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), BLOCK_UPDATE_FLAGS);
        }
    }

    private static boolean isTownhallStairGround(ServerWorld world, BlockPos pos, BlockState state) {
        return !state.getCollisionShape(world, pos).isEmpty();
    }

    private static List<BlockPos> applyTownhallWaterPlaceholders(
            ServerWorld world,
            BreachedStructureDefinition definition,
            StructureTemplate template,
            BlockPos pos,
            StructurePlacementData placementData
    ) {
        if (!definition.structureId().equals(BreachedStructureDefinitions.TOWNHALL.structureId())) {
            return List.of();
        }

        List<StructureTemplate.StructureBlockInfo> placeholders = template.getInfosForBlock(
                pos,
                placementData,
                Blocks.RESIN_BLOCK,
                true
        );
        if (placeholders.isEmpty()) {
            return List.of();
        }

        List<BlockPos> extensionBlocks = new ArrayList<>();
        int waterSources = 0;
        int catchBasins = 0;
        int freezeLights = 0;
        Set<Long> waterColumnKeys = new HashSet<>();
        for (StructureTemplate.StructureBlockInfo placeholder : placeholders) {
            waterColumnKeys.add(townhallColumnKey(placeholder.pos()));
        }

        for (StructureTemplate.StructureBlockInfo placeholder : placeholders) {
            BlockPos waterSourcePos = placeholder.pos();
            if (!world.getBlockState(waterSourcePos).isOf(Blocks.RESIN_BLOCK)
                    || world.getBlockEntity(waterSourcePos) != null) {
                continue;
            }

            world.setBlockState(waterSourcePos, Blocks.WATER.getDefaultState(), BLOCK_UPDATE_FLAGS);
            waterSources++;
            List<BlockPos> placedLights = placeTownhallWaterFreezeLight(world, waterSourcePos);
            extensionBlocks.addAll(placedLights);
            freezeLights += placedLights.size();

            List<BlockPos> catchBasinBlocks = createTownhallWaterCatchBasin(world, waterSourcePos, waterColumnKeys);
            if (!catchBasinBlocks.isEmpty()) {
                extensionBlocks.addAll(catchBasinBlocks);
                catchBasins++;
            }
        }

        if (waterSources > 0 || catchBasins > 0) {
            System.out.println("[Breached] Built Town Hall water features from "
                    + waterSources + " resin block placeholders and "
                    + catchBasins + " catch basins with "
                    + freezeLights + " hidden freeze-prevention lights.");
        }
        return extensionBlocks;
    }

    private static List<BlockPos> createTownhallWaterCatchBasin(
            ServerWorld world,
            BlockPos waterSourcePos,
            Set<Long> waterColumnKeys
    ) {
        Optional<BlockPos> groundSurface = findTownhallGroundSurfaceBelow(world, waterSourcePos);
        if (groundSurface.isEmpty()) {
            return List.of();
        }

        BlockPos catchPos = groundSurface.get();
        if (world.getBlockEntity(catchPos) != null) {
            return List.of();
        }

        List<BlockPos> extensionBlocks = new ArrayList<>();
        clearTownhallWaterDropColumn(world, waterSourcePos, catchPos);
        clearTownhallWaterDropVegetationArea(world, waterSourcePos, catchPos);

        BlockPos floorPos = catchPos.down();
        if (!isTownhallStairGround(world, floorPos, world.getBlockState(floorPos))
                && world.getBlockEntity(floorPos) == null) {
            world.setBlockState(floorPos, Blocks.QUARTZ_BLOCK.getDefaultState(), BLOCK_UPDATE_FLAGS);
            extensionBlocks.add(floorPos.toImmutable());
        }

        for (int xOffset = -TOWNHALL_WATER_CATCH_RADIUS; xOffset <= TOWNHALL_WATER_CATCH_RADIUS; xOffset++) {
            for (int zOffset = -TOWNHALL_WATER_CATCH_RADIUS; zOffset <= TOWNHALL_WATER_CATCH_RADIUS; zOffset++) {
                if (xOffset == 0 && zOffset == 0) {
                    continue;
                }

                BlockPos wallPos = catchPos.add(xOffset, 0, zOffset);
                BlockState wallState = clearTownhallWaterFeatureVegetation(world, wallPos);
                if (waterColumnKeys.contains(townhallColumnKey(wallPos))) {
                    if (world.getBlockEntity(wallPos) == null && wallState.isOf(Blocks.QUARTZ_BLOCK)) {
                        world.setBlockState(wallPos, Blocks.AIR.getDefaultState(), BLOCK_UPDATE_FLAGS);
                    }
                    continue;
                }

                if (world.getBlockEntity(wallPos) == null
                        && (wallState.getCollisionShape(world, wallPos).isEmpty()
                        || !wallState.getFluidState().isEmpty())) {
                    world.setBlockState(wallPos, Blocks.QUARTZ_BLOCK.getDefaultState(), BLOCK_UPDATE_FLAGS);
                    extensionBlocks.add(wallPos.toImmutable());
                }
            }
        }

        world.setBlockState(catchPos, Blocks.WATER.getDefaultState(), BLOCK_UPDATE_FLAGS);
        extensionBlocks.add(catchPos.toImmutable());
        return extensionBlocks;
    }

    private static List<BlockPos> placeTownhallWaterFreezeLight(ServerWorld world, BlockPos waterPos) {
        List<BlockPos> placedLights = new ArrayList<>();
        placeTownhallFreezeLightIfPossible(world, waterPos.up()).ifPresent(placedLights::add);
        for (Direction direction : Direction.Type.HORIZONTAL) {
            placeTownhallFreezeLightIfPossible(world, waterPos.offset(direction)).ifPresent(placedLights::add);
        }

        return placedLights;
    }

    private static Optional<BlockPos> placeTownhallFreezeLightIfPossible(ServerWorld world, BlockPos pos) {
        world.getChunk(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
        BlockState state = world.getBlockState(pos);
        if (world.getBlockEntity(pos) != null || (!state.isAir() && !state.isOf(Blocks.LIGHT))) {
            return Optional.empty();
        }

        world.setBlockState(
                pos,
                Blocks.LIGHT.getDefaultState().with(Properties.LEVEL_15, TOWNHALL_WATER_FREEZE_LIGHT_LEVEL),
                BLOCK_UPDATE_FLAGS
        );
        return state.isOf(Blocks.LIGHT) ? Optional.empty() : Optional.of(pos.toImmutable());
    }

    private static List<BlockPos> applyTownhallUnderfloorSpawnLights(
            ServerWorld world,
            BreachedStructureDefinition definition,
            StructureTemplate template,
            BlockPos origin
    ) {
        if (!definition.structureId().equals(BreachedStructureDefinitions.TOWNHALL.structureId())) {
            return List.of();
        }

        Vec3i size = getRotatedSize(template.getSize(), definition.rotation());
        int minX = origin.getX() + 4;
        int maxX = origin.getX() + Math.max(4, size.getX() - 5);
        int minZ = origin.getZ() + 4;
        int maxZ = origin.getZ() + Math.max(4, size.getZ() - 5);
        int centerX = origin.getX() + size.getX() / 2;
        int centerZ = origin.getZ() + size.getZ() / 2;
        List<BlockPos> placedLights = new ArrayList<>();
        Set<Long> usedColumns = new HashSet<>();

        for (int xOffset = -TOWNHALL_UNDERFLOOR_LIGHT_GRID_RADIUS; xOffset <= TOWNHALL_UNDERFLOOR_LIGHT_GRID_RADIUS; xOffset++) {
            for (int zOffset = -TOWNHALL_UNDERFLOOR_LIGHT_GRID_RADIUS; zOffset <= TOWNHALL_UNDERFLOOR_LIGHT_GRID_RADIUS; zOffset++) {
                int x = clamp(centerX + xOffset * TOWNHALL_UNDERFLOOR_LIGHT_SPACING, minX, maxX);
                int z = clamp(centerZ + zOffset * TOWNHALL_UNDERFLOOR_LIGHT_SPACING, minZ, maxZ);
                long columnKey = townhallColumnKey(new BlockPos(x, 0, z));
                if (!usedColumns.add(columnKey)) {
                    continue;
                }

                Optional<BlockPos> lightPos = findTownhallUnderfloorLightPos(world, x, origin.getY() - 1, z);
                if (lightPos.isPresent()) {
                    placeTownhallFreezeLightIfPossible(world, lightPos.get()).ifPresent(placedLights::add);
                }
            }
        }

        if (!placedLights.isEmpty()) {
            System.out.println("[Breached] Placed " + placedLights.size()
                    + " hidden underfloor spawn lights below Town Hall.");
        }
        return placedLights;
    }

    private static Optional<BlockPos> findTownhallUnderfloorLightPos(ServerWorld world, int x, int startY, int z) {
        Optional<BlockPos> firstAir = Optional.empty();
        int minY = Math.max(world.getBottomY(), startY - TOWNHALL_UNDERFLOOR_LIGHT_SEARCH_DEPTH);
        for (int y = startY; y >= minY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            world.getChunk(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
            BlockState state = world.getBlockState(pos);
            if (world.getBlockEntity(pos) != null || (!state.isAir() && !state.isOf(Blocks.LIGHT))) {
                continue;
            }

            if (firstAir.isEmpty()) {
                firstAir = Optional.of(pos);
            }

            BlockPos belowPos = pos.down();
            BlockState belowState = world.getBlockState(belowPos);
            if (!belowState.getCollisionShape(world, belowPos).isEmpty()) {
                return Optional.of(pos);
            }
        }

        return firstAir;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Optional<BlockPos> findTownhallGroundSurfaceBelow(ServerWorld world, BlockPos waterSourcePos) {
        for (BlockPos.Mutable cursor = waterSourcePos.down().mutableCopy();
             cursor.getY() > world.getBottomY();
             cursor.move(Direction.DOWN)) {
            world.getChunk(Math.floorDiv(cursor.getX(), 16), Math.floorDiv(cursor.getZ(), 16));
            BlockState state = clearTownhallWaterFeatureVegetation(world, cursor);
            if (isTownhallStairGround(world, cursor, state)) {
                return Optional.of(cursor.toImmutable());
            }
        }

        return Optional.empty();
    }

    private static void clearTownhallWaterDropColumn(ServerWorld world, BlockPos waterSourcePos, BlockPos catchPos) {
        int minY = Math.max(catchPos.getY() + 1, world.getBottomY());
        for (int y = waterSourcePos.getY() - 1; y >= minY; y--) {
            BlockPos pos = new BlockPos(waterSourcePos.getX(), y, waterSourcePos.getZ());
            world.getChunk(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
            BlockState state = clearTownhallWaterFeatureVegetation(world, pos);
            if (world.getBlockEntity(pos) == null && (state.isOf(Blocks.QUARTZ_BLOCK) || state.isOf(Blocks.LIGHT))) {
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), BLOCK_UPDATE_FLAGS);
            }
        }
    }

    private static void clearTownhallWaterDropVegetationArea(
            ServerWorld world,
            BlockPos waterSourcePos,
            BlockPos catchPos
    ) {
        int minY = Math.max(catchPos.getY(), world.getBottomY());
        for (int y = waterSourcePos.getY() - 1; y >= minY; y--) {
            for (int xOffset = -TOWNHALL_WATER_CATCH_RADIUS; xOffset <= TOWNHALL_WATER_CATCH_RADIUS; xOffset++) {
                for (int zOffset = -TOWNHALL_WATER_CATCH_RADIUS; zOffset <= TOWNHALL_WATER_CATCH_RADIUS; zOffset++) {
                    BlockPos pos = new BlockPos(
                            waterSourcePos.getX() + xOffset,
                            y,
                            waterSourcePos.getZ() + zOffset
                    );
                    world.getChunk(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
                    clearTownhallWaterFeatureVegetation(world, pos);
                }
            }
        }
    }

    private static BlockState clearTownhallWaterFeatureVegetation(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (world.getBlockEntity(pos) == null && isTownhallWaterFeatureVegetation(state)) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), BLOCK_UPDATE_FLAGS);
            return Blocks.AIR.getDefaultState();
        }

        return state;
    }

    private static boolean isTownhallWaterFeatureVegetation(BlockState state) {
        return state.isIn(BlockTags.LOGS)
                || state.isIn(BlockTags.LEAVES)
                || state.isIn(BlockTags.FLOWERS)
                || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.SHORT_GRASS)
                || state.isOf(Blocks.FERN)
                || state.isOf(Blocks.LARGE_FERN)
                || state.isOf(Blocks.VINE)
                || state.isOf(Blocks.LEAF_LITTER)
                || state.isOf(Blocks.SNOW);
    }

    private static long townhallColumnKey(BlockPos pos) {
        return ((long) pos.getX() << 32) ^ (pos.getZ() & 0xFFFFFFFFL);
    }

    private static NbtList getPrimaryPalette(NbtCompound templateNbt) {
        NbtList palette = templateNbt.getListOrEmpty("palette");
        if (!palette.isEmpty()) {
            return palette;
        }

        NbtList palettes = templateNbt.getListOrEmpty("palettes");
        if (!palettes.isEmpty()) {
            return palettes.getListOrEmpty(0);
        }

        return palette;
    }

    private static List<BlockState> getPrimaryPaletteBlockStates(ServerWorld world, NbtCompound templateNbt) {
        NbtList palette = getPrimaryPalette(templateNbt);
        List<BlockState> states = new ArrayList<>();
        for (int index = 0; index < palette.size(); index++) {
            states.add(NbtHelper.toBlockState(
                    world.getRegistryManager().getOrThrow(RegistryKeys.BLOCK),
                    palette.getCompoundOrEmpty(index)
            ));
        }

        return states;
    }

    private static BlockState normalizePlacedTemplateState(BlockState state, BlockMirror mirror, BlockRotation rotation) {
        state = state.mirror(mirror).rotate(rotation);
        if (state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED)) {
            return state.with(Properties.WATERLOGGED, false);
        }

        return state;
    }

    private static void applyTemplateLootTables(
            ServerWorld world,
            StructureTemplate template,
            BlockPos pos,
            StructurePlacementData placementData
    ) {
        int appliedLootTables = 0;

        for (TemplateLootContainer container : getTemplateLootContainers(world, template, pos, placementData)) {
            if (!(world.getBlockEntity(container.pos()) instanceof LootableInventory lootableInventory)) {
                continue;
            }

            RegistryKey<LootTable> lootTableKey = RegistryKey.of(RegistryKeys.LOOT_TABLE, container.lootTableId());
            lootableInventory.setLootTable(lootTableKey, container.lootTableSeed());
            appliedLootTables++;
        }

        if (appliedLootTables > 0) {
            System.out.println("[Breached] Applied " + appliedLootTables + " template container loot tables.");
        }
    }

    private static List<TemplateLootContainer> getTemplateLootContainers(
            ServerWorld world,
            StructureTemplate template,
            BlockPos pos,
            StructurePlacementData placementData
    ) {
        List<TemplateLootContainer> lootContainers = new ArrayList<>();
        for (Block block : LOOTABLE_TEMPLATE_BLOCKS) {
            for (StructureTemplate.StructureBlockInfo blockInfo : template.getInfosForBlock(pos, placementData, block, true)) {
                NbtCompound nbt = blockInfo.nbt();
                if (nbt == null) {
                    continue;
                }

                Optional<String> lootTableValue = nbt.getString("LootTable");
                if (lootTableValue.isEmpty()) {
                    continue;
                }

                Identifier lootTableId = Identifier.tryParse(lootTableValue.get());
                if (lootTableId == null || !(world.getBlockEntity(blockInfo.pos()) instanceof LootableInventory lootableInventory)) {
                    continue;
                }

                long lootTableSeed = nbt.getLong("LootTableSeed", 0L);
                lootContainers.add(new TemplateLootContainer(blockInfo.pos(), lootTableId, lootTableSeed));
            }
        }

        return lootContainers;
    }

    private static void logPlacement(BreachedStructurePlacement placement, BlockMirror mirror, BlockRotation rotation) {
        System.out.println("[Breached] Placed " + placement.logName()
                + " using " + placement.structureId()
                + " at x " + placement.origin().getX()
                + ", y " + placement.origin().getY()
                + ", z " + placement.origin().getZ()
                + " size " + placement.size().getX() + "x" + placement.size().getY() + "x" + placement.size().getZ()
                + " surface y " + placement.surfaceY()
                + " sampled range " + placement.minSurfaceY() + "-" + placement.maxSurfaceY()
                + " height range " + placement.heightRange()
                + " mirror " + mirror
                + " rotation " + rotation + ".");
    }

    public record RadiusCandidate(int index, int x, int z, int radius, double angle) {
    }

    public record TemplatePlacedBlock(BlockPos pos, BlockState state) {
    }

    public record TemplateLootContainer(BlockPos pos, Identifier lootTableId, long lootTableSeed) {
    }
}
