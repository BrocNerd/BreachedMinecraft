package nrd.breached.reinforcement;

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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ReinforcementState extends PersistentState {
    private static final String REINFORCEMENTS_KEY = "reinforcements";
    private static final String DIMENSION_KEY = "dimension";
    private static final String X_KEY = "x";
    private static final String Y_KEY = "y";
    private static final String Z_KEY = "z";
    private static final String TIER_KEY = "tier";
    private static final Codec<ReinforcementState> CODEC = NbtCompound.CODEC.xmap(
            ReinforcementState::fromNbt,
            ReinforcementState::toNbt
    );
    private static final PersistentStateType<ReinforcementState> TYPE = new PersistentStateType<>(
            "breached_reinforcements",
            ReinforcementState::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final Map<ReinforcedBlockKey, ReinforcementTier> reinforcements = new HashMap<>();

    public static ReinforcementState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
    }

    public Optional<ReinforcementTier> getTier(RegistryKey<World> dimension, BlockPos pos) {
        return Optional.ofNullable(reinforcements.get(new ReinforcedBlockKey(dimension, pos.toImmutable())));
    }

    public Map<BlockPos, ReinforcementTier> getTiersWithin(RegistryKey<World> dimension, BlockPos center, int radius) {
        Map<BlockPos, ReinforcementTier> nearbyReinforcements = new HashMap<>();
        for (Map.Entry<ReinforcedBlockKey, ReinforcementTier> entry : reinforcements.entrySet()) {
            ReinforcedBlockKey key = entry.getKey();
            if (key.dimension().equals(dimension) && isWithinRadius(key.pos(), center, radius)) {
                nearbyReinforcements.put(key.pos(), entry.getValue());
            }
        }

        return nearbyReinforcements;
    }

    public void setTier(RegistryKey<World> dimension, BlockPos pos, ReinforcementTier tier) {
        reinforcements.put(new ReinforcedBlockKey(dimension, pos.toImmutable()), tier);
        markDirty();
    }

    public void remove(RegistryKey<World> dimension, BlockPos pos) {
        if (reinforcements.remove(new ReinforcedBlockKey(dimension, pos.toImmutable())) != null) {
            markDirty();
        }
    }

    private NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        NbtList reinforcementList = new NbtList();

        for (Map.Entry<ReinforcedBlockKey, ReinforcementTier> entry : reinforcements.entrySet()) {
            NbtCompound reinforcementNbt = new NbtCompound();
            reinforcementNbt.putString(DIMENSION_KEY, entry.getKey().dimension().getValue().toString());
            reinforcementNbt.putInt(X_KEY, entry.getKey().pos().getX());
            reinforcementNbt.putInt(Y_KEY, entry.getKey().pos().getY());
            reinforcementNbt.putInt(Z_KEY, entry.getKey().pos().getZ());
            reinforcementNbt.putString(TIER_KEY, entry.getValue().serializedName());
            reinforcementList.add(reinforcementNbt);
        }

        root.put(REINFORCEMENTS_KEY, reinforcementList);
        return root;
    }

    private static ReinforcementState fromNbt(NbtCompound root) {
        ReinforcementState state = new ReinforcementState();
        NbtList reinforcementList = root.getListOrEmpty(REINFORCEMENTS_KEY);

        for (int index = 0; index < reinforcementList.size(); index++) {
            NbtCompound reinforcementNbt = reinforcementList.getCompoundOrEmpty(index);
            Optional<String> dimensionValue = reinforcementNbt.getString(DIMENSION_KEY);
            Optional<String> tierValue = reinforcementNbt.getString(TIER_KEY);
            if (dimensionValue.isEmpty() || tierValue.isEmpty()) {
                continue;
            }

            Identifier dimensionId = Identifier.tryParse(dimensionValue.get());
            if (dimensionId == null) {
                continue;
            }

            Optional<ReinforcementTier> tier = ReinforcementTier.fromSerializedName(tierValue.get());
            if (tier.isEmpty()) {
                continue;
            }

            RegistryKey<World> dimension = RegistryKey.of(RegistryKeys.WORLD, dimensionId);
            BlockPos pos = new BlockPos(
                    reinforcementNbt.getInt(X_KEY, 0),
                    reinforcementNbt.getInt(Y_KEY, 0),
                    reinforcementNbt.getInt(Z_KEY, 0)
            );
            state.reinforcements.put(new ReinforcedBlockKey(dimension, pos), tier.get());
        }

        return state;
    }

    private static boolean isWithinRadius(BlockPos pos, BlockPos center, int radius) {
        int dx = pos.getX() - center.getX();
        int dy = pos.getY() - center.getY();
        int dz = pos.getZ() - center.getZ();
        return Math.abs(dx) <= radius
                && Math.abs(dy) <= radius
                && Math.abs(dz) <= radius
                && dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private record ReinforcedBlockKey(RegistryKey<World> dimension, BlockPos pos) {
    }
}
