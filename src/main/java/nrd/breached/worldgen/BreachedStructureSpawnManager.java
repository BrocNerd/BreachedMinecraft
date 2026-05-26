package nrd.breached.worldgen;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Block;
import net.minecraft.inventory.LootableInventory;
import net.minecraft.loot.LootTable;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
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

    public static boolean isInsideProtectionRadius(BreachedStructureDefinition definition, int centerX, int centerZ, BlockPos pos) {
        if (!definition.protectedStructure() || definition.protectionRadius() <= 0) {
            return false;
        }

        long xDistance = pos.getX() - centerX;
        long zDistance = pos.getZ() - centerZ;
        return xDistance * xDistance + zDistance * zDistance <= definition.protectionRadiusSquared();
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
                int surfaceY = getSurfaceY(world, x, z);
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
            case ORIGIN_SURFACE -> getSurfaceY(world, originX, originZ);
            case MEDIAN_SURFACE -> getMedianSurfaceY(surfaceHeights);
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
        removePlacedTemplateWaterlogging(world, templateNbt, pos, placementData);
        applyJigsawAirMarkers(world, templateNbt, pos, placementData);
        applyTemplateLootTables(world, template, pos, placementData);

        BreachedStructurePlacement placement = new BreachedStructurePlacement(
                definition.structureId(),
                definition.logName(),
                pos,
                getRotatedSize(template.getSize(), rotation),
                site.surfaceY(),
                site.minSurfaceY(),
                site.maxSurfaceY(),
                site.heightRange()
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

    public static int getSurfaceY(ServerWorld world, int x, int z) {
        world.getChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
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

    public record TemplateLootContainer(BlockPos pos, Identifier lootTableId, long lootTableSeed) {
    }
}
