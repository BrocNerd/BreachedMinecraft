package nrd.breached.worldgen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PillarBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BreachedStructurePlacementManager {
    private static final int CHUNK_SIZE = 16;
    private static final int PORTAL_ACTIVE_CHECK_RADIUS = 16;
    private static final int REQUIRED_CHUNK_LOADS_PER_TICK = 1;
    private static final int MAX_FORCED_CHUNK_TICKS = 200;
    private static final int MINOR_POI_CHANCE_DIVISOR = 10;
    private static final int MINOR_POI_LOCAL_CANDIDATES_PER_CHUNK = 6;
    private static final int MINOR_POI_CHUNK_INSET = 2;
    private static final int MINOR_POI_PLAYER_SCAN_INTERVAL_TICKS = 200;
    private static final int MINOR_POI_PLAYER_SCAN_RADIUS_CHUNKS = 2;
    private static final int MINOR_POI_PENDING_CHUNKS_PER_TICK = 2;
    private static final Set<PendingStructurePlacement> PENDING_PLACEMENTS = new HashSet<>();
    private static final Set<ForcedStructureChunk> FORCED_CHUNKS = new HashSet<>();
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
        tryPlacePendingMinorPoiChunks(world, state);
        tryPlaceMinorPoisNearPlayers(world, state);

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

            for (BreachedStructureSpawnManager.RadiusCandidate candidate : generatePlannedCandidates(world, activeDefinition)) {
                String placementKey = placementKey(structureKey, candidate.index());
                PendingStructurePlacement pendingPlacement = new PendingStructurePlacement(world.getSeed(), structureKey, placementKey, candidate);
                if (!PENDING_PLACEMENTS.remove(pendingPlacement)
                        || state.hasPlacement(placementKey)
                        || !state.hasReservation(placementKey)
                        || state.hasFailedCandidate(structureKey, candidate.index())) {
                    continue;
                }

                PlacementAttemptResult result = placePlannedCandidate(world, state, activeDefinition, structureKey, placementKey, candidate);
                if (result == PlacementAttemptResult.PLACED || result == PlacementAttemptResult.HANDLED) {
                    if (countPlacedStructures(state, structureKey) >= activeDefinition.countPerWorld()) {
                        PENDING_PLACEMENTS.removeIf(pending -> pending.worldSeed() == world.getSeed() && pending.structureKey().equals(structureKey));
                    }
                    break;
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
    }

    private static MinorPoiAttemptResult tryPlaceMinorPoisInChunk(
            ServerWorld world,
            BreachedStructurePlacementState state,
            ChunkPos chunkPos
    ) {
        if (!world.isChunkLoaded(chunkPos.x, chunkPos.z)) {
            return MinorPoiAttemptResult.NOT_READY;
        }

        for (BreachedStructureDefinition definition : BreachedStructureDefinitions.MINOR_POI_STRUCTURES) {
            BreachedStructureDefinition activeDefinition = getActiveDefinition(world, definition);
            if (!world.getRegistryKey().equals(activeDefinition.requiredDimension())) {
                continue;
            }

            String structureKey = BreachedStructureDefinitions.key(activeDefinition);
            if (countPlacedStructures(state, structureKey) >= activeDefinition.countPerWorld()) {
                continue;
            }

            int candidateIndex = minorCandidateIndex(chunkPos);
            String placementKey = placementKey(structureKey, candidateIndex);
            if (state.hasPlacement(placementKey) || state.hasFailedCandidate(structureKey, candidateIndex)) {
                continue;
            }

            java.util.Random random = createMinorPoiRandom(world.getSeed(), activeDefinition, chunkPos);
            if (random.nextInt(MINOR_POI_CHANCE_DIVISOR) != 0) {
                continue;
            }

            Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, activeDefinition);
            if (template.isEmpty()) {
                continue;
            }

            MinorPoiAttemptResult result = placeMinorPoiInChunk(
                    world,
                    state,
                    activeDefinition,
                    structureKey,
                    placementKey,
                    candidateIndex,
                    template.get(),
                    chunkPos,
                    random
            );
            if (result == MinorPoiAttemptResult.FAILED) {
                state.markCandidateFailed(structureKey, candidateIndex);
                continue;
            }

            if (result == MinorPoiAttemptResult.PLACED || result == MinorPoiAttemptResult.NOT_READY) {
                return result;
            }
        }

        return MinorPoiAttemptResult.FAILED;
    }

    private static void tryPlacePendingMinorPoiChunks(ServerWorld world, BreachedStructurePlacementState state) {
        int attempted = 0;
        for (PendingMinorPoiChunk pendingChunk : new HashSet<>(PENDING_MINOR_POI_CHUNKS)) {
            if (pendingChunk.worldSeed() != world.getSeed()) {
                continue;
            }

            if (attempted >= MINOR_POI_PENDING_CHUNKS_PER_TICK) {
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
        if (world.getTime() % MINOR_POI_PLAYER_SCAN_INTERVAL_TICKS != 0 || world.getPlayers().isEmpty()) {
            return;
        }

        for (PlayerEntity player : world.getPlayers()) {
            ChunkPos playerChunk = player.getChunkPos();
            for (int xOffset = -MINOR_POI_PLAYER_SCAN_RADIUS_CHUNKS; xOffset <= MINOR_POI_PLAYER_SCAN_RADIUS_CHUNKS; xOffset++) {
                for (int zOffset = -MINOR_POI_PLAYER_SCAN_RADIUS_CHUNKS; zOffset <= MINOR_POI_PLAYER_SCAN_RADIUS_CHUNKS; zOffset++) {
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

    private static MinorPoiAttemptResult placeMinorPoiInChunk(
            ServerWorld world,
            BreachedStructurePlacementState state,
            BreachedStructureDefinition definition,
            String structureKey,
            String placementKey,
            int candidateIndex,
            StructureTemplate template,
            ChunkPos chunkPos,
            java.util.Random random
    ) {
        Vec3i footprintSize = BreachedStructureSpawnManager.getRotatedSize(template.getSize(), definition.rotation());
        boolean skippedUnloadedFootprint = false;
        for (int attempt = 0; attempt < MINOR_POI_LOCAL_CANDIDATES_PER_CHUNK; attempt++) {
            int x = chunkPos.x * CHUNK_SIZE + MINOR_POI_CHUNK_INSET + random.nextInt(CHUNK_SIZE - (MINOR_POI_CHUNK_INSET * 2));
            int z = chunkPos.z * CHUNK_SIZE + MINOR_POI_CHUNK_INSET + random.nextInt(CHUNK_SIZE - (MINOR_POI_CHUNK_INSET * 2));
            if (!isInsideDefinitionRadius(definition, x, z)) {
                continue;
            }

            BreachedStructureSpawnManager.RadiusCandidate candidate = new BreachedStructureSpawnManager.RadiusCandidate(
                    candidateIndex,
                    x,
                    z,
                    distanceFromDefinitionCenter(definition, x, z),
                    0.0D
            );
            SpacingEvaluation spacing = evaluateSpacing(state, definition, structureKey, placementKey, candidate);
            if (!spacing.accepted()) {
                continue;
            }

            int originX = getPlacementOriginX(definition, candidate);
            int originZ = getPlacementOriginZ(definition, candidate);
            if (!isFootprintLoaded(world, originX, originZ, footprintSize)) {
                skippedUnloadedFootprint = true;
                continue;
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
                continue;
            }

            ObstructionEvaluation obstruction = evaluateObstruction(world, definition, footprintSize, site);
            if (!obstruction.accepted()) {
                continue;
            }

            BreachedStructurePlacement placement = BreachedStructureSpawnManager.place(
                    world,
                    definition,
                    template,
                    originX,
                    getPlacementOriginY(world, definition, candidate),
                    originZ,
                    definition.mirror(),
                    definition.rotation(),
                    site
            );
            BreachedStructureSupportGenerator.generate(world, definition, placement);
            clearNaturalBlocksAboveStructure(world, definition, placement);
            state.markPlaced(placementKey, placement, world.getTime());
            System.out.println("[Breached] Placed minor POI " + definition.logName()
                    + " from chunk " + chunkPos.x + ", " + chunkPos.z
                    + " at x " + placement.origin().getX()
                    + ", y " + placement.origin().getY()
                    + ", z " + placement.origin().getZ() + ".");
            return MinorPoiAttemptResult.PLACED;
        }

        return skippedUnloadedFootprint ? MinorPoiAttemptResult.NOT_READY : MinorPoiAttemptResult.FAILED;
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

                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
                    clearedBlocks++;
                }
            }
        }

        if (clearedBlocks > 0) {
            System.out.println("[Breached] Cleared " + clearedBlocks
                    + " natural surface obstruction blocks above placed blocks in " + placement.logName() + ".");
        }
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

            Optional<BreachedStructureSpawnManager.RadiusCandidate> candidate = getReservedCandidate(world, definition.get(), reservation);
            if (candidate.isEmpty()) {
                continue;
            }

            ChunkPos chunkPos = new ChunkPos(Math.floorDiv(reservation.x(), 16), Math.floorDiv(reservation.z(), 16));
            ForcedStructureChunk forcedChunk = new ForcedStructureChunk(
                    world.getSeed(),
                    reservation.structureKey(),
                    placementKey,
                    reservation.candidateIndex(),
                    chunkPos.x,
                    chunkPos.z
            );
            if (!isForceLoaded(world.getSeed(), placementKey)) {
                FORCED_CHUNKS.add(forcedChunk);
                world.setChunkForced(chunkPos.x, chunkPos.z, true);
                System.out.println("[Breached] Force-loading required structure chunk "
                        + chunkPos.x + ", " + chunkPos.z
                        + " for " + placementKey + ".");
            }

            world.getChunk(chunkPos.x, chunkPos.z);
            PENDING_PLACEMENTS.add(new PendingStructurePlacement(world.getSeed(), reservation.structureKey(), placementKey, candidate.get()));
            loadedThisTick++;
        }
    }

    private static boolean isForceLoaded(long worldSeed, String placementKey) {
        return FORCED_CHUNKS.stream()
                .anyMatch(forcedChunk -> forcedChunk.worldSeed() == worldSeed
                        && forcedChunk.placementKey().equals(placementKey));
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
        int penaltyCompare = Double.compare(left.spacingPenalty(), right.spacingPenalty());
        if (penaltyCompare != 0) {
            return penaltyCompare;
        }

        return Integer.compare(left.candidate().index(), right.candidate().index());
    }

    private static PlacementAttemptResult placePlannedCandidate(
            ServerWorld world,
            BreachedStructurePlacementState state,
            BreachedStructureDefinition definition,
            String structureKey,
            String placementKey,
            BreachedStructureSpawnManager.RadiusCandidate candidate
    ) {
        if (isHandledByPrePlacementCheck(world, state, definition, placementKey, candidate)) {
            return PlacementAttemptResult.HANDLED;
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
            return PlacementAttemptResult.FAILED;
        }

        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, definition);
        if (template.isEmpty()) {
            System.out.println("[Breached] Skipped planned candidate " + candidate.index()
                    + " for " + definition.logName()
                    + " because the structure template is missing; it was not marked failed.");
            return PlacementAttemptResult.SKIPPED;
        }

        BreachedStructureSite site = BreachedStructureSpawnManager.evaluateSite(
                world,
                definition,
                template.get(),
                getPlacementOriginX(definition, candidate),
                getPlacementOriginZ(definition, candidate),
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
            return PlacementAttemptResult.FAILED;
        }

        Vec3i footprintSize = BreachedStructureSpawnManager.getRotatedSize(template.get().getSize(), definition.rotation());
        ObstructionEvaluation obstruction = evaluateObstruction(world, definition, footprintSize, site);
        if (!obstruction.accepted()) {
            state.markCandidateFailed(structureKey, candidate.index());
            System.out.println("[Breached] Rejected planned candidate " + candidate.index()
                    + " for " + definition.logName()
                    + " at x " + candidate.x()
                    + ", z " + candidate.z()
                    + ", radius " + candidate.radius()
                    + ": " + obstruction.rejectionReason() + ".");
            return PlacementAttemptResult.FAILED;
        }

        BreachedStructurePlacement placement = BreachedStructureSpawnManager.place(
                world,
                definition,
                template.get(),
                getPlacementOriginX(definition, candidate),
                getPlacementOriginY(world, definition, candidate),
                getPlacementOriginZ(definition, candidate),
                definition.mirror(),
                definition.rotation(),
                site
        );
        BreachedStructureSupportGenerator.generate(world, definition, placement);
        state.markPlaced(placementKey, placement, world.getTime());
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
        return BreachedStructureDefinitions.applyPresetOverrides(
                definition,
                BreachedDimensionRules.getBreachedPreset(world.getServer())
        );
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
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructureSpawnManager.RadiusCandidate candidate
    ) {
        return BreachedStructureSpawnManager.getSurfaceY(world, candidate.x(), candidate.z()) + definition.placementOffsetY();
    }

    private static int getPlacementOriginZ(
            BreachedStructureDefinition definition,
            BreachedStructureSpawnManager.RadiusCandidate candidate
    ) {
        return candidate.z() + definition.placementOffsetZ();
    }

    private static java.util.Random createMinorPoiRandom(
            long worldSeed,
            BreachedStructureDefinition definition,
            ChunkPos chunkPos
    ) {
        long seed = worldSeed
                ^ definition.seedSalt()
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
        int minChunkX = Math.floorDiv(originX, CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(originZ, CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(originX + footprintSize.getX() - 1, CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(originZ + footprintSize.getZ() - 1, CHUNK_SIZE);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    return false;
                }
            }
        }

        return true;
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
            if (isPlacementForStructure(placement.getKey(), structureKey)) {
                placed++;
            }
        }

        return placed;
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
            if (!shouldProtect(world, player, pos)) {
                return true;
            }

            player.sendMessage(Text.literal("Protected Breached structures cannot be modified."), false);
            return false;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient() || canBypassProtection(player) || !(player.getStackInHand(hand).getItem() instanceof BlockItem)) {
                return ActionResult.PASS;
            }

            ItemPlacementContext placementContext = new ItemPlacementContext(player, hand, player.getStackInHand(hand), hitResult);
            if (!placementContext.canPlace() || !isInsideProtectedStructure(world, placementContext.getBlockPos())) {
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

    private enum PlacementAttemptResult {
        PLACED,
        HANDLED,
        FAILED,
        SKIPPED
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
