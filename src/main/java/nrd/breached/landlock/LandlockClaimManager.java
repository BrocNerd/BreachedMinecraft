package nrd.breached.landlock;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import nrd.breached.block.LandlockBlockEntity;

import java.util.UUID;

public final class LandlockClaimManager {
    public static final int CLAIM_RADIUS = 16;
    public static final int REQUIRED_CLAIM_GAP = 16;
    public static final int MIN_LANDLOCK_CENTER_SPACING = CLAIM_RADIUS + REQUIRED_CLAIM_GAP + CLAIM_RADIUS;
    public static final int MAX_AUTHORIZED_LANDLOCKS = 3;

    private LandlockClaimManager() {
    }

    public static boolean isInsideAnyClaim(World world, BlockPos pos) {
        return findClaimingLandlock(world, pos) != null;
    }

    public static boolean canPlayerModify(World world, PlayerEntity player, BlockPos targetPos) {
        LandlockBlockEntity landlock = findClaimingLandlock(world, targetPos);

        if (landlock == null) {
            return true;
        }

        return landlock.isAuthorized(player.getUuid());
    }

    public static int countPlayerLandlockAuthorizations(World world, UUID playerUuid) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return 0;
        }

        LandlockCounter counter = new LandlockCounter(playerUuid);
        serverWorld.getChunkManager().chunkLoadingManager.forEachChunk(counter::countInChunk);
        return counter.count;
    }

    public static boolean isTooCloseToExistingLandlock(World world, BlockPos newLandlockPos) {
        BlockPos.Mutable checkPos = new BlockPos.Mutable();
        int minY = world.getBottomY();
        int maxY = minY + world.getHeight();

        for (int x = newLandlockPos.getX() - MIN_LANDLOCK_CENTER_SPACING; x <= newLandlockPos.getX() + MIN_LANDLOCK_CENTER_SPACING; x++) {
            for (int z = newLandlockPos.getZ() - MIN_LANDLOCK_CENTER_SPACING; z <= newLandlockPos.getZ() + MIN_LANDLOCK_CENTER_SPACING; z++) {
                for (int y = minY; y < maxY; y++) {
                    checkPos.set(x, y, z);

                    if (world.getBlockEntity(checkPos) instanceof LandlockBlockEntity && isTooCloseToLandlock(checkPos, newLandlockPos)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static LandlockBlockEntity findClaimingLandlock(World world, BlockPos pos) {
        BlockPos.Mutable checkPos = new BlockPos.Mutable();
        int minY = world.getBottomY();
        int maxY = minY + world.getHeight();

        for (int x = pos.getX() - CLAIM_RADIUS; x <= pos.getX() + CLAIM_RADIUS; x++) {
            for (int z = pos.getZ() - CLAIM_RADIUS; z <= pos.getZ() + CLAIM_RADIUS; z++) {
                for (int y = minY; y < maxY; y++) {
                    checkPos.set(x, y, z);

                    if (world.getBlockEntity(checkPos) instanceof LandlockBlockEntity landlock && isInsideClaim(checkPos, pos)) {
                        return landlock;
                    }
                }
            }
        }

        return null;
    }

    public static boolean isInsideClaim(BlockPos landlockPos, BlockPos pos) {
        return Math.abs(pos.getX() - landlockPos.getX()) <= CLAIM_RADIUS
                && Math.abs(pos.getZ() - landlockPos.getZ()) <= CLAIM_RADIUS;
    }

    private static boolean isTooCloseToLandlock(BlockPos existingLandlockPos, BlockPos newLandlockPos) {
        return Math.abs(existingLandlockPos.getX() - newLandlockPos.getX()) <= MIN_LANDLOCK_CENTER_SPACING
                && Math.abs(existingLandlockPos.getZ() - newLandlockPos.getZ()) <= MIN_LANDLOCK_CENTER_SPACING;
    }

    private static final class LandlockCounter {
        private final UUID playerUuid;
        private int count;

        private LandlockCounter(UUID playerUuid) {
            this.playerUuid = playerUuid;
        }

        private void countInChunk(WorldChunk chunk) {
            for (var blockEntity : chunk.getBlockEntities().values()) {
                if (blockEntity instanceof LandlockBlockEntity landlock && landlock.isAuthorized(playerUuid)) {
                    count++;
                }
            }
        }
    }
}
