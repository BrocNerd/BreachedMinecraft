package nrd.breached.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

public class CentralSpawnPoiState extends PersistentState {
    private static final String PLACED_KEY = "placed";
    private static final String CENTER_X_KEY = "center_x";
    private static final String CENTER_Z_KEY = "center_z";
    private static final Codec<CentralSpawnPoiState> CODEC = NbtCompound.CODEC.xmap(CentralSpawnPoiState::fromNbt, CentralSpawnPoiState::toNbt);
    private static final PersistentStateType<CentralSpawnPoiState> TYPE = new PersistentStateType<>(
            "breached_central_spawn_poi",
            CentralSpawnPoiState::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private boolean placed;
    private int centerX;
    private int centerZ;

    public static CentralSpawnPoiState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
    }

    public boolean isPlaced() {
        return placed;
    }

    public int getCenterX() {
        return centerX;
    }

    public int getCenterZ() {
        return centerZ;
    }

    public void markPlaced(int centerX, int centerZ) {
        placed = true;
        this.centerX = centerX;
        this.centerZ = centerZ;
        markDirty();
    }

    private NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        root.putBoolean(PLACED_KEY, placed);
        root.putInt(CENTER_X_KEY, centerX);
        root.putInt(CENTER_Z_KEY, centerZ);
        return root;
    }

    private static CentralSpawnPoiState fromNbt(NbtCompound root) {
        CentralSpawnPoiState state = new CentralSpawnPoiState();
        state.placed = root.getBoolean(PLACED_KEY, false);
        state.centerX = root.getInt(CENTER_X_KEY, 0);
        state.centerZ = root.getInt(CENTER_Z_KEY, 0);
        return state;
    }
}
