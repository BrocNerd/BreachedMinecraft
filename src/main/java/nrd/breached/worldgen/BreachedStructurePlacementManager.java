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
    private static final int PORTAL_ACTIVE_CHECK_RADIUS = 16;
    private static final int REQUIRED_CHUNK_LOADS_PER_TICK = 1;
    private static final int MAX_FORCED_CHUNK_TICKS = 200;
    private static final Set<PendingStructurePlacement> PENDING_PLACEMENTS = new HashSet<>();
    private static final Set<ForcedStructureChunk> FORCED_CHUNKS = new HashSet<>();

    private BreachedStructurePlacementManager() {
    }

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register(BreachedStructurePlacementManager::enqueuePlannedStructures);
        ServerTickEvents.END_WORLD_TICK.register(BreachedStructurePlacementManager::placePendingStructures);
        registerProtectionEvents();
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
        state.markPlaced(placementKey, placement);
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

    private static List<BreachedStructureSpawnManager.RadiusCandidate> generatePlannedCandidates(
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
        return BreachedStructureDefinitions.PLANNED_STRUCTURES.stream()
                .filter(definition -> BreachedStructureDefinitions.key(definition).equals(structureKey))
                .findFirst();
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
