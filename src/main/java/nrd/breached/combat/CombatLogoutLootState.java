package nrd.breached.combat;

import com.mojang.serialization.Codec;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CombatLogoutLootState extends PersistentState {
    private static final String ENTRIES_KEY = "entries";
    private static final String PENDING_DEATHS_KEY = "pending_deaths";
    private static final String PLAYER_UUID_KEY = "player_uuid";
    private static final String ENTITY_UUID_KEY = "entity_uuid";
    private static final String PLAYER_NAME_KEY = "player_name";
    private static final String WORLD_KEY = "world";
    private static final String STACKS_KEY = "stacks";
    private static final String SLOT_KEY = "slot";
    private static final String STACK_KEY = "stack";
    private static final Codec<CombatLogoutLootState> CODEC = NbtCompound.CODEC.xmap(CombatLogoutLootState::fromNbt, CombatLogoutLootState::toNbt);
    private static final PersistentStateType<CombatLogoutLootState> TYPE = new PersistentStateType<>(
            "breached_combat_logout_loot",
            CombatLogoutLootState::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final Map<UUID, Entry> entriesByPlayer = new HashMap<>();
    private final Set<UUID> pendingDeaths = new HashSet<>();

    public static CombatLogoutLootState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
    }

    public static NbtCompound encodeStack(MinecraftServer server, ItemStack stack) {
        RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, server.getRegistryManager());
        NbtElement stackNbt = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
        return stackNbt instanceof NbtCompound compound ? compound.copy() : null;
    }

    public static ItemStack decodeStack(MinecraftServer server, NbtCompound stackNbt) {
        RegistryOps<NbtElement> ops = RegistryOps.of(NbtOps.INSTANCE, server.getRegistryManager());
        return ItemStack.CODEC.parse(ops, stackNbt).result().orElse(ItemStack.EMPTY).copy();
    }

    public void put(Entry entry) {
        entriesByPlayer.put(entry.playerUuid(), entry);
        markDirty();
    }

    public Entry getByPlayer(UUID playerUuid) {
        return entriesByPlayer.get(playerUuid);
    }

    public Entry removeByPlayer(UUID playerUuid) {
        Entry removed = entriesByPlayer.remove(playerUuid);
        if (removed != null) {
            markDirty();
        }
        return removed;
    }

    public Entry removeByEntity(UUID entityUuid) {
        UUID playerUuid = null;
        for (Entry entry : entriesByPlayer.values()) {
            if (entry.entityUuid().equals(entityUuid)) {
                playerUuid = entry.playerUuid();
                break;
            }
        }

        return playerUuid == null ? null : removeByPlayer(playerUuid);
    }

    public boolean hasPendingDeath(UUID playerUuid) {
        return pendingDeaths.contains(playerUuid);
    }

    public void addPendingDeath(UUID playerUuid) {
        if (pendingDeaths.add(playerUuid)) {
            markDirty();
        }
    }

    public void clearPendingDeath(UUID playerUuid) {
        if (pendingDeaths.remove(playerUuid)) {
            markDirty();
        }
    }

    public Set<UUID> pendingDeaths() {
        return Set.copyOf(pendingDeaths);
    }

    private NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        NbtList entries = new NbtList();
        for (Entry entry : entriesByPlayer.values()) {
            NbtCompound entryNbt = new NbtCompound();
            entryNbt.putString(PLAYER_UUID_KEY, entry.playerUuid().toString());
            entryNbt.putString(ENTITY_UUID_KEY, entry.entityUuid().toString());
            entryNbt.putString(PLAYER_NAME_KEY, entry.playerName());
            entryNbt.putString(WORLD_KEY, entry.worldKey().getValue().toString());

            NbtList stacks = new NbtList();
            for (StoredStack stack : entry.stacks()) {
                NbtCompound stackNbt = new NbtCompound();
                stackNbt.putInt(SLOT_KEY, stack.slot());
                stackNbt.put(STACK_KEY, stack.stackNbt().copy());
                stacks.add(stackNbt);
            }
            entryNbt.put(STACKS_KEY, stacks);
            entries.add(entryNbt);
        }
        root.put(ENTRIES_KEY, entries);

        NbtList pendingDeathList = new NbtList();
        for (UUID playerUuid : pendingDeaths) {
            pendingDeathList.add(NbtString.of(playerUuid.toString()));
        }
        root.put(PENDING_DEATHS_KEY, pendingDeathList);
        return root;
    }

    private static CombatLogoutLootState fromNbt(NbtCompound root) {
        CombatLogoutLootState state = new CombatLogoutLootState();
        NbtList entries = root.getListOrEmpty(ENTRIES_KEY);
        for (int index = 0; index < entries.size(); index++) {
            NbtCompound entryNbt = entries.getCompoundOrEmpty(index);
            UUID playerUuid = parseUuid(entryNbt.getString(PLAYER_UUID_KEY, ""));
            UUID entityUuid = parseUuid(entryNbt.getString(ENTITY_UUID_KEY, ""));
            RegistryKey<World> worldKey = parseWorldKey(entryNbt.getString(WORLD_KEY, World.OVERWORLD.getValue().toString()));
            if (playerUuid == null || entityUuid == null || worldKey == null) {
                continue;
            }

            NbtList stacksNbt = entryNbt.getListOrEmpty(STACKS_KEY);
            ArrayList<StoredStack> stacks = new ArrayList<>(stacksNbt.size());
            for (int stackIndex = 0; stackIndex < stacksNbt.size(); stackIndex++) {
                NbtCompound stackNbt = stacksNbt.getCompoundOrEmpty(stackIndex);
                NbtCompound itemStackNbt = stackNbt.getCompound(STACK_KEY).map(NbtCompound::copy).orElse(null);
                if (itemStackNbt != null) {
                    stacks.add(new StoredStack(stackNbt.getInt(SLOT_KEY, 0), itemStackNbt));
                }
            }

            state.entriesByPlayer.put(playerUuid, new Entry(
                    playerUuid,
                    entityUuid,
                    entryNbt.getString(PLAYER_NAME_KEY, "A player"),
                    worldKey,
                    stacks
            ));
        }

        NbtList pendingDeathsNbt = root.getListOrEmpty(PENDING_DEATHS_KEY);
        for (int index = 0; index < pendingDeathsNbt.size(); index++) {
            UUID playerUuid = parseUuid(pendingDeathsNbt.getString(index, ""));
            if (playerUuid != null) {
                state.pendingDeaths.add(playerUuid);
            }
        }

        return state;
    }

    private static RegistryKey<World> parseWorldKey(String value) {
        try {
            return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public record Entry(UUID playerUuid, UUID entityUuid, String playerName, RegistryKey<World> worldKey, List<StoredStack> stacks) {
        public Entry {
            stacks = List.copyOf(stacks);
        }
    }

    public record StoredStack(int slot, NbtCompound stackNbt) {
        public StoredStack {
            stackNbt = stackNbt.copy();
        }
    }
}
