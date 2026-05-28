package nrd.breached.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import nrd.breached.Breached;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class LandlockBlockEntity extends BlockEntity {
    private static final String OWNER_UUID_KEY = "owner_uuid";
    private static final String AUTHORIZED_PLAYERS_KEY = "authorized_players";
    private static final String CLAIM_CENTER_X_KEY = "claim_center_x";
    private static final String CLAIM_CENTER_Y_KEY = "claim_center_y";
    private static final String CLAIM_CENTER_Z_KEY = "claim_center_z";

    private UUID ownerUuid;
    private BlockPos claimCenter;
    private final Set<UUID> authorizedPlayers = new HashSet<>();

    public LandlockBlockEntity(BlockPos pos, BlockState state) {
        super(Breached.LANDLOCK_BLOCK_ENTITY, pos, state);
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
        authorizedPlayers.add(ownerUuid);
        markDirty();
    }

    public boolean isAuthorized(UUID playerUuid) {
        return playerUuid.equals(ownerUuid) || authorizedPlayers.contains(playerUuid);
    }

    public void addAuthorizedPlayer(UUID playerUuid) {
        authorizedPlayers.add(playerUuid);
        markDirty();
    }

    public boolean removeAuthorizedPlayer(UUID playerUuid) {
        if (playerUuid.equals(ownerUuid)) {
            return false;
        }

        boolean removed = authorizedPlayers.remove(playerUuid);
        if (removed) {
            markDirty();
        }

        return removed;
    }

    public Set<UUID> getAuthorizedPlayers() {
        return authorizedPlayers;
    }

    public BlockPos getClaimCenter() {
        return claimCenter == null ? getPos() : claimCenter;
    }

    public void setClaimCenter(BlockPos claimCenter) {
        this.claimCenter = claimCenter.toImmutable();
        markDirty();
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        ownerUuid = view.getOptionalString(OWNER_UUID_KEY)
                .map(LandlockBlockEntity::parseUuid)
                .orElse(null);
        claimCenter = new BlockPos(
                view.getInt(CLAIM_CENTER_X_KEY, getPos().getX()),
                view.getInt(CLAIM_CENTER_Y_KEY, getPos().getY()),
                view.getInt(CLAIM_CENTER_Z_KEY, getPos().getZ())
        );
        authorizedPlayers.clear();
        authorizedPlayers.addAll(view.read(AUTHORIZED_PLAYERS_KEY, Uuids.SET_CODEC).orElse(Set.of()));
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);

        if (ownerUuid != null) {
            view.putString(OWNER_UUID_KEY, ownerUuid.toString());
        }

        view.put(AUTHORIZED_PLAYERS_KEY, Uuids.SET_CODEC, authorizedPlayers);

        BlockPos center = getClaimCenter();
        view.putInt(CLAIM_CENTER_X_KEY, center.getX());
        view.putInt(CLAIM_CENTER_Y_KEY, center.getY());
        view.putInt(CLAIM_CENTER_Z_KEY, center.getZ());
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
