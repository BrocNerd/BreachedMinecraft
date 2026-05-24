package nrd.breached.worldgen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
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
    private static final int EXTRA_PLANNED_CANDIDATES = 8;
    private static final int PORTAL_ACTIVE_CHECK_RADIUS = 16;
    private static final Set<PendingStructurePlacement> PENDING_PLACEMENTS = new HashSet<>();

    private BreachedStructurePlacementManager() {
    }

    public static void register() {
        ServerWorldEvents.LOAD.register((server, world) -> reservePlannedStructures(world, BreachedStructurePlacementState.get(server)));
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
        if (PENDING_PLACEMENTS.isEmpty()) {
            return;
        }

        BreachedStructurePlacementState state = BreachedStructurePlacementState.get(world.getServer());
        migrateLegacyCentralSpawnState(world, state);
        reservePlannedStructures(world, state);

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

            for (BreachedStructureSpawnManager.RadiusCandidate candidate : generatePlannedCandidates(world, definition)) {
                String placementKey = placementKey(structureKey, candidate.index());
                if (state.hasPlacement(placementKey)
                        || state.hasReservation(placementKey)
                        || state.hasFailedCandidate(structureKey, candidate.index())) {
                    continue;
                }

                Optional<String> rejectionReason = getSpacingRejectionReason(state, definition, structureKey, placementKey, candidate);
                if (rejectionReason.isPresent()) {
                    state.markCandidateFailed(structureKey, candidate.index());
                    System.out.println("[Breached] Rejected planned reservation candidate " + candidate.index()
                            + " for " + definition.logName()
                            + " at x " + candidate.x()
                            + ", z " + candidate.z()
                            + ", radius " + candidate.radius()
                            + ": " + rejectionReason.get() + ".");
                    continue;
                }

                state.reservePlacement(placementKey, structureKey, candidate.index(), candidate.x(), candidate.z());
                System.out.println("[Breached] Reserved planned structure " + definition.logName()
                        + " candidate " + candidate.index()
                        + " at x " + candidate.x()
                        + ", z " + candidate.z()
                        + " as " + placementKey + ".");
                neededReservations--;
                if (neededReservations <= 0) {
                    break;
                }
            }
        }
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

        Optional<String> spacingRejectionReason = getSpacingRejectionReason(state, definition, structureKey, placementKey, candidate);
        if (spacingRejectionReason.isPresent()) {
            state.markCandidateFailed(structureKey, candidate.index());
            System.out.println("[Breached] Rejected planned candidate " + candidate.index()
                    + " for " + definition.logName()
                    + " at x " + candidate.x()
                    + ", z " + candidate.z()
                    + ", radius " + candidate.radius()
                    + ": " + spacingRejectionReason.get() + ".");
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
                candidate.x(),
                candidate.z(),
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

        BreachedStructurePlacement placement = BreachedStructureSpawnManager.place(
                world,
                definition,
                template.get(),
                candidate.x(),
                site.surfaceY(),
                candidate.z(),
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
                Math.max(1, definition.countPerWorld() + EXTRA_PLANNED_CANDIDATES),
                definition.roughlyOpposed()
        );
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

    private static Optional<String> getSpacingRejectionReason(
            BreachedStructurePlacementState state,
            BreachedStructureDefinition definition,
            String structureKey,
            String placementKey,
            BreachedStructureSpawnManager.RadiusCandidate candidate
    ) {
        int requiredSpacing = definition.spacingFromOtherBreachedStructures();
        if (requiredSpacing <= 0) {
            return Optional.empty();
        }

        long requiredSpacingSquared = (long) requiredSpacing * requiredSpacing;
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> placement : state.placements()) {
            if (placement.getKey().equals(placementKey)) {
                continue;
            }

            long xDistance = candidate.x() - placement.getValue().centerX();
            long zDistance = candidate.z() - placement.getValue().centerZ();
            long distanceSquared = xDistance * xDistance + zDistance * zDistance;
            if (distanceSquared < requiredSpacingSquared) {
                return Optional.of("too close to existing " + structureKey
                        + " placement at x " + placement.getValue().centerX()
                        + ", z " + placement.getValue().centerZ()
                        + "; required spacing " + requiredSpacing);
            }
        }

        for (Map.Entry<String, BreachedStructurePlacementState.ReservedPlacement> reservation : state.reservations()) {
            if (reservation.getKey().equals(placementKey)) {
                continue;
            }

            long xDistance = candidate.x() - reservation.getValue().x();
            long zDistance = candidate.z() - reservation.getValue().z();
            long distanceSquared = xDistance * xDistance + zDistance * zDistance;
            if (distanceSquared < requiredSpacingSquared) {
                return Optional.of("too close to reserved " + reservation.getValue().structureKey()
                        + " candidate " + reservation.getValue().candidateIndex()
                        + " at x " + reservation.getValue().x()
                        + ", z " + reservation.getValue().z()
                        + "; required spacing " + requiredSpacing);
            }
        }

        return Optional.empty();
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

    private enum PlacementAttemptResult {
        PLACED,
        HANDLED,
        FAILED,
        SKIPPED
    }
}
