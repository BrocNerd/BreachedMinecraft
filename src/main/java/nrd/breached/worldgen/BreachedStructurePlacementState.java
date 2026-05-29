package nrd.breached.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class BreachedStructurePlacementState extends PersistentState {
    private static final String PLACEMENTS_KEY = "placements";
    private static final String RESERVATIONS_KEY = "reservations";
    private static final String FAILED_CANDIDATES_KEY = "failed_candidates";
    private static final String CENTER_X_KEY = "center_x";
    private static final String CENTER_Z_KEY = "center_z";
    private static final String ORIGIN_X_KEY = "origin_x";
    private static final String ORIGIN_Y_KEY = "origin_y";
    private static final String ORIGIN_Z_KEY = "origin_z";
    private static final String PLACED_TIME_KEY = "placed_time";
    private static final String LOOT_CONTAINERS_KEY = "loot_containers";
    private static final String LOOT_CONTAINERS_SCANNED_KEY = "loot_containers_scanned";
    private static final String LAST_RESTOCK_TIME_KEY = "last_restock_time";
    private static final String NEXT_RESTOCK_TIME_KEY = "next_restock_time";
    private static final String SIZE_X_KEY = "size_x";
    private static final String SIZE_Y_KEY = "size_y";
    private static final String SIZE_Z_KEY = "size_z";
    private static final String RETIRED_KEY = "retired";
    private static final String PLAYER_TOUCHED_KEY = "player_touched";
    private static final String NEXT_MINOR_DESPAWN_TIME_KEY = "next_minor_despawn_time";
    private static final String CLEANUP_BLOCKS_KEY = "cleanup_blocks";
    private static final String RESTORE_BLOCKS_KEY = "restore_blocks";
    private static final String STRUCTURE_KEY = "structure_key";
    private static final String CANDIDATE_INDEX_KEY = "candidate_index";
    private static final String PORTAL_EVENT_END_TIME_KEY = "portal_event_end_time";
    private static final String PORTAL_EVENT_WARNINGS_KEY = "portal_event_warnings";
    private static final String X_KEY = "x";
    private static final String Y_KEY = "y";
    private static final String Z_KEY = "z";
    private static final String BLOCK_STATE_KEY = "block_state";
    private static final String LOOT_TABLE_KEY = "loot_table";
    private static final String LOOT_TABLE_SEED_KEY = "loot_table_seed";
    private static final Codec<BreachedStructurePlacementState> CODEC = NbtCompound.CODEC.xmap(
            BreachedStructurePlacementState::fromNbt,
            BreachedStructurePlacementState::toNbt
    );
    private static final PersistentStateType<BreachedStructurePlacementState> TYPE = new PersistentStateType<>(
            "breached_structure_placements",
            BreachedStructurePlacementState::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final Map<String, SavedPlacement> placements = new HashMap<>();
    private final Map<String, ReservedPlacement> reservations = new HashMap<>();
    private final Map<String, Set<Integer>> failedCandidates = new HashMap<>();
    private long portalEventEndTime;
    private final Set<Integer> portalEventWarnings = new HashSet<>();

    public static BreachedStructurePlacementState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
    }

    public boolean hasPlacement(String key) {
        return placements.containsKey(key);
    }

    public Optional<SavedPlacement> getPlacement(String key) {
        return Optional.ofNullable(placements.get(key));
    }

    public Set<Map.Entry<String, SavedPlacement>> placements() {
        return placements.entrySet();
    }

    public boolean hasReservation(String key) {
        return reservations.containsKey(key);
    }

    public Set<Map.Entry<String, ReservedPlacement>> reservations() {
        return reservations.entrySet();
    }

    public void reservePlacement(String key, String structureKey, int candidateIndex, int x, int z) {
        reservations.put(key, new ReservedPlacement(structureKey, candidateIndex, x, z));
        markDirty();
    }

    public boolean hasFailedCandidate(String key, int candidateIndex) {
        return failedCandidates.getOrDefault(key, Set.of()).contains(candidateIndex);
    }

    public int failedCandidateCount(String key) {
        return failedCandidates.getOrDefault(key, Set.of()).size();
    }

    public void markCandidateFailed(String key, int candidateIndex) {
        failedCandidates.computeIfAbsent(key, ignored -> new HashSet<>()).add(candidateIndex);
        markDirty();
    }

    public void markPlaced(String key, BreachedStructurePlacement placement) {
        markPlaced(key, placement, 0L);
    }

    public void markPlaced(String key, BreachedStructurePlacement placement, long placedTime) {
        markPlaced(key, placement, placedTime, List.of(), false);
    }

    public void markPlacedMinor(
            String key,
            BreachedStructurePlacement placement,
            long placedTime,
            long nextMinorDespawnTime,
            List<BlockPos> cleanupBlocks,
            List<SavedBlockSnapshot> restoreBlocks
    ) {
        placements.put(key, new SavedPlacement(
                BreachedStructureSpawnManager.getProtectedCenterX(placement),
                BreachedStructureSpawnManager.getProtectedCenterZ(placement),
                placement.origin().getX(),
                placement.origin().getY(),
                placement.origin().getZ(),
                placedTime,
                List.of(),
                false,
                placedTime,
                placedTime,
                placement.size().getX(),
                placement.size().getY(),
                placement.size().getZ(),
                false,
                false,
                nextMinorDespawnTime,
                cleanupBlocks,
                restoreBlocks
        ));
        markDirty();
    }

    public void markPlaced(
            String key,
            BreachedStructurePlacement placement,
            long placedTime,
            List<SavedLootContainer> lootContainers
    ) {
        markPlaced(key, placement, placedTime, lootContainers, placedTime);
    }

    public void markPlaced(
            String key,
            BreachedStructurePlacement placement,
            long placedTime,
            List<SavedLootContainer> lootContainers,
            long nextRestockTime
    ) {
        markPlaced(key, placement, placedTime, lootContainers, true, nextRestockTime);
    }

    private void markPlaced(
            String key,
            BreachedStructurePlacement placement,
            long placedTime,
            List<SavedLootContainer> lootContainers,
            boolean lootContainersScanned
    ) {
        markPlaced(key, placement, placedTime, lootContainers, lootContainersScanned, placedTime);
    }

    private void markPlaced(
            String key,
            BreachedStructurePlacement placement,
            long placedTime,
            List<SavedLootContainer> lootContainers,
            boolean lootContainersScanned,
            long nextRestockTime
    ) {
        placements.put(key, new SavedPlacement(
                BreachedStructureSpawnManager.getProtectedCenterX(placement),
                BreachedStructureSpawnManager.getProtectedCenterZ(placement),
                placement.origin().getX(),
                placement.origin().getY(),
                placement.origin().getZ(),
                placedTime,
                lootContainers,
                lootContainersScanned,
                placedTime,
                nextRestockTime,
                placement.size().getX(),
                placement.size().getY(),
                placement.size().getZ(),
                false,
                false,
                0L,
                List.of(),
                List.of()
        ));
        markDirty();
    }

    public void markPlaced(String key, int centerX, int centerZ) {
        placements.put(key, new SavedPlacement(centerX, centerZ, centerX, 0, centerZ, 0L, List.of(), false, 0L, 0L, 0, 0, 0, false, false, 0L, List.of(), List.of()));
        markDirty();
    }

    public void setLootContainers(String key, List<SavedLootContainer> lootContainers, long lastRestockTime) {
        setLootContainers(key, lootContainers, lastRestockTime, lastRestockTime);
    }

    public void setLootContainers(
            String key,
            List<SavedLootContainer> lootContainers,
            long lastRestockTime,
            long nextRestockTime
    ) {
        SavedPlacement placement = placements.get(key);
        if (placement == null) {
            return;
        }

        placements.put(key, new SavedPlacement(
                placement.centerX(),
                placement.centerZ(),
                placement.originX(),
                placement.originY(),
                placement.originZ(),
                placement.placedTime(),
                lootContainers,
                true,
                lastRestockTime,
                nextRestockTime,
                placement.sizeX(),
                placement.sizeY(),
                placement.sizeZ(),
                placement.retired(),
                placement.playerTouched(),
                placement.nextMinorDespawnTime(),
                placement.cleanupBlocks(),
                placement.restoreBlocks()
        ));
        markDirty();
    }

    public void markLootRestocked(String key, long lastRestockTime) {
        markLootRestocked(key, lastRestockTime, lastRestockTime);
    }

    public void markLootRestocked(String key, long lastRestockTime, long nextRestockTime) {
        SavedPlacement placement = placements.get(key);
        if (placement == null) {
            return;
        }

        placements.put(key, new SavedPlacement(
                placement.centerX(),
                placement.centerZ(),
                placement.originX(),
                placement.originY(),
                placement.originZ(),
                placement.placedTime(),
                placement.lootContainers(),
                placement.lootContainersScanned(),
                lastRestockTime,
                nextRestockTime,
                placement.sizeX(),
                placement.sizeY(),
                placement.sizeZ(),
                placement.retired(),
                placement.playerTouched(),
                placement.nextMinorDespawnTime(),
                placement.cleanupBlocks(),
                placement.restoreBlocks()
        ));
        markDirty();
    }

    public void setMinorFootprintSize(String key, int sizeX, int sizeY, int sizeZ) {
        SavedPlacement placement = placements.get(key);
        if (placement == null
                || (placement.sizeX() == sizeX && placement.sizeY() == sizeY && placement.sizeZ() == sizeZ)) {
            return;
        }

        placements.put(key, copyPlacement(
                placement,
                placement.lootContainers(),
                placement.lootContainersScanned(),
                placement.lastRestockTime(),
                placement.nextRestockTime(),
                Math.max(0, sizeX),
                Math.max(0, sizeY),
                Math.max(0, sizeZ),
                placement.retired(),
                placement.playerTouched(),
                placement.nextMinorDespawnTime(),
                placement.cleanupBlocks(),
                placement.restoreBlocks()
        ));
        markDirty();
    }

    public void scheduleMinorDespawn(String key, long nextMinorDespawnTime) {
        SavedPlacement placement = placements.get(key);
        if (placement == null || placement.nextMinorDespawnTime() == nextMinorDespawnTime) {
            return;
        }

        placements.put(key, copyPlacement(
                placement,
                placement.lootContainers(),
                placement.lootContainersScanned(),
                placement.lastRestockTime(),
                placement.nextRestockTime(),
                placement.sizeX(),
                placement.sizeY(),
                placement.sizeZ(),
                placement.retired(),
                placement.playerTouched(),
                nextMinorDespawnTime,
                placement.cleanupBlocks(),
                placement.restoreBlocks()
        ));
        markDirty();
    }

    public void markPlayerTouched(String key) {
        SavedPlacement placement = placements.get(key);
        if (placement == null || placement.playerTouched()) {
            return;
        }

        placements.put(key, copyPlacement(
                placement,
                placement.lootContainers(),
                placement.lootContainersScanned(),
                placement.lastRestockTime(),
                placement.nextRestockTime(),
                placement.sizeX(),
                placement.sizeY(),
                placement.sizeZ(),
                placement.retired(),
                true,
                placement.nextMinorDespawnTime(),
                placement.cleanupBlocks(),
                placement.restoreBlocks()
        ));
        markDirty();
    }

    public void retirePlacement(String key, long retiredTime) {
        SavedPlacement placement = placements.get(key);
        if (placement == null || placement.retired()) {
            return;
        }

        placements.put(key, copyPlacement(
                placement,
                placement.lootContainers(),
                placement.lootContainersScanned(),
                placement.lastRestockTime(),
                placement.nextRestockTime(),
                placement.sizeX(),
                placement.sizeY(),
                placement.sizeZ(),
                true,
                placement.playerTouched(),
                retiredTime,
                placement.cleanupBlocks(),
                placement.restoreBlocks()
        ));
        markDirty();
    }

    public long getPortalEventEndTime() {
        return portalEventEndTime;
    }

    public boolean isPortalEventActive(long worldTime) {
        return portalEventEndTime > worldTime;
    }

    public void startPortalEvent(long endTime) {
        portalEventEndTime = endTime;
        portalEventWarnings.clear();
        markDirty();
    }

    public boolean markPortalEventWarningSent(int secondsRemaining) {
        boolean added = portalEventWarnings.add(secondsRemaining);
        if (added) {
            markDirty();
        }

        return added;
    }

    public void clearPortalEvent() {
        if (portalEventEndTime == 0L && portalEventWarnings.isEmpty()) {
            return;
        }

        portalEventEndTime = 0L;
        portalEventWarnings.clear();
        markDirty();
    }

    private NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        NbtCompound placementRoot = new NbtCompound();
        NbtCompound reservationRoot = new NbtCompound();
        NbtCompound failedRoot = new NbtCompound();
        NbtCompound portalWarningRoot = new NbtCompound();

        for (Map.Entry<String, SavedPlacement> entry : placements.entrySet()) {
            SavedPlacement placement = entry.getValue();
            NbtCompound placementNbt = new NbtCompound();
            placementNbt.putInt(CENTER_X_KEY, placement.centerX());
            placementNbt.putInt(CENTER_Z_KEY, placement.centerZ());
            placementNbt.putInt(ORIGIN_X_KEY, placement.originX());
            placementNbt.putInt(ORIGIN_Y_KEY, placement.originY());
            placementNbt.putInt(ORIGIN_Z_KEY, placement.originZ());
            placementNbt.putLong(PLACED_TIME_KEY, placement.placedTime());
            placementNbt.putBoolean(LOOT_CONTAINERS_SCANNED_KEY, placement.lootContainersScanned());
            placementNbt.putLong(LAST_RESTOCK_TIME_KEY, placement.lastRestockTime());
            placementNbt.putLong(NEXT_RESTOCK_TIME_KEY, placement.nextRestockTime());
            placementNbt.putInt(SIZE_X_KEY, placement.sizeX());
            placementNbt.putInt(SIZE_Y_KEY, placement.sizeY());
            placementNbt.putInt(SIZE_Z_KEY, placement.sizeZ());
            placementNbt.putBoolean(RETIRED_KEY, placement.retired());
            placementNbt.putBoolean(PLAYER_TOUCHED_KEY, placement.playerTouched());
            placementNbt.putLong(NEXT_MINOR_DESPAWN_TIME_KEY, placement.nextMinorDespawnTime());

            NbtList cleanupBlockList = new NbtList();
            for (BlockPos cleanupBlock : placement.cleanupBlocks()) {
                NbtCompound cleanupBlockNbt = new NbtCompound();
                cleanupBlockNbt.putInt(X_KEY, cleanupBlock.getX());
                cleanupBlockNbt.putInt(Y_KEY, cleanupBlock.getY());
                cleanupBlockNbt.putInt(Z_KEY, cleanupBlock.getZ());
                cleanupBlockList.add(cleanupBlockNbt);
            }
            placementNbt.put(CLEANUP_BLOCKS_KEY, cleanupBlockList);

            NbtList restoreBlockList = new NbtList();
            for (SavedBlockSnapshot restoreBlock : placement.restoreBlocks()) {
                NbtCompound restoreBlockNbt = new NbtCompound();
                restoreBlockNbt.putInt(X_KEY, restoreBlock.pos().getX());
                restoreBlockNbt.putInt(Y_KEY, restoreBlock.pos().getY());
                restoreBlockNbt.putInt(Z_KEY, restoreBlock.pos().getZ());
                restoreBlockNbt.put(BLOCK_STATE_KEY, restoreBlock.stateNbt().copy());
                restoreBlockList.add(restoreBlockNbt);
            }
            placementNbt.put(RESTORE_BLOCKS_KEY, restoreBlockList);

            NbtList lootContainerList = new NbtList();
            for (SavedLootContainer lootContainer : placement.lootContainers()) {
                NbtCompound lootContainerNbt = new NbtCompound();
                lootContainerNbt.putInt(X_KEY, lootContainer.pos().getX());
                lootContainerNbt.putInt(Y_KEY, lootContainer.pos().getY());
                lootContainerNbt.putInt(Z_KEY, lootContainer.pos().getZ());
                lootContainerNbt.putString(LOOT_TABLE_KEY, lootContainer.lootTableId().toString());
                lootContainerNbt.putLong(LOOT_TABLE_SEED_KEY, lootContainer.lootTableSeed());
                lootContainerList.add(lootContainerNbt);
            }
            placementNbt.put(LOOT_CONTAINERS_KEY, lootContainerList);
            placementRoot.put(entry.getKey(), placementNbt);
        }

        for (Map.Entry<String, ReservedPlacement> entry : reservations.entrySet()) {
            ReservedPlacement reservation = entry.getValue();
            NbtCompound reservationNbt = new NbtCompound();
            reservationNbt.putString(STRUCTURE_KEY, reservation.structureKey());
            reservationNbt.putInt(CANDIDATE_INDEX_KEY, reservation.candidateIndex());
            reservationNbt.putInt(X_KEY, reservation.x());
            reservationNbt.putInt(Z_KEY, reservation.z());
            reservationRoot.put(entry.getKey(), reservationNbt);
        }

        for (Map.Entry<String, Set<Integer>> entry : failedCandidates.entrySet()) {
            NbtCompound failedNbt = new NbtCompound();
            for (int candidateIndex : entry.getValue()) {
                failedNbt.putBoolean(Integer.toString(candidateIndex), true);
            }

            failedRoot.put(entry.getKey(), failedNbt);
        }

        root.put(PLACEMENTS_KEY, placementRoot);
        root.put(RESERVATIONS_KEY, reservationRoot);
        root.put(FAILED_CANDIDATES_KEY, failedRoot);
        root.putLong(PORTAL_EVENT_END_TIME_KEY, portalEventEndTime);
        for (int warning : portalEventWarnings) {
            portalWarningRoot.putBoolean(Integer.toString(warning), true);
        }
        root.put(PORTAL_EVENT_WARNINGS_KEY, portalWarningRoot);
        return root;
    }

    private static BreachedStructurePlacementState fromNbt(NbtCompound root) {
        BreachedStructurePlacementState state = new BreachedStructurePlacementState();
        state.portalEventEndTime = root.getLong(PORTAL_EVENT_END_TIME_KEY, 0L);
        Optional<NbtCompound> placementRoot = root.getCompound(PLACEMENTS_KEY);
        if (placementRoot.isPresent()) {
            for (String key : placementRoot.get().getKeys()) {
                Optional<NbtCompound> placementNbt = placementRoot.get().getCompound(key);
                if (placementNbt.isEmpty()) {
                    continue;
                }

                long placedTime = placementNbt.get().getLong(PLACED_TIME_KEY, 0L);
                long lastRestockTime = placementNbt.get().getLong(LAST_RESTOCK_TIME_KEY, placedTime);
                state.placements.put(key, new SavedPlacement(
                        placementNbt.get().getInt(CENTER_X_KEY, 0),
                        placementNbt.get().getInt(CENTER_Z_KEY, 0),
                        placementNbt.get().getInt(ORIGIN_X_KEY, 0),
                        placementNbt.get().getInt(ORIGIN_Y_KEY, 0),
                        placementNbt.get().getInt(ORIGIN_Z_KEY, 0),
                        placedTime,
                        readLootContainers(placementNbt.get()),
                        placementNbt.get().getBoolean(LOOT_CONTAINERS_SCANNED_KEY, false),
                        lastRestockTime,
                        placementNbt.get().getLong(NEXT_RESTOCK_TIME_KEY, lastRestockTime),
                        placementNbt.get().getInt(SIZE_X_KEY, 0),
                        placementNbt.get().getInt(SIZE_Y_KEY, 0),
                        placementNbt.get().getInt(SIZE_Z_KEY, 0),
                        placementNbt.get().getBoolean(RETIRED_KEY, false),
                        placementNbt.get().getBoolean(PLAYER_TOUCHED_KEY, false),
                        placementNbt.get().getLong(NEXT_MINOR_DESPAWN_TIME_KEY, 0L),
                        readCleanupBlocks(placementNbt.get()),
                        readRestoreBlocks(placementNbt.get())
                ));
            }
        }

        Optional<NbtCompound> reservationRoot = root.getCompound(RESERVATIONS_KEY);
        if (reservationRoot.isPresent()) {
            for (String key : reservationRoot.get().getKeys()) {
                Optional<NbtCompound> reservationNbt = reservationRoot.get().getCompound(key);
                if (reservationNbt.isEmpty()) {
                    continue;
                }

                state.reservations.put(key, new ReservedPlacement(
                        reservationNbt.get().getString(STRUCTURE_KEY, ""),
                        reservationNbt.get().getInt(CANDIDATE_INDEX_KEY, 0),
                        reservationNbt.get().getInt(X_KEY, 0),
                        reservationNbt.get().getInt(Z_KEY, 0)
                ));
            }
        }

        Optional<NbtCompound> failedRoot = root.getCompound(FAILED_CANDIDATES_KEY);
        if (failedRoot.isPresent()) {
            for (String key : failedRoot.get().getKeys()) {
                Optional<NbtCompound> failedNbt = failedRoot.get().getCompound(key);
                if (failedNbt.isEmpty()) {
                    continue;
                }

                Set<Integer> failedIndexes = new HashSet<>();
                for (String candidateIndex : failedNbt.get().getKeys()) {
                    try {
                        failedIndexes.add(Integer.parseInt(candidateIndex));
                    } catch (NumberFormatException ignored) {
                    }
                }

                state.failedCandidates.put(key, failedIndexes);
            }
        }

        Optional<NbtCompound> portalWarningRoot = root.getCompound(PORTAL_EVENT_WARNINGS_KEY);
        if (portalWarningRoot.isPresent()) {
            for (String warning : portalWarningRoot.get().getKeys()) {
                try {
                    state.portalEventWarnings.add(Integer.parseInt(warning));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return state;
    }

    private static List<SavedLootContainer> readLootContainers(NbtCompound placementNbt) {
        List<SavedLootContainer> lootContainers = new ArrayList<>();
        NbtList lootContainerList = placementNbt.getListOrEmpty(LOOT_CONTAINERS_KEY);
        for (int index = 0; index < lootContainerList.size(); index++) {
            NbtCompound lootContainerNbt = lootContainerList.getCompoundOrEmpty(index);
            Optional<String> lootTableValue = lootContainerNbt.getString(LOOT_TABLE_KEY);
            if (lootTableValue.isEmpty()) {
                continue;
            }

            Identifier lootTableId = Identifier.tryParse(lootTableValue.get());
            if (lootTableId == null) {
                continue;
            }

            lootContainers.add(new SavedLootContainer(
                    new BlockPos(
                            lootContainerNbt.getInt(X_KEY, 0),
                            lootContainerNbt.getInt(Y_KEY, 0),
                            lootContainerNbt.getInt(Z_KEY, 0)
                    ),
                    lootTableId,
                    lootContainerNbt.getLong(LOOT_TABLE_SEED_KEY, 0L)
            ));
        }

        return lootContainers;
    }

    private static List<BlockPos> readCleanupBlocks(NbtCompound placementNbt) {
        List<BlockPos> cleanupBlocks = new ArrayList<>();
        NbtList cleanupBlockList = placementNbt.getListOrEmpty(CLEANUP_BLOCKS_KEY);
        for (int index = 0; index < cleanupBlockList.size(); index++) {
            NbtCompound cleanupBlockNbt = cleanupBlockList.getCompoundOrEmpty(index);
            cleanupBlocks.add(new BlockPos(
                    cleanupBlockNbt.getInt(X_KEY, 0),
                    cleanupBlockNbt.getInt(Y_KEY, 0),
                    cleanupBlockNbt.getInt(Z_KEY, 0)
            ));
        }

        return cleanupBlocks;
    }

    private static List<SavedBlockSnapshot> readRestoreBlocks(NbtCompound placementNbt) {
        List<SavedBlockSnapshot> restoreBlocks = new ArrayList<>();
        NbtList restoreBlockList = placementNbt.getListOrEmpty(RESTORE_BLOCKS_KEY);
        for (int index = 0; index < restoreBlockList.size(); index++) {
            NbtCompound restoreBlockNbt = restoreBlockList.getCompoundOrEmpty(index);
            Optional<NbtCompound> stateNbt = restoreBlockNbt.getCompound(BLOCK_STATE_KEY);
            if (stateNbt.isEmpty()) {
                continue;
            }

            restoreBlocks.add(new SavedBlockSnapshot(
                    new BlockPos(
                            restoreBlockNbt.getInt(X_KEY, 0),
                            restoreBlockNbt.getInt(Y_KEY, 0),
                            restoreBlockNbt.getInt(Z_KEY, 0)
                    ),
                    stateNbt.get()
            ));
        }

        return restoreBlocks;
    }

    public record SavedPlacement(
            int centerX,
            int centerZ,
            int originX,
            int originY,
            int originZ,
            long placedTime,
            List<SavedLootContainer> lootContainers,
            boolean lootContainersScanned,
            long lastRestockTime,
            long nextRestockTime,
            int sizeX,
            int sizeY,
            int sizeZ,
            boolean retired,
            boolean playerTouched,
            long nextMinorDespawnTime,
            List<BlockPos> cleanupBlocks,
            List<SavedBlockSnapshot> restoreBlocks
    ) {
        public SavedPlacement {
            lootContainers = List.copyOf(lootContainers);
            cleanupBlocks = List.copyOf(cleanupBlocks);
            restoreBlocks = List.copyOf(restoreBlocks);
            sizeX = Math.max(0, sizeX);
            sizeY = Math.max(0, sizeY);
            sizeZ = Math.max(0, sizeZ);
        }

        public boolean active() {
            return !retired;
        }
    }

    public record SavedLootContainer(BlockPos pos, Identifier lootTableId, long lootTableSeed) {
    }

    public record SavedBlockSnapshot(BlockPos pos, NbtCompound stateNbt) {
        public SavedBlockSnapshot {
            stateNbt = stateNbt.copy();
        }
    }

    public record ReservedPlacement(String structureKey, int candidateIndex, int x, int z) {
    }

    private static SavedPlacement copyPlacement(
            SavedPlacement placement,
            List<SavedLootContainer> lootContainers,
            boolean lootContainersScanned,
            long lastRestockTime,
            long nextRestockTime,
            int sizeX,
            int sizeY,
            int sizeZ,
            boolean retired,
            boolean playerTouched,
            long nextMinorDespawnTime,
            List<BlockPos> cleanupBlocks,
            List<SavedBlockSnapshot> restoreBlocks
    ) {
        return new SavedPlacement(
                placement.centerX(),
                placement.centerZ(),
                placement.originX(),
                placement.originY(),
                placement.originZ(),
                placement.placedTime(),
                lootContainers,
                lootContainersScanned,
                lastRestockTime,
                nextRestockTime,
                sizeX,
                sizeY,
                sizeZ,
                retired,
                playerTouched,
                nextMinorDespawnTime,
                cleanupBlocks,
                restoreBlocks
        );
    }
}
