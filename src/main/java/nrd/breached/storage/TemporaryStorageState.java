package nrd.breached.storage;

import com.mojang.serialization.Codec;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class TemporaryStorageState extends PersistentState {
    private static final String CHESTS_KEY = "chests";
    private static final String STORAGE_ENTITIES_KEY = "storage_entities";
    private static final String DIMENSION_KEY = "dimension";
    private static final String ENTITY_UUID_KEY = "entity_uuid";
    private static final String X_KEY = "x";
    private static final String Y_KEY = "y";
    private static final String Z_KEY = "z";
    private static final String EXPIRES_AT_KEY = "expires_at";
    private static final Codec<TemporaryStorageState> CODEC = NbtCompound.CODEC.xmap(
            TemporaryStorageState::fromNbt,
            TemporaryStorageState::toNbt
    );
    private static final PersistentStateType<TemporaryStorageState> TYPE = new PersistentStateType<>(
            "breached_temporary_chests",
            TemporaryStorageState::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final Map<TrackedStorageKey, Long> trackedStorageBlocks = new HashMap<>();
    private final Map<UUID, TrackedStorageEntity> trackedStorageEntities = new HashMap<>();

    public static TemporaryStorageState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
    }

    public void track(RegistryKey<World> dimension, BlockPos pos, long expiresAt) {
        TrackedStorageKey key = new TrackedStorageKey(dimension, pos.toImmutable());
        Long previousExpiresAt = trackedStorageBlocks.put(key, expiresAt);
        if (previousExpiresAt == null || previousExpiresAt != expiresAt) {
            markDirty();
        }
    }

    public void trackIfEarlier(RegistryKey<World> dimension, BlockPos pos, long expiresAt) {
        TrackedStorageKey key = new TrackedStorageKey(dimension, pos.toImmutable());
        Long previousExpiresAt = trackedStorageBlocks.get(key);
        if (previousExpiresAt == null || expiresAt < previousExpiresAt) {
            trackedStorageBlocks.put(key, expiresAt);
            markDirty();
        }
    }

    public void remove(RegistryKey<World> dimension, BlockPos pos) {
        if (trackedStorageBlocks.remove(new TrackedStorageKey(dimension, pos.toImmutable())) != null) {
            markDirty();
        }
    }

    public void trackEntity(RegistryKey<World> dimension, UUID entityUuid, long expiresAt) {
        TrackedStorageEntity entry = new TrackedStorageEntity(dimension, entityUuid, expiresAt);
        TrackedStorageEntity previous = trackedStorageEntities.put(entityUuid, entry);
        if (!entry.equals(previous)) {
            markDirty();
        }
    }

    public boolean isTrackedEntity(UUID entityUuid) {
        return trackedStorageEntities.containsKey(entityUuid);
    }

    public void removeEntity(UUID entityUuid) {
        if (trackedStorageEntities.remove(entityUuid) != null) {
            markDirty();
        }
    }

    public List<Entry> getExpired(RegistryKey<World> dimension, long worldTime) {
        ArrayList<Entry> expired = new ArrayList<>();
        for (Map.Entry<TrackedStorageKey, Long> entry : trackedStorageBlocks.entrySet()) {
            TrackedStorageKey key = entry.getKey();
            if (key.dimension().equals(dimension) && entry.getValue() <= worldTime) {
                expired.add(new Entry(key.dimension(), key.pos(), entry.getValue()));
            }
        }

        return expired;
    }

    public List<EntityEntry> getExpiredEntities(RegistryKey<World> dimension, long worldTime) {
        ArrayList<EntityEntry> expired = new ArrayList<>();
        for (TrackedStorageEntity entry : trackedStorageEntities.values()) {
            if (entry.dimension().equals(dimension) && entry.expiresAt() <= worldTime) {
                expired.add(new EntityEntry(entry.dimension(), entry.entityUuid(), entry.expiresAt()));
            }
        }

        return expired;
    }

    private NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        NbtList chestList = new NbtList();

        for (Map.Entry<TrackedStorageKey, Long> entry : trackedStorageBlocks.entrySet()) {
            NbtCompound chestNbt = new NbtCompound();
            TrackedStorageKey key = entry.getKey();
            chestNbt.putString(DIMENSION_KEY, key.dimension().getValue().toString());
            chestNbt.putInt(X_KEY, key.pos().getX());
            chestNbt.putInt(Y_KEY, key.pos().getY());
            chestNbt.putInt(Z_KEY, key.pos().getZ());
            chestNbt.putLong(EXPIRES_AT_KEY, entry.getValue());
            chestList.add(chestNbt);
        }

        root.put(CHESTS_KEY, chestList);

        NbtList storageEntityList = new NbtList();
        for (TrackedStorageEntity entry : trackedStorageEntities.values()) {
            NbtCompound storageEntityNbt = new NbtCompound();
            storageEntityNbt.putString(DIMENSION_KEY, entry.dimension().getValue().toString());
            storageEntityNbt.putString(ENTITY_UUID_KEY, entry.entityUuid().toString());
            storageEntityNbt.putLong(EXPIRES_AT_KEY, entry.expiresAt());
            storageEntityList.add(storageEntityNbt);
        }

        root.put(STORAGE_ENTITIES_KEY, storageEntityList);
        return root;
    }

    private static TemporaryStorageState fromNbt(NbtCompound root) {
        TemporaryStorageState state = new TemporaryStorageState();
        NbtList chestList = root.getListOrEmpty(CHESTS_KEY);

        for (int index = 0; index < chestList.size(); index++) {
            NbtCompound chestNbt = chestList.getCompoundOrEmpty(index);
            Optional<String> dimensionValue = chestNbt.getString(DIMENSION_KEY);
            if (dimensionValue.isEmpty()) {
                continue;
            }

            Identifier dimensionId = Identifier.tryParse(dimensionValue.get());
            if (dimensionId == null) {
                continue;
            }

            RegistryKey<World> dimension = RegistryKey.of(RegistryKeys.WORLD, dimensionId);
            BlockPos pos = new BlockPos(
                    chestNbt.getInt(X_KEY, 0),
                    chestNbt.getInt(Y_KEY, 0),
                    chestNbt.getInt(Z_KEY, 0)
            );
            state.trackedStorageBlocks.put(
                    new TrackedStorageKey(dimension, pos),
                    chestNbt.getLong(EXPIRES_AT_KEY, 0L)
            );
        }

        NbtList storageEntityList = root.getListOrEmpty(STORAGE_ENTITIES_KEY);
        for (int index = 0; index < storageEntityList.size(); index++) {
            NbtCompound storageEntityNbt = storageEntityList.getCompoundOrEmpty(index);
            Optional<String> dimensionValue = storageEntityNbt.getString(DIMENSION_KEY);
            Optional<String> entityUuidValue = storageEntityNbt.getString(ENTITY_UUID_KEY);
            if (dimensionValue.isEmpty() || entityUuidValue.isEmpty()) {
                continue;
            }

            Identifier dimensionId = Identifier.tryParse(dimensionValue.get());
            UUID entityUuid = parseUuid(entityUuidValue.get());
            if (dimensionId == null || entityUuid == null) {
                continue;
            }

            RegistryKey<World> dimension = RegistryKey.of(RegistryKeys.WORLD, dimensionId);
            state.trackedStorageEntities.put(
                    entityUuid,
                    new TrackedStorageEntity(dimension, entityUuid, storageEntityNbt.getLong(EXPIRES_AT_KEY, 0L))
            );
        }

        return state;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record TrackedStorageKey(RegistryKey<World> dimension, BlockPos pos) {
        private TrackedStorageKey {
            pos = pos.toImmutable();
        }
    }

    public record Entry(RegistryKey<World> dimension, BlockPos pos, long expiresAt) {
        public Entry {
            pos = pos.toImmutable();
        }
    }

    private record TrackedStorageEntity(RegistryKey<World> dimension, UUID entityUuid, long expiresAt) {
    }

    public record EntityEntry(RegistryKey<World> dimension, UUID entityUuid, long expiresAt) {
    }
}
