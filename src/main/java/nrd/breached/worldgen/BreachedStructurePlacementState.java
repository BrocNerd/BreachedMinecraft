package nrd.breached.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class BreachedStructurePlacementState extends PersistentState {
    private static final String PLACEMENTS_KEY = "placements";
    private static final String FAILED_CANDIDATES_KEY = "failed_candidates";
    private static final String CENTER_X_KEY = "center_x";
    private static final String CENTER_Z_KEY = "center_z";
    private static final String ORIGIN_X_KEY = "origin_x";
    private static final String ORIGIN_Y_KEY = "origin_y";
    private static final String ORIGIN_Z_KEY = "origin_z";
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
    private final Map<String, Set<Integer>> failedCandidates = new HashMap<>();

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
        placements.put(key, new SavedPlacement(
                BreachedStructureSpawnManager.getProtectedCenterX(placement),
                BreachedStructureSpawnManager.getProtectedCenterZ(placement),
                placement.origin().getX(),
                placement.origin().getY(),
                placement.origin().getZ()
        ));
        markDirty();
    }

    public void markPlaced(String key, int centerX, int centerZ) {
        placements.put(key, new SavedPlacement(centerX, centerZ, centerX, 0, centerZ));
        markDirty();
    }

    private NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        NbtCompound placementRoot = new NbtCompound();
        NbtCompound failedRoot = new NbtCompound();

        for (Map.Entry<String, SavedPlacement> entry : placements.entrySet()) {
            SavedPlacement placement = entry.getValue();
            NbtCompound placementNbt = new NbtCompound();
            placementNbt.putInt(CENTER_X_KEY, placement.centerX());
            placementNbt.putInt(CENTER_Z_KEY, placement.centerZ());
            placementNbt.putInt(ORIGIN_X_KEY, placement.originX());
            placementNbt.putInt(ORIGIN_Y_KEY, placement.originY());
            placementNbt.putInt(ORIGIN_Z_KEY, placement.originZ());
            placementRoot.put(entry.getKey(), placementNbt);
        }

        for (Map.Entry<String, Set<Integer>> entry : failedCandidates.entrySet()) {
            NbtCompound failedNbt = new NbtCompound();
            for (int candidateIndex : entry.getValue()) {
                failedNbt.putBoolean(Integer.toString(candidateIndex), true);
            }

            failedRoot.put(entry.getKey(), failedNbt);
        }

        root.put(PLACEMENTS_KEY, placementRoot);
        root.put(FAILED_CANDIDATES_KEY, failedRoot);
        return root;
    }

    private static BreachedStructurePlacementState fromNbt(NbtCompound root) {
        BreachedStructurePlacementState state = new BreachedStructurePlacementState();
        Optional<NbtCompound> placementRoot = root.getCompound(PLACEMENTS_KEY);
        if (placementRoot.isPresent()) {
            for (String key : placementRoot.get().getKeys()) {
                Optional<NbtCompound> placementNbt = placementRoot.get().getCompound(key);
                if (placementNbt.isEmpty()) {
                    continue;
                }

                state.placements.put(key, new SavedPlacement(
                        placementNbt.get().getInt(CENTER_X_KEY, 0),
                        placementNbt.get().getInt(CENTER_Z_KEY, 0),
                        placementNbt.get().getInt(ORIGIN_X_KEY, 0),
                        placementNbt.get().getInt(ORIGIN_Y_KEY, 0),
                        placementNbt.get().getInt(ORIGIN_Z_KEY, 0)
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

        return state;
    }

    public record SavedPlacement(int centerX, int centerZ, int originX, int originY, int originZ) {
    }
}
