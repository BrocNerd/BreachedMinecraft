package nrd.breached.landlock;

import com.mojang.serialization.Codec;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;
import net.minecraft.world.World;
import nrd.breached.block.LandlockBlockEntity;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LandlockMapState extends PersistentState {
    private static final String LANDLOCKS_KEY = "landlocks";
    private static final String OWNER_UUID_KEY = "owner_uuid";
    private static final String POS_X_KEY = "pos_x";
    private static final String POS_Y_KEY = "pos_y";
    private static final String POS_Z_KEY = "pos_z";
    private static final String CLAIM_CENTER_X_KEY = "claim_center_x";
    private static final String CLAIM_CENTER_Y_KEY = "claim_center_y";
    private static final String CLAIM_CENTER_Z_KEY = "claim_center_z";
    private static final String AUTHORIZED_PLAYERS_KEY = "authorized_players";
    private static final Codec<LandlockMapState> CODEC = NbtCompound.CODEC.xmap(LandlockMapState::fromNbt, LandlockMapState::toNbt);
    private static final PersistentStateType<LandlockMapState> TYPE = new PersistentStateType<>(
            "breached_landlock_map",
            LandlockMapState::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final Map<Long, Entry> landlocksByPos = new HashMap<>();

    public static LandlockMapState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
    }

    public static void update(ServerWorld world, LandlockBlockEntity landlock) {
        if (!world.getRegistryKey().equals(World.OVERWORLD) || landlock.getOwnerUuid() == null) {
            return;
        }

        get(world.getServer()).setLandlock(
                landlock.getPos().toImmutable(),
                landlock.getOwnerUuid(),
                landlock.getClaimCenter().toImmutable(),
                landlock.getAuthorizedPlayers()
        );
    }

    public static void remove(ServerWorld world, BlockPos pos) {
        if (!world.getRegistryKey().equals(World.OVERWORLD)) {
            return;
        }

        get(world.getServer()).removeLandlock(pos);
    }

    public void backfillLoadedLandlocks(ServerWorld world) {
        if (!world.getRegistryKey().equals(World.OVERWORLD)) {
            return;
        }

        LandlockClaimManager.forEachLoadedLandlock(world, (pos, landlock) -> {
            if (landlock.getOwnerUuid() != null) {
                setLandlock(pos.toImmutable(), landlock.getOwnerUuid(), landlock.getClaimCenter().toImmutable(), landlock.getAuthorizedPlayers());
            }
        });
    }

    public List<Entry> getAuthorizedLandlocks(UUID playerUuid) {
        return landlocksByPos.values()
                .stream()
                .filter(entry -> entry.isAuthorized(playerUuid))
                .sorted(Comparator
                        .comparingInt((Entry entry) -> entry.pos().getX())
                        .thenComparingInt(entry -> entry.pos().getZ())
                        .thenComparingInt(entry -> entry.pos().getY()))
                .toList();
    }

    private void setLandlock(BlockPos pos, UUID ownerUuid, BlockPos claimCenter, Set<UUID> authorizedPlayers) {
        Set<UUID> playerIds = new HashSet<>(authorizedPlayers);
        playerIds.add(ownerUuid);
        Entry entry = new Entry(pos.toImmutable(), ownerUuid, claimCenter.toImmutable(), playerIds);
        Entry previous = landlocksByPos.put(pos.asLong(), entry);
        if (!entry.equals(previous)) {
            markDirty();
        }
    }

    private void removeLandlock(BlockPos pos) {
        if (landlocksByPos.remove(pos.asLong()) != null) {
            markDirty();
        }
    }

    private NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        NbtList landlocks = new NbtList();

        for (Entry entry : landlocksByPos.values()) {
            NbtCompound landlockNbt = new NbtCompound();
            landlockNbt.putString(OWNER_UUID_KEY, entry.ownerUuid().toString());
            landlockNbt.putInt(POS_X_KEY, entry.pos().getX());
            landlockNbt.putInt(POS_Y_KEY, entry.pos().getY());
            landlockNbt.putInt(POS_Z_KEY, entry.pos().getZ());
            landlockNbt.putInt(CLAIM_CENTER_X_KEY, entry.claimCenter().getX());
            landlockNbt.putInt(CLAIM_CENTER_Y_KEY, entry.claimCenter().getY());
            landlockNbt.putInt(CLAIM_CENTER_Z_KEY, entry.claimCenter().getZ());
            NbtList authorizedPlayers = new NbtList();
            for (UUID authorizedPlayer : entry.authorizedPlayers()) {
                authorizedPlayers.add(NbtString.of(authorizedPlayer.toString()));
            }
            landlockNbt.put(AUTHORIZED_PLAYERS_KEY, authorizedPlayers);
            landlocks.add(landlockNbt);
        }

        root.put(LANDLOCKS_KEY, landlocks);
        return root;
    }

    private static LandlockMapState fromNbt(NbtCompound root) {
        LandlockMapState state = new LandlockMapState();
        NbtList landlocks = root.getListOrEmpty(LANDLOCKS_KEY);

        for (int index = 0; index < landlocks.size(); index++) {
            NbtCompound landlockNbt = landlocks.getCompoundOrEmpty(index);
            UUID ownerUuid = parseUuid(landlockNbt.getString(OWNER_UUID_KEY, ""));
            if (ownerUuid == null) {
                continue;
            }

            BlockPos pos = new BlockPos(
                    landlockNbt.getInt(POS_X_KEY, 0),
                    landlockNbt.getInt(POS_Y_KEY, 0),
                    landlockNbt.getInt(POS_Z_KEY, 0)
            );
            BlockPos claimCenter = new BlockPos(
                    landlockNbt.getInt(CLAIM_CENTER_X_KEY, pos.getX()),
                    landlockNbt.getInt(CLAIM_CENTER_Y_KEY, pos.getY()),
                    landlockNbt.getInt(CLAIM_CENTER_Z_KEY, pos.getZ())
            );
            Set<UUID> authorizedPlayers = new HashSet<>();
            authorizedPlayers.add(ownerUuid);
            NbtList authorizedPlayersNbt = landlockNbt.getListOrEmpty(AUTHORIZED_PLAYERS_KEY);
            for (int playerIndex = 0; playerIndex < authorizedPlayersNbt.size(); playerIndex++) {
                UUID authorizedPlayerUuid = parseUuid(authorizedPlayersNbt.getString(playerIndex, ""));
                if (authorizedPlayerUuid != null) {
                    authorizedPlayers.add(authorizedPlayerUuid);
                }
            }

            state.landlocksByPos.put(pos.asLong(), new Entry(pos, ownerUuid, claimCenter, authorizedPlayers));
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

    public record Entry(BlockPos pos, UUID ownerUuid, BlockPos claimCenter, Set<UUID> authorizedPlayers) {
        public Entry {
            pos = pos.toImmutable();
            claimCenter = claimCenter.toImmutable();
            authorizedPlayers = Set.copyOf(authorizedPlayers);
        }

        private boolean isAuthorized(UUID playerUuid) {
            return ownerUuid.equals(playerUuid) || authorizedPlayers.contains(playerUuid);
        }
    }
}
