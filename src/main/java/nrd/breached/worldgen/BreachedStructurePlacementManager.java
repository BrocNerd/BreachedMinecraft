package nrd.breached.worldgen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.LootableInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.loot.LootTable;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.WorldProperties;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.level.ServerWorldProperties;
import nrd.breached.config.BreachedConfig;
import nrd.breached.landlock.LandlockClaimManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class BreachedStructurePlacementManager {
    private static final int CHUNK_SIZE = 16;
    private static final int PORTAL_ACTIVE_CHECK_RADIUS = 16;
    private static final int REQUIRED_CHUNK_LOADS_PER_TICK = 1;
    private static final int MAX_FORCED_CHUNK_TICKS = 200;
    private static final int MAX_FORCED_RESTOCK_CHUNK_TICKS = 200;
    private static final int MAX_FORCED_MINOR_DESPAWN_CHUNK_TICKS = 200;
    private static final int MAX_PLANNED_STRUCTURE_COMPLETIONS_PER_TICK = 1;
    private static final int REMOVE_BLOCK_WITHOUT_DROPS_FLAGS = Block.NOTIFY_LISTENERS | Block.SKIP_DROPS;
    private static final int PORTAL_EVENT_DURATION_TICKS = 20 * 60 * 60;
    private static final int PORTAL_MIN_RESTOCK_INTERVAL_TICKS = 20 * 60 * 65;
    private static final int PORTAL_MAX_RESTOCK_INTERVAL_TICKS = 20 * 60 * 120;
    private static final int PORTAL_EVENT_TICK_INTERVAL = 20;
    private static final int MAX_PORTAL_FRAME_INTERIOR_WIDTH = 21;
    private static final int MAX_PORTAL_FRAME_INTERIOR_HEIGHT = 21;
    private static final int[] PORTAL_EVENT_WARNING_SECONDS = {
            30 * 60,
            5 * 60,
            60,
            30,
            15,
            5,
            4,
            3,
            2,
            1
    };
    private static final String TOWNHALL_STRUCTURE_KEY = BreachedStructureDefinitions.key(BreachedStructureDefinitions.TOWNHALL);
    private static final String END_PORTAL_STRUCTURE_KEY = BreachedStructureDefinitions.key(BreachedStructureDefinitions.END_PORTAL);
    private static final String PORTAL_STRUCTURE_KEY = BreachedStructureDefinitions.key(BreachedStructureDefinitions.OFFICIAL_NETHER_PORTAL);
    private static final String EYEBALL_STRUCTURE_KEY = BreachedStructureDefinitions.key(BreachedStructureDefinitions.EYEBALL);
    private static final String SKYHOME_STRUCTURE_KEY = BreachedStructureDefinitions.key(BreachedStructureDefinitions.SKY_HOME);
    private static final int END_PORTAL_TOWNHALL_Y_OFFSET = 96;
    private static final int EYEBALL_BORDER_MARGIN_BLOCKS = 100;
    private static final int EYEBALL_CANDIDATE_SPREAD_BLOCKS = 48;
    private static final int EYEBALL_ORIGIN_Y = 200;
    private static final int MAJOR_STRUCTURE_LANDLOCK_EXCLUSION_MARGIN = 12;
    private static final int SKYHOME_MIN_ORIGIN_Y = 120;
    private static final int SKYHOME_MAX_ORIGIN_Y = 200;
    private static final int BIG_BOAT_MIN_EXPANDED_CANDIDATES = 384;
    private static final int BIG_BOAT_MAX_EXPANDED_CANDIDATES = 768;
    private static final int MINOR_FOREST_VEGETATION_OBSTRUCTION_LIMIT = 256;
    private static final int MINOR_POI_PLAYER_REPLACED_BLOCK_PERCENT = 25;
    private static final int MINOR_POI_CHUNK_INSET = 2;
    private static final int MAJOR_ZONE_MESSAGE_INTERVAL_TICKS = 20;
    private static final int TOWNHALL_SPAWN_PLAYER_HEIGHT = 2;
    private static final Block TOWNHALL_SPAWN_MARKER_BLOCK = Blocks.LIGHT_BLUE_CARPET;
    private static final Set<PendingStructurePlacement> PENDING_PLACEMENTS = new HashSet<>();
    private static final Set<ForcedStructureChunk> FORCED_CHUNKS = new HashSet<>();
    private static final Set<ForcedRestockChunk> FORCED_RESTOCK_CHUNKS = new HashSet<>();
    private static final Set<ForcedMinorDespawnChunk> FORCED_MINOR_DESPAWN_CHUNKS = new HashSet<>();
    private static final Set<PendingMinorPoiChunk> PENDING_MINOR_POI_CHUNKS = new HashSet<>();
    private static final Set<Long> COMPLETED_PLANNED_STRUCTURE_WORLDS = new HashSet<>();
    private static final Map<String, Set<BlockPos>> EXACT_PROTECTION_BLOCKS = new HashMap<>();
    private static final Map<UUID, String> PLAYER_MAJOR_ZONE_KEYS = new HashMap<>();

    private BreachedStructurePlacementManager() {
    }

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register(BreachedStructurePlacementManager::handleChunkLoad);
        ServerTickEvents.END_WORLD_TICK.register(BreachedStructurePlacementManager::placePendingStructures);
        ServerTickEvents.END_WORLD_TICK.register(BreachedStructurePlacementManager::tickMajorZoneMessages);
        registerProtectionEvents();
        registerSafezoneEvents();
    }

    private static void handleChunkLoad(ServerWorld world, WorldChunk chunk) {
        if (!isStructurePlacementWorld(world)) {
            return;
        }

        if (!COMPLETED_PLANNED_STRUCTURE_WORLDS.contains(world.getSeed())) {
            enqueuePlannedStructures(world, chunk);
        }
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
        if (!isStructurePlacementWorld(world)) {
            return;
        }

        StructureTickDiagnostics diagnostics = new StructureTickDiagnostics(world.getTime());
        long tickStartNanos = System.nanoTime();
        try {
            long sectionStartNanos = System.nanoTime();
            BreachedStructurePlacementState state = BreachedStructurePlacementState.get(world.getServer());
            migrateLegacyCentralSpawnState(world, state);
            tickPortalEvent(world, state, PortalEventType.NETHER);
            tickPortalEvent(world, state, PortalEventType.END);

            boolean hasScheduledWork = hasScheduledPlacementWork(world);
            if (!hasScheduledWork && COMPLETED_PLANNED_STRUCTURE_WORLDS.contains(world.getSeed())) {
                diagnostics.stateSetupNanos += elapsedSince(sectionStartNanos);
                return;
            }

            boolean hasForcedStructureChunks = hasForcedStructureChunksForWorld(world);
            boolean hasPlannedWork = !COMPLETED_PLANNED_STRUCTURE_WORLDS.contains(world.getSeed())
                    && hasPlannedStructureWork(world, state);
            if (hasPlannedWork) {
                COMPLETED_PLANNED_STRUCTURE_WORLDS.remove(world.getSeed());
            } else {
                COMPLETED_PLANNED_STRUCTURE_WORLDS.add(world.getSeed());
            }
            diagnostics.stateSetupNanos += elapsedSince(sectionStartNanos);

            if (!hasScheduledWork && !hasPlannedWork) {
                return;
            }

            if (hasPlannedWork || hasPendingMajorPlacementsForWorld(world) || hasForcedStructureChunks) {
                sectionStartNanos = System.nanoTime();
                reservePlannedStructures(world, state);
                forceLoadRequiredReservedChunks(world, state);
                releaseCompletedForcedChunks(world, state);
                diagnostics.plannedPreparationNanos += elapsedSince(sectionStartNanos);
            }

            if (isMinorPoiDespawnScanDue(world) || hasForcedMinorDespawnChunksForWorld(world)) {
                sectionStartNanos = System.nanoTime();
                despawnAndRetireMinorPois(world, state, diagnostics);
                releaseExpiredForcedMinorDespawnChunks(world, state);
                diagnostics.minorDespawnNanos += elapsedSince(sectionStartNanos);
            }
            if (hasPendingMinorPoiChunksForWorld(world)) {
                sectionStartNanos = System.nanoTime();
                tryPlacePendingMinorPoiChunks(world, state, diagnostics);
                diagnostics.pendingMinorNanos += elapsedSince(sectionStartNanos);
            }
            if (isMinorPoiPlayerScanDue(world)) {
                sectionStartNanos = System.nanoTime();
                tryPlaceMinorPoisNearPlayers(world, state, diagnostics);
                diagnostics.playerMinorNanos += elapsedSince(sectionStartNanos);
            }
            if (isMajorRestockScanDue(world) || hasForcedRestockChunksForWorld(world)) {
                sectionStartNanos = System.nanoTime();
                restockMajorStructureLoot(world, state, diagnostics);
                releaseExpiredForcedRestockChunks(world);
                diagnostics.majorRestockNanos += elapsedSince(sectionStartNanos);
            }

            sectionStartNanos = System.nanoTime();
            try {
                if (!hasPendingMajorPlacementsForWorld(world)) {
                    return;
                }

                int completedPlannedStructures = 0;
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

                        diagnostics.plannedCandidateEvaluations++;
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
                        completedPlannedStructures++;
                        diagnostics.plannedStructuresCompleted++;
                        if (completedPlannedStructures >= MAX_PLANNED_STRUCTURE_COMPLETIONS_PER_TICK) {
                            return;
                        }
                        continue;
                    }

                    if (bestEvaluation != null) {
                        placeEvaluatedPlannedCandidate(world, state, activeDefinition, bestEvaluation);
                        if (countPlacedStructures(state, structureKey) >= activeDefinition.countPerWorld()) {
                            PENDING_PLACEMENTS.removeIf(pending -> pending.worldSeed() == world.getSeed() && pending.structureKey().equals(structureKey));
                        }
                        completedPlannedStructures++;
                        diagnostics.plannedStructuresCompleted++;
                        if (completedPlannedStructures >= MAX_PLANNED_STRUCTURE_COMPLETIONS_PER_TICK) {
                            return;
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
            } finally {
                diagnostics.plannedPlacementNanos += elapsedSince(sectionStartNanos);
            }
        } finally {
            diagnostics.totalNanos = elapsedSince(tickStartNanos);
            diagnostics.logIfSlow();
        }
    }

    private static void tickMajorZoneMessages(ServerWorld world) {
        if (!isStructurePlacementWorld(world) || world.getTime() % MAJOR_ZONE_MESSAGE_INTERVAL_TICKS != 0) {
            return;
        }

        BreachedStructurePlacementState state = BreachedStructurePlacementState.get(world.getServer());
        Set<UUID> activePlayerIds = new HashSet<>();
        for (ServerPlayerEntity player : world.getPlayers()) {
            UUID playerId = player.getUuid();
            activePlayerIds.add(playerId);

            Optional<MajorStructureZone> currentZone = getCurrentMajorStructureZone(world, state, player.getBlockPos());
            if (currentZone.isEmpty()) {
                PLAYER_MAJOR_ZONE_KEYS.remove(playerId);
                continue;
            }

            String previousZoneKey = PLAYER_MAJOR_ZONE_KEYS.get(playerId);
            if (currentZone.get().placementKey().equals(previousZoneKey)) {
                continue;
            }

            PLAYER_MAJOR_ZONE_KEYS.put(playerId, currentZone.get().placementKey());
            player.sendMessage(currentZone.get().entryMessage(), true);
        }

        PLAYER_MAJOR_ZONE_KEYS.keySet().removeIf(playerId -> !activePlayerIds.contains(playerId));
    }

    private static Optional<MajorStructureZone> getCurrentMajorStructureZone(
            ServerWorld world,
            BreachedStructurePlacementState state,
            BlockPos pos
    ) {
        MajorStructureZone bestZone = null;
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> entry : state.placements()) {
            BreachedStructurePlacementState.SavedPlacement placement = entry.getValue();
            if (!placement.active()) {
                continue;
            }

            Optional<BreachedStructureDefinition> definition = getMajorDefinition(entry.getKey())
                    .map(baseDefinition -> getActiveDefinition(world, baseDefinition));
            if (definition.isEmpty() || !world.getRegistryKey().equals(definition.get().requiredDimension())) {
                continue;
            }

            long distanceSquared = getMajorStructureZoneDistanceSquared(definition.get(), placement, pos);
            if (distanceSquared < 0L) {
                continue;
            }

            MajorStructureZone zone = new MajorStructureZone(
                    entry.getKey(),
                    getMajorZoneEntryMessage(definition.get()),
                    definition.get().priority(),
                    distanceSquared
            );
            if (bestZone == null || isBetterMajorStructureZone(zone, bestZone)) {
                bestZone = zone;
            }
        }

        return Optional.ofNullable(bestZone);
    }

    private static long getMajorStructureZoneDistanceSquared(
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement,
            BlockPos pos
    ) {
        if (definition.protectedStructure() && definition.protectionRadius() > 0) {
            long xDistance = pos.getX() - placement.centerX();
            long yDistance = pos.getY() - getProtectedCenterY(placement, pos);
            long zDistance = pos.getZ() - placement.centerZ();
            long distanceSquared = xDistance * xDistance + yDistance * yDistance + zDistance * zDistance;
            return distanceSquared <= definition.protectionRadiusSquared() ? distanceSquared : -1L;
        }

        if ((isExactProtectedStructure(definition) || isVolumeProtectedStructure(definition))
                && isInsidePlacementBounds(placement, pos)) {
            return 0L;
        }

        return -1L;
    }

    private static boolean isBetterMajorStructureZone(MajorStructureZone candidate, MajorStructureZone current) {
        if (candidate.priority() != current.priority()) {
            return candidate.priority() > current.priority();
        }

        return candidate.distanceSquared() < current.distanceSquared();
    }

    private static Text getMajorZoneEntryMessage(BreachedStructureDefinition definition) {
        String structureKey = BreachedStructureDefinitions.key(definition);
        if (structureKey.equals(TOWNHALL_STRUCTURE_KEY)) {
            return Text.literal("Entering Town Hall Safezone").formatted(Formatting.GOLD);
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.SWORD_STATUE))) {
            return Text.literal("Entering Statue").formatted(Formatting.BLACK);
        }
        if (structureKey.equals(PORTAL_STRUCTURE_KEY)) {
            return Text.literal("Entering Nether Portal").formatted(Formatting.DARK_PURPLE);
        }
        if (structureKey.equals(END_PORTAL_STRUCTURE_KEY)) {
            return Text.literal("Entering End Portal").formatted(Formatting.DARK_PURPLE);
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.HORACE))) {
            return Text.literal("Entering Horace").formatted(Formatting.RED);
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.PINK_TREE))) {
            return Text.literal("Entering Great Tree").formatted(Formatting.LIGHT_PURPLE);
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.BIG_BOAT))) {
            return Text.literal("Entering Ship").formatted(Formatting.BLUE);
        }
        if (structureKey.equals(EYEBALL_STRUCTURE_KEY)) {
            return Text.literal("Entering Eyeball").formatted(Formatting.DARK_RED);
        }

        return Text.literal("Entering major structure").formatted(Formatting.GOLD);
    }

    private static boolean isStructurePlacementWorld(ServerWorld world) {
        return world.getRegistryKey().equals(World.OVERWORLD);
    }

    public static Optional<BlockPos> getTownhallSpawnPos(ServerWorld world) {
        if (!isStructurePlacementWorld(world)) {
            return Optional.empty();
        }

        BreachedStructurePlacementState state = BreachedStructurePlacementState.get(world.getServer());
        return getActiveTownhallPlacement(state)
                .map(placement -> findTownhallSpawnPos(world, placement));
    }

    public static List<BreachedMapMarker> getBreachedMapMarkers(ServerWorld world) {
        if (!isStructurePlacementWorld(world)) {
            return List.of();
        }

        BreachedStructurePlacementState state = BreachedStructurePlacementState.get(world.getServer());
        migrateLegacyCentralSpawnState(world, state);

        List<BreachedMapMarker> markers = new ArrayList<>();
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> entry : state.placements()) {
            BreachedStructurePlacementState.SavedPlacement placement = entry.getValue();
            String structureKey = structureKey(entry.getKey());
            if (!placement.active() || !isPlannedStructureKey(structureKey)) {
                continue;
            }

            Optional<BreachedStructureDefinition> definition = getActiveDefinition(world, structureKey);
            if (definition.isEmpty() || !world.getRegistryKey().equals(definition.get().requiredDimension())) {
                continue;
            }

            markers.add(new BreachedMapMarker(
                    getBreachedMapLabel(structureKey, definition.get()),
                    placement.centerX(),
                    placement.centerZ(),
                    getBreachedMapColor(structureKey)
            ));
        }

        markers.sort(Comparator
                .comparing(BreachedMapMarker::label)
                .thenComparingInt(BreachedMapMarker::x)
                .thenComparingInt(BreachedMapMarker::z));
        return List.copyOf(markers);
    }

    private static String getBreachedMapLabel(String structureKey, BreachedStructureDefinition definition) {
        if (structureKey.equals(TOWNHALL_STRUCTURE_KEY)) {
            return "Town Hall";
        }
        if (structureKey.equals(END_PORTAL_STRUCTURE_KEY)) {
            return "End Portal";
        }
        if (structureKey.equals(PORTAL_STRUCTURE_KEY)) {
            return "Nether Portal";
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.SWORD_STATUE))) {
            return "Statue";
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.HORACE))) {
            return "Horace";
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.PINK_TREE))) {
            return "Great Tree";
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.BIG_BOAT))) {
            return "Ship";
        }
        if (structureKey.equals(EYEBALL_STRUCTURE_KEY)) {
            return "Eyeball";
        }

        return formatBreachedMapLabel(definition.logName());
    }

    private static String formatBreachedMapLabel(String rawLabel) {
        String withoutExtension = rawLabel.endsWith(".nbt")
                ? rawLabel.substring(0, rawLabel.length() - 4)
                : rawLabel;
        String[] words = withoutExtension.replace('_', ' ').replace('-', ' ').split(" ");
        List<String> formattedWords = new ArrayList<>();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }

            formattedWords.add(word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase());
        }

        return String.join(" ", formattedWords);
    }

    private static int getBreachedMapColor(String structureKey) {
        if (structureKey.equals(TOWNHALL_STRUCTURE_KEY)) {
            return 0xFFE2D2B0;
        }
        if (structureKey.equals(END_PORTAL_STRUCTURE_KEY)) {
            return 0xFFC084FC;
        }
        if (structureKey.equals(PORTAL_STRUCTURE_KEY)) {
            return 0xFFFF6B5A;
        }
        if (structureKey.equals(EYEBALL_STRUCTURE_KEY)) {
            return 0xFFFF4B7A;
        }

        return 0xFF7DD3FC;
    }

    public static Optional<BlockPos> ensureTownhallSpawnReady(ServerWorld world) {
        if (!isStructurePlacementWorld(world)) {
            return Optional.empty();
        }

        BreachedStructurePlacementState state = BreachedStructurePlacementState.get(world.getServer());
        migrateLegacyCentralSpawnState(world, state);
        Optional<BreachedStructurePlacementState.SavedPlacement> existingTownhallPlacement = getActiveTownhallPlacement(state);
        if (existingTownhallPlacement.isPresent()) {
            return Optional.of(findTownhallSpawnPos(world, existingTownhallPlacement.get()));
        }

        BreachedStructureDefinition definition = getActiveDefinition(world, BreachedStructureDefinitions.TOWNHALL);
        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, definition);
        if (template.isEmpty()) {
            return Optional.empty();
        }

        List<BreachedStructureSpawnManager.RadiusCandidate> candidates = generateTownhallCenteredCandidate(world, definition);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        BreachedStructureSpawnManager.RadiusCandidate candidate = candidates.get(0);
        Vec3i footprintSize = BreachedStructureSpawnManager.getRotatedSize(template.get().getSize(), definition.rotation());
        int originX = getPlacementOriginX(definition, candidate);
        int originZ = getPlacementOriginZ(definition, candidate);
        loadFootprintChunks(world, originX, originZ, footprintSize);

        BreachedStructureSite site = BreachedStructureSpawnManager.evaluateSite(
                world,
                definition,
                template.get(),
                originX,
                originZ,
                candidate.radius()
        );
        if (!site.accepted()) {
            System.out.println("[Breached] Could not pre-place Town Hall for spawn: " + site.rejectionReason() + ".");
            return Optional.empty();
        }

        String structureKey = BreachedStructureDefinitions.key(definition);
        String placementKey = placementKey(structureKey, candidate.index());
        BreachedStructurePlacement placement = BreachedStructureSpawnManager.place(
                world,
                definition,
                template.get(),
                originX,
                getPlacementOriginY(world, state, definition, site, template.get()),
                originZ,
                definition.mirror(),
                definition.rotation(),
                site
        );
        BreachedStructureSupportGenerator.generate(world, definition, placement);
        state.markPlaced(
                placementKey,
                placement,
                world.getTime(),
                getSavedLootContainers(world, definition, template.get(), placement),
                createNextLootRestockTime(world, placementKey)
        );
        return getActiveTownhallPlacement(state)
                .map(savedPlacement -> findTownhallSpawnPos(world, savedPlacement));
    }

    private static void loadFootprintChunks(ServerWorld world, int originX, int originZ, Vec3i footprintSize) {
        int minChunkX = Math.floorDiv(originX, CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(originZ, CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(originX + Math.max(1, footprintSize.getX()) - 1, CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(originZ + Math.max(1, footprintSize.getZ()) - 1, CHUNK_SIZE);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                world.getChunk(chunkX, chunkZ);
            }
        }
    }

    private static void syncTownhallWorldSpawn(ServerWorld world, BreachedStructurePlacementState state) {
        if (!isStructurePlacementWorld(world)) {
            return;
        }

        Optional<BreachedStructurePlacementState.SavedPlacement> townhallPlacement = getActiveTownhallPlacement(state);
        if (townhallPlacement.isEmpty()) {
            return;
        }

        BlockPos spawnPos = findTownhallSpawnPos(world, townhallPlacement.get());
        if (isCurrentWorldSpawn(world, spawnPos)) {
            return;
        }

        ((ServerWorldProperties) world.getLevelProperties()).setSpawnPoint(WorldProperties.SpawnPoint.create(
                world.getRegistryKey(),
                spawnPos,
                0.0F,
                0.0F
        ));
        System.out.println("[Breached] Set world spawn to Town Hall bottom floor at x "
                + spawnPos.getX()
                + ", y " + spawnPos.getY()
                + ", z " + spawnPos.getZ() + ".");
    }

    private static boolean isCurrentWorldSpawn(ServerWorld world, BlockPos spawnPos) {
        WorldProperties.SpawnPoint currentSpawn = world.getLevelProperties().getSpawnPoint();
        return currentSpawn.getDimension().equals(world.getRegistryKey())
                && currentSpawn.getPos().equals(spawnPos);
    }

    private static BlockPos findTownhallSpawnPos(
            ServerWorld world,
            BreachedStructurePlacementState.SavedPlacement placement
    ) {
        int minX = placement.originX();
        int maxX = placement.originX() + Math.max(1, placement.sizeX()) - 1;
        int minY = Math.max(world.getBottomY(), placement.originY());
        int maxY = Math.min(
                world.getTopYInclusive() - TOWNHALL_SPAWN_PLAYER_HEIGHT + 1,
                placement.originY() + Math.max(1, placement.sizeY()) - TOWNHALL_SPAWN_PLAYER_HEIGHT
        );
        int minZ = placement.originZ();
        int maxZ = placement.originZ() + Math.max(1, placement.sizeZ()) - 1;
        int preferredX = clampInt(placement.centerX(), minX, maxX);
        int preferredZ = clampInt(placement.centerZ(), minZ, maxZ);
        Optional<BlockPos> markerSpawnPos = findTownhallMarkerSpawnPos(world, placement);
        if (markerSpawnPos.isPresent()) {
            return markerSpawnPos.get();
        }

        Set<BlockPos> templateFloorBlocks = getTownhallTemplateFloorBlocks(world, placement);

        for (int y = minY; y <= maxY; y++) {
            Optional<BlockPos> spawnPos = findTownhallSpawnPosAtY(
                    world,
                    preferredX,
                    preferredZ,
                    y,
                    minX,
                    maxX,
                    minZ,
                    maxZ,
                    templateFloorBlocks
            );
            if (spawnPos.isPresent()) {
                return spawnPos.get();
            }
        }

        int fallbackY = clampInt(placement.originY() + 1, world.getBottomY(), world.getTopYInclusive());
        return new BlockPos(preferredX, fallbackY, preferredZ);
    }

    private static Optional<BlockPos> findTownhallMarkerSpawnPos(
            ServerWorld world,
            BreachedStructurePlacementState.SavedPlacement placement
    ) {
        BreachedStructureDefinition definition = getActiveDefinition(world, BreachedStructureDefinitions.TOWNHALL);
        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, definition);
        if (template.isEmpty()) {
            return Optional.empty();
        }

        BlockPos origin = new BlockPos(placement.originX(), placement.originY(), placement.originZ());
        BlockPos bestMarkerPos = null;
        BlockPos bestSpawnPos = null;
        long bestDistanceSquared = Long.MAX_VALUE;
        for (BreachedStructureSpawnManager.TemplatePlacedBlock block : BreachedStructureSpawnManager.getTemplatePlacedBlocks(
                world,
                definition,
                template.get(),
                origin,
                definition.mirror(),
                definition.rotation()
        )) {
            if (!block.state().isOf(TOWNHALL_SPAWN_MARKER_BLOCK)) {
                continue;
            }

            BlockPos markerPos = block.pos();
            BlockPos spawnPos = markerPos.up();
            if (!isValidTownhallSpawnMarker(world, markerPos, spawnPos)) {
                continue;
            }

            long distanceSquared = squaredHorizontalDistance(markerPos, placement.centerX(), placement.centerZ());
            if (isBetterTownhallSpawnMarker(markerPos, distanceSquared, bestMarkerPos, bestDistanceSquared)) {
                bestMarkerPos = markerPos;
                bestSpawnPos = spawnPos;
                bestDistanceSquared = distanceSquared;
            }
        }

        return Optional.ofNullable(bestSpawnPos);
    }

    private static boolean isValidTownhallSpawnMarker(ServerWorld world, BlockPos markerPos, BlockPos spawnPos) {
        BlockPos floorPos = markerPos.down();
        BlockState floorState = world.getBlockState(floorPos);
        return world.getBlockState(markerPos).isOf(TOWNHALL_SPAWN_MARKER_BLOCK)
                && !floorState.getCollisionShape(world, floorPos).isEmpty()
                && isPassableForSpawn(world, spawnPos)
                && isPassableForSpawn(world, spawnPos.up());
    }

    private static boolean isBetterTownhallSpawnMarker(
            BlockPos markerPos,
            long distanceSquared,
            BlockPos currentMarkerPos,
            long currentDistanceSquared
    ) {
        if (currentMarkerPos == null) {
            return true;
        }

        if (markerPos.getY() != currentMarkerPos.getY()) {
            return markerPos.getY() < currentMarkerPos.getY();
        }

        if (distanceSquared != currentDistanceSquared) {
            return distanceSquared < currentDistanceSquared;
        }

        if (markerPos.getX() != currentMarkerPos.getX()) {
            return markerPos.getX() < currentMarkerPos.getX();
        }

        return markerPos.getZ() < currentMarkerPos.getZ();
    }

    private static long squaredHorizontalDistance(BlockPos pos, int x, int z) {
        long xDistance = pos.getX() - x;
        long zDistance = pos.getZ() - z;
        return xDistance * xDistance + zDistance * zDistance;
    }

    private static Optional<BlockPos> findTownhallSpawnPosAtY(
            ServerWorld world,
            int preferredX,
            int preferredZ,
            int y,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            Set<BlockPos> templateFloorBlocks
    ) {
        int maxRadius = Math.max(maxX - minX, maxZ - minZ);
        for (int radius = 0; radius <= maxRadius; radius++) {
            int startX = clampInt(preferredX - radius, minX, maxX);
            int endX = clampInt(preferredX + radius, minX, maxX);
            int startZ = clampInt(preferredZ - radius, minZ, maxZ);
            int endZ = clampInt(preferredZ + radius, minZ, maxZ);
            for (int x = startX; x <= endX; x++) {
                for (int z = startZ; z <= endZ; z++) {
                    if (Math.abs(x - preferredX) != radius && Math.abs(z - preferredZ) != radius) {
                        continue;
                    }

                    BlockPos candidate = new BlockPos(x, y, z);
                    if (isSafeTownhallSpawnPos(world, candidate, templateFloorBlocks)) {
                        return Optional.of(candidate);
                    }
                }
            }
        }

        return Optional.empty();
    }

    private static boolean isSafeTownhallSpawnPos(ServerWorld world, BlockPos pos, Set<BlockPos> templateFloorBlocks) {
        BlockPos floorPos = pos.down();
        if (!templateFloorBlocks.isEmpty() && !templateFloorBlocks.contains(floorPos)) {
            return false;
        }

        BlockState floorState = world.getBlockState(floorPos);
        return !floorState.getCollisionShape(world, floorPos).isEmpty()
                && isPassableForSpawn(world, pos)
                && isPassableForSpawn(world, pos.up());
    }

    private static Set<BlockPos> getTownhallTemplateFloorBlocks(
            ServerWorld world,
            BreachedStructurePlacementState.SavedPlacement placement
    ) {
        BreachedStructureDefinition definition = getActiveDefinition(world, BreachedStructureDefinitions.TOWNHALL);
        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, definition);
        if (template.isEmpty()) {
            return Set.of();
        }

        Set<BlockPos> floorBlocks = new HashSet<>();
        BlockPos origin = new BlockPos(placement.originX(), placement.originY(), placement.originZ());
        for (BreachedStructureSpawnManager.TemplatePlacedBlock block : BreachedStructureSpawnManager.getTemplatePlacedBlocks(
                world,
                definition,
                template.get(),
                origin,
                definition.mirror(),
                definition.rotation()
        )) {
            if (!block.state().getCollisionShape(world, block.pos()).isEmpty()) {
                floorBlocks.add(block.pos().toImmutable());
            }
        }

        return floorBlocks;
    }

    private static boolean isPassableForSpawn(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.getCollisionShape(world, pos).isEmpty()
                && state.getFluidState().isEmpty();
    }

    private static int clampInt(int value, int min, int max) {
        if (min > max) {
            return value;
        }

        return Math.max(min, Math.min(max, value));
    }

    private static boolean hasScheduledPlacementWork(ServerWorld world) {
        return hasPendingMajorPlacementsForWorld(world)
                || hasPendingMinorPoiChunksForWorld(world)
                || hasForcedStructureChunksForWorld(world)
                || hasForcedMinorDespawnChunksForWorld(world)
                || hasForcedRestockChunksForWorld(world)
                || isMinorPoiDespawnScanDue(world)
                || isMinorPoiPlayerScanDue(world)
                || isMajorRestockScanDue(world);
    }

    private static boolean hasPlannedStructureWork(ServerWorld world, BreachedStructurePlacementState state) {
        for (BreachedStructureDefinition definition : BreachedStructureDefinitions.PLANNED_STRUCTURES) {
            BreachedStructureDefinition activeDefinition = getActiveDefinition(world, definition);
            if (!world.getRegistryKey().equals(activeDefinition.requiredDimension())) {
                continue;
            }

            String structureKey = BreachedStructureDefinitions.key(activeDefinition);
            if (countPlacedStructures(state, structureKey) >= activeDefinition.countPerWorld()) {
                continue;
            }

            if (hasOpenReservedPlacement(state, structureKey)) {
                return true;
            }

            if (countReservedOrPlacedStructures(state, structureKey) < activeDefinition.countPerWorld()
                    && hasRemainingCandidates(world, state, activeDefinition, structureKey)) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasOpenReservedPlacement(BreachedStructurePlacementState state, String structureKey) {
        for (Map.Entry<String, BreachedStructurePlacementState.ReservedPlacement> reservation : state.reservations()) {
            if (isPlacementForStructure(reservation.getKey(), structureKey)
                    && !state.hasPlacement(reservation.getKey())
                    && !state.hasFailedCandidate(structureKey, reservation.getValue().candidateIndex())) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasPendingMajorPlacementsForWorld(ServerWorld world) {
        return PENDING_PLACEMENTS.stream()
                .anyMatch(placement -> placement.worldSeed() == world.getSeed());
    }

    private static boolean hasPendingMinorPoiChunksForWorld(ServerWorld world) {
        return PENDING_MINOR_POI_CHUNKS.stream()
                .anyMatch(pendingChunk -> pendingChunk.worldSeed() == world.getSeed());
    }

    private static boolean hasForcedStructureChunksForWorld(ServerWorld world) {
        return FORCED_CHUNKS.stream()
                .anyMatch(forcedChunk -> forcedChunk.worldSeed() == world.getSeed());
    }

    private static boolean hasForcedMinorDespawnChunksForWorld(ServerWorld world) {
        return FORCED_MINOR_DESPAWN_CHUNKS.stream()
                .anyMatch(forcedChunk -> forcedChunk.worldSeed() == world.getSeed());
    }

    private static boolean isMinorPoiDespawnScanDue(ServerWorld world) {
        BreachedConfig.MinorPoiSettings minorPoiConfig = BreachedConfig.get().minorPoi;
        return minorPoiConfig.lifecycleEnabled
                && world.getTime() % minorPoiConfig.despawnScanIntervalTicks == 0;
    }

    private static boolean isMinorPoiPlayerScanDue(ServerWorld world) {
        BreachedConfig.MinorPoiSettings minorPoiConfig = BreachedConfig.get().minorPoi;
        return !world.getPlayers().isEmpty()
                && world.getTime() % minorPoiConfig.playerScanIntervalTicks == 0;
    }

    private static boolean isMajorRestockScanDue(ServerWorld world) {
        BreachedConfig.MajorStructureLootSettings lootConfig = BreachedConfig.get().majorStructureLoot;
        return lootConfig.enabled
                && world.getTime() % lootConfig.scanIntervalTicks == 0;
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

    private static void tryPlacePendingMinorPoiChunks(
            ServerWorld world,
            BreachedStructurePlacementState state,
            StructureTickDiagnostics diagnostics
    ) {
        int attempted = 0;
        for (PendingMinorPoiChunk pendingChunk : new HashSet<>(PENDING_MINOR_POI_CHUNKS)) {
            if (pendingChunk.worldSeed() != world.getSeed()) {
                continue;
            }

            if (attempted >= BreachedConfig.get().minorPoi.pendingChunksPerTick) {
                break;
            }

            ChunkPos chunkPos = new ChunkPos(pendingChunk.chunkX(), pendingChunk.chunkZ());
            diagnostics.pendingMinorChunksAttempted++;
            MinorPoiAttemptResult result = tryPlaceMinorPoisInChunk(world, state, chunkPos);
            if (result != MinorPoiAttemptResult.NOT_READY) {
                PENDING_MINOR_POI_CHUNKS.remove(pendingChunk);
                diagnostics.pendingMinorChunksResolved++;
            }
            if (result == MinorPoiAttemptResult.PLACED) {
                diagnostics.pendingMinorPoisPlaced++;
            }

            attempted++;
        }
    }

    private static void tryPlaceMinorPoisNearPlayers(
            ServerWorld world,
            BreachedStructurePlacementState state,
            StructureTickDiagnostics diagnostics
    ) {
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

                    diagnostics.playerMinorChunksChecked++;
                    MinorPoiAttemptResult result = tryPlaceMinorPoisInChunk(world, state, chunkPos);
                    if (result == MinorPoiAttemptResult.PLACED) {
                        diagnostics.playerMinorPoisPlaced++;
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

        int originY = getPlacementOriginY(world, bestChoice.definition(), bestChoice.site());
        BlockPos origin = new BlockPos(bestChoice.originX(), originY, bestChoice.originZ());
        Vec3i footprintSize = BreachedStructureSpawnManager.getRotatedSize(bestChoice.template().getSize(), bestChoice.definition().rotation());
        List<BreachedStructurePlacementState.SavedBlockSnapshot> restoreBlocks = captureOriginalMinorPoiBlocks(
                world,
                bestChoice.definition(),
                bestChoice.template(),
                origin,
                footprintSize
        );
        int clearedVegetation = clearMinorPoiVegetationInFootprint(world, bestChoice.definition(), origin, footprintSize);
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
        if (clearedVegetation > 0) {
            System.out.println("[Breached] Cleared " + clearedVegetation
                    + " vegetation blocks before placing minor POI " + bestChoice.definition().logName() + ".");
        }
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
        if (!definition.canClearBlocks() && !isForestTolerantMinorPoi(definition)) {
            return;
        }

        int maxY = Math.min(world.getTopYInclusive(), origin.getY() + footprintSize.getY() + 5);
        for (int x = origin.getX(); x < origin.getX() + footprintSize.getX(); x++) {
            for (int z = origin.getZ(); z < origin.getZ() + footprintSize.getZ(); z++) {
                for (int y = origin.getY(); y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (shouldSnapshotMinorPoiClearanceBlock(definition, world.getBlockState(pos))) {
                        captureOriginalBlockSnapshot(world, pos, snapshots);
                    }
                }
            }
        }
    }

    private static int clearMinorPoiVegetationInFootprint(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BlockPos origin,
            Vec3i footprintSize
    ) {
        if (!isForestTolerantMinorPoi(definition)) {
            return 0;
        }

        int clearedBlocks = 0;
        int maxY = Math.min(world.getTopYInclusive(), origin.getY() + footprintSize.getY() + 5);
        for (int x = origin.getX(); x < origin.getX() + footprintSize.getX(); x++) {
            for (int z = origin.getZ(); z < origin.getZ() + footprintSize.getZ(); z++) {
                for (int y = origin.getY(); y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isVegetation(world.getBlockState(pos))) {
                        removeBlockWithoutDrops(world, pos);
                        clearedBlocks++;
                    }
                }
            }
        }

        return clearedBlocks;
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

    private static void despawnAndRetireMinorPois(
            ServerWorld world,
            BreachedStructurePlacementState state,
            StructureTickDiagnostics diagnostics
    ) {
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
            diagnostics.minorPoiPlacementsScanned++;

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
                diagnostics.minorPoiDueForDespawn++;
            }
            tryDespawnAndRetireMinorPoi(world, state, entry.getKey(), definition.get(), placement, dueForDespawn, diagnostics);
        }
    }

    private static void tryDespawnAndRetireMinorPoi(
            ServerWorld world,
            BreachedStructurePlacementState state,
            String placementKey,
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement,
            boolean dueForDespawn,
            StructureTickDiagnostics diagnostics
    ) {
        diagnostics.minorPoiEvaluated++;
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
                forceLoadMissingMinorDespawnFootprintChunks(world, placementKey, placement.originX(), placement.originZ(), footprintSize, diagnostics);
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
        diagnostics.minorPoiExpectedBlocksChecked += expectedBlocks.size();
        diagnostics.minorPoiFootprintBlocksChecked += footprintBlocks.size();
        MinorPoiBlockEvaluation blockEvaluation = evaluateMinorPoiBlocks(world, expectedBlocks, footprintBlocks);
        boolean footprintClaimed = isMinorPoiFootprintClaimed(world, placement, footprintSize, diagnostics);
        Optional<String> playerTouchReason = getMinorPoiPlayerTouchReason(placement, blockEvaluation, footprintClaimed);
        if (playerTouchReason.isPresent()) {
            retirePlayerTouchedMinorPoi(world, state, placementKey, placement, blockEvaluation, playerTouchReason.get());
            diagnostics.minorPoiPlayerTouched++;
            return;
        }

        if (!dueForDespawn) {
            return;
        }

        int removedBlocks = removeMatchingMinorPoiBlocks(world, expectedBlocks);
        removedBlocks += removeMatchingCleanupBlocks(world, definition, placement.cleanupBlocks());
        int restoredBlocks = restoreOriginalMinorPoiBlocks(world, placement.restoreBlocks());

        state.retirePlacement(placementKey, world.getTime());
        releaseForcedMinorDespawnChunks(world, placementKey);
        diagnostics.minorPoiRetired++;
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
            Vec3i footprintSize,
            StructureTickDiagnostics diagnostics
    ) {
        for (int x = placement.originX(); x < placement.originX() + footprintSize.getX(); x++) {
            for (int z = placement.originZ(); z < placement.originZ() + footprintSize.getZ(); z++) {
                diagnostics.minorPoiFootprintClaimChecks++;
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
            Vec3i footprintSize,
            StructureTickDiagnostics diagnostics
    ) {
        int minChunkX = Math.floorDiv(originX, CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(originZ, CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(originX + footprintSize.getX() - 1, CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(originZ + footprintSize.getZ() - 1, CHUNK_SIZE);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                forceLoadMinorDespawnChunk(world, placementKey, new ChunkPos(chunkX, chunkZ), diagnostics);
            }
        }
    }

    private static void forceLoadMinorDespawnChunk(
            ServerWorld world,
            String placementKey,
            ChunkPos chunkPos,
            StructureTickDiagnostics diagnostics
    ) {
        if (world.isChunkLoaded(chunkPos.x, chunkPos.z)) {
            return;
        }

        if (!isMinorDespawnChunkForceLoaded(world.getSeed(), placementKey, chunkPos.x, chunkPos.z)) {
            FORCED_MINOR_DESPAWN_CHUNKS.add(new ForcedMinorDespawnChunk(world.getSeed(), placementKey, chunkPos.x, chunkPos.z));
            diagnostics.minorDespawnChunksForceLoaded++;
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

        if (isEndPortalStructure(definition) && getActiveTownhallPlacement(state).isEmpty()) {
            return new PlannedCandidateEvaluation(pendingPlacement, null, null, Double.MAX_VALUE, PlacementAttemptResult.NOT_READY);
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
                getPlacementOriginY(world, state, definition, evaluation.site(), evaluation.template()),
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
                createNextLootRestockTime(world, pendingPlacement.placementKey())
        );
        if (isTownhallStructure(definition)) {
            syncTownhallWorldSpawn(world, state);
        }
        Optional<PortalEventType> portalEventType = getPortalEventType(definition);
        if (portalEventType.isPresent()) {
            if (isPortalEventActive(state, portalEventType.get(), world.getTime())) {
                lightOfficialPortalStructures(world, state, portalEventType.get());
            } else {
                closeOfficialPortalStructures(world, state, portalEventType.get());
            }
        }
        if (isExactProtectedStructure(definition)) {
            System.out.println("[Breached] Registered protected structure " + definition.logName()
                    + " using exact template-block protection.");
        } else if (isVolumeProtectedStructure(definition)) {
            System.out.println("[Breached] Registered protected structure " + definition.logName()
                    + " using exact placement-volume protection.");
        } else if (definition.protectedStructure()) {
            System.out.println("[Breached] Registered protected structure " + definition.logName()
                    + " around x " + BreachedStructureSpawnManager.getProtectedCenterX(placement)
                    + ", y " + BreachedStructureSpawnManager.getProtectedCenterY(placement)
                    + ", z " + BreachedStructureSpawnManager.getProtectedCenterZ(placement)
                    + " radius " + definition.protectionRadius() + ".");
        }
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
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructureSite site
    ) {
        return getPlacementOriginY(world, null, definition, site, null);
    }

    private static int getPlacementOriginY(
            ServerWorld world,
            BreachedStructurePlacementState state,
            BreachedStructureDefinition definition,
            BreachedStructureSite site
    ) {
        return getPlacementOriginY(world, state, definition, site, null);
    }

    private static int getPlacementOriginY(
            ServerWorld world,
            BreachedStructurePlacementState state,
            BreachedStructureDefinition definition,
            BreachedStructureSite site,
            StructureTemplate template
    ) {
        if (definition.structureId().equals(BreachedStructureDefinitions.EYEBALL.structureId())) {
            return EYEBALL_ORIGIN_Y;
        }

        if (isEndPortalStructure(definition) && state != null) {
            Optional<BreachedStructurePlacementState.SavedPlacement> townhallPlacement = getActiveTownhallPlacement(state);
            if (townhallPlacement.isPresent()) {
                int targetY = townhallPlacement.get().originY() + END_PORTAL_TOWNHALL_Y_OFFSET;
                int templateHeight = template == null
                        ? 1
                        : Math.max(1, BreachedStructureSpawnManager.getRotatedSize(template.getSize(), definition.rotation()).getY());
                int maxOriginY = Math.max(world.getBottomY(), world.getTopYInclusive() - templateHeight + 1);
                return Math.max(world.getBottomY(), Math.min(targetY, maxOriginY));
            }
        }

        if (isSkyHomeStructure(definition)) {
            return getSkyHomeOriginY(world, definition, site);
        }

        return site.surfaceY() + definition.placementOffsetY();
    }

    private static boolean isSkyHomeStructure(BreachedStructureDefinition definition) {
        return BreachedStructureDefinitions.key(definition).equals(SKYHOME_STRUCTURE_KEY);
    }

    private static boolean isTownhallStructure(BreachedStructureDefinition definition) {
        return BreachedStructureDefinitions.key(definition).equals(TOWNHALL_STRUCTURE_KEY);
    }

    private static boolean isEndPortalStructure(BreachedStructureDefinition definition) {
        return BreachedStructureDefinitions.key(definition).equals(END_PORTAL_STRUCTURE_KEY);
    }

    private static boolean isBigBoatStructure(BreachedStructureDefinition definition) {
        return definition.structureId().equals(BreachedStructureDefinitions.BIG_BOAT.structureId());
    }

    private static int getSkyHomeOriginY(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructureSite site
    ) {
        int heightRange = SKYHOME_MAX_ORIGIN_Y - SKYHOME_MIN_ORIGIN_Y + 1;
        java.util.Random random = new java.util.Random(
                world.getSeed()
                        ^ definition.seedSalt()
                        ^ ((long) site.originX() * 341873128712L)
                        ^ ((long) site.originZ() * 132897987541L)
        );
        return SKYHOME_MIN_ORIGIN_Y + random.nextInt(heightRange);
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
        if (definition.structureId().equals(BreachedStructureDefinitions.EYEBALL.structureId())) {
            return generateEyeballCornerCandidates(world, definition);
        }

        if (isTownhallStructure(definition)) {
            return generateTownhallCenteredCandidate(world, definition);
        }

        if (isEndPortalStructure(definition)) {
            return generateEndPortalTownhallCandidate(world, definition);
        }

        if (isBigBoatStructure(definition)) {
            return generateBigBoatExpandedCandidates(world, definition);
        }

        if (definition.structureId().equals(BreachedStructureDefinitions.SWORD_STATUE.structureId())) {
            return generateSwordStatueCandidatesOpposedToPinkTree(world, definition);
        }

        return generateRadiusCandidates(world, definition);
    }

    private static List<BreachedStructureSpawnManager.RadiusCandidate> generateTownhallCenteredCandidate(
            ServerWorld world,
            BreachedStructureDefinition definition
    ) {
        Optional<StructureTemplate> template = world.getStructureTemplateManager().getTemplate(definition.structureId());
        Vec3i footprintSize = template
                .map(value -> BreachedStructureSpawnManager.getRotatedSize(value.getSize(), definition.rotation()))
                .orElse(new Vec3i(1, 1, 1));
        int originX = definition.centerX() - Math.max(1, footprintSize.getX()) / 2;
        int originZ = definition.centerZ() - Math.max(1, footprintSize.getZ()) / 2;
        return List.of(new BreachedStructureSpawnManager.RadiusCandidate(1, originX, originZ, 0, 0.0D));
    }

    private static List<BreachedStructureSpawnManager.RadiusCandidate> generateEndPortalTownhallCandidate(
            ServerWorld world,
            BreachedStructureDefinition definition
    ) {
        BreachedStructurePlacementState state = BreachedStructurePlacementState.get(world.getServer());
        Optional<BreachedStructurePlacementState.SavedPlacement> townhallPlacement = getActiveTownhallPlacement(state);
        if (townhallPlacement.isEmpty()) {
            return List.of();
        }

        Optional<StructureTemplate> template = world.getStructureTemplateManager().getTemplate(definition.structureId());
        Vec3i footprintSize = template
                .map(value -> BreachedStructureSpawnManager.getRotatedSize(value.getSize(), definition.rotation()))
                .orElse(new Vec3i(1, 1, 1));
        int originX = townhallPlacement.get().centerX() - Math.max(1, footprintSize.getX()) / 2;
        int originZ = townhallPlacement.get().centerZ() - Math.max(1, footprintSize.getZ()) / 2;
        return List.of(new BreachedStructureSpawnManager.RadiusCandidate(1, originX, originZ, 0, 0.0D));
    }

    private static Optional<BreachedStructurePlacementState.SavedPlacement> getActiveTownhallPlacement(
            BreachedStructurePlacementState state
    ) {
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> placement : state.placements()) {
            if (placement.getValue().active() && structureKey(placement.getKey()).equals(TOWNHALL_STRUCTURE_KEY)) {
                return Optional.of(placement.getValue());
            }
        }

        return Optional.empty();
    }

    private static List<BreachedStructureSpawnManager.RadiusCandidate> generateEyeballCornerCandidates(
            ServerWorld world,
            BreachedStructureDefinition definition
    ) {
        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, definition);
        Vec3i footprintSize = template
                .map(value -> BreachedStructureSpawnManager.getRotatedSize(value.getSize(), definition.rotation()))
                .orElse(new Vec3i(1, 1, 1));
        int sizeX = Math.max(1, footprintSize.getX());
        int sizeZ = Math.max(1, footprintSize.getZ());

        double borderSize = getEffectiveOverworldBorderSize(world);
        double borderHalfSize = borderSize / 2.0D;
        int minBorderX = (int) Math.ceil(world.getWorldBorder().getCenterX() - borderHalfSize);
        int maxBorderX = (int) Math.floor(world.getWorldBorder().getCenterX() + borderHalfSize);
        int minBorderZ = (int) Math.ceil(world.getWorldBorder().getCenterZ() - borderHalfSize);
        int maxBorderZ = (int) Math.floor(world.getWorldBorder().getCenterZ() + borderHalfSize);

        int minOriginX = minBorderX + EYEBALL_BORDER_MARGIN_BLOCKS;
        int maxOriginX = maxBorderX - EYEBALL_BORDER_MARGIN_BLOCKS - sizeX;
        int minOriginZ = minBorderZ + EYEBALL_BORDER_MARGIN_BLOCKS;
        int maxOriginZ = maxBorderZ - EYEBALL_BORDER_MARGIN_BLOCKS - sizeZ;
        int baseOriginX = minOriginX;
        int baseOriginZ = maxOriginZ;

        int candidateCount = Math.max(definition.countPerWorld(), definition.plannedCandidateCount());
        List<BreachedStructureSpawnManager.RadiusCandidate> candidates = new ArrayList<>();
        java.util.Random random = new java.util.Random(world.getSeed() ^ definition.seedSalt());
        for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
            int xOffset = candidateIndex == 0 ? 0 : random.nextInt(EYEBALL_CANDIDATE_SPREAD_BLOCKS * 2 + 1) - EYEBALL_CANDIDATE_SPREAD_BLOCKS;
            int zOffset = candidateIndex == 0 ? 0 : random.nextInt(EYEBALL_CANDIDATE_SPREAD_BLOCKS * 2 + 1) - EYEBALL_CANDIDATE_SPREAD_BLOCKS;
            int x = clampStructureOrigin(baseOriginX + xOffset, minOriginX, maxOriginX);
            int z = clampStructureOrigin(baseOriginZ + zOffset, minOriginZ, maxOriginZ);
            int radius = distanceBetween(baseOriginX, baseOriginZ, x, z);
            double angle = Math.atan2(z - baseOriginZ, x - baseOriginX);

            candidates.add(new BreachedStructureSpawnManager.RadiusCandidate(candidateIndex + 1, x, z, radius, angle));
        }

        return candidates;
    }

    private static double getEffectiveOverworldBorderSize(ServerWorld world) {
        double borderSize = world.getWorldBorder().getSize();
        if (borderSize < 100_000.0D) {
            return borderSize;
        }

        Optional<BreachedDimensionRules.BreachedPreset> preset = BreachedDimensionRules.getBreachedPreset(world.getServer());
        if (preset.isPresent() && preset.get() == BreachedDimensionRules.BreachedPreset.REGULAR) {
            return 1000.0D;
        }

        return 2500.0D;
    }

    private static int clampStructureOrigin(int value, int min, int max) {
        if (min > max) {
            return value;
        }

        return Math.max(min, Math.min(max, value));
    }

    private static int distanceBetween(int firstX, int firstZ, int secondX, int secondZ) {
        long xDistance = secondX - firstX;
        long zDistance = secondZ - firstZ;
        return (int) Math.round(Math.sqrt(xDistance * xDistance + zDistance * zDistance));
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

    private static List<BreachedStructureSpawnManager.RadiusCandidate> generateBigBoatExpandedCandidates(
            ServerWorld world,
            BreachedStructureDefinition definition
    ) {
        int baseCandidateCount = Math.max(definition.countPerWorld(), definition.plannedCandidateCount());
        int expandedCandidateCount = Math.max(
                baseCandidateCount,
                Math.min(
                        BIG_BOAT_MAX_EXPANDED_CANDIDATES,
                        Math.max(BIG_BOAT_MIN_EXPANDED_CANDIDATES, baseCandidateCount * 4)
                )
        );

        return BreachedStructureSpawnManager.generateRadiusCandidates(
                world.getSeed(),
                definition.seedSalt(),
                definition.centerX(),
                definition.centerZ(),
                definition.minRadius(),
                definition.maxRadius(),
                expandedCandidateCount,
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

    private static void restockMajorStructureLoot(
            ServerWorld world,
            BreachedStructurePlacementState state,
            StructureTickDiagnostics diagnostics
    ) {
        BreachedConfig.MajorStructureLootSettings lootConfig = BreachedConfig.get().majorStructureLoot;
        boolean scanTick = world.getTime() % lootConfig.scanIntervalTicks == 0;
        if (!lootConfig.enabled || (!scanTick && !hasForcedRestockChunksForWorld(world))) {
            return;
        }

        PortalRestockResult netherPortalRestockResult = restockPortalStructureLoot(
                world,
                state,
                lootConfig,
                diagnostics,
                PortalEventType.NETHER
        );
        PortalRestockResult endPortalRestockResult = restockPortalStructureLoot(
                world,
                state,
                lootConfig,
                diagnostics,
                PortalEventType.END
        );
        int restockedStructures = netherPortalRestockResult.restockedStructures()
                + endPortalRestockResult.restockedStructures();
        int totalRestockedContainers = netherPortalRestockResult.restockedContainers()
                + endPortalRestockResult.restockedContainers();
        boolean netherPortalRestocked = netherPortalRestockResult.portalRestocked();
        boolean endPortalRestocked = endPortalRestockResult.portalRestocked();
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> entry : new ArrayList<>(state.placements())) {
            if (isAnyPortalEventPlacement(entry.getKey())) {
                continue;
            }

            Optional<BreachedStructureDefinition> definition = getMajorDefinition(entry.getKey())
                    .map(baseDefinition -> getActiveDefinition(world, baseDefinition));
            if (definition.isEmpty() || !world.getRegistryKey().equals(definition.get().requiredDimension())) {
                continue;
            }
            diagnostics.majorPlacementsScanned++;

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
            diagnostics.majorPlacementsDue++;

            if (!placement.lootContainersScanned()) {
                if (!tryBackfillMajorLootContainers(world, state, entry.getKey(), definition.get(), placement, diagnostics)) {
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
                forceLoadMissingRestockChunks(world, entry.getKey(), placement.lootContainers(), diagnostics);
                continue;
            }

            diagnostics.majorLootContainersChecked += placement.lootContainers().size();
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
                diagnostics.majorStructuresRestocked++;
                diagnostics.majorContainersRestocked += restockedContainers;
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

        if (netherPortalRestocked) {
            startPortalEvent(world, state, PortalEventType.NETHER);
        }
        if (endPortalRestocked) {
            startPortalEvent(world, state, PortalEventType.END);
        }

        if (totalRestockedContainers > 0) {
            System.out.println("[Breached] Announced major structure loot restock for "
                    + restockedStructures + " structures and "
                    + totalRestockedContainers + " containers.");
        }
    }

    private static PortalRestockResult restockPortalStructureLoot(
            ServerWorld world,
            BreachedStructurePlacementState state,
            BreachedConfig.MajorStructureLootSettings lootConfig,
            StructureTickDiagnostics diagnostics,
            PortalEventType portalEventType
    ) {
        List<PortalRestockTarget> portalTargets = getPortalRestockTargets(world, state, portalEventType);
        if (portalTargets.isEmpty()) {
            return PortalRestockResult.NONE;
        }

        diagnostics.majorPlacementsScanned += portalTargets.size();
        long sharedRestockTime = syncPortalRestockTimes(world, state, portalTargets, portalEventType);
        if (world.getTime() < sharedRestockTime) {
            return PortalRestockResult.NONE;
        }

        diagnostics.majorPlacementsDue += portalTargets.size();
        portalTargets = getPortalRestockTargets(world, state, portalEventType);
        List<PortalRestockTarget> readyTargets = new ArrayList<>();
        boolean allTargetsReady = true;
        for (PortalRestockTarget target : portalTargets) {
            BreachedStructurePlacementState.SavedPlacement placement = target.placement();
            if (!placement.lootContainersScanned()) {
                if (!tryBackfillMajorLootContainers(
                        world,
                        state,
                        target.placementKey(),
                        target.definition(),
                        placement,
                        diagnostics
                )) {
                    allTargetsReady = false;
                    continue;
                }

                placement = state.getPlacement(target.placementKey()).orElse(placement);
            }

            if (!placement.lootContainers().isEmpty() && !areLootContainersLoaded(world, placement.lootContainers())) {
                forceLoadMissingRestockChunks(world, target.placementKey(), placement.lootContainers(), diagnostics);
                allTargetsReady = false;
                continue;
            }

            readyTargets.add(new PortalRestockTarget(target.placementKey(), target.definition(), placement));
        }

        if (!allTargetsReady) {
            return PortalRestockResult.NONE;
        }

        long nextRestockTime = createNextPortalLootRestockTime(world, portalEventType);
        int restockedStructures = 0;
        int totalRestockedContainers = 0;
        for (PortalRestockTarget target : readyTargets) {
            BreachedStructurePlacementState.SavedPlacement placement = target.placement();
            int restockedContainers = 0;
            if (!placement.lootContainers().isEmpty()) {
                diagnostics.majorLootContainersChecked += placement.lootContainers().size();
                restockedContainers = restockLootContainers(world, target.placementKey(), placement.lootContainers());
            }

            releaseForcedRestockChunks(world, target.placementKey());
            state.markLootRestocked(target.placementKey(), world.getTime(), nextRestockTime);
            if (restockedContainers > 0) {
                restockedStructures++;
                totalRestockedContainers += restockedContainers;
                diagnostics.majorStructuresRestocked++;
                diagnostics.majorContainersRestocked += restockedContainers;
                System.out.println("[Breached] Restocked " + restockedContainers
                        + " portal structure containers for " + target.placementKey() + ".");
            }
        }

        if (totalRestockedContainers <= 0) {
            return PortalRestockResult.NONE;
        }

        if (lootConfig.announceRestocks) {
            world.getServer().getPlayerManager().broadcast(
                    getMajorRestockAnnouncement(getPortalStructureKey(portalEventType)),
                    false
            );
        }

        return new PortalRestockResult(restockedStructures, totalRestockedContainers, true);
    }

    private static List<PortalRestockTarget> getPortalRestockTargets(
            ServerWorld world,
            BreachedStructurePlacementState state,
            PortalEventType portalEventType
    ) {
        List<PortalRestockTarget> portalTargets = new ArrayList<>();
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> entry : new ArrayList<>(state.placements())) {
            if (!isPortalEventPlacement(entry.getKey(), portalEventType)) {
                continue;
            }

            Optional<BreachedStructureDefinition> definition = getMajorDefinition(entry.getKey())
                    .map(baseDefinition -> getActiveDefinition(world, baseDefinition));
            if (definition.isEmpty() || !world.getRegistryKey().equals(definition.get().requiredDimension())) {
                continue;
            }

            portalTargets.add(new PortalRestockTarget(entry.getKey(), definition.get(), entry.getValue()));
        }

        return portalTargets;
    }

    private static long syncPortalRestockTimes(
            ServerWorld world,
            BreachedStructurePlacementState state,
            List<PortalRestockTarget> portalTargets,
            PortalEventType portalEventType
    ) {
        long sharedRestockTime = Long.MAX_VALUE;
        for (PortalRestockTarget target : portalTargets) {
            BreachedStructurePlacementState.SavedPlacement placement = target.placement();
            if (placement.nextRestockTime() > placement.lastRestockTime()) {
                sharedRestockTime = Math.min(sharedRestockTime, placement.nextRestockTime());
            }
        }

        if (sharedRestockTime == Long.MAX_VALUE) {
            sharedRestockTime = createNextPortalLootRestockTime(
                    world,
                    portalEventType,
                    getLatestPortalLastRestockTime(portalTargets)
            );
        }
        long latestLastRestockTime = getLatestPortalLastRestockTime(portalTargets);
        long earliestAllowedRestockTime = latestLastRestockTime + PORTAL_MIN_RESTOCK_INTERVAL_TICKS;
        long latestAllowedRestockTime = latestLastRestockTime + PORTAL_MAX_RESTOCK_INTERVAL_TICKS;
        sharedRestockTime = Math.max(earliestAllowedRestockTime, Math.min(sharedRestockTime, latestAllowedRestockTime));

        for (PortalRestockTarget target : portalTargets) {
            BreachedStructurePlacementState.SavedPlacement placement = target.placement();
            if (placement.nextRestockTime() <= placement.lastRestockTime()
                    || placement.nextRestockTime() != sharedRestockTime) {
                state.markLootRestocked(target.placementKey(), placement.lastRestockTime(), sharedRestockTime);
            }
        }

        return sharedRestockTime;
    }

    private static long getLatestPortalLastRestockTime(List<PortalRestockTarget> portalTargets) {
        long latestLastRestockTime = 0L;
        for (PortalRestockTarget target : portalTargets) {
            latestLastRestockTime = Math.max(latestLastRestockTime, target.placement().lastRestockTime());
        }

        return latestLastRestockTime;
    }

    private static void tickPortalEvent(
            ServerWorld world,
            BreachedStructurePlacementState state,
            PortalEventType portalEventType
    ) {
        long eventEndTime = getPortalEventEndTime(state, portalEventType);
        if (eventEndTime <= 0L || world.getTime() % PORTAL_EVENT_TICK_INTERVAL != 0) {
            return;
        }

        long remainingTicks = eventEndTime - world.getTime();
        if (remainingTicks <= 0L) {
            closePortalEvent(world, state, portalEventType);
            return;
        }

        for (int warningSeconds : PORTAL_EVENT_WARNING_SECONDS) {
            if (remainingTicks <= warningSeconds * 20L
                    && markPortalEventWarningSent(state, portalEventType, warningSeconds)) {
                broadcastPortalEventMessage(world, getPortalWarningMessage(portalEventType, warningSeconds));
            }
        }
    }

    private static void startPortalEvent(
            ServerWorld world,
            BreachedStructurePlacementState state,
            PortalEventType portalEventType
    ) {
        long eventEndTime = world.getTime() + PORTAL_EVENT_DURATION_TICKS;
        startPortalEventState(state, portalEventType, eventEndTime);
        int litBlocks = lightOfficialPortalStructures(world, state, portalEventType);
        broadcastPortalEventMessage(world, getPortalOpenedMessage(portalEventType));
        System.out.println("[Breached] " + getPortalEventLogName(portalEventType)
                + " portal event opened until world time " + eventEndTime
                + "; lit " + litBlocks + " official portal blocks.");
    }

    private static void closePortalEvent(
            ServerWorld world,
            BreachedStructurePlacementState state,
            PortalEventType portalEventType
    ) {
        int removedPortalBlocks = closeOfficialPortalStructures(world, state, portalEventType);
        clearPortalEventState(state, portalEventType);
        broadcastPortalEventMessage(world, getPortalClosedMessage(portalEventType));
        if (portalEventType == PortalEventType.NETHER) {
            int killedPlayers = killDimensionPlayers(world, World.NETHER);
            System.out.println("[Breached] Nether portal event closed; removed " + removedPortalBlocks
                    + " official portal blocks and killed " + killedPlayers + " Nether players.");
            return;
        }

        System.out.println("[Breached] End portal event closed; removed " + removedPortalBlocks
                + " timed top portal blocks. End players were not killed.");
    }

    private static Text getPortalWarningMessage(PortalEventType portalEventType, int secondsRemaining) {
        String subject = switch (portalEventType) {
            case NETHER -> "Nether Portal";
            case END -> "End Portal";
        };
        if (secondsRemaining >= 60) {
            int minutesRemaining = secondsRemaining / 60;
            String minuteLabel = minutesRemaining == 1 ? "minute" : "minutes";
            return Text.literal(subject + " closes in " + minutesRemaining + " " + minuteLabel + ".")
                    .formatted(Formatting.DARK_PURPLE);
        }

        String secondLabel = secondsRemaining == 1 ? "second" : "seconds";
        return Text.literal(subject + " closes in " + secondsRemaining + " " + secondLabel + ".")
                .formatted(Formatting.DARK_PURPLE);
    }

    private static Text getPortalOpenedMessage(PortalEventType portalEventType) {
        return switch (portalEventType) {
            case NETHER -> Text.literal("Nether Portal has been lit. Nether Portal is open for 1 hour.")
                    .formatted(Formatting.DARK_PURPLE);
            case END -> Text.literal("End Portal has been lit. End Portal is open for 1 hour.")
                    .formatted(Formatting.DARK_PURPLE);
        };
    }

    private static Text getPortalClosedMessage(PortalEventType portalEventType) {
        return switch (portalEventType) {
            case NETHER -> Text.literal("Nether Portal has closed. The portal light has gone out.")
                    .formatted(Formatting.DARK_PURPLE);
            case END -> Text.literal("End Portal event has closed. The top portal light has gone out.")
                    .formatted(Formatting.DARK_PURPLE);
        };
    }

    private static void broadcastPortalEventMessage(ServerWorld world, Text message) {
        MinecraftServer server = world.getServer();
        if (server != null) {
            server.getPlayerManager().broadcast(message, false);
        }
    }

    private static int lightOfficialPortalStructures(
            ServerWorld world,
            BreachedStructurePlacementState state,
            PortalEventType portalEventType
    ) {
        int litBlocks = 0;
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> entry : new ArrayList<>(state.placements())) {
            if (!isPortalEventPlacement(entry.getKey(), portalEventType)
                    || !entry.getValue().active()
                    || !hasPlacementFootprint(entry.getValue())) {
                continue;
            }

            Optional<BreachedStructureDefinition> definition = getActiveDefinition(world, structureKey(entry.getKey()));
            if (definition.isEmpty()) {
                continue;
            }

            litBlocks += lightOfficialPortalStructure(world, definition.get(), entry.getValue());
        }

        return litBlocks;
    }

    private static int lightOfficialPortalStructure(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement
    ) {
        loadPlacementFootprintChunks(world, placement);
        Set<BlockPos> litBlocks = new HashSet<>();
        if (isEndPortalStructure(definition)) {
            lightPermanentEndPortalTemplateBlocks(world, definition, placement, litBlocks);
            lightEndPortalFrames(world, placement, litBlocks);
            return litBlocks.size();
        }

        lightTemplatePortalBlocks(world, definition, placement, Blocks.NETHER_PORTAL, litBlocks);
        int maxX = placement.originX() + placement.sizeX();
        int maxY = Math.min(world.getTopYInclusive() + 1, placement.originY() + placement.sizeY());
        int maxZ = placement.originZ() + placement.sizeZ();

        for (int x = placement.originX(); x < maxX; x++) {
            for (int y = Math.max(world.getBottomY(), placement.originY()); y < maxY; y++) {
                for (int z = placement.originZ(); z < maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    tryLightPortalFrame(world, pos, Direction.Axis.X, litBlocks);
                    tryLightPortalFrame(world, pos, Direction.Axis.Z, litBlocks);
                }
            }
        }

        return litBlocks.size();
    }

    private static void lightTemplatePortalBlocks(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement,
            Block portalBlock,
            Set<BlockPos> litBlocks
    ) {
        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, definition);
        if (template.isEmpty()) {
            return;
        }

        BlockPos origin = new BlockPos(placement.originX(), placement.originY(), placement.originZ());
        for (BreachedStructureSpawnManager.TemplatePlacedBlock block : BreachedStructureSpawnManager.getTemplatePlacedBlocks(
                world,
                definition,
                template.get(),
                origin,
                definition.mirror(),
                definition.rotation()
        )) {
            if (!block.state().isOf(portalBlock)) {
                continue;
            }

            if (litBlocks.add(block.pos())) {
                world.setBlockState(block.pos(), block.state(), Block.NOTIFY_LISTENERS);
            }
        }
    }

    private static void lightPermanentEndPortalTemplateBlocks(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement,
            Set<BlockPos> litBlocks
    ) {
        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, definition);
        if (template.isEmpty()) {
            return;
        }

        Set<BlockPos> timedPortalBlocks = getTemplateEndPortalFrameInteriorBlocks(world, definition, placement);
        BlockPos origin = new BlockPos(placement.originX(), placement.originY(), placement.originZ());
        for (BreachedStructureSpawnManager.TemplatePlacedBlock block : BreachedStructureSpawnManager.getTemplatePlacedBlocks(
                world,
                definition,
                template.get(),
                origin,
                definition.mirror(),
                definition.rotation()
        )) {
            if (!block.state().isOf(Blocks.END_PORTAL) || timedPortalBlocks.contains(block.pos())) {
                continue;
            }

            if (litBlocks.add(block.pos())) {
                world.setBlockState(block.pos(), block.state(), Block.NOTIFY_LISTENERS);
            }
        }
    }

    private static void lightEndPortalFrames(
            ServerWorld world,
            BreachedStructurePlacementState.SavedPlacement placement,
            Set<BlockPos> litBlocks
    ) {
        int maxX = placement.originX() + placement.sizeX();
        int maxY = Math.min(world.getTopYInclusive() + 1, placement.originY() + placement.sizeY());
        int maxZ = placement.originZ() + placement.sizeZ();

        for (int x = placement.originX() + 1; x <= maxX - 4; x++) {
            for (int y = Math.max(world.getBottomY(), placement.originY()); y < maxY; y++) {
                for (int z = placement.originZ() + 1; z <= maxZ - 4; z++) {
                    tryLightEndPortalFrame(world, new BlockPos(x, y, z), litBlocks);
                }
            }
        }
    }

    private static void tryLightEndPortalFrame(
            ServerWorld world,
            BlockPos interiorNorthWest,
            Set<BlockPos> litBlocks
    ) {
        if (!isValidEndPortalFrame(world, interiorNorthWest)) {
            return;
        }

        for (int xOffset = 0; xOffset < 3; xOffset++) {
            for (int zOffset = 0; zOffset < 3; zOffset++) {
                BlockPos portalPos = interiorNorthWest.add(xOffset, 0, zOffset);
                if (litBlocks.add(portalPos)) {
                    world.setBlockState(portalPos, Blocks.END_PORTAL.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
            }
        }
    }

    private static boolean isValidEndPortalFrame(ServerWorld world, BlockPos interiorNorthWest) {
        for (int xOffset = -1; xOffset <= 3; xOffset++) {
            for (int zOffset = -1; zOffset <= 3; zOffset++) {
                boolean frame = ((zOffset == -1 || zOffset == 3) && xOffset >= 0 && xOffset <= 2)
                        || ((xOffset == -1 || xOffset == 3) && zOffset >= 0 && zOffset <= 2);
                boolean interior = xOffset >= 0 && xOffset <= 2 && zOffset >= 0 && zOffset <= 2;
                BlockPos pos = interiorNorthWest.add(xOffset, 0, zOffset);
                if (frame) {
                    if (!world.getBlockState(pos).isOf(Blocks.END_PORTAL_FRAME)) {
                        return false;
                    }
                    continue;
                }

                if (interior && !canReplaceWithEndPortalBlock(world, pos)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean isInsideEndPortalFrameInterior(ServerWorld world, BlockPos pos) {
        for (int xOffset = 0; xOffset < 3; xOffset++) {
            for (int zOffset = 0; zOffset < 3; zOffset++) {
                if (isValidEndPortalFrame(world, pos.add(-xOffset, 0, -zOffset))) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean canReplaceWithEndPortalBlock(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return world.getBlockEntity(pos) == null
                && !state.isOf(Blocks.END_PORTAL_FRAME);
    }

    private static void tryLightPortalFrame(
            ServerWorld world,
            BlockPos bottomLeftInterior,
            Direction.Axis axis,
            Set<BlockPos> litBlocks
    ) {
        for (int width = 2; width <= MAX_PORTAL_FRAME_INTERIOR_WIDTH; width++) {
            for (int height = 3; height <= MAX_PORTAL_FRAME_INTERIOR_HEIGHT; height++) {
                if (!isValidPortalFrame(world, bottomLeftInterior, axis, width, height)) {
                    continue;
                }

                Direction widthDirection = getPortalWidthDirection(axis);
                BlockState portalState = Blocks.NETHER_PORTAL.getDefaultState().with(NetherPortalBlock.AXIS, axis);
                for (int widthOffset = 0; widthOffset < width; widthOffset++) {
                    for (int yOffset = 0; yOffset < height; yOffset++) {
                        BlockPos portalPos = bottomLeftInterior.offset(widthDirection, widthOffset).up(yOffset);
                        if (litBlocks.add(portalPos)) {
                            world.setBlockState(portalPos, portalState, Block.NOTIFY_LISTENERS);
                        }
                    }
                }

                return;
            }
        }
    }

    private static boolean isValidPortalFrame(
            ServerWorld world,
            BlockPos bottomLeftInterior,
            Direction.Axis axis,
            int width,
            int height
    ) {
        Direction widthDirection = getPortalWidthDirection(axis);
        for (int yOffset = -1; yOffset <= height; yOffset++) {
            if (!isPortalFrameBlock(world.getBlockState(bottomLeftInterior.offset(widthDirection, -1).up(yOffset)))
                    || !isPortalFrameBlock(world.getBlockState(bottomLeftInterior.offset(widthDirection, width).up(yOffset)))) {
                return false;
            }
        }

        for (int widthOffset = 0; widthOffset < width; widthOffset++) {
            if (!isPortalFrameBlock(world.getBlockState(bottomLeftInterior.offset(widthDirection, widthOffset).down()))
                    || !isPortalFrameBlock(world.getBlockState(bottomLeftInterior.offset(widthDirection, widthOffset).up(height)))) {
                return false;
            }

            for (int yOffset = 0; yOffset < height; yOffset++) {
                if (!canReplaceWithPortalBlock(world, bottomLeftInterior.offset(widthDirection, widthOffset).up(yOffset))) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean isPortalFrameBlock(BlockState state) {
        return state.isOf(Blocks.OBSIDIAN)
                || state.isOf(Blocks.CRYING_OBSIDIAN);
    }

    private static boolean canReplaceWithPortalBlock(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (world.getBlockEntity(pos) != null || isPortalFrameBlock(state)) {
            return false;
        }

        return true;
    }

    private static Direction getPortalWidthDirection(Direction.Axis axis) {
        return axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
    }

    private static int closeOfficialPortalStructures(
            ServerWorld world,
            BreachedStructurePlacementState state,
            PortalEventType portalEventType
    ) {
        int removedBlocks = 0;
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> entry : new ArrayList<>(state.placements())) {
            if (!isPortalEventPlacement(entry.getKey(), portalEventType)
                    || !entry.getValue().active()
                    || !hasPlacementFootprint(entry.getValue())) {
                continue;
            }

            Optional<BreachedStructureDefinition> definition = getActiveDefinition(world, structureKey(entry.getKey()));
            if (definition.isEmpty()) {
                continue;
            }

            removedBlocks += removeOfficialPortalBlocks(world, definition.get(), entry.getValue(), portalEventType);
        }

        return removedBlocks;
    }

    private static int removeOfficialPortalBlocks(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement,
            PortalEventType portalEventType
    ) {
        loadPlacementFootprintChunks(world, placement);
        if (portalEventType == PortalEventType.END && isEndPortalStructure(definition)) {
            return removeTimedEndPortalBlocks(world, definition, placement);
        }

        Block portalBlock = getPortalBlock(portalEventType);
        int removedBlocks = 0;
        int maxX = placement.originX() + placement.sizeX();
        int maxY = Math.min(world.getTopYInclusive() + 1, placement.originY() + placement.sizeY());
        int maxZ = placement.originZ() + placement.sizeZ();

        for (int x = placement.originX(); x < maxX; x++) {
            for (int y = Math.max(world.getBottomY(), placement.originY()); y < maxY; y++) {
                for (int z = placement.originZ(); z < maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (world.getBlockState(pos).isOf(portalBlock)) {
                        removeBlockWithoutDrops(world, pos);
                        removedBlocks++;
                    }
                }
            }
        }

        return removedBlocks;
    }

    private static int removeTimedEndPortalBlocks(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement
    ) {
        Set<BlockPos> timedPortalBlocks = getTemplateEndPortalFrameInteriorBlocks(world, definition, placement);
        if (timedPortalBlocks.isEmpty()) {
            return removeWorldDetectedTimedEndPortalBlocks(world, placement);
        }

        int removedBlocks = 0;
        for (BlockPos pos : timedPortalBlocks) {
            if (world.getBlockState(pos).isOf(Blocks.END_PORTAL)) {
                removeBlockWithoutDrops(world, pos);
                removedBlocks++;
            }
        }

        Set<BlockPos> permanentPortalBlocks = new HashSet<>();
        lightPermanentEndPortalTemplateBlocks(world, definition, placement, permanentPortalBlocks);
        return removedBlocks;
    }

    private static Set<BlockPos> getTemplateEndPortalFrameInteriorBlocks(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement
    ) {
        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, definition);
        if (template.isEmpty()) {
            return Set.of();
        }

        Set<BlockPos> frameBlocks = new HashSet<>();
        BlockPos origin = new BlockPos(placement.originX(), placement.originY(), placement.originZ());
        for (BreachedStructureSpawnManager.TemplatePlacedBlock block : BreachedStructureSpawnManager.getTemplatePlacedBlocks(
                world,
                definition,
                template.get(),
                origin,
                definition.mirror(),
                definition.rotation()
        )) {
            if (block.state().isOf(Blocks.END_PORTAL_FRAME)) {
                frameBlocks.add(block.pos().toImmutable());
            }
        }

        if (frameBlocks.isEmpty()) {
            return Set.of();
        }

        Set<BlockPos> interiorBlocks = new HashSet<>();
        int maxX = placement.originX() + placement.sizeX();
        int maxY = Math.min(world.getTopYInclusive() + 1, placement.originY() + placement.sizeY());
        int maxZ = placement.originZ() + placement.sizeZ();

        for (int x = placement.originX() + 1; x <= maxX - 4; x++) {
            for (int y = Math.max(world.getBottomY(), placement.originY()); y < maxY; y++) {
                for (int z = placement.originZ() + 1; z <= maxZ - 4; z++) {
                    BlockPos interiorNorthWest = new BlockPos(x, y, z);
                    if (!isTemplateEndPortalFrame(frameBlocks, interiorNorthWest)) {
                        continue;
                    }

                    for (int xOffset = 0; xOffset < 3; xOffset++) {
                        for (int zOffset = 0; zOffset < 3; zOffset++) {
                            interiorBlocks.add(interiorNorthWest.add(xOffset, 0, zOffset));
                        }
                    }
                }
            }
        }

        return interiorBlocks;
    }

    private static boolean isTemplateEndPortalFrame(Set<BlockPos> frameBlocks, BlockPos interiorNorthWest) {
        for (int xOffset = -1; xOffset <= 3; xOffset++) {
            for (int zOffset = -1; zOffset <= 3; zOffset++) {
                boolean frame = ((zOffset == -1 || zOffset == 3) && xOffset >= 0 && xOffset <= 2)
                        || ((xOffset == -1 || xOffset == 3) && zOffset >= 0 && zOffset <= 2);
                if (frame && !frameBlocks.contains(interiorNorthWest.add(xOffset, 0, zOffset))) {
                    return false;
                }
            }
        }

        return true;
    }

    private static int removeWorldDetectedTimedEndPortalBlocks(
            ServerWorld world,
            BreachedStructurePlacementState.SavedPlacement placement
    ) {
        int removedBlocks = 0;
        int maxX = placement.originX() + placement.sizeX();
        int maxY = Math.min(world.getTopYInclusive() + 1, placement.originY() + placement.sizeY());
        int maxZ = placement.originZ() + placement.sizeZ();

        for (int x = placement.originX(); x < maxX; x++) {
            for (int y = Math.max(world.getBottomY(), placement.originY()); y < maxY; y++) {
                for (int z = placement.originZ(); z < maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (world.getBlockState(pos).isOf(Blocks.END_PORTAL)
                            && isInsideEndPortalFrameInterior(world, pos)) {
                        removeBlockWithoutDrops(world, pos);
                        removedBlocks++;
                    }
                }
            }
        }

        return removedBlocks;
    }

    private static int killDimensionPlayers(ServerWorld overworld, RegistryKey<World> dimensionKey) {
        MinecraftServer server = overworld.getServer();
        if (server == null) {
            return 0;
        }

        ServerWorld dimensionWorld = server.getWorld(dimensionKey);
        if (dimensionWorld == null) {
            return 0;
        }

        int killedPlayers = 0;
        for (ServerPlayerEntity player : new ArrayList<>(dimensionWorld.getPlayers())) {
            player.kill(dimensionWorld);
            killedPlayers++;
        }

        return killedPlayers;
    }

    private static boolean hasPlacementFootprint(BreachedStructurePlacementState.SavedPlacement placement) {
        return placement.sizeX() > 0 && placement.sizeY() > 0 && placement.sizeZ() > 0;
    }

    private static void loadPlacementFootprintChunks(
            ServerWorld world,
            BreachedStructurePlacementState.SavedPlacement placement
    ) {
        int maxX = placement.originX() + Math.max(1, placement.sizeX()) - 1;
        int maxZ = placement.originZ() + Math.max(1, placement.sizeZ()) - 1;
        int minChunkX = Math.floorDiv(placement.originX(), CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(maxX, CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(placement.originZ(), CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(maxZ, CHUNK_SIZE);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                world.getChunk(chunkX, chunkZ);
            }
        }
    }

    private static Optional<PortalEventType> getPortalEventType(BreachedStructureDefinition definition) {
        return getPortalEventType(BreachedStructureDefinitions.key(definition));
    }

    private static Optional<PortalEventType> getPortalEventType(String placementKey) {
        String structureKey = structureKey(placementKey);
        if (structureKey.equals(PORTAL_STRUCTURE_KEY)) {
            return Optional.of(PortalEventType.NETHER);
        }
        if (structureKey.equals(END_PORTAL_STRUCTURE_KEY)) {
            return Optional.of(PortalEventType.END);
        }

        return Optional.empty();
    }

    private static boolean isPortalEventPlacement(String placementKey, PortalEventType portalEventType) {
        return isPlacementForStructure(placementKey, getPortalStructureKey(portalEventType));
    }

    private static boolean isAnyPortalEventPlacement(String placementKey) {
        return getPortalEventType(placementKey).isPresent();
    }

    private static String getPortalStructureKey(PortalEventType portalEventType) {
        return switch (portalEventType) {
            case NETHER -> PORTAL_STRUCTURE_KEY;
            case END -> END_PORTAL_STRUCTURE_KEY;
        };
    }

    private static Block getPortalBlock(PortalEventType portalEventType) {
        return switch (portalEventType) {
            case NETHER -> Blocks.NETHER_PORTAL;
            case END -> Blocks.END_PORTAL;
        };
    }

    private static String getPortalEventLogName(PortalEventType portalEventType) {
        return switch (portalEventType) {
            case NETHER -> "Nether";
            case END -> "End";
        };
    }

    private static long getPortalEventEndTime(
            BreachedStructurePlacementState state,
            PortalEventType portalEventType
    ) {
        return switch (portalEventType) {
            case NETHER -> state.getPortalEventEndTime();
            case END -> state.getEndPortalEventEndTime();
        };
    }

    private static boolean isPortalEventActive(
            BreachedStructurePlacementState state,
            PortalEventType portalEventType,
            long worldTime
    ) {
        return switch (portalEventType) {
            case NETHER -> state.isPortalEventActive(worldTime);
            case END -> state.isEndPortalEventActive(worldTime);
        };
    }

    private static void startPortalEventState(
            BreachedStructurePlacementState state,
            PortalEventType portalEventType,
            long eventEndTime
    ) {
        switch (portalEventType) {
            case NETHER -> state.startPortalEvent(eventEndTime);
            case END -> state.startEndPortalEvent(eventEndTime);
        }
    }

    private static boolean markPortalEventWarningSent(
            BreachedStructurePlacementState state,
            PortalEventType portalEventType,
            int secondsRemaining
    ) {
        return switch (portalEventType) {
            case NETHER -> state.markPortalEventWarningSent(secondsRemaining);
            case END -> state.markEndPortalEventWarningSent(secondsRemaining);
        };
    }

    private static void clearPortalEventState(
            BreachedStructurePlacementState state,
            PortalEventType portalEventType
    ) {
        switch (portalEventType) {
            case NETHER -> state.clearPortalEvent();
            case END -> state.clearEndPortalEvent();
        }
    }

    private static Text getMajorRestockAnnouncement(String placementKey) {
        String structureKey = structureKey(placementKey);
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.SWORD_STATUE))) {
            return Text.literal("Statue has been restocked").formatted(Formatting.BLACK);
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.PINK_TREE))) {
            return Text.literal("Great Tree has been restocked").formatted(Formatting.LIGHT_PURPLE);
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.BIG_BOAT))) {
            return Text.literal("Ship has been restocked").formatted(Formatting.BLUE);
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.HORACE))) {
            return Text.literal("Horace has been restocked").formatted(Formatting.RED);
        }
        if (structureKey.equals(BreachedStructureDefinitions.key(BreachedStructureDefinitions.OFFICIAL_NETHER_PORTAL))) {
            return Text.literal("Nether Portal structures have been restocked.").formatted(Formatting.DARK_PURPLE);
        }
        if (structureKey.equals(END_PORTAL_STRUCTURE_KEY)) {
            return Text.literal("End Portal has been restocked.").formatted(Formatting.DARK_PURPLE);
        }
        if (structureKey.equals(EYEBALL_STRUCTURE_KEY)) {
            return Text.literal("Eyeball has been restocked").formatted(Formatting.DARK_RED);
        }

        return Text.literal("Major structure loot has been restocked");
    }

    private static boolean tryBackfillMajorLootContainers(
            ServerWorld world,
            BreachedStructurePlacementState state,
            String placementKey,
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement,
            StructureTickDiagnostics diagnostics
    ) {
        diagnostics.majorLootBackfillAttempts++;
        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, definition);
        if (template.isEmpty()) {
            return false;
        }

        Vec3i footprintSize = BreachedStructureSpawnManager.getRotatedSize(template.get().getSize(), definition.rotation());
        if (!isFootprintLoaded(world, placement.originX(), placement.originZ(), footprintSize)) {
            forceLoadMissingRestockFootprintChunks(world, placementKey, placement.originX(), placement.originZ(), footprintSize, diagnostics);
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
                : createNextLootRestockTime(world, placementKey);
        state.setLootContainers(placementKey, lootContainers, placement.lastRestockTime(), nextRestockTime);
        diagnostics.majorLootBackfillsCompleted++;
        diagnostics.majorLootContainersTracked += lootContainers.size();
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
            List<BreachedStructurePlacementState.SavedLootContainer> lootContainers,
            StructureTickDiagnostics diagnostics
    ) {
        Set<ChunkPos> chunks = new HashSet<>();
        for (BreachedStructurePlacementState.SavedLootContainer lootContainer : lootContainers) {
            BlockPos pos = lootContainer.pos();
            chunks.add(new ChunkPos(Math.floorDiv(pos.getX(), CHUNK_SIZE), Math.floorDiv(pos.getZ(), CHUNK_SIZE)));
        }

        for (ChunkPos chunkPos : chunks) {
            forceLoadRestockChunk(world, placementKey, chunkPos, diagnostics);
        }
    }

    private static void forceLoadMissingRestockFootprintChunks(
            ServerWorld world,
            String placementKey,
            int originX,
            int originZ,
            Vec3i footprintSize,
            StructureTickDiagnostics diagnostics
    ) {
        int minChunkX = Math.floorDiv(originX, CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(originZ, CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(originX + footprintSize.getX() - 1, CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(originZ + footprintSize.getZ() - 1, CHUNK_SIZE);

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                forceLoadRestockChunk(world, placementKey, new ChunkPos(chunkX, chunkZ), diagnostics);
            }
        }
    }

    private static void forceLoadRestockChunk(
            ServerWorld world,
            String placementKey,
            ChunkPos chunkPos,
            StructureTickDiagnostics diagnostics
    ) {
        if (world.isChunkLoaded(chunkPos.x, chunkPos.z)) {
            return;
        }

        if (!isRestockChunkForceLoaded(world.getSeed(), placementKey, chunkPos.x, chunkPos.z)) {
            FORCED_RESTOCK_CHUNKS.add(new ForcedRestockChunk(world.getSeed(), placementKey, chunkPos.x, chunkPos.z));
            diagnostics.majorRestockChunksForceLoaded++;
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
        return createNextLootRestockTime(
                world,
                placementKey,
                world.getTime(),
                lootConfig.minRestockIntervalTicks,
                lootConfig.maxRestockIntervalTicks
        );
    }

    private static long createNextLootRestockTime(ServerWorld world, String placementKey) {
        Optional<PortalEventType> portalEventType = getPortalEventType(placementKey);
        if (portalEventType.isPresent()) {
            return createNextPortalLootRestockTime(world, portalEventType.get());
        }

        return createNextMajorLootRestockTime(world, placementKey);
    }

    private static long createNextPortalLootRestockTime(ServerWorld world, PortalEventType portalEventType) {
        return createNextPortalLootRestockTime(world, portalEventType, world.getTime());
    }

    private static long createNextPortalLootRestockTime(
            ServerWorld world,
            PortalEventType portalEventType,
            long baseTime
    ) {
        return createNextLootRestockTime(
                world,
                getPortalStructureKey(portalEventType),
                baseTime,
                PORTAL_MIN_RESTOCK_INTERVAL_TICKS,
                PORTAL_MAX_RESTOCK_INTERVAL_TICKS
        );
    }

    private static long createNextLootRestockTime(
            ServerWorld world,
            String restockKey,
            long baseTime,
            int minIntervalTicks,
            int maxIntervalTicks
    ) {
        int intervalRange = maxIntervalTicks - minIntervalTicks;
        java.util.Random random = new java.util.Random(
                world.getSeed()
                        ^ (baseTime * 0x9E3779B97F4A7C15L)
                        ^ ((long) restockKey.hashCode() * 0xBF58476D1CE4E5B9L)
        );
        int interval = minIntervalTicks;
        if (intervalRange > 0) {
            interval += random.nextInt(intervalRange + 1);
        }

        return baseTime + interval;
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
        if (preset.isPresent() && preset.get() == BreachedDimensionRules.BreachedPreset.REGULAR) {
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
        int maxVegetationObstruction = getMaxVegetationObstruction(definition);
        if (maxVegetationObstruction <= 0 && definition.maxSolidObstruction() <= 0) {
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

        if (maxVegetationObstruction >= 0 && vegetationBlocks > maxVegetationObstruction) {
            return new ObstructionEvaluation(false, Double.MAX_VALUE,
                    "too much vegetation obstruction: " + vegetationBlocks
                            + " blocks, max " + maxVegetationObstruction);
        }

        if (definition.maxSolidObstruction() >= 0 && solidBlocks > definition.maxSolidObstruction()) {
            return new ObstructionEvaluation(false, Double.MAX_VALUE,
                    "too much solid obstruction: " + solidBlocks
                            + " blocks, max " + definition.maxSolidObstruction());
        }

        return new ObstructionEvaluation(true, vegetationBlocks + (solidBlocks * 4.0D), null);
    }

    private static int getMaxVegetationObstruction(BreachedStructureDefinition definition) {
        if (isForestTolerantMinorPoi(definition)) {
            return Math.max(definition.maxVegetationObstruction(), MINOR_FOREST_VEGETATION_OBSTRUCTION_LIMIT);
        }

        return definition.maxVegetationObstruction();
    }

    private static boolean isForestTolerantMinorPoi(BreachedStructureDefinition definition) {
        return BreachedStructureDefinitions.isForestTolerantGroundMinorPoi(definition);
    }

    private static boolean isVegetation(BlockState state) {
        return state.getBlock() instanceof LeavesBlock
                || state.isIn(BlockTags.LOGS)
                || state.isOf(Blocks.TALL_GRASS)
                || state.isOf(Blocks.FERN)
                || state.isOf(Blocks.LARGE_FERN)
                || state.isOf(Blocks.VINE)
                || state.isOf(Blocks.SHORT_GRASS);
    }

    private static boolean isMinorPoiClearanceBlock(BlockState state) {
        return isNaturalSurfaceObstruction(state) || isVegetation(state);
    }

    private static boolean shouldSnapshotMinorPoiClearanceBlock(
            BreachedStructureDefinition definition,
            BlockState state
    ) {
        if (definition.canClearBlocks()) {
            return isMinorPoiClearanceBlock(state);
        }

        return isForestTolerantMinorPoi(definition) && isVegetation(state);
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

            if (!isInsideProtectedStructureBuildArea(world, placementContext.getBlockPos())) {
                return ActionResult.PASS;
            }

            player.sendMessage(Text.literal("Protected Breached structures cannot be modified."), false);
            return ActionResult.FAIL;
        });
    }

    private static void registerSafezoneEvents() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity victim)) {
                return true;
            }

            Entity attacker = source.getAttacker();
            if (!(attacker instanceof PlayerEntity attackingPlayer) || attacker == victim) {
                return true;
            }

            if (!isInsideTownhallSafezone(victim.getEntityWorld(), victim.getBlockPos())
                    && !isInsideTownhallSafezone(attackingPlayer.getEntityWorld(), attackingPlayer.getBlockPos())) {
                return true;
            }

            if (attackingPlayer instanceof ServerPlayerEntity serverAttacker) {
                serverAttacker.sendMessage(Text.literal("PvP is disabled inside the Town Hall safezone."), false);
            }
            return false;
        });
    }

    public static boolean isInsideTownhallSafezone(World world, BlockPos pos) {
        if (world.isClient() || !world.getRegistryKey().equals(World.OVERWORLD) || !(world instanceof ServerWorld serverWorld)) {
            return false;
        }

        BreachedStructurePlacementState state = BreachedStructurePlacementState.get(serverWorld.getServer());
        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> placement : state.placements()) {
            if (!placement.getValue().active() || !structureKey(placement.getKey()).equals(TOWNHALL_STRUCTURE_KEY)) {
                continue;
            }

            if (isInsideTownhallSafezoneBounds(placement.getValue(), pos)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isInsideTownhallSafezoneBounds(
            BreachedStructurePlacementState.SavedPlacement placement,
            BlockPos pos
    ) {
        if (placement.sizeX() <= 0 || placement.sizeY() <= 0 || placement.sizeZ() <= 0) {
            return false;
        }

        int maxSafezoneY = Math.min(
                placement.originY() + placement.sizeY(),
                placement.originY() + END_PORTAL_TOWNHALL_Y_OFFSET
        );
        return pos.getX() >= placement.originX()
                && pos.getX() < placement.originX() + placement.sizeX()
                && pos.getY() >= placement.originY()
                && pos.getY() < maxSafezoneY
                && pos.getZ() >= placement.originZ()
                && pos.getZ() < placement.originZ() + placement.sizeZ();
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

            if (isExactProtectedStructure(definition.get())) {
                if (isInsideExactProtectedStructure(serverWorld, definition.get(), placement.getValue(), pos)) {
                    return true;
                }
                continue;
            }

            if (isVolumeProtectedStructure(definition.get())) {
                if (isInsidePlacementBounds(placement.getValue(), pos)) {
                    return true;
                }
                continue;
            }

            if (BreachedStructureSpawnManager.isInsideProtectionRadius(
                    definition.get(),
                    placement.getValue().centerX(),
                    getProtectedCenterY(placement.getValue(), pos),
                    placement.getValue().centerZ(),
                    pos
            )) {
                return true;
            }
        }

        return false;
    }

    public static boolean isInsideMajorStructureLandlockExclusion(World world, BlockPos pos) {
        if (world.isClient() || !world.getRegistryKey().equals(World.OVERWORLD) || !(world instanceof ServerWorld serverWorld)) {
            return false;
        }

        BreachedStructurePlacementState state = BreachedStructurePlacementState.get(serverWorld.getServer());
        migrateLegacyCentralSpawnState(serverWorld, state);

        for (Map.Entry<String, BreachedStructurePlacementState.SavedPlacement> placement : state.placements()) {
            if (!placement.getValue().active()) {
                continue;
            }

            Optional<BreachedStructureDefinition> definition = getProtectedDefinition(placement.getKey());
            if (definition.isEmpty()) {
                continue;
            }

            if (isCuboidLandlockExclusion(definition.get(), placement.getValue(), pos)
                    || isRadiusLandlockExclusion(definition.get(), placement.getValue(), pos)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isInsideProtectedStructureBuildArea(World world, BlockPos pos) {
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

            if (isExactProtectedStructure(definition.get())) {
                if (isInsidePlacementBounds(placement.getValue(), pos)) {
                    return true;
                }
                continue;
            }

            if (isVolumeProtectedStructure(definition.get())) {
                if (isInsidePlacementBounds(placement.getValue(), pos)) {
                    return true;
                }
                continue;
            }

            if (BreachedStructureSpawnManager.isInsideProtectionRadius(
                    definition.get(),
                    placement.getValue().centerX(),
                    getProtectedCenterY(placement.getValue(), pos),
                    placement.getValue().centerZ(),
                    pos
            )) {
                return true;
            }
        }

        return false;
    }

    private static boolean isCuboidLandlockExclusion(
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement,
            BlockPos pos
    ) {
        if (!isExactProtectedStructure(definition) && !isVolumeProtectedStructure(definition)) {
            return false;
        }

        return isInsideExpandedPlacementBounds(placement, pos, MAJOR_STRUCTURE_LANDLOCK_EXCLUSION_MARGIN);
    }

    private static boolean isRadiusLandlockExclusion(
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement,
            BlockPos pos
    ) {
        if (!definition.protectedStructure() || definition.protectionRadius() <= 0) {
            return false;
        }

        int expandedRadius = definition.protectionRadius() + MAJOR_STRUCTURE_LANDLOCK_EXCLUSION_MARGIN;
        long xDistance = pos.getX() - placement.centerX();
        long yDistance = pos.getY() - getProtectedCenterY(placement, pos);
        long zDistance = pos.getZ() - placement.centerZ();
        return xDistance * xDistance + yDistance * yDistance + zDistance * zDistance <= (long) expandedRadius * expandedRadius;
    }

    private static boolean isExactProtectedStructure(BreachedStructureDefinition definition) {
        return false;
    }

    private static boolean isVolumeProtectedStructure(BreachedStructureDefinition definition) {
        String structureKey = BreachedStructureDefinitions.key(definition);
        return structureKey.equals(END_PORTAL_STRUCTURE_KEY) || structureKey.equals(EYEBALL_STRUCTURE_KEY);
    }

    private static boolean isInsideExactProtectedStructure(
            ServerWorld world,
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement,
            BlockPos pos
    ) {
        if (!isInsidePlacementBounds(placement, pos)) {
            return false;
        }

        Set<BlockPos> protectedBlocks = getExactProtectionBlocks(world, definition);
        BlockPos relativePos = new BlockPos(
                pos.getX() - placement.originX(),
                pos.getY() - placement.originY(),
                pos.getZ() - placement.originZ()
        );
        return protectedBlocks.contains(relativePos);
    }

    private static boolean isInsidePlacementBounds(
            BreachedStructurePlacementState.SavedPlacement placement,
            BlockPos pos
    ) {
        if (placement.sizeX() <= 0 || placement.sizeY() <= 0 || placement.sizeZ() <= 0) {
            return false;
        }

        return pos.getX() >= placement.originX()
                && pos.getX() < placement.originX() + placement.sizeX()
                && pos.getY() >= placement.originY()
                && pos.getY() < placement.originY() + placement.sizeY()
                && pos.getZ() >= placement.originZ()
                && pos.getZ() < placement.originZ() + placement.sizeZ();
    }

    private static boolean isInsideExpandedPlacementBounds(
            BreachedStructurePlacementState.SavedPlacement placement,
            BlockPos pos,
            int margin
    ) {
        if (placement.sizeX() <= 0 || placement.sizeY() <= 0 || placement.sizeZ() <= 0) {
            return false;
        }

        int expandedMargin = Math.max(0, margin);
        return pos.getX() >= placement.originX() - expandedMargin
                && pos.getX() < placement.originX() + placement.sizeX() + expandedMargin
                && pos.getY() >= placement.originY() - expandedMargin
                && pos.getY() < placement.originY() + placement.sizeY() + expandedMargin
                && pos.getZ() >= placement.originZ() - expandedMargin
                && pos.getZ() < placement.originZ() + placement.sizeZ() + expandedMargin;
    }

    private static int getProtectedCenterY(BreachedStructurePlacementState.SavedPlacement placement, BlockPos pos) {
        if (placement.sizeY() <= 0) {
            return pos.getY();
        }

        return getPlacementCenterY(placement);
    }

    private static int getPlacementCenterY(BreachedStructurePlacementState.SavedPlacement placement) {
        if (placement.sizeY() <= 0) {
            return placement.originY();
        }

        return placement.originY() + placement.sizeY() / 2;
    }

    private static Set<BlockPos> getExactProtectionBlocks(ServerWorld world, BreachedStructureDefinition definition) {
        String structureKey = BreachedStructureDefinitions.key(definition);
        Set<BlockPos> cachedBlocks = EXACT_PROTECTION_BLOCKS.get(structureKey);
        if (cachedBlocks != null) {
            return cachedBlocks;
        }

        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, definition);
        if (template.isEmpty()) {
            EXACT_PROTECTION_BLOCKS.put(structureKey, Set.of());
            return Set.of();
        }

        Set<BlockPos> protectedBlocks = new HashSet<>();
        for (BreachedStructureSpawnManager.TemplatePlacedBlock block : BreachedStructureSpawnManager.getTemplatePlacedBlocks(
                world,
                definition,
                template.get(),
                BlockPos.ORIGIN,
                definition.mirror(),
                definition.rotation()
        )) {
            if (!block.state().isAir()) {
                protectedBlocks.add(block.pos().toImmutable());
            }
        }

        Set<BlockPos> immutableBlocks = Set.copyOf(protectedBlocks);
        EXACT_PROTECTION_BLOCKS.put(structureKey, immutableBlocks);
        return immutableBlocks;
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

    private static long elapsedSince(long startNanos) {
        return System.nanoTime() - startNanos;
    }

    private static final class StructureTickDiagnostics {
        private final long worldTime;
        private long totalNanos;
        private long stateSetupNanos;
        private long plannedPreparationNanos;
        private long minorDespawnNanos;
        private long pendingMinorNanos;
        private long playerMinorNanos;
        private long majorRestockNanos;
        private long plannedPlacementNanos;
        private int pendingMinorChunksAttempted;
        private int pendingMinorChunksResolved;
        private int pendingMinorPoisPlaced;
        private int playerMinorChunksChecked;
        private int playerMinorPoisPlaced;
        private int minorPoiPlacementsScanned;
        private int minorPoiDueForDespawn;
        private int minorPoiEvaluated;
        private int minorPoiExpectedBlocksChecked;
        private int minorPoiFootprintBlocksChecked;
        private int minorPoiFootprintClaimChecks;
        private int minorPoiPlayerTouched;
        private int minorPoiRetired;
        private int minorDespawnChunksForceLoaded;
        private int majorPlacementsScanned;
        private int majorPlacementsDue;
        private int majorLootBackfillAttempts;
        private int majorLootBackfillsCompleted;
        private int majorLootContainersTracked;
        private int majorLootContainersChecked;
        private int majorStructuresRestocked;
        private int majorContainersRestocked;
        private int majorRestockChunksForceLoaded;
        private int plannedCandidateEvaluations;
        private int plannedStructuresCompleted;

        private StructureTickDiagnostics(long worldTime) {
            this.worldTime = worldTime;
        }

        private void logIfSlow() {
            BreachedConfig.DiagnosticsSettings diagnosticsConfig = BreachedConfig.get().diagnostics;
            if (!diagnosticsConfig.structureTickLoggingEnabled) {
                return;
            }

            long slowTickThresholdNanos = (long) diagnosticsConfig.slowStructureTickThresholdMs * 1_000_000L;
            if (totalNanos < slowTickThresholdNanos) {
                return;
            }

            System.out.println("[Breached][Perf] Slow structure tick at world time " + worldTime
                    + ": total=" + toMillis(totalNanos) + "ms"
                    + " sections[state=" + toMillis(stateSetupNanos) + "ms"
                    + ", plannedPrep=" + toMillis(plannedPreparationNanos) + "ms"
                    + ", minorDespawn=" + toMillis(minorDespawnNanos) + "ms"
                    + ", pendingMinor=" + toMillis(pendingMinorNanos) + "ms"
                    + ", playerMinor=" + toMillis(playerMinorNanos) + "ms"
                    + ", majorRestock=" + toMillis(majorRestockNanos) + "ms"
                    + ", plannedPlacement=" + toMillis(plannedPlacementNanos) + "ms]");
            System.out.println("[Breached][Perf] Slow structure tick work at world time " + worldTime
                    + ": major[scanned=" + majorPlacementsScanned
                    + ", due=" + majorPlacementsDue
                    + ", backfillAttempts=" + majorLootBackfillAttempts
                    + ", backfills=" + majorLootBackfillsCompleted
                    + ", trackedContainers=" + majorLootContainersTracked
                    + ", checkedContainers=" + majorLootContainersChecked
                    + ", restockedStructures=" + majorStructuresRestocked
                    + ", restockedContainers=" + majorContainersRestocked
                    + ", forcedChunks=" + majorRestockChunksForceLoaded + "]"
                    + " minor[scanned=" + minorPoiPlacementsScanned
                    + ", due=" + minorPoiDueForDespawn
                    + ", evaluated=" + minorPoiEvaluated
                    + ", expectedBlocks=" + minorPoiExpectedBlocksChecked
                    + ", footprintBlocks=" + minorPoiFootprintBlocksChecked
                    + ", footprintClaimChecks=" + minorPoiFootprintClaimChecks
                    + ", playerTouched=" + minorPoiPlayerTouched
                    + ", retired=" + minorPoiRetired
                    + ", forcedChunks=" + minorDespawnChunksForceLoaded + "]"
                    + " pendingMinor[attempted=" + pendingMinorChunksAttempted
                    + ", resolved=" + pendingMinorChunksResolved
                    + ", placed=" + pendingMinorPoisPlaced + "]"
                    + " playerMinor[chunksChecked=" + playerMinorChunksChecked
                    + ", placed=" + playerMinorPoisPlaced + "]"
                    + " planned[evaluations=" + plannedCandidateEvaluations
                    + ", completed=" + plannedStructuresCompleted + "]");
        }

        private static long toMillis(long nanos) {
            return Math.max(0L, nanos / 1_000_000L);
        }
    }

    private record MajorStructureZone(String placementKey, Text entryMessage, int priority, long distanceSquared) {
    }

    public record BreachedMapMarker(String label, int x, int z, int color) {
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

    private record PortalRestockTarget(
            String placementKey,
            BreachedStructureDefinition definition,
            BreachedStructurePlacementState.SavedPlacement placement
    ) {
    }

    private record PortalRestockResult(
            int restockedStructures,
            int restockedContainers,
            boolean portalRestocked
    ) {
        private static final PortalRestockResult NONE = new PortalRestockResult(0, 0, false);
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

    private enum PortalEventType {
        NETHER,
        END
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
