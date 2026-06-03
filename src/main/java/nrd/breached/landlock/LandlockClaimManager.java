package nrd.breached.landlock;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import nrd.breached.block.LandlockBlockEntity;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

public final class LandlockClaimManager {
    public static final int CLAIM_SIZE = 17;
    private static final int CLAIM_NEGATIVE_RANGE = CLAIM_SIZE / 2;
    private static final int CLAIM_POSITIVE_RANGE = CLAIM_SIZE - CLAIM_NEGATIVE_RANGE - 1;
    private static final int CLAIMING_LANDLOCK_SEARCH_RADIUS = CLAIM_NEGATIVE_RANGE + CLAIM_POSITIVE_RANGE;
    public static final int MIN_LANDLOCK_CENTER_SPACING = 32;
    public static final int MAX_AUTHORIZED_LANDLOCKS = 3;
    public static final int MIN_LANDLOCK_PLACEMENT_Y = 60;

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

    public static Optional<ClaimAccess> getClaimAccess(World world, BlockPos targetPos, UUID playerUuid) {
        LandlockBlockEntity landlock = findClaimingLandlock(world, targetPos);
        if (landlock == null) {
            return Optional.empty();
        }

        boolean decayed = landlock.isDecayed();
        return Optional.of(new ClaimAccess(
                landlock.getClaimCenter(),
                landlock.isAuthorized(playerUuid),
                !decayed && isLockdownActive(world, landlock),
                decayed
        ));
    }

    public static boolean isLockdownActive(World world, LandlockBlockEntity landlock) {
        if (landlock.isDecayed()) {
            return false;
        }

        if (!(world instanceof ServerWorld serverWorld)) {
            return false;
        }

        for (var player : serverWorld.getServer().getPlayerManager().getPlayerList()) {
            if (landlock.isAuthorized(player.getUuid())) {
                return false;
            }
        }

        return true;
    }

    public static boolean isClaimDecayed(World world, BlockPos targetPos) {
        LandlockBlockEntity landlock = findClaimingLandlock(world, targetPos);
        return landlock != null && landlock.isDecayed();
    }

    public static void refreshClaimCost(World world, BlockPos targetPos) {
        LandlockBlockEntity landlock = findClaimingLandlock(world, targetPos);
        if (landlock != null) {
            landlock.refreshClaimCost();
        }
    }

    public static int countPlayerLandlockAuthorizations(World world, UUID playerUuid) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return 0;
        }

        LandlockCounter counter = new LandlockCounter(playerUuid);
        visitLoadedLandlocks(serverWorld, counter::count);
        return counter.count;
    }

    public static boolean isTooCloseToExistingLandlock(World world, BlockPos newLandlockPos) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return false;
        }

        return visitLoadedLandlocks(serverWorld, (landlockPos, landlock) -> isTooCloseToLandlock(landlockPos, newLandlockPos));
    }

    public static void forEachLoadedLandlockWithin(World world, BlockPos center, int radius, BiConsumer<BlockPos, LandlockBlockEntity> consumer) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }

        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                WorldChunk chunk = serverWorld.getChunkManager().getWorldChunk(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }

                for (var blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof LandlockBlockEntity landlock && isWithinRadius(landlock.getPos(), center, radius)) {
                        consumer.accept(landlock.getPos(), landlock);
                    }
                }
            }
        }
    }

    public static void forEachLoadedLandlock(World world, BiConsumer<BlockPos, LandlockBlockEntity> consumer) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }

        visitLoadedLandlocks(serverWorld, (landlockPos, landlock) -> {
            consumer.accept(landlockPos, landlock);
            return false;
        });
    }

    private static LandlockBlockEntity findClaimingLandlock(World world, BlockPos pos) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return null;
        }

        int minChunkX = (pos.getX() - CLAIMING_LANDLOCK_SEARCH_RADIUS) >> 4;
        int maxChunkX = (pos.getX() + CLAIMING_LANDLOCK_SEARCH_RADIUS) >> 4;
        int minChunkZ = (pos.getZ() - CLAIMING_LANDLOCK_SEARCH_RADIUS) >> 4;
        int maxChunkZ = (pos.getZ() + CLAIMING_LANDLOCK_SEARCH_RADIUS) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                WorldChunk chunk = serverWorld.getChunkManager().getWorldChunk(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }

                for (var blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof LandlockBlockEntity landlock
                            && isPotentialClaimingLandlock(landlock.getPos(), pos)
                            && isInsideClaim(landlock.getClaimCenter(), pos)) {
                        return landlock;
                    }
                }
            }
        }

        return null;
    }

    public static boolean isInsideClaim(BlockPos landlockPos, BlockPos pos) {
        return isInsideClaimAxis(landlockPos.getX(), pos.getX())
                && isInsideClaimAxis(landlockPos.getY(), pos.getY())
                && isInsideClaimAxis(landlockPos.getZ(), pos.getZ());
    }

    public static boolean canSetClaimCenter(LandlockBlockEntity landlock, BlockPos newClaimCenter) {
        return isInsideClaim(landlock.getClaimCenter(), newClaimCenter)
                && isInsideClaim(newClaimCenter, landlock.getPos());
    }

    private static boolean isInsideClaimAxis(int landlockCoordinate, int targetCoordinate) {
        int offset = targetCoordinate - landlockCoordinate;
        return offset >= -CLAIM_NEGATIVE_RANGE && offset <= CLAIM_POSITIVE_RANGE;
    }

    private static boolean isTooCloseToLandlock(BlockPos existingLandlockPos, BlockPos newLandlockPos) {
        return Math.abs(existingLandlockPos.getX() - newLandlockPos.getX()) <= MIN_LANDLOCK_CENTER_SPACING
                && Math.abs(existingLandlockPos.getZ() - newLandlockPos.getZ()) <= MIN_LANDLOCK_CENTER_SPACING;
    }

    private static boolean isPotentialClaimingLandlock(BlockPos landlockPos, BlockPos targetPos) {
        return Math.abs(landlockPos.getX() - targetPos.getX()) <= CLAIMING_LANDLOCK_SEARCH_RADIUS
                && Math.abs(landlockPos.getY() - targetPos.getY()) <= CLAIMING_LANDLOCK_SEARCH_RADIUS
                && Math.abs(landlockPos.getZ() - targetPos.getZ()) <= CLAIMING_LANDLOCK_SEARCH_RADIUS;
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

    private static boolean visitLoadedLandlocks(ServerWorld world, LandlockVisitor visitor) {
        boolean[] stopped = {false};
        world.getChunkManager().chunkLoadingManager.forEachChunk(chunk -> {
            if (stopped[0]) {
                return;
            }

            for (var blockEntity : chunk.getBlockEntities().values()) {
                if (blockEntity instanceof LandlockBlockEntity landlock && visitor.visit(landlock.getPos(), landlock)) {
                    stopped[0] = true;
                    return;
                }
            }
        });

        return stopped[0];
    }

    private interface LandlockVisitor {
        boolean visit(BlockPos landlockPos, LandlockBlockEntity landlock);
    }

    public record ClaimAccess(BlockPos claimCenter, boolean authorized, boolean lockdown, boolean decayed) {
        public ClaimAccess {
            claimCenter = claimCenter.toImmutable();
        }
    }

    private static final class LandlockCounter {
        private final UUID playerUuid;
        private int count;

        private LandlockCounter(UUID playerUuid) {
            this.playerUuid = playerUuid;
        }

        private boolean count(BlockPos landlockPos, LandlockBlockEntity landlock) {
            if (landlock.isAuthorized(playerUuid)) {
                count++;
            }

            return false;
        }
    }
}
