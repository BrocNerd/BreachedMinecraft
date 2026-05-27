package nrd.breached.worldgen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.LootableInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.loot.LootTable;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import nrd.breached.config.BreachedConfig;
import nrd.breached.landlock.LandlockClaimManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BreachedStructurePlacementManager {
    private static final int CHUNK_SIZE = 16;
    private static final int PORTAL_ACTIVE_CHECK_RADIUS = 16;
    private static final int REQUIRED_CHUNK_LOADS_PER_TICK = 1;
    private static final int MAX_FORCED_CHUNK_TICKS = 200;
    private static final int MAX_FORCED_RESTOCK_CHUNK_TICKS = 200;
    private static final int MAX_FORCED_MINOR_DESPAWN_CHUNK_TICKS = 200;
    private static final int REMOVE_BLOCK_WITHOUT_DROPS_FLAGS = Block.NOTIFY_LISTENERS | Block.SKIP_DROPS;
    private static final int MINOR_POI_PLAYER_REPLACED_BLOCK_PERCENT = 25;
    private static final int MINOR_POI_CHUNK_INSET = 2;
    private static final Set<PendingStructurePlacement> PENDING_PLACEMENTS = new HashSet<>();
    private static final Set<ForcedStructureChunk> FORCED_CHUNKS = new HashSet<>();
    private static final Set<ForcedRestockChunk> FORCED_RESTOCK_CHUNKS = new HashSet<>();
    private static final Set<ForcedMinorDespawnChunk> FORCED_MINOR_DESPAWN_CHUNKS = new HashSet<>();
    private static final Set<PendingMinorPoiChunk> PENDING_MINOR_POI_CHUNKS = new HashSet<>();

    private BreachedStructurePlacementManager() {
    }

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register(BreachedStructurePlacementManager::handleChunkLoad);
        ServerTickEvents.END_WORLD_TICK.register(BreachedStructurePlacementManager::placePendingStructures);
        registerProtectionEvents();
    }

    private static void handleChunkLoad(ServerWorld world, WorldChunk chunk) {
        enqueuePlannedStructures(world, chunk);
        ChunkPos chunkPos = chunk.getPos();
        PENDING_MINOR_POI_CHUNKS.add(new PendingMinorPoiChunk(world.getSeed(), chunkPos.x, chunkPos.z));
    }

    private static void enqueuePlannedStructures(ServerWorld world, WorldChunk chunk) {
        BreachedStructurePlacementState state = BreachedStructurePlacementState.get(world.getServer());
        migrateLegacyCentralSpawnState(world, state);
        reservePlannedStructures(world, state);

        ChunkPos chunkPos = chunk.getPos();
        for (BreachedStructureDefinition definition : BreachedStructureDefinitions.PLANNED_STRUCTURES) {
            BreachedStructureDefinition activeDefinition = getActiveDefinition(world, definition);
            if (!world.getRegistryKey().equals(activeDefinition.requiredDimension())) {
                continue;
            }

            String structureKey = BreachedStructureDefinitions.key(activeDefinition);
            if (countPlacedStructures(state, structureKey) >= activeDefinition.countPerWorld()) {
                continue;
            }

            for (BreachedStructureSpawnManager.RadiusCandidate candidate : generatePlannedCandidates(world, activeDefinition)) {
                String placementKey = placementKey(structureKey, candidate.index());
                if (state.hasPlacement(placementKey)
                        || !state.hasReservation(placementKey)
                        || state.hasFailedCandidate(structureKey, candidate.index())) {
                    continue;
                }

                if (chunkPos.x == Math.floorDiv(candidate.x(), 16) && chunkPos.z == Math.floorDiv(candidate.z(), 16)) {
                    PENDING_PLACEMENTS.add(new PendingStructurePlacement(world.getSeed(), structureKey, placementKey, candidate));
                }
            }
        }
    }

    private static void placePendingStructures(ServerWorld world) {
        BreachedStructurePlacementState state = BreachedStructurePlacementState.get(world.getServer());
        migrateLegacyCentralSpawnState(world, state);
        reservePlannedStructures(world, state);
        forceLoadRequiredReservedChunks(world, state);
        releaseCompletedForcedChunks(world, state);
        despawnAndRetireMinorPois(world, state);
        releaseExpiredForcedMinorDespawnChunks(world, state);
        tryPlacePendingMinorPoiChunks(world, state);
        tryPlaceMinorPoisNearPlayers(world, state);
        restockMajorStructureLoot(world, state);
        releaseExpiredForcedRestockChunks(world);

        if (PENDING_PLACEMENTS.isEmpty()) {
            return;
        }

        for (BreachedStructureDefinition definition : BreachedStructureDefinitions.PLANNED_STRUCTURES) {
            BreachedStructureDefinition activeDefinition = getActiveDefinition(world, definition);
            if (!world.getRegistryKey().equals(activeDefinition.requiredDimension())) {
                continue;
            }

            String structureKey = BreachedStructureDefinitions.key(activeDefinition);
            if (countPlacedStructures(state, structureKey) >= activeDefinition.countPerWorld()) {
                PENDING_PLACEMENTS.removeIf(placement -> placement.worldSeed() == world.getSeed() && placement.structureKey().equals(structureKey));
                continue;
            }

            PlannedCandidateEvaluation bestEvaluation = null;
            int evaluatedCandidates = 0;
            boolean handledPlacement = false;

            for (BreachedStructureSpawnManager.RadiusCandidate candidate : generatePlannedCandidates(world, activeDefinition)) {
                String placementKey = placementKey(structureKey, candidate.index());
                PendingStructurePlacement pendingPlacement = new PendingStructurePlacement(world.getSeed(), structureKey, placementKey, candidate);
                if (!PENDING_PLACEMENTS.contains(pendingPlacement)) {
                    continue;
                }

                if (state.hasPlacement(placementKey)
                        || !state.hasReservation(placementKey)
                        || state.hasFailedCandidate(structureKey, candidate.index())) {
                    PENDING_PLACEMENTS.remove(pendingPlacement);
                    continue;
                }

                PlannedCandidateEvaluation evaluation = evaluatePlannedCandidate(
                        world,
                        state,
                        activeDefinition,
                        structureKey,
                        pendingPlacement
                );
                if (evaluation.result() == PlacementAttemptResult.NOT_READY) {
                    continue;
                }

                PENDING_PLACEMENTS.remove(pendingPlacement);
                if (evaluation.result() == PlacementAttemptResult.HANDLED) {
                    if (countPlacedStructures(state, structureKey) >= activeDefinition.countPerWorld()) {
                        PENDING_PLACEMENTS.removeIf(pending -> pending.worldSeed() == world.getSeed() && pending.structureKey().equals(structureKey));
                    }
                    handledPlacement = true;
                    break;
                }

                if (evaluation.result() == PlacementAttemptResult.READY) {
                    evaluatedCandidates++;
                    if (bestEvaluation == null || comparePlannedCandidateEvaluations(evaluation, bestEvaluation) < 0) {
                        bestEvaluation = evaluation;
                    }

                    if (evaluatedCandidates >= BreachedConfig.get().plannedCandidateEvaluationsPerStructureTick) {
                        break;
                    }
                }
            }

            if (handledPlacement) {
                continue;
            }

            if (bestEvaluation != null) {
                placeEvaluatedPlannedCandidate(world, state, activeDefinition, bestEvaluation);
                if (countPlacedStructures(state, structureKey) >= activeDefinition.countPerWorld()) {
                    PENDING_PLACEMENTS.removeIf(pending -> pending.worldSeed() == world.getSeed() && pending.structureKey().equals(structureKey));
                }
                continue;
            }

            if (!hasRemainingCandidates(world, state, activeDefinition, structureKey)) {
                int plannedCandidateCount = generatePlannedCandidates(world, activeDefinition).size();
                System.out.println("[Breached] Failed to place " + activeDefinition.logName()
                        + " after rejecting " + state.failedCandidateCount(structureKey)
                        + " of " + plannedCandidateCount
                        + " planned candidates for " + structureKey + ".");
            }
        }
    }

    private static MinorPoiAttemptResult tryPlaceMinorPoisInChunk(
            ServerWorld world,
            BreachedStructurePlacementState state,
            ChunkPos chunkPos
    ) {
        if (!world.isChunkLoaded(chunkPos.x, chunkPos.z)) {
            return MinorPoiAttemptResult.NOT_READY;
        }

        if (countPlacedMinorPois(state) >= getMinorPoiBudget(world)) {
            return MinorPoiAttemptResult.FAILED;
        }

        java.util.Random random = createMinorPoiChunkRandom(world.getSeed(), chunkPos);
        if (random.nextInt(BreachedConfig.get().minorPoi.chunkChanceDivisor) != 0) {
            return MinorPoiAttemptResult.FAILED;
        }

        return placeBestMinorPoiInChunk(world, state, chunkPos, random);
    }

    private static void tryPlacePendingMinorPoiChunks(ServerWorld world, BreachedStructurePlacementState state) {
        int attempted = 0;
        for (PendingMinorPoiChunk pendingChunk : new HashSet<>(PENDING_MINOR_POI_CHUNKS)) {
            if (pendingChunk.worldSeed() != world.getSeed()) {
                continue;
            }

            if (attempted >= BreachedConfig.get().minorPoi.pendingChunksPerTick) {
                break;
            }

            ChunkPos chunkPos = new ChunkPos(pendingChunk.chunkX(), pendingChunk.chunkZ());
            MinorPoiAttemptResult result = tryPlaceMinorPoisInChunk(world, state, chunkPos);
            if (result != MinorPoiAttemptResult.NOT_READY) {
                PENDING_MINOR_POI_CHUNKS.remove(pendingChunk);
            }

            attempted++;
        }
    }

    private static void tryPlaceMinorPoisNearPlayers(ServerWorld world, BreachedStructurePlacementState state) {
        BreachedConfig.MinorPoiSettings minorPoiConfig = BreachedConfig.get().minorPoi;
        if (world.getTime() % minorPoiConfig.playerScanIntervalTicks != 0 || world.getPlayers().isEmpty()) {
            return;
        }

        for (PlayerEntity player : world.getPlayers()) {
            ChunkPos playerChunk = player.getChunkPos();
            for (int xOffset = -minorPoiConfig.playerScanRadiusChunks; xOffset <= minorPoiConfig.playerScanRadiusChunks; xOffset++) {
                for (int zOffset = -minorPoiConfig.playerScanRadiusChunks; zOffset <= minorPoiConfig.playerScanRadiusChunks; zOffset++) {
                    ChunkPos chunkPos = new ChunkPos(playerChunk.x + xOffset, playerChunk.z + zOffset);
                    if (!world.isChunkLoaded(chunkPos.x, chunkPos.z)) {
                        continue;
                    }

                    MinorPoiAttemptResult result = tryPlaceMinorPoisInChunk(world, state, chunkPos);
                    if (result == MinorPoiAttemptResult.PLACED) {
                        return;
                    }
                }
            }
        }
    }

    private static MinorPoiAttemptResult placeBestMinorPoiInChunk(
            ServerWorld world,
            BreachedStructurePlacementState state,
            ChunkPos chunkPos,
            java.util.Random random
    ) {
        int candidateIndex = minorCandidateIndex(chunkPos);
        List<MinorPoiLocalCandidate> localCandidates = generateMinorPoiLocalCandidates(chunkPos, random);
        MinorPoiPlacementChoice bestChoice = null;
        boolean skippedUnloadedFootprint = false;

        for (BreachedStructureDefinition definition : BreachedStructureDefinitions.MINOR_POI_STRUCTURES) {
            BreachedStructureDefinition activeDefinition = getActiveDefinition(world, definition);
            if (!world.getRegistryKey().equals(activeDefinition.requiredDimension())) {
                continue;
            }

            String structureKey = BreachedStructureDefinitions.key(activeDefinition);
            if (activeDefinition.countPerWorld() > 0 && countPlacedStructures(state, structureKey) >= activeDefinition.countPerWorld()) {
                continue;
            }

            String placementKey = placementKey(structureKey, candidateIndex);
            if (state.hasPlacement(placementKey) || state.hasFailedCandidate(structureKey, candidateIndex)) {
                continue;
            }

            Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, activeDefinition);
            if (template.isEmpty()) {
                continue;
            }

            Vec3i footprintSize = BreachedStructureSpawnManager.getRotatedSize(template.get().getSize(), activeDefinition.rotation());
            boolean foundCandidateForStructure = false;
            boolean skippedFullSpreadCellForStructure = false;
            boolean skippedSpacingBlockedForStructure = false;

            for (MinorPoiLocalCandidate localCandidate : localCandidates) {
                MinorPoiPlacementChoice choice = evaluateMinorPoiChoice(
                        world,
                        state,
                        activeDefinition,
                        placementKey,
                        candidateIndex,
                        template.get(),
                        footprintSize,
                        localCandidate
                );

                if (choice == MinorPoiPlacementChoice.NOT_READY) {
                    skippedUnloadedFootprint = true;
                    continue;
                }

                if (choice == MinorPoiPlacementChoice.SPACING_BLOCKED) {
                    skippedSpacingBlockedForStructure = true;
                    continue;
                }

                if (choice == MinorPoiPlacementChoice.SPREAD_CELL_FULL) {
                    skippedFullSpreadCellForStructure = true;
                    continue;
                }

                if (choice == null) {
                    continue;
                }

                foundCandidateForStructure = true;
                if (bestChoice == null || compareMinorPoiPlacementChoices(choice, bestChoice) < 0) {
                    bestChoice = choice;
                }
            }

            if (!foundCandidateForStructure
                    && !skippedUnloadedFootprint
                    && !skippedFullSpreadCellForStructure
                    && !skippedSpacingBlockedForStructure) {
                state.markCandidateFailed(structureKey, candidateIndex);
            }
        }

        if (bestChoice == null) {
            return skippedUnloadedFootprint ? MinorPoiAttemptResult.NOT_READY : MinorPoiAttemptResult.FAILED;
        }

        int originY = getPlacementOriginY(bestChoice.definition(), bestChoice.site());
        BlockPos origin = new BlockPos(bestChoice.originX(), originY, bestChoice.originZ());
        List<BreachedStructurePlacementState.SavedBlockSnapshot> restoreBlocks = captureOriginalMinorPoiBlocks(
                world,
                bestChoice.definition(),
                bestChoice.template(),
                origin,
                BreachedStructureSpawnManager.getRotatedSize(bestChoice.template().getSize(), bestChoice.definition().rotation())
        );
        BreachedStructurePlacement placement = BreachedStructureSpawnManager.place(
                world,
                bestChoice.definition(),
                bestChoice.template(),
                bestChoice.originX(),
                originY,
                bestChoice.originZ(),
                bestChoice.definition().mirror(),
                bestChoice.definition().rotation(),
                bestChoice.site()
        );
        List<BlockPos> cleanupBlocks = BreachedStructureSupportGenerator.generate(world, bestChoice.definition(), placement);
        clearNaturalBlocksAboveStructure(world, bestChoice.definition(), placement);
        state.markPlacedMinor(
                bestChoice.placementKey(),
                placement,
                world.getTime(),
                createNextMinorPoiDespawnTime(world, bestChoice.placementKey()),
                cleanupBlocks,
                restoreBlocks
        );
        System.out.println("[Breached] Placed minor POI " + bestChoice.definition().logName()
                + " from chunk " + chunkPos.x + ", " + chunkPos.z
                + " at x " + placement.origin().getX()
                + ", y " + placement.origin().getY()
                + ", z " + placement.origin().getZ()
                + " score " + Math.round(bestChoice.score()) + ".");
        return MinorPoiAttemptResult.PLACED;
    }

    private static MinorPoiPlacementChoice evaluateMinorPoiChoice(
            ServerWorld world,
            BreachedStructurePlacementState state,
            BreachedStructureDefinition definition,
            String placementKey,
            int candidateIndex,
            StructureTemplate template,
            Vec3i footprintSize,
            MinorPoiLocalCandidate localCandidate
    ) {
        if (!isInsideDefinitionRadius(definition, localCandidate.x(), localCandidate.z())) {
            return null;
        }

        BreachedStructureSpawnManager.RadiusCandidate candidate = new BreachedStructureSpawnManager.RadiusCandidate(
                candidateIndex,
                localCandidate.x(),
                localCandidate.z(),
                distanceFromDefinitionCenter(definition, localCandidate.x(), localCandidate.z()),
                0.0D
        );

        int originX = getPlacementOriginX(definition, candidate);
        int originZ = getPlacementOriginZ(definition, candidate);
        int centerX = originX + footprintSize.getX() / 2;
        int centerZ = originZ + footprintSize.getZ() / 2;
        if (isMinorPoiSpreadCellFull(state, centerX, centerZ)) {
            return MinorPoiPlacementChoice.SPREAD_CELL_FULL;
        }

        SpacingEvaluation minorSpacing = evaluateMinorPoiCenterSpacing(state, definition, placementKey, centerX, centerZ);
        if (!minorSpacing.accepted()) {
            return MinorPoiPlacementChoice.SPACING_BLOCKED;
        }

        if (!isFootprintLoaded(world, originX, originZ, footprintSize)) {
            return MinorPoiPlacementChoice.NOT_READY;
        }

        BreachedStructureSite site = BreachedStructureSpawnManager.evaluateSite(
                world,
                definition,
                template,
                originX,
                originZ,
                candidate.radius()
        );
        if (!site.accepted()) {
            return null;
        }

        ObstructionEvaluation obstruction = evaluateObstruction(world, definition, footprintSize, site);
        if (!obstruction.accepted()) {
            return null;
        }

        return new MinorPoiPlacementChoice(
                definition,
                placementKey,
                template,
                candidate,
                site,
                originX,
                originZ,
                site.score() + obstruction.penalty() + minorSpacing.penalty()
        );
    }

    private static List<MinorPoiLocalCandidate> generateMinorPoiLocalCandidates(ChunkPos chunkPos, java.util.Random random) {
        List<MinorPoiLocalCandidate> candidates = new ArrayList<>();
        for (int attempt = 0; attempt < BreachedConfig.get().minorPoi.localCandidatesPerChunk; attempt++) {
            int x = chunkPos.x * CHUNK_SIZE + MINOR_POI_CHUNK_INSET + random.nextInt(CHUNK_SIZE - (MINOR_POI_CHUNK_INSET * 2));
            int z = chunkPos.z * CHUNK_SIZE + MINOR_POI_CHUNK_INSET + random.nextInt(CHUNK_SIZE - (MINOR_POI_CHUNK_INSET * 2));
            candidates.add(new MinorPoiLocalCandidate(x, z));
        }

        return candidates;
    }

    private static int compareMinorPoiPlacementChoices(MinorPoiPlacementChoice left, MinorPoiPlacementChoice right) {
        int scoreCompare = Double.compare(left.score(), right.score());
        if (scoreCompare != 0) {
            return scoreCompare;
        }

        return Integer.compare(left.candidate().index(), right.candidate().index());
    }

    private static List<BreachedStructurePlacementState.SavedBlockSnapshot> captureOriginalMinorPoiBlocks(
            ServerWorld world,
            BreachedStructureDefinition definition,
            StructureTemplate template,
            BlockPos origin,
            Vec3i footprintSize
    ) {
        Map<BlockPos, BreachedStructurePlacementState.SavedBlockSnapshot> snapshots = new LinkedHashMap<>();
        for (BreachedStructureSpawnManager.TemplatePlacedBlock expectedBlock : BreachedStructureSpawnManager.getTemplatePlacedBlocks(
                world,
                definition,
                template,
                origin,
                definition.mirror(),
                definition.rotation()
        )) {
            captureOriginalBlockSnapshot(world, expectedBlock.pos(), snapshots);
        }

        captureOriginalSupportBlocks(world, definition, origin, footprintSize, snapshots);
        captureOriginalClearanceBlocks(world, definition, origin, footprintSize, snapshots);
        return List.copyOf(snapshots.values());
    }

    private static void captureOriginalSupportBlocks(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BlockPos origin,
            Vec3i footprintSize,
            Map<BlockPos, BreachedStructurePlacementState.SavedBlockSnapshot> snapshots
    ) {
        if (definition.supportMode() == BreachedStructureDefinition.SupportMode.NONE || definition.supportMaxDepth() <= 0) {
            return;
        }

        int minY = Math.max(world.getBottomY(), origin.getY() - definition.supportMaxDepth());
        for (int x = origin.getX(); x < origin.getX() + footprintSize.getX(); x++) {
            for (int z = origin.getZ(); z < origin.getZ() + footprintSize.getZ(); z++) {
                for (int y = origin.getY() - 1; y >= minY; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!state.isOf(Blocks.WATER) && !state.isAir()) {
                        break;
                    }

                    captureOriginalBlockSnapshot(world, pos, snapshots);
                }
            }
        }
    }

    private static void captureOriginalClearanceBlocks(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BlockPos origin,
            Vec3i footprintSize,
            Map<BlockPos, BreachedStructurePlacementState.SavedBlockSnapshot> snapshots
    ) {
        if (!definition.canClearBlocks()) {
            return;
        }

        int maxY = Math.min(world.getTopYInclusive(), origin.getY() + footprintSize.getY() + 5);
        for (int x = origin.getX(); x < origin.getX() + footprintSize.getX(); x++) {
            for (int z = origin.getZ(); z < origin.getZ() + footprintSize.getZ(); z++) {
                for (int y = origin.getY(); y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isNaturalSurfaceObstruction(world.getBlockState(pos))) {
                        captureOriginalBlockSnapshot(world, pos, snapshots);
                    }
                }
            }
        }
    }

    private static void captureOriginalBlockSnapshot(
            ServerWorld world,
            BlockPos pos,
            Map<BlockPos, BreachedStructurePlacementState.SavedBlockSnapshot> snapshots
    ) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || world.getBlockEntity(pos) != null) {
            return;
        }

        BlockPos immutablePos = pos.toImmutable();
        snapshots.putIfAbsent(
                immutablePos,
                new BreachedStructurePlacementState.SavedBlockSnapshot(immutablePos, NbtHelper.fromBlockState(state))
        );
    }

    private static void clearNaturalBlocksAboveStructure(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructurePlacement placement
    ) {
        if (!definition.canClearBlocks()) {
            return;
        }

        BlockPos origin = placement.origin();
        int minY = origin.getY();
        int structureMaxY = Math.min(world.getTopYInclusive(), origin.getY() + placement.size().getY() - 1);
        int clearedBlocks = 0;

        for (int x = origin.getX(); x < origin.getX() + placement.size().getX(); x++) {
            for (int z = origin.getZ(); z < origin.getZ() + placement.size().getZ(); z++) {
                int highestStructureY = Integer.MIN_VALUE;
                for (int y = minY; y <= structureMaxY; y++) {
                    BlockState state = world.getBlockState(new BlockPos(x, y, z));
                    if (isStructureClearanceAnchor(state)) {
                        highestStructureY = y;
                    }
                }

                if (highestStructureY == Integer.MIN_VALUE) {
                    continue;
                }

                int clearMaxY = Math.min(world.getTopYInclusive(), highestStructureY + 5);
                for (int y = highestStructureY + 1; y <= clearMaxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!isNaturalSurfaceObstruction(state)) {
                        continue;
                    }

                    removeBlockWithoutDrops(world, pos);
                    clearedBlocks++;
                }
            }
        }

        if (clearedBlocks > 0) {
            System.out.println("[Breached] Cleared " + clearedBlocks
                    + " natural surface obstruction blocks above placed blocks in " + placement.logName() + ".");
        }
    }

    private static void despawnAndRetireMinorPois(ServerWorld world, BreachedStructurePlacementState state) {
        BreachedConfig.MinorPoiSettings minorPoiConfig = BreachedConfig.get().minorPoi;
        if (!minorPoiConfig.lifecycleEnabled
                || world.getTime() % minorPoiConfig.despawnScanIntervalTicks != 0) {
            return;
        }

        int dueDespawnChecks = 0;
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> entry : new ArrayList<>(state.placements())) {
            BreachedStructurePlacementState.SavedPlacement placement = entry.getValue();
            if (!placement.active()) {
                continue;
            }

            Optional<BreachedStructureDefinition> definition = getActiveDefinition(world, structureKey(entry.getKey()));
            if (definition.isEmpty()
                    || definition.get().spacingGroup() != BreachedStructureDefinition.SpacingGroup.MINOR
                    || !world.getRegistryKey().equals(definition.get().requiredDimension())) {
                continue;
            }

            if (placement.nextMinorDespawnTime() <= placement.placedTime()) {
                state.scheduleMinorDespawn(entry.getKey(), createNextMinorPoiDespawnTime(world, entry.getKey()));
                continue;
            }

            long earliestConfiguredDespawnTime = placement.placedTime() + minorPoiConfig.minDespawnIntervalTicks;
            if (placement.nextMinorDespawnTime() < earliestConfiguredDespawnTime) {
                state.scheduleMinorDespawn(entry.getKey(), earliestConfiguredDespawnTime);
                placement = state.getPlacement(entry.getKey()).orElse(placement);
            }

            long latestConfiguredDespawnTime = placement.placedTime() + minorPoiConfig.maxDespawnIntervalTicks;
            if (placement.nextMinorDespawnTime() > latestConfiguredDespawnTime) {
                long rescheduledTime = Math.max(world.getTime(), latestConfiguredDespawnTime);
                state.scheduleMinorDespawn(entry.getKey(), rescheduledTime);
                placement = state.getPlacement(entry.getKey()).orElse(placement);
            }

            boolean dueForDespawn = world.getTime() >= placement.nextMinorDespawnTime();
            if (dueForDespawn) {
                if (dueDespawnChecks >= minorPoiConfig.despawnChecksPerScan) {
                    continue;
                }

                dueDespawnChecks++;
            }
            tryDespawnAndRetireMinorPoi(world, state, entry.getKey(), definition.get(), placement, dueForDespawn);
        }
    }

    private static void tryDespawnAndRetireMinorPoi(
            ServerWorld world,
            BreachedStructurePlacementState state,
            String placementKey,
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement,
            boolean dueForDespawn
    ) {
        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, definition);
        if (template.isEmpty()) {
            if (dueForDespawn) {
                state.scheduleMinorDespawn(placementKey, createMinorPoiDespawnRetryTime(world));
            }
            return;
        }

        Vec3i footprintSize = BreachedStructureSpawnManager.getRotatedSize(template.get().getSize(), definition.rotation());
        if (placement.sizeX() != footprintSize.getX()
                || placement.sizeY() != footprintSize.getY()
                || placement.sizeZ() != footprintSize.getZ()) {
            state.setMinorFootprintSize(placementKey, footprintSize.getX(), footprintSize.getY(), footprintSize.getZ());
        }

        if (!isFootprintLoaded(world, placement.originX(), placement.originZ(), footprintSize)) {
            if (dueForDespawn) {
                forceLoadMissingMinorDespawnFootprintChunks(world, placementKey, placement.originX(), placement.originZ(), footprintSize);
            }
            return;
        }

        List<BreachedStructureSpawnManager.TemplatePlacedBlock> expectedBlocks = BreachedStructureSpawnManager.getTemplatePlacedBlocks(
                world,
                definition,
                template.get(),
                new BlockPos(placement.originX(), placement.originY(), placement.originZ()),
                definition.mirror(),
                definition.rotation()
        );
        List<BreachedStructureSpawnManager.TemplatePlacedBlock> footprintBlocks = BreachedStructureSpawnManager.getTemplateFootprintBlocks(
                world,
                definition,
                template.get(),
                new BlockPos(placement.originX(), placement.originY(), placement.originZ()),
                definition.mirror(),
                definition.rotation()
        );
        MinorPoiBlockEvaluation blockEvaluation = evaluateMinorPoiBlocks(world, expectedBlocks, footprintBlocks);
        boolean footprintClaimed = isMinorPoiFootprintClaimed(world, placement, footprintSize);
        Optional<String> playerTouchReason = getMinorPoiPlayerTouchReason(placement, blockEvaluation, footprintClaimed);
        if (playerTouchReason.isPresent()) {
            retirePlayerTouchedMinorPoi(world, state, placementKey, placement, blockEvaluation, playerTouchReason.get());
            return;
        }

        if (!dueForDespawn) {
            return;
        }

        int removedBlocks = 0;
        removedBlocks = removeMatchingMinorPoiBlocks(world, expectedBlocks);
        removedBlocks += removeMatchingCleanupBlocks(world, definition, placement.cleanupBlocks());
        int restoredBlocks = restoreOriginalMinorPoiBlocks(world, placement.restoreBlocks());

        state.retirePlacement(placementKey, world.getTime());
        releaseForcedMinorDespawnChunks(world, placementKey);
        System.out.println("[Breached] Retired minor POI " + placementKey
                + " at x " + placement.originX()
                + ", z " + placement.originZ()
                + " with " + blockEvaluation.intactPercent()
                + "% of template blocks intact; removed " + removedBlocks
                + " matching blocks and restored " + restoredBlocks
                + " original blocks.");
    }

    private static MinorPoiBlockEvaluation evaluateMinorPoiBlocks(
            ServerWorld world,
            List<BreachedStructureSpawnManager.TemplatePlacedBlock> expectedBlocks,
            List<BreachedStructureSpawnManager.TemplatePlacedBlock> footprintBlocks
    ) {
        int matchingBlocks = 0;
        int replacedBlocks = 0;
        boolean hasInventoryContents = false;
        Set<BlockPos> expectedBlockPositions = new HashSet<>();

        for (BreachedStructureSpawnManager.TemplatePlacedBlock expectedBlock : expectedBlocks) {
            expectedBlockPositions.add(expectedBlock.pos());
            BlockState actualState = world.getBlockState(expectedBlock.pos());
            if (isMatchingMinorPoiBlock(actualState, expectedBlock.state())) {
                matchingBlocks++;
            } else if (isPlayerReplacementState(actualState)) {
                replacedBlocks++;
            }

            if (hasPlayerInventoryContents(world.getBlockEntity(expectedBlock.pos()))) {
                hasInventoryContents = true;
            }
        }

        for (BreachedStructureSpawnManager.TemplatePlacedBlock footprintBlock : footprintBlocks) {
            if (!footprintBlock.state().isAir() || expectedBlockPositions.contains(footprintBlock.pos())) {
                continue;
            }

            if (hasPlayerInventoryContents(world.getBlockEntity(footprintBlock.pos()))) {
                hasInventoryContents = true;
            }
        }

        return new MinorPoiBlockEvaluation(expectedBlocks.size(), matchingBlocks, replacedBlocks, hasInventoryContents);
    }

    private static Optional<String> getMinorPoiPlayerTouchReason(
            BreachedStructurePlacementState.SavedPlacement placement,
            MinorPoiBlockEvaluation blockEvaluation,
            boolean footprintClaimed
    ) {
        if (placement.playerTouched()) {
            return Optional.of("already marked player-touched");
        }

        if (footprintClaimed) {
            return Optional.of("a landlock claim overlaps the footprint");
        }

        if (blockEvaluation.replacedBlockPercentAtLeast(MINOR_POI_PLAYER_REPLACED_BLOCK_PERCENT)) {
            return Optional.of(blockEvaluation.replacedBlockPercent() + "% of structure blocks were replaced");
        }

        return Optional.empty();
    }

    private static void retirePlayerTouchedMinorPoi(
            ServerWorld world,
            BreachedStructurePlacementState state,
            String placementKey,
            BreachedStructurePlacementState.SavedPlacement placement,
            MinorPoiBlockEvaluation blockEvaluation,
            String reason
    ) {
        state.markPlayerTouched(placementKey);
        state.retirePlacement(placementKey, world.getTime());
        releaseForcedMinorDespawnChunks(world, placementKey);
        System.out.println("[Breached] Retired player-touched minor POI " + placementKey
                + " at x " + placement.originX()
                + ", z " + placement.originZ()
                + "; reason: " + reason
                + "; intact " + blockEvaluation.intactPercent()
                + "%, replaced " + blockEvaluation.replacedBlockPercent()
                + "%; no blocks removed.");
    }

    private static int removeMatchingMinorPoiBlocks(
            ServerWorld world,
            List<BreachedStructureSpawnManager.TemplatePlacedBlock> expectedBlocks
    ) {
        int removedBlocks = 0;
        for (BreachedStructureSpawnManager.TemplatePlacedBlock expectedBlock : expectedBlocks) {
            if (!isMatchingMinorPoiBlock(world.getBlockState(expectedBlock.pos()), expectedBlock.state())) {
                continue;
            }

            if (hasPlayerInventoryContents(world.getBlockEntity(expectedBlock.pos()))) {
                continue;
            }

            removeBlockWithoutDrops(world, expectedBlock.pos());
            removedBlocks++;
        }

        return removedBlocks;
    }

    private static boolean isMatchingMinorPoiBlock(BlockState actualState, BlockState expectedState) {
        return actualState.isOf(expectedState.getBlock());
    }

    private static boolean isPlayerReplacementState(BlockState state) {
        return !state.isAir()
                && !state.isOf(Blocks.WATER)
                && !state.isOf(Blocks.LAVA);
    }

    private static void removeBlockWithoutDrops(ServerWorld world, BlockPos pos) {
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), REMOVE_BLOCK_WITHOUT_DROPS_FLAGS);
    }

    private static int removeMatchingCleanupBlocks(
            ServerWorld world,
            BreachedStructureDefinition definition,
            List<BlockPos> cleanupBlocks
    ) {
        if (cleanupBlocks.isEmpty() || definition.supportBlock().equals(Blocks.AIR)) {
            return 0;
        }

        int removedBlocks = 0;
        BlockState cleanupState = definition.supportBlock().getDefaultState();
        for (BlockPos cleanupBlock : cleanupBlocks) {
            if (!world.getBlockState(cleanupBlock).equals(cleanupState)) {
                continue;
            }

            removeBlockWithoutDrops(world, cleanupBlock);
            removedBlocks++;
        }

        return removedBlocks;
    }

    private static int restoreOriginalMinorPoiBlocks(
            ServerWorld world,
            List<BreachedStructurePlacementState.SavedBlockSnapshot> restoreBlocks
    ) {
        int restoredBlocks = 0;
        for (BreachedStructurePlacementState.SavedBlockSnapshot restoreBlock : restoreBlocks) {
            BlockPos pos = restoreBlock.pos();
            if (!world.getBlockState(pos).isAir() || world.getBlockEntity(pos) != null) {
                continue;
            }

            BlockState originalState = NbtHelper.toBlockState(
                    world.getRegistryManager().getOrThrow(RegistryKeys.BLOCK),
                    restoreBlock.stateNbt()
            );
            if (originalState.isAir()) {
                continue;
            }

            world.setBlockState(pos, originalState, REMOVE_BLOCK_WITHOUT_DROPS_FLAGS);
            restoredBlocks++;
        }

        return restoredBlocks;
    }

    private static boolean hasPlayerInventoryContents(BlockEntity blockEntity) {
        if (!(blockEntity instanceof Inventory inventory)) {
            return false;
        }

        if (blockEntity instanceof LootableInventory lootableInventory && lootableInventory.getLootTable() != null) {
            return false;
        }

        return !isInventoryEmpty(inventory);
    }

    private static boolean isInventoryEmpty(Inventory inventory) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!inventory.getStack(slot).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private static boolean isMinorPoiFootprintClaimed(
            ServerWorld world,
            BreachedStructurePlacementState.SavedPlacement placement,
            Vec3i footprintSize
    ) {
        for (int x = placement.originX(); x < placement.originX() + footprintSize.getX(); x++) {
            for (int z = placement.originZ(); z < placement.originZ() + footprintSize.getZ(); z++) {
                if (LandlockClaimManager.isInsideAnyClaim(world, new BlockPos(x, placement.originY(), z))) {
                    return true;
                }
            }
        }

        return false;
    }

    private static void forceLoadMissingMinorDespawnFootprintChunks(
            ServerWorld world,
            String placementKey,
            int originX,
            int originZ,
            Vec3i footprintSize
    ) {
        int minChunkX = Math.floorDiv(originX, CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(originZ, CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(originX + footprintSize.getX() - 1, CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(originZ + footprintSize.getZ() - 1, CHUNK_SIZE);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                forceLoadMinorDespawnChunk(world, placementKey, new ChunkPos(chunkX, chunkZ));
            }
        }
    }

    private static void forceLoadMinorDespawnChunk(ServerWorld world, String placementKey, ChunkPos chunkPos) {
        if (world.isChunkLoaded(chunkPos.x, chunkPos.z)) {
            return;
        }

        if (!isMinorDespawnChunkForceLoaded(world.getSeed(), placementKey, chunkPos.x, chunkPos.z)) {
            FORCED_MINOR_DESPAWN_CHUNKS.add(new ForcedMinorDespawnChunk(world.getSeed(), placementKey, chunkPos.x, chunkPos.z));
            world.setChunkForced(chunkPos.x, chunkPos.z, true);
            System.out.println("[Breached] Force-loading minor POI despawn chunk "
                    + chunkPos.x + ", " + chunkPos.z
                    + " for " + placementKey + ".");
        }

        world.getChunk(chunkPos.x, chunkPos.z);
    }

    private static boolean isMinorDespawnChunkForceLoaded(long worldSeed, String placementKey, int chunkX, int chunkZ) {
        return FORCED_MINOR_DESPAWN_CHUNKS.stream()
                .anyMatch(forcedChunk -> forcedChunk.worldSeed() == worldSeed
                        && forcedChunk.placementKey().equals(placementKey)
                        && forcedChunk.chunkX() == chunkX
                        && forcedChunk.chunkZ() == chunkZ);
    }

    private static void releaseForcedMinorDespawnChunks(ServerWorld world, String placementKey) {
        Set<ForcedMinorDespawnChunk> updatedForcedChunks = new HashSet<>();
        for (ForcedMinorDespawnChunk forcedChunk : FORCED_MINOR_DESPAWN_CHUNKS) {
            if (forcedChunk.worldSeed() != world.getSeed() || !forcedChunk.placementKey().equals(placementKey)) {
                updatedForcedChunks.add(forcedChunk);
                continue;
            }

            world.setChunkForced(forcedChunk.chunkX(), forcedChunk.chunkZ(), false);
            System.out.println("[Breached] Released force-loaded minor POI despawn chunk "
                    + forcedChunk.chunkX() + ", " + forcedChunk.chunkZ()
                    + " for " + forcedChunk.placementKey() + ".");
        }

        FORCED_MINOR_DESPAWN_CHUNKS.clear();
        FORCED_MINOR_DESPAWN_CHUNKS.addAll(updatedForcedChunks);
    }

    private static void releaseExpiredForcedMinorDespawnChunks(
            ServerWorld world,
            BreachedStructurePlacementState state
    ) {
        Set<ForcedMinorDespawnChunk> updatedForcedChunks = new HashSet<>();
        for (ForcedMinorDespawnChunk forcedChunk : FORCED_MINOR_DESPAWN_CHUNKS) {
            if (forcedChunk.worldSeed() != world.getSeed()) {
                updatedForcedChunks.add(forcedChunk);
                continue;
            }

            boolean completed = state.getPlacement(forcedChunk.placementKey())
                    .map(placement -> !placement.active())
                    .orElse(true);
            boolean expired = forcedChunk.ticksLoaded() >= MAX_FORCED_MINOR_DESPAWN_CHUNK_TICKS;
            if (completed || expired) {
                world.setChunkForced(forcedChunk.chunkX(), forcedChunk.chunkZ(), false);
                System.out.println("[Breached] Released "
                        + (completed ? "completed" : "expired")
                        + " force-loaded minor POI despawn chunk "
                        + forcedChunk.chunkX() + ", " + forcedChunk.chunkZ()
                        + " for " + forcedChunk.placementKey() + ".");
                continue;
            }

            updatedForcedChunks.add(forcedChunk.withAdditionalTick());
        }

        FORCED_MINOR_DESPAWN_CHUNKS.clear();
        FORCED_MINOR_DESPAWN_CHUNKS.addAll(updatedForcedChunks);
    }

    private static long createNextMinorPoiDespawnTime(ServerWorld world, String placementKey) {
        BreachedConfig.MinorPoiSettings minorPoiConfig = BreachedConfig.get().minorPoi;
        int intervalRange = minorPoiConfig.maxDespawnIntervalTicks - minorPoiConfig.minDespawnIntervalTicks;
        java.util.Random random = new java.util.Random(
                world.getSeed()
                        ^ (world.getTime() * 0xD6E8FEB86659FD93L)
                        ^ ((long) placementKey.hashCode() * 0x94D049BB133111EBL)
        );
        int interval = minorPoiConfig.minDespawnIntervalTicks;
        if (intervalRange > 0) {
            interval += random.nextInt(intervalRange + 1);
        }

        return world.getTime() + interval;
    }

    private static long createMinorPoiDespawnRetryTime(ServerWorld world) {
        return world.getTime() + BreachedConfig.get().minorPoi.despawnRetryIntervalTicks;
    }

    private static void forceLoadRequiredReservedChunks(ServerWorld world, BreachedStructurePlacementState state) {
        int loadedThisTick = 0;
        for (Map.Entry<String, BreachedStructurePlacementState.ReservedPlacement> reservationEntry : state.reservations()) {
            if (loadedThisTick >= REQUIRED_CHUNK_LOADS_PER_TICK) {
                return;
            }

            String placementKey = reservationEntry.getKey();
            BreachedStructurePlacementState.ReservedPlacement reservation = reservationEntry.getValue();
            if (state.hasPlacement(placementKey) || state.hasFailedCandidate(reservation.structureKey(), reservation.candidateIndex())) {
                continue;
            }

            Optional<BreachedStructureDefinition> definition = getActiveDefinition(world, reservation.structureKey());
            if (definition.isEmpty() || definition.get().spawnImportance() != BreachedStructureDefinition.SpawnImportance.REQUIRED) {
                continue;
            }
            if (countPlacedStructures(state, reservation.structureKey()) >= definition.get().countPerWorld()) {
                continue;
            }

            Optional<BreachedStructureSpawnManager.RadiusCandidate> candidate = getReservedCandidate(world, definition.get(), reservation);
            if (candidate.isEmpty()) {
                continue;
            }

            Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, definition.get());
            if (template.isEmpty()) {
                continue;
            }

            Vec3i footprintSize = BreachedStructureSpawnManager.getRotatedSize(template.get().getSize(), definition.get().rotation());
            int originX = getPlacementOriginX(definition.get(), candidate.get());
            int originZ = getPlacementOriginZ(definition.get(), candidate.get());
            Optional<ChunkPos> unloadedFootprintChunk = getFirstUnloadedFootprintChunk(world, originX, originZ, footprintSize);
            if (unloadedFootprintChunk.isPresent()) {
                ChunkPos chunkPos = unloadedFootprintChunk.get();
                ForcedStructureChunk forcedChunk = new ForcedStructureChunk(
                        world.getSeed(),
                        reservation.structureKey(),
                        placementKey,
                        reservation.candidateIndex(),
                        chunkPos.x,
                        chunkPos.z
                );
                if (!isForceLoaded(world.getSeed(), placementKey, chunkPos.x, chunkPos.z)) {
                    FORCED_CHUNKS.add(forcedChunk);
                    world.setChunkForced(chunkPos.x, chunkPos.z, true);
                    System.out.println("[Breached] Force-loading required structure footprint chunk "
                            + chunkPos.x + ", " + chunkPos.z
                            + " for " + placementKey + ".");
                }

                world.getChunk(chunkPos.x, chunkPos.z);
                loadedThisTick++;
            }

            PENDING_PLACEMENTS.add(new PendingStructurePlacement(world.getSeed(), reservation.structureKey(), placementKey, candidate.get()));
        }
    }

    private static boolean isForceLoaded(long worldSeed, String placementKey, int chunkX, int chunkZ) {
        return FORCED_CHUNKS.stream()
                .anyMatch(forcedChunk -> forcedChunk.worldSeed() == worldSeed
                        && forcedChunk.placementKey().equals(placementKey)
                        && forcedChunk.chunkX() == chunkX
                        && forcedChunk.chunkZ() == chunkZ);
    }

    private static void releaseCompletedForcedChunks(ServerWorld world, BreachedStructurePlacementState state) {
        Set<ForcedStructureChunk> updatedForcedChunks = new HashSet<>();
        for (ForcedStructureChunk forcedChunk : FORCED_CHUNKS) {
            if (forcedChunk.worldSeed() != world.getSeed()) {
                updatedForcedChunks.add(forcedChunk);
                continue;
            }

            boolean completed = state.hasPlacement(forcedChunk.placementKey())
                    || state.hasFailedCandidate(forcedChunk.structureKey(), forcedChunk.candidateIndex());
            boolean expired = forcedChunk.ticksLoaded() >= MAX_FORCED_CHUNK_TICKS;
            if (completed || expired) {
                world.setChunkForced(forcedChunk.chunkX(), forcedChunk.chunkZ(), false);
                System.out.println("[Breached] Released force-loaded structure chunk "
                        + forcedChunk.chunkX() + ", " + forcedChunk.chunkZ()
                        + " for " + forcedChunk.placementKey() + ".");
                continue;
            }

            updatedForcedChunks.add(forcedChunk.withAdditionalTick());
        }

        FORCED_CHUNKS.clear();
        FORCED_CHUNKS.addAll(updatedForcedChunks);
    }

    private static void reservePlannedStructures(ServerWorld world, BreachedStructurePlacementState state) {
        List<BreachedStructureDefinition> definitions = new ArrayList<>();
        for (BreachedStructureDefinition definition : BreachedStructureDefinitions.PLANNED_STRUCTURES) {
            BreachedStructureDefinition activeDefinition = getActiveDefinition(world, definition);
            if (world.getRegistryKey().equals(activeDefinition.requiredDimension())) {
                definitions.add(activeDefinition);
            }
        }

        definitions.sort(Comparator.comparingInt(BreachedStructureDefinition::priority).reversed());

        for (BreachedStructureDefinition definition : definitions) {
            String structureKey = BreachedStructureDefinitions.key(definition);
            int neededReservations = definition.countPerWorld() - countReservedOrPlacedStructures(state, structureKey);
            if (neededReservations <= 0) {
                continue;
            }

            while (neededReservations > 0) {
                Optional<ReservationChoice> reservationChoice = chooseReservationCandidate(world, state, definition, structureKey);
                if (reservationChoice.isEmpty()) {
                    break;
                }

                BreachedStructureSpawnManager.RadiusCandidate candidate = reservationChoice.get().candidate();
                String placementKey = placementKey(structureKey, candidate.index());
                state.reservePlacement(placementKey, structureKey, candidate.index(), candidate.x(), candidate.z());
                System.out.println("[Breached] Reserved planned structure " + definition.logName()
                        + " candidate " + candidate.index()
                        + " at x " + candidate.x()
                        + ", z " + candidate.z()
                        + " spacing penalty " + Math.round(reservationChoice.get().spacingPenalty())
                        + " as " + placementKey + ".");
                neededReservations--;
            }
        }
    }

    private static Optional<ReservationChoice> chooseReservationCandidate(
            ServerWorld world,
            BreachedStructurePlacementState state,
            BreachedStructureDefinition definition,
            String structureKey
    ) {
        ReservationChoice bestChoice = null;
        for (BreachedStructureSpawnManager.RadiusCandidate candidate : generatePlannedCandidates(world, definition)) {
            String placementKey = placementKey(structureKey, candidate.index());
            if (state.hasPlacement(placementKey)
                    || state.hasReservation(placementKey)
                    || state.hasFailedCandidate(structureKey, candidate.index())) {
                continue;
            }

            SpacingEvaluation spacing = evaluateSpacing(state, definition, structureKey, placementKey, candidate);
            if (!spacing.accepted()) {
                continue;
            }

            ReservationChoice choice = new ReservationChoice(candidate, spacing.penalty());
            if (bestChoice == null || compareReservationChoices(choice, bestChoice) < 0) {
                bestChoice = choice;
            }
        }

        return Optional.ofNullable(bestChoice);
    }

    private static int compareReservationChoices(ReservationChoice left, ReservationChoice right) {
        int spacingCompare = Double.compare(left.spacingPenalty(), right.spacingPenalty());
        if (spacingCompare != 0) {
            return spacingCompare;
        }

        return Integer.compare(left.candidate().index(), right.candidate().index());
    }

    private static PlannedCandidateEvaluation evaluatePlannedCandidate(
            ServerWorld world,
            BreachedStructurePlacementState state,
            BreachedStructureDefinition definition,
            String structureKey,
            PendingStructurePlacement pendingPlacement
    ) {
        String placementKey = pendingPlacement.placementKey();
        BreachedStructureSpawnManager.RadiusCandidate candidate = pendingPlacement.candidate();
        if (isHandledByPrePlacementCheck(world, state, definition, placementKey, candidate)) {
            return new PlannedCandidateEvaluation(pendingPlacement, null, null, 0.0D, PlacementAttemptResult.HANDLED);
        }

        SpacingEvaluation spacing = evaluateSpacing(state, definition, structureKey, placementKey, candidate);
        if (!spacing.accepted()) {
            state.markCandidateFailed(structureKey, candidate.index());
            System.out.println("[Breached] Rejected planned candidate " + candidate.index()
                    + " for " + definition.logName()
                    + " at x " + candidate.x()
                    + ", z " + candidate.z()
                    + ", radius " + candidate.radius()
                    + ": " + spacing.rejectionReason() + ".");
            return new PlannedCandidateEvaluation(pendingPlacement, null, null, Double.MAX_VALUE, PlacementAttemptResult.FAILED);
        }

        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, definition);
        if (template.isEmpty()) {
            System.out.println("[Breached] Skipped planned candidate " + candidate.index()
                    + " for " + definition.logName()
                    + " because the structure template is missing; it was not marked failed.");
            return new PlannedCandidateEvaluation(pendingPlacement, null, null, Double.MAX_VALUE, PlacementAttemptResult.SKIPPED);
        }

        Vec3i footprintSize = BreachedStructureSpawnManager.getRotatedSize(template.get().getSize(), definition.rotation());
        int originX = getPlacementOriginX(definition, candidate);
        int originZ = getPlacementOriginZ(definition, candidate);
        if (!isFootprintLoaded(world, originX, originZ, footprintSize)) {
            return new PlannedCandidateEvaluation(pendingPlacement, null, null, Double.MAX_VALUE, PlacementAttemptResult.NOT_READY);
        }

        BreachedStructureSite site = BreachedStructureSpawnManager.evaluateSite(
                world,
                definition,
                template.get(),
                originX,
                originZ,
                candidate.radius()
        );
        if (!site.accepted()) {
            state.markCandidateFailed(structureKey, candidate.index());
            System.out.println("[Breached] Rejected planned candidate " + candidate.index()
                    + " for " + definition.logName()
                    + " at x " + candidate.x()
                    + ", z " + candidate.z()
                    + ", radius " + candidate.radius()
                    + ": " + site.rejectionReason() + ".");
            return new PlannedCandidateEvaluation(pendingPlacement, null, null, Double.MAX_VALUE, PlacementAttemptResult.FAILED);
        }

        ObstructionEvaluation obstruction = evaluateObstruction(world, definition, footprintSize, site);
        if (!obstruction.accepted()) {
            state.markCandidateFailed(structureKey, candidate.index());
            System.out.println("[Breached] Rejected planned candidate " + candidate.index()
                    + " for " + definition.logName()
                    + " at x " + candidate.x()
                    + ", z " + candidate.z()
                    + ", radius " + candidate.radius()
                    + ": " + obstruction.rejectionReason() + ".");
            return new PlannedCandidateEvaluation(pendingPlacement, null, null, Double.MAX_VALUE, PlacementAttemptResult.FAILED);
        }

        return new PlannedCandidateEvaluation(
                pendingPlacement,
                template.get(),
                site,
                site.score() + obstruction.penalty() + spacing.penalty(),
                PlacementAttemptResult.READY
        );
    }

    private static int comparePlannedCandidateEvaluations(PlannedCandidateEvaluation left, PlannedCandidateEvaluation right) {
        int scoreCompare = Double.compare(left.score(), right.score());
        if (scoreCompare != 0) {
            return scoreCompare;
        }

        return Integer.compare(left.pendingPlacement().candidate().index(), right.pendingPlacement().candidate().index());
    }

    private static PlacementAttemptResult placeEvaluatedPlannedCandidate(
            ServerWorld world,
            BreachedStructurePlacementState state,
            BreachedStructureDefinition definition,
            PlannedCandidateEvaluation evaluation
    ) {
        PendingStructurePlacement pendingPlacement = evaluation.pendingPlacement();
        BreachedStructureSpawnManager.RadiusCandidate candidate = pendingPlacement.candidate();
        BreachedStructurePlacement placement = BreachedStructureSpawnManager.place(
                world,
                definition,
                evaluation.template(),
                getPlacementOriginX(definition, candidate),
                getPlacementOriginY(definition, evaluation.site()),
                getPlacementOriginZ(definition, candidate),
                definition.mirror(),
                definition.rotation(),
                evaluation.site()
        );
        BreachedStructureSupportGenerator.generate(world, definition, placement);
        state.markPlaced(
                pendingPlacement.placementKey(),
                placement,
                world.getTime(),
                getSavedLootContainers(world, definition, evaluation.template(), placement),
                createNextMajorLootRestockTime(world, pendingPlacement.placementKey())
        );
        System.out.println("[Breached] Registered protected structure " + definition.logName()
                + " around x " + BreachedStructureSpawnManager.getProtectedCenterX(placement)
                + ", z " + BreachedStructureSpawnManager.getProtectedCenterZ(placement)
                + " radius " + definition.protectionRadius() + ".");
        return PlacementAttemptResult.PLACED;
    }

    private static boolean isHandledByPrePlacementCheck(
            ServerWorld world,
            BreachedStructurePlacementState state,
            BreachedStructureDefinition definition,
            String placementKey,
            BreachedStructureSpawnManager.RadiusCandidate candidate
    ) {
        if (definition.prePlacementCheck() != BreachedStructureDefinition.PrePlacementCheck.NO_ACTIVE_NETHER_PORTAL_NEARBY) {
            return false;
        }

        if (!hasActivePortal(world, candidate.x(), candidate.z())) {
            return false;
        }

        state.markPlaced(placementKey, candidate.x(), candidate.z());
        System.out.println("[Breached] Registered existing active Nether portal for " + definition.logName()
                + " candidate " + candidate.index()
                + " at x " + candidate.x()
                + ", z " + candidate.z()
                + "; structure placement was skipped.");
        return true;
    }

    private static BreachedStructureDefinition getActiveDefinition(ServerWorld world, BreachedStructureDefinition definition) {
        BreachedStructureDefinition presetDefinition = BreachedStructureDefinitions.applyPresetOverrides(
                definition,
                BreachedDimensionRules.getBreachedPreset(world.getServer())
        );
        return BreachedConfig.get().applyStructureOverrides(presetDefinition);
    }

    private static Optional<BreachedStructureDefinition> getActiveDefinition(ServerWorld world, String structureKey) {
        return getBaseDefinition(structureKey).map(definition -> getActiveDefinition(world, definition));
    }

    private static int getPlacementOriginX(
            BreachedStructureDefinition definition,
            BreachedStructureSpawnManager.RadiusCandidate candidate
    ) {
        return candidate.x() + definition.placementOffsetX();
    }

    private static int getPlacementOriginY(
            BreachedStructureDefinition definition,
            BreachedStructureSite site
    ) {
        return site.surfaceY() + definition.placementOffsetY();
    }

    private static int getPlacementOriginZ(
            BreachedStructureDefinition definition,
            BreachedStructureSpawnManager.RadiusCandidate candidate
    ) {
        return candidate.z() + definition.placementOffsetZ();
    }

    private static java.util.Random createMinorPoiChunkRandom(long worldSeed, ChunkPos chunkPos) {
        long seed = worldSeed
                ^ 0x4D49504F4943484CL
                ^ ((long) chunkPos.x * 341873128712L)
                ^ ((long) chunkPos.z * 132897987541L);
        return new java.util.Random(seed);
    }

    private static int minorCandidateIndex(ChunkPos chunkPos) {
        return (chunkPos.x & 0xFFFF) << 16 | (chunkPos.z & 0xFFFF);
    }

    private static boolean isInsideDefinitionRadius(BreachedStructureDefinition definition, int x, int z) {
        long xDistance = x - definition.centerX();
        long zDistance = z - definition.centerZ();
        long distanceSquared = xDistance * xDistance + zDistance * zDistance;
        return distanceSquared >= (long) definition.minRadius() * definition.minRadius()
                && distanceSquared <= (long) definition.maxRadius() * definition.maxRadius();
    }

    private static int distanceFromDefinitionCenter(BreachedStructureDefinition definition, int x, int z) {
        long xDistance = x - definition.centerX();
        long zDistance = z - definition.centerZ();
        return (int) Math.round(Math.sqrt(xDistance * xDistance + zDistance * zDistance));
    }

    private static boolean isFootprintLoaded(ServerWorld world, int originX, int originZ, Vec3i footprintSize) {
        return getFirstUnloadedFootprintChunk(world, originX, originZ, footprintSize).isEmpty();
    }

    private static Optional<ChunkPos> getFirstUnloadedFootprintChunk(ServerWorld world, int originX, int originZ, Vec3i footprintSize) {
        int minChunkX = Math.floorDiv(originX, CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(originZ, CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(originX + footprintSize.getX() - 1, CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(originZ + footprintSize.getZ() - 1, CHUNK_SIZE);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    return Optional.of(new ChunkPos(chunkX, chunkZ));
                }
            }
        }

        return Optional.empty();
    }

    private static List<BreachedStructureSpawnManager.RadiusCandidate> generatePlannedCandidates(
            ServerWorld world,
            BreachedStructureDefinition definition
    ) {
        if (definition.structureId().equals(BreachedStructureDefinitions.SWORD_STATUE.structureId())) {
            return generateSwordStatueCandidatesOpposedToPinkTree(world, definition);
        }

        return generateRadiusCandidates(world, definition);
    }

    private static List<BreachedStructureSpawnManager.RadiusCandidate> generateSwordStatueCandidatesOpposedToPinkTree(
            ServerWorld world,
            BreachedStructureDefinition definition
    ) {
        BreachedStructureDefinition pinkTreeDefinition = getActiveDefinition(world, BreachedStructureDefinitions.PINK_TREE);
        List<BreachedStructureSpawnManager.RadiusCandidate> pinkTreeCandidates = generateRadiusCandidates(world, pinkTreeDefinition);
        List<BreachedStructureSpawnManager.RadiusCandidate> swordCandidates = new ArrayList<>();

        for (BreachedStructureSpawnManager.RadiusCandidate pinkTreeCandidate : pinkTreeCandidates) {
            int x = definition.centerX() - (pinkTreeCandidate.x() - definition.centerX());
            int z = definition.centerZ() - (pinkTreeCandidate.z() - definition.centerZ());
            swordCandidates.add(new BreachedStructureSpawnManager.RadiusCandidate(
                    pinkTreeCandidate.index(),
                    x,
                    z,
                    distanceFromDefinitionCenter(definition, x, z),
                    pinkTreeCandidate.angle() + Math.PI
            ));
        }

        return swordCandidates;
    }

    private static List<BreachedStructureSpawnManager.RadiusCandidate> generateRadiusCandidates(
            ServerWorld world,
            BreachedStructureDefinition definition
    ) {
        return BreachedStructureSpawnManager.generateRadiusCandidates(
                world.getSeed(),
                definition.seedSalt(),
                definition.centerX(),
                definition.centerZ(),
                definition.minRadius(),
                definition.maxRadius(),
                Math.max(definition.countPerWorld(), definition.plannedCandidateCount()),
                definition.roughlyOpposed()
        );
    }

    private static Optional<BreachedStructureSpawnManager.RadiusCandidate> getReservedCandidate(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.ReservedPlacement reservation
    ) {
        return generatePlannedCandidates(world, definition).stream()
                .filter(candidate -> candidate.index() == reservation.candidateIndex())
                .findFirst();
    }

    private static boolean hasRemainingCandidates(
            ServerWorld world,
            BreachedStructurePlacementState state,
            BreachedStructureDefinition definition,
            String key
    ) {
        return generatePlannedCandidates(world, definition).stream()
                .anyMatch(candidate -> !state.hasFailedCandidate(key, candidate.index()));
    }

    private static int countPlacedStructures(BreachedStructurePlacementState state, String structureKey) {
        int placed = 0;
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> placement : state.placements()) {
            if (isPlacementForStructure(placement.getKey(), structureKey)
                    && shouldCountPlacement(placement.getKey(), placement.getValue())) {
                placed++;
            }
        }

        return placed;
    }

    private static boolean shouldCountPlacement(
            String placementKey,
            BreachedStructurePlacementState.SavedPlacement placement
    ) {
        return !isMinorPoiPlacement(placementKey) || placement.active();
    }

    private static boolean isActiveMinorPoiPlacement(
            String placementKey,
            BreachedStructurePlacementState.SavedPlacement placement
    ) {
        return isMinorPoiPlacement(placementKey) && placement.active();
    }

    private static boolean isMinorPoiPlacement(String placementKey) {
        return getBaseDefinition(structureKey(placementKey))
                .map(definition -> definition.spacingGroup() == BreachedStructureDefinition.SpacingGroup.MINOR)
                .orElse(false);
    }

    private static void restockMajorStructureLoot(ServerWorld world, BreachedStructurePlacementState state) {
        BreachedConfig.MajorStructureLootSettings lootConfig = BreachedConfig.get().majorStructureLoot;
        boolean scanTick = world.getTime() % lootConfig.scanIntervalTicks == 0;
        if (!lootConfig.enabled || (!scanTick && !hasForcedRestockChunksForWorld(world))) {
            return;
        }

        int restockedStructures = 0;
        int totalRestockedContainers = 0;
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> entry : new ArrayList<>(state.placements())) {
            Optional<BreachedStructureDefinition> definition = getMajorDefinition(entry.getKey())
                    .map(baseDefinition -> getActiveDefinition(world, baseDefinition));
            if (definition.isEmpty() || !world.getRegistryKey().equals(definition.get().requiredDimension())) {
                continue;
            }

            BreachedStructurePlacementState.SavedPlacement placement = entry.getValue();
            if (placement.nextRestockTime() <= placement.lastRestockTime()) {
                state.markLootRestocked(
                        entry.getKey(),
                        placement.lastRestockTime(),
                        createNextMajorLootRestockTime(world, entry.getKey())
                );
                continue;
            }

            if (world.getTime() < placement.nextRestockTime()) {
                continue;
            }

            if (!placement.lootContainersScanned()) {
                if (!tryBackfillMajorLootContainers(world, state, entry.getKey(), definition.get(), placement)) {
                    continue;
                }

                placement = state.getPlacement(entry.getKey()).orElse(placement);
            }

            if (placement.lootContainers().isEmpty()) {
                releaseForcedRestockChunks(world, entry.getKey());
                state.markLootRestocked(
                        entry.getKey(),
                        world.getTime(),
                        createNextMajorLootRestockTime(world, entry.getKey())
                );
                continue;
            }

            if (!areLootContainersLoaded(world, placement.lootContainers())) {
                forceLoadMissingRestockChunks(world, entry.getKey(), placement.lootContainers());
                continue;
            }

            int restockedContainers = restockLootContainers(world, entry.getKey(), placement.lootContainers());
            releaseForcedRestockChunks(world, entry.getKey());
            state.markLootRestocked(
                    entry.getKey(),
                    world.getTime(),
                    createNextMajorLootRestockTime(world, entry.getKey())
            );
            if (restockedContainers > 0) {
                restockedStructures++;
                totalRestockedContainers += restockedContainers;
                if (lootConfig.announceRestocks) {
                    world.getServer().getPlayerManager().broadcast(
                            getMajorRestockAnnouncement(entry.getKey()),
                            false
                    );
                }
                System.out.println("[Breached] Restocked " + restockedContainers
                        + " major structure containers for " + entry.getKey() + ".");
            }
        }

        if (totalRestockedContainers > 0) {
            System.out.println("[Breached] Announced major structure loot restock for "
                    + restockedStructures + " structures and "
                    + totalRestockedContainers + " containers.");
        }
    }

    private static Text getMajorRestockAnnouncement(String placementKey) {
        String structureKey = structureKey(placementKey);
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.SWORD_STATUE))) {
            return Text.literal("The statue has been restocked").formatted(Formatting.BLACK);
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.PINK_TREE))) {
            return Text.literal("The Tree has been restocked").formatted(Formatting.LIGHT_PURPLE);
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.BIG_BOAT))) {
            return Text.literal("The Ship has been restocked").formatted(Formatting.BLUE);
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.HORACE))) {
            return Text.literal("Horace has been restocked").formatted(Formatting.RED);
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.OFFICIAL_NETHER_PORTAL))) {
            return Text.literal("A portal has been restocked").formatted(Formatting.DARK_PURPLE);
        }

        return Text.literal("Major structure loot has been restocked");
    }

    private static boolean tryBackfillMajorLootContainers(
            ServerWorld world,
            BreachedStructurePlacementState state,
            String placementKey,
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement
    ) {
        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, definition);
        if (template.isEmpty()) {
            return false;
        }

        Vec3i footprintSize = BreachedStructureSpawnManager.getRotatedSize(template.get().getSize(), definition.rotation());
        if (!isFootprintLoaded(world, placement.originX(), placement.originZ(), footprintSize)) {
            forceLoadMissingRestockFootprintChunks(world, placementKey, placement.originX(), placement.originZ(), footprintSize);
            return false;
        }

        List<BreachedStructurePlacementState.SavedLootContainer> lootContainers = getSavedLootContainers(
                world,
                definition,
                template.get(),
                new BlockPos(placement.originX(), placement.originY(), placement.originZ())
        );
        long nextRestockTime = placement.nextRestockTime() > placement.lastRestockTime()
                ? placement.nextRestockTime()
                : createNextMajorLootRestockTime(world, placementKey);
        state.setLootContainers(placementKey, lootContainers, placement.lastRestockTime(), nextRestockTime);
        if (!lootContainers.isEmpty()) {
            System.out.println("[Breached] Tracked " + lootContainers.size()
                    + " major structure containers for " + placementKey + ".");
        }
        return true;
    }

    private static List<BreachedStructurePlacementState.SavedLootContainer> getSavedLootContainers(
            ServerWorld world,
            BreachedStructureDefinition definition,
            StructureTemplate template,
            BreachedStructurePlacement placement
    ) {
        return getSavedLootContainers(world, definition, template, placement.origin());
    }

    private static List<BreachedStructurePlacementState.SavedLootContainer> getSavedLootContainers(
            ServerWorld world,
            BreachedStructureDefinition definition,
            StructureTemplate template,
            BlockPos origin
    ) {
        if (definition.spacingGroup() != BreachedStructureDefinition.SpacingGroup.MAJOR) {
            return List.of();
        }

        List<BreachedStructurePlacementState.SavedLootContainer> lootContainers = new ArrayList<>();
        for (BreachedStructureSpawnManager.TemplateLootContainer container : BreachedStructureSpawnManager.getTemplateLootContainers(
                world,
                definition,
                template,
                origin,
                definition.mirror(),
                definition.rotation()
        )) {
            lootContainers.add(new BreachedStructurePlacementState.SavedLootContainer(
                    container.pos(),
                    container.lootTableId(),
                    container.lootTableSeed()
            ));
        }

        return lootContainers;
    }

    private static boolean areLootContainersLoaded(
            ServerWorld world,
            List<BreachedStructurePlacementState.SavedLootContainer> lootContainers
    ) {
        for (BreachedStructurePlacementState.SavedLootContainer lootContainer : lootContainers) {
            BlockPos pos = lootContainer.pos();
            if (!world.isChunkLoaded(Math.floorDiv(pos.getX(), CHUNK_SIZE), Math.floorDiv(pos.getZ(), CHUNK_SIZE))) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasForcedRestockChunksForWorld(ServerWorld world) {
        return FORCED_RESTOCK_CHUNKS.stream()
                .anyMatch(forcedChunk -> forcedChunk.worldSeed() == world.getSeed());
    }

    private static void forceLoadMissingRestockChunks(
            ServerWorld world,
            String placementKey,
            List<BreachedStructurePlacementState.SavedLootContainer> lootContainers
    ) {
        Set<ChunkPos> chunks = new HashSet<>();
        for (BreachedStructurePlacementState.SavedLootContainer lootContainer : lootContainers) {
            BlockPos pos = lootContainer.pos();
            chunks.add(new ChunkPos(Math.floorDiv(pos.getX(), CHUNK_SIZE), Math.floorDiv(pos.getZ(), CHUNK_SIZE)));
        }

        for (ChunkPos chunkPos : chunks) {
            forceLoadRestockChunk(world, placementKey, chunkPos);
        }
    }

    private static void forceLoadMissingRestockFootprintChunks(
            ServerWorld world,
            String placementKey,
            int originX,
            int originZ,
            Vec3i footprintSize
    ) {
        int minChunkX = Math.floorDiv(originX, CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(originZ, CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(originX + footprintSize.getX() - 1, CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(originZ + footprintSize.getZ() - 1, CHUNK_SIZE);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                forceLoadRestockChunk(world, placementKey, new ChunkPos(chunkX, chunkZ));
            }
        }
    }

    private static void forceLoadRestockChunk(ServerWorld world, String placementKey, ChunkPos chunkPos) {
        if (world.isChunkLoaded(chunkPos.x, chunkPos.z)) {
            return;
        }

        if (!isRestockChunkForceLoaded(world.getSeed(), placementKey, chunkPos.x, chunkPos.z)) {
            FORCED_RESTOCK_CHUNKS.add(new ForcedRestockChunk(world.getSeed(), placementKey, chunkPos.x, chunkPos.z));
            world.setChunkForced(chunkPos.x, chunkPos.z, true);
            System.out.println("[Breached] Force-loading major structure restock chunk "
                    + chunkPos.x + ", " + chunkPos.z
                    + " for " + placementKey + ".");
        }

        world.getChunk(chunkPos.x, chunkPos.z);
    }

    private static boolean isRestockChunkForceLoaded(long worldSeed, String placementKey, int chunkX, int chunkZ) {
        return FORCED_RESTOCK_CHUNKS.stream()
                .anyMatch(forcedChunk -> forcedChunk.worldSeed() == worldSeed
                        && forcedChunk.placementKey().equals(placementKey)
                        && forcedChunk.chunkX() == chunkX
                        && forcedChunk.chunkZ() == chunkZ);
    }

    private static void releaseForcedRestockChunks(ServerWorld world, String placementKey) {
        Set<ForcedRestockChunk> updatedForcedChunks = new HashSet<>();
        for (ForcedRestockChunk forcedChunk : FORCED_RESTOCK_CHUNKS) {
            if (forcedChunk.worldSeed() != world.getSeed() || !forcedChunk.placementKey().equals(placementKey)) {
                updatedForcedChunks.add(forcedChunk);
                continue;
            }

            world.setChunkForced(forcedChunk.chunkX(), forcedChunk.chunkZ(), false);
            System.out.println("[Breached] Released force-loaded restock chunk "
                    + forcedChunk.chunkX() + ", " + forcedChunk.chunkZ()
                    + " for " + forcedChunk.placementKey() + ".");
        }

        FORCED_RESTOCK_CHUNKS.clear();
        FORCED_RESTOCK_CHUNKS.addAll(updatedForcedChunks);
    }

    private static void releaseExpiredForcedRestockChunks(ServerWorld world) {
        Set<ForcedRestockChunk> updatedForcedChunks = new HashSet<>();
        for (ForcedRestockChunk forcedChunk : FORCED_RESTOCK_CHUNKS) {
            if (forcedChunk.worldSeed() != world.getSeed()) {
                updatedForcedChunks.add(forcedChunk);
                continue;
            }

            if (forcedChunk.ticksLoaded() >= MAX_FORCED_RESTOCK_CHUNK_TICKS) {
                world.setChunkForced(forcedChunk.chunkX(), forcedChunk.chunkZ(), false);
                System.out.println("[Breached] Released expired force-loaded restock chunk "
                        + forcedChunk.chunkX() + ", " + forcedChunk.chunkZ()
                        + " for " + forcedChunk.placementKey() + ".");
                continue;
            }

            updatedForcedChunks.add(forcedChunk.withAdditionalTick());
        }

        FORCED_RESTOCK_CHUNKS.clear();
        FORCED_RESTOCK_CHUNKS.addAll(updatedForcedChunks);
    }

    private static int restockLootContainers(
            ServerWorld world,
            String placementKey,
            List<BreachedStructurePlacementState.SavedLootContainer> lootContainers
    ) {
        int restockedContainers = 0;
        for (BreachedStructurePlacementState.SavedLootContainer lootContainer : lootContainers) {
            if (restockLootContainer(world, placementKey, lootContainer)) {
                restockedContainers++;
            }
        }

        return restockedContainers;
    }

    private static boolean restockLootContainer(
            ServerWorld world,
            String placementKey,
            BreachedStructurePlacementState.SavedLootContainer lootContainer
    ) {
        BlockEntity blockEntity = world.getBlockEntity(lootContainer.pos());
        if (!(blockEntity instanceof LootableInventory lootableInventory) || !(blockEntity instanceof Inventory inventory)) {
            return false;
        }

        inventory.clear();
        RegistryKey<LootTable> lootTableKey = RegistryKey.of(RegistryKeys.LOOT_TABLE, lootContainer.lootTableId());
        lootableInventory.setLootTable(lootTableKey, createRestockLootSeed(world, placementKey, lootContainer));
        inventory.markDirty();
        world.updateListeners(lootContainer.pos(), world.getBlockState(lootContainer.pos()), world.getBlockState(lootContainer.pos()), 3);
        return true;
    }

    private static long createRestockLootSeed(
            ServerWorld world,
            String placementKey,
            BreachedStructurePlacementState.SavedLootContainer lootContainer
    ) {
        return world.getSeed()
                ^ (world.getTime() * 31L)
                ^ lootContainer.pos().asLong()
                ^ placementKey.hashCode()
                ^ lootContainer.lootTableSeed();
    }

    private static long createNextMajorLootRestockTime(ServerWorld world, String placementKey) {
        BreachedConfig.MajorStructureLootSettings lootConfig = BreachedConfig.get().majorStructureLoot;
        int intervalRange = lootConfig.maxRestockIntervalTicks - lootConfig.minRestockIntervalTicks;
        java.util.Random random = new java.util.Random(
                world.getSeed()
                        ^ (world.getTime() * 0x9E3779B97F4A7C15L)
                        ^ ((long) placementKey.hashCode() * 0xBF58476D1CE4E5B9L)
        );
        int interval = lootConfig.minRestockIntervalTicks;
        if (intervalRange > 0) {
            interval += random.nextInt(intervalRange + 1);
        }

        return world.getTime() + interval;
    }

    private static int countPlacedMinorPois(BreachedStructurePlacementState state) {
        int placed = 0;
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> placement : state.placements()) {
            if (isActiveMinorPoiPlacement(placement.getKey(), placement.getValue())) {
                placed++;
            }
        }

        return placed;
    }

    private static SpacingEvaluation evaluateMinorPoiCenterSpacing(
            BreachedStructurePlacementState state,
            BreachedStructureDefinition definition,
            String placementKey,
            int centerX,
            int centerZ
    ) {
        BreachedConfig.MinorPoiSettings minorPoiConfig = BreachedConfig.get().minorPoi;
        int minimumSpacing = Math.max(
                Math.max(0, definition.minimumSpacingFromBreachedStructures()),
                minorPoiConfig.hardMinimumSpacing
        );
        int preferredSpacing = Math.max(minimumSpacing, definition.preferredSpacingFromBreachedStructures());
        if (minimumSpacing <= 0 && preferredSpacing <= 0) {
            return new SpacingEvaluation(true, 0.0D, null);
        }

        double penalty = 0.0D;
        long minimumSpacingSquared = (long) minimumSpacing * minimumSpacing;
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> placement : state.placements()) {
            if (placement.getKey().equals(placementKey)) {
                continue;
            }

            if (!isActiveMinorPoiPlacement(placement.getKey(), placement.getValue())) {
                continue;
            }

            long xDistance = centerX - placement.getValue().centerX();
            long zDistance = centerZ - placement.getValue().centerZ();
            long distanceSquared = xDistance * xDistance + zDistance * zDistance;
            if (distanceSquared < minimumSpacingSquared) {
                return new SpacingEvaluation(false, Double.MAX_VALUE, "too close to existing minor POI "
                        + structureKey(placement.getKey())
                        + " at x " + placement.getValue().centerX()
                        + ", z " + placement.getValue().centerZ()
                        + "; minimum spacing " + minimumSpacing);
            }

            double distance = Math.sqrt(distanceSquared);
            if (distance < preferredSpacing) {
                penalty += preferredSpacing - distance;
            }
        }

        return new SpacingEvaluation(true, penalty, null);
    }

    private static boolean isMinorPoiSpreadCellFull(BreachedStructurePlacementState state, int x, int z) {
        BreachedConfig.MinorPoiSettings minorPoiConfig = BreachedConfig.get().minorPoi;
        return countPlacedMinorPoisInSpreadCell(state, x, z, minorPoiConfig.spreadCellSizeBlocks) >= minorPoiConfig.maxPerSpreadCell;
    }

    private static int countPlacedMinorPoisInSpreadCell(
            BreachedStructurePlacementState state,
            int x,
            int z,
            int spreadCellSizeBlocks
    ) {
        int cellX = spreadCellCoordinate(x, spreadCellSizeBlocks);
        int cellZ = spreadCellCoordinate(z, spreadCellSizeBlocks);
        int placed = 0;

        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> placement : state.placements()) {
            if (!isActiveMinorPoiPlacement(placement.getKey(), placement.getValue())) {
                continue;
            }

            if (spreadCellCoordinate(placement.getValue().centerX(), spreadCellSizeBlocks) == cellX
                    && spreadCellCoordinate(placement.getValue().centerZ(), spreadCellSizeBlocks) == cellZ) {
                placed++;
            }
        }

        return placed;
    }

    private static int spreadCellCoordinate(int coordinate, int spreadCellSizeBlocks) {
        return Math.floorDiv(coordinate, Math.max(1, spreadCellSizeBlocks));
    }

    private static int getMinorPoiBudget(ServerWorld world) {
        BreachedConfig.MinorPoiSettings minorPoiConfig = BreachedConfig.get().minorPoi;
        Optional<BreachedDimensionRules.BreachedPreset> preset = BreachedDimensionRules.getBreachedPreset(world.getServer());
        if (preset.isPresent() && preset.get() == BreachedDimensionRules.BreachedPreset.SMALL) {
            return minorPoiConfig.smallWorldBudget;
        }

        return minorPoiConfig.standardWorldBudget;
    }

    private static int countReservedOrPlacedStructures(BreachedStructurePlacementState state, String structureKey) {
        int count = countPlacedStructures(state, structureKey);
        for (Map.Entry<String, BreachedStructurePlacementState.ReservedPlacement> reservation : state.reservations()) {
            if (isPlacementForStructure(reservation.getKey(), structureKey)
                    && !state.hasPlacement(reservation.getKey())
                    && !state.hasFailedCandidate(structureKey, reservation.getValue().candidateIndex())) {
                count++;
            }
        }

        return count;
    }

    private static String placementKey(String structureKey, int candidateIndex) {
        return structureKey + "#" + candidateIndex;
    }

    private static String structureKey(String placementKey) {
        int separator = placementKey.indexOf('#');
        return separator >= 0 ? placementKey.substring(0, separator) : placementKey;
    }

    private static boolean isPlacementForStructure(String placementKey, String structureKey) {
        return placementKey.equals(structureKey) || placementKey.startsWith(structureKey + "#");
    }

    private static SpacingEvaluation evaluateSpacing(
            BreachedStructurePlacementState state,
            BreachedStructureDefinition definition,
            String structureKey,
            String placementKey,
            BreachedStructureSpawnManager.RadiusCandidate candidate
    ) {
        int minimumSpacing = Math.max(0, definition.minimumSpacingFromBreachedStructures());
        int preferredSpacing = Math.max(minimumSpacing, definition.preferredSpacingFromBreachedStructures());
        if (minimumSpacing <= 0 && preferredSpacing <= 0) {
            return new SpacingEvaluation(true, 0.0D, null);
        }

        double penalty = 0.0D;
        long minimumSpacingSquared = (long) minimumSpacing * minimumSpacing;
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> placement : state.placements()) {
            if (placement.getKey().equals(placementKey)) {
                continue;
            }

            if (!shouldCountPlacement(placement.getKey(), placement.getValue())) {
                continue;
            }

            String otherStructureKey = structureKey(placement.getKey());
            if (!shouldCheckSpacingAgainst(definition, structureKey, otherStructureKey)) {
                continue;
            }

            long xDistance = candidate.x() - placement.getValue().centerX();
            long zDistance = candidate.z() - placement.getValue().centerZ();
            long distanceSquared = xDistance * xDistance + zDistance * zDistance;
            if (distanceSquared < minimumSpacingSquared) {
                return new SpacingEvaluation(false, Double.MAX_VALUE, "too close to existing " + otherStructureKey
                        + " placement at x " + placement.getValue().centerX()
                        + ", z " + placement.getValue().centerZ()
                        + "; minimum spacing " + minimumSpacing);
            }

            double distance = Math.sqrt(distanceSquared);
            if (distance < preferredSpacing) {
                penalty += preferredSpacing - distance;
            }
        }

        for (Map.Entry<String, BreachedStructurePlacementState.ReservedPlacement> reservation : state.reservations()) {
            if (reservation.getKey().equals(placementKey)) {
                continue;
            }

            if (state.hasPlacement(reservation.getKey())
                    || state.hasFailedCandidate(reservation.getValue().structureKey(), reservation.getValue().candidateIndex())
                    || !isPlannedStructureKey(reservation.getValue().structureKey())
                    || !shouldCheckSpacingAgainst(definition, structureKey, reservation.getValue().structureKey())) {
                continue;
            }

            long xDistance = candidate.x() - reservation.getValue().x();
            long zDistance = candidate.z() - reservation.getValue().z();
            long distanceSquared = xDistance * xDistance + zDistance * zDistance;
            if (distanceSquared < minimumSpacingSquared) {
                return new SpacingEvaluation(false, Double.MAX_VALUE, "too close to reserved " + reservation.getValue().structureKey()
                        + " candidate " + reservation.getValue().candidateIndex()
                        + " at x " + reservation.getValue().x()
                        + ", z " + reservation.getValue().z()
                        + "; minimum spacing " + minimumSpacing);
            }

            double distance = Math.sqrt(distanceSquared);
            if (distance < preferredSpacing) {
                penalty += preferredSpacing - distance;
            }
        }

        return new SpacingEvaluation(true, penalty, null);
    }

    private static boolean shouldCheckSpacingAgainst(
            BreachedStructureDefinition definition,
            String structureKey,
            String otherStructureKey
    ) {
        return switch (definition.spacingPolicy()) {
            case NONE -> false;
            case SAME_STRUCTURE -> structureKey.equals(otherStructureKey);
            case SAME_GROUP -> getBaseDefinition(otherStructureKey)
                    .map(otherDefinition -> otherDefinition.spacingGroup() == definition.spacingGroup())
                    .orElse(false);
            case MAJOR_ONLY -> getBaseDefinition(otherStructureKey)
                    .map(otherDefinition -> otherDefinition.spacingGroup() == BreachedStructureDefinition.SpacingGroup.MAJOR)
                    .orElse(false);
            case ALL_BREACHED -> true;
        };
    }

    private static Optional<BreachedStructureDefinition> getBaseDefinition(String structureKey) {
        return BreachedStructureDefinitions.ALL_STRUCTURES.stream()
                .filter(definition -> BreachedStructureDefinitions.key(definition).equals(structureKey))
                .findFirst();
    }

    private static Optional<BreachedStructureDefinition> getMajorDefinition(String placementKey) {
        String structureKey = structureKey(placementKey);
        return BreachedStructureDefinitions.PLANNED_STRUCTURES.stream()
                .filter(definition -> BreachedStructureDefinitions.key(definition).equals(structureKey))
                .findFirst();
    }

    private static boolean isPlannedStructureKey(String structureKey) {
        return BreachedStructureDefinitions.PLANNED_STRUCTURES.stream()
                .anyMatch(definition -> BreachedStructureDefinitions.key(definition).equals(structureKey));
    }

    private static ObstructionEvaluation evaluateObstruction(
            ServerWorld world,
            BreachedStructureDefinition definition,
            Vec3i footprintSize,
            BreachedStructureSite site
    ) {
        if (definition.maxVegetationObstruction() <= 0 && definition.maxSolidObstruction() <= 0) {
            return new ObstructionEvaluation(true, 0.0D, null);
        }

        int vegetationBlocks = 0;
        int solidBlocks = 0;
        int maxY = Math.min(world.getTopYInclusive(), site.surfaceY() + footprintSize.getY());

        for (int x = site.originX(); x < site.originX() + footprintSize.getX(); x += Math.max(1, definition.sampleStep())) {
            for (int z = site.originZ(); z < site.originZ() + footprintSize.getZ(); z += Math.max(1, definition.sampleStep())) {
                for (int y = site.surfaceY(); y < maxY; y++) {
                    BlockState state = world.getBlockState(new BlockPos(x, y, z));
                    if (state.isAir() || state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA)) {
                        continue;
                    }

                    if (isVegetation(state)) {
                        vegetationBlocks++;
                    } else if (!state.getCollisionShape(world, new BlockPos(x, y, z)).isEmpty()) {
                        solidBlocks++;
                    }
                }
            }
        }

        if (definition.maxVegetationObstruction() >= 0 && vegetationBlocks > definition.maxVegetationObstruction()) {
            return new ObstructionEvaluation(false, Double.MAX_VALUE,
                    "too much vegetation obstruction: " + vegetationBlocks
                            + " blocks, max " + definition.maxVegetationObstruction());
        }

        if (definition.maxSolidObstruction() >= 0 && solidBlocks > definition.maxSolidObstruction()) {
            return new ObstructionEvaluation(false, Double.MAX_VALUE,
                    "too much solid obstruction: " + solidBlocks
                            + " blocks, max " + definition.maxSolidObstruction());
        }

        return new ObstructionEvaluation(true, vegetationBlocks + (solidBlocks * 4.0D), null);
    }

    private static boolean isVegetation(BlockState state) {
        return state.getBlock() instanceof LeavesBlock
                || state.getBlock() instanceof PillarBlock
                || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.FERN)
                || state.isOf(Blocks.LARGE_FERN)
                || state.isOf(Blocks.VINE)
                || state.isOf(Blocks.SHORT_GRASS);
    }

    private static boolean isNaturalSurfaceObstruction(BlockState state) {
        return state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.ROOTED_DIRT)
                || state.isOf(Blocks.PODZOL)
                || state.isOf(Blocks.MYCELIUM)
                || state.isOf(Blocks.DIRT_PATH)
                || state.isOf(Blocks.FARMLAND)
                || state.isOf(Blocks.MOSS_BLOCK)
                || state.isOf(Blocks.SNOW)
                || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.FERN)
                || state.isOf(Blocks.LARGE_FERN)
                || state.isOf(Blocks.SHORT_GRASS);
    }

    private static boolean isStructureClearanceAnchor(BlockState state) {
        return !state.isAir()
                && !state.isOf(Blocks.WATER)
                && !state.isOf(Blocks.LAVA)
                && !isNaturalSurfaceObstruction(state);
    }

    private static boolean hasActivePortal(ServerWorld world, int centerX, int centerZ) {
        int topY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ);
        int minY = Math.max(world.getBottomY(), topY - 32);
        int maxY = Math.min(world.getTopYInclusive(), topY + 32);

        for (int xOffset = -PORTAL_ACTIVE_CHECK_RADIUS; xOffset <= PORTAL_ACTIVE_CHECK_RADIUS; xOffset++) {
            for (int zOffset = -PORTAL_ACTIVE_CHECK_RADIUS; zOffset <= PORTAL_ACTIVE_CHECK_RADIUS; zOffset++) {
                for (int y = minY; y <= maxY; y++) {
                    if (world.getBlockState(new BlockPos(centerX + xOffset, y, centerZ + zOffset)).isOf(Blocks.NETHER_PORTAL)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static void migrateLegacyCentralSpawnState(ServerWorld world, BreachedStructurePlacementState state) {
        String replacementKey = BreachedStructureDefinitions.key(BreachedStructureDefinitions.SWORD_STATUE);
        if (countPlacedStructures(state, replacementKey) > 0) {
            return;
        }

        CentralSpawnPoiState legacyState = CentralSpawnPoiState.get(world.getServer());
        if (!legacyState.isPlaced()) {
            return;
        }

        state.markPlaced(replacementKey, legacyState.getCenterX(), legacyState.getCenterZ());
        System.out.println("[Breached] Migrated legacy central spawn protection into generic structure placement state for "
                + replacementKey + ".");
    }

    private static void registerProtectionEvents() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (shouldProtect(world, player, pos)) {
                player.sendMessage(Text.literal("Protected Breached structures cannot be modified."), false);
                return false;
            }

            return true;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient() || !(player.getStackInHand(hand).getItem() instanceof BlockItem)) {
                return ActionResult.PASS;
            }

            ItemPlacementContext placementContext = new ItemPlacementContext(player, hand, player.getStackInHand(hand), hitResult);
            if (!placementContext.canPlace()) {
                return ActionResult.PASS;
            }

            if (canBypassProtection(player)) {
                return ActionResult.PASS;
            }

            if (!isInsideProtectedStructure(world, placementContext.getBlockPos())) {
                return ActionResult.PASS;
            }

            player.sendMessage(Text.literal("Protected Breached structures cannot be modified."), false);
            return ActionResult.FAIL;
        });
    }

    public static boolean isInsideProtectedStructure(World world, BlockPos pos) {
        if (world.isClient() || !world.getRegistryKey().equals(World.OVERWORLD) || !(world instanceof ServerWorld serverWorld)) {
            return false;
        }

        BreachedStructurePlacementState state = BreachedStructurePlacementState.get(serverWorld.getServer());
        migrateLegacyCentralSpawnState(serverWorld, state);

        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> placement : state.placements()) {
            Optional<BreachedStructureDefinition> definition = getProtectedDefinition(placement.getKey());
            if (definition.isEmpty()) {
                continue;
            }

            if (BreachedStructureSpawnManager.isInsideProtectionRadius(
                    definition.get(),
                    placement.getValue().centerX(),
                    placement.getValue().centerZ(),
                    pos
            )) {
                return true;
            }
        }

        return false;
    }

    private static Optional<BreachedStructureDefinition> getProtectedDefinition(String key) {
        String structureKey = structureKey(key);
        return BreachedStructureDefinitions.PROTECTED_STRUCTURES.stream()
                .filter(definition -> BreachedStructureDefinitions.key(definition).equals(structureKey))
                .findFirst();
    }

    private static boolean shouldProtect(World world, PlayerEntity player, BlockPos pos) {
        return !world.isClient()
                && !canBypassProtection(player)
                && isInsideProtectedStructure(world, pos);
    }

    private static boolean canBypassProtection(PlayerEntity player) {
        return player.isCreative() || player.isCreativeLevelTwoOp();
    }

    private record PendingStructurePlacement(
            long worldSeed,
            String structureKey,
            String placementKey,
            BreachedStructureSpawnManager.RadiusCandidate candidate
    ) {
    }

    private record PlannedCandidateEvaluation(
            PendingStructurePlacement pendingPlacement,
            StructureTemplate template,
            BreachedStructureSite site,
            double score,
            PlacementAttemptResult result
    ) {
    }

    private record MinorPoiLocalCandidate(int x, int z) {
    }

    private record MinorPoiPlacementChoice(
            BreachedStructureDefinition definition,
            String placementKey,
            StructureTemplate template,
            BreachedStructureSpawnManager.RadiusCandidate candidate,
            BreachedStructureSite site,
            int originX,
            int originZ,
            double score
    ) {
        private static final MinorPoiPlacementChoice NOT_READY = new MinorPoiPlacementChoice(
                null,
                "",
                null,
                null,
                null,
                0,
                0,
                Double.MAX_VALUE
        );
        private static final MinorPoiPlacementChoice SPREAD_CELL_FULL = new MinorPoiPlacementChoice(
                null,
                "",
                null,
                null,
                null,
                0,
                0,
                Double.MAX_VALUE
        );
        private static final MinorPoiPlacementChoice SPACING_BLOCKED = new MinorPoiPlacementChoice(
                null,
                "",
                null,
                null,
                null,
                0,
                0,
                Double.MAX_VALUE
        );
    }

    private record MinorPoiBlockEvaluation(
            int expectedBlocks,
            int matchingBlocks,
            int replacedBlocks,
            boolean hasInventoryContents
    ) {
        private int intactPercent() {
            if (expectedBlocks <= 0) {
                return 0;
            }

            return (int) Math.round((matchingBlocks * 100.0D) / expectedBlocks);
        }

        private int replacedBlockPercent() {
            if (expectedBlocks <= 0) {
                return 0;
            }

            return (int) Math.round((replacedBlocks * 100.0D) / expectedBlocks);
        }

        private boolean replacedBlockPercentAtLeast(int percent) {
            return expectedBlocks > 0 && (long) replacedBlocks * 100L >= (long) expectedBlocks * percent;
        }
    }

    private record PendingMinorPoiChunk(long worldSeed, int chunkX, int chunkZ) {
    }

    private record ForcedStructureChunk(
            long worldSeed,
            String structureKey,
            String placementKey,
            int candidateIndex,
            int chunkX,
            int chunkZ,
            int ticksLoaded
    ) {
        private ForcedStructureChunk(
                long worldSeed,
                String structureKey,
                String placementKey,
                int candidateIndex,
                int chunkX,
                int chunkZ
        ) {
            this(worldSeed, structureKey, placementKey, candidateIndex, chunkX, chunkZ, 0);
        }

        private ForcedStructureChunk withAdditionalTick() {
            return new ForcedStructureChunk(worldSeed, structureKey, placementKey, candidateIndex, chunkX, chunkZ, ticksLoaded + 1);
        }
    }

    private record ForcedRestockChunk(
            long worldSeed,
            String placementKey,
            int chunkX,
            int chunkZ,
            int ticksLoaded
    ) {
        private ForcedRestockChunk(long worldSeed, String placementKey, int chunkX, int chunkZ) {
            this(worldSeed, placementKey, chunkX, chunkZ, 0);
        }

        private ForcedRestockChunk withAdditionalTick() {
            return new ForcedRestockChunk(worldSeed, placementKey, chunkX, chunkZ, ticksLoaded + 1);
        }
    }

    private record ForcedMinorDespawnChunk(
            long worldSeed,
            String placementKey,
            int chunkX,
            int chunkZ,
            int ticksLoaded
    ) {
        private ForcedMinorDespawnChunk(long worldSeed, String placementKey, int chunkX, int chunkZ) {
            this(worldSeed, placementKey, chunkX, chunkZ, 0);
        }

        private ForcedMinorDespawnChunk withAdditionalTick() {
            return new ForcedMinorDespawnChunk(worldSeed, placementKey, chunkX, chunkZ, ticksLoaded + 1);
        }
    }

    private enum PlacementAttemptResult {
        READY,
        PLACED,
        HANDLED,
        FAILED,
        SKIPPED,
        NOT_READY
    }

    private enum MinorPoiAttemptResult {
        PLACED,
        FAILED,
        NOT_READY
    }

    private record ReservationChoice(
            BreachedStructureSpawnManager.RadiusCandidate candidate,
            double spacingPenalty
    ) {
    }

    private record SpacingEvaluation(boolean accepted, double penalty, String rejectionReason) {
    }

    private record ObstructionEvaluation(boolean accepted, double penalty, String rejectionReason) {
    }
}
