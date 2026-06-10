package nrd.breached.storage;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.CrafterBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.StorageMinecartEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import nrd.breached.landlock.LandlockClaimManager;

public final class TemporaryStorageManager {
    private static final long DESPAWN_DELAY_TICKS = 20L * 60L * 60L;
    private static final int CHECK_INTERVAL_TICKS = 20 * 10;
    private static final int MAX_REMOVALS_PER_WORLD_CHECK = 128;
    private static final Text WILDERNESS_STORAGE_MESSAGE = Text.literal(
            "Storage and automation placed outside an authorized Landlock claim despawn with their contents after 1 hour."
    );

    private TemporaryStorageManager() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TemporaryStorageManager::tick);
    }

    public static void onBlockItemPlaced(ItemPlacementContext context, ActionResult result) {
        if (!result.isAccepted() || !(context.getWorld() instanceof ServerWorld world)) {
            return;
        }

        PlayerEntity player = context.getPlayer();
        if (player == null) {
            return;
        }

        BlockPos pos = context.getBlockPos().toImmutable();
        BlockState state = world.getBlockState(pos);
        if (!isTrackedStorageBlock(state) || isInsideAuthorizedClaim(world, player, pos)) {
            return;
        }

        long expiresAt = world.getTime() + DESPAWN_DELAY_TICKS;
        TemporaryStorageState temporaryStorageState = TemporaryStorageState.get(world.getServer());
        trackStorageBlock(world, temporaryStorageState, player, pos, state, expiresAt);
        player.sendMessage(WILDERNESS_STORAGE_MESSAGE, false);
    }

    public static void onMinecartItemUsed(ItemUsageContext context, ActionResult result, EntityType<?> minecartType) {
        if (!result.isAccepted()
                || !isTrackedStorageMinecartType(minecartType)
                || !(context.getWorld() instanceof ServerWorld world)) {
            return;
        }

        PlayerEntity player = context.getPlayer();
        if (player == null || isInsideAuthorizedClaim(world, player, context.getBlockPos())) {
            return;
        }

        TemporaryStorageState temporaryStorageState = TemporaryStorageState.get(world.getServer());
        StorageMinecartEntity minecart = findPlacedStorageMinecart(world, temporaryStorageState, context.getBlockPos(), minecartType);
        if (minecart == null) {
            return;
        }

        temporaryStorageState.trackEntity(
                world.getRegistryKey(),
                minecart.getUuid(),
                world.getTime() + DESPAWN_DELAY_TICKS
        );
        player.sendMessage(WILDERNESS_STORAGE_MESSAGE, false);
    }

    public static void untrackStorageMinecart(StorageMinecartEntity minecart) {
        if (minecart.getEntityWorld() instanceof ServerWorld world) {
            TemporaryStorageState.get(world.getServer()).removeEntity(minecart.getUuid());
        }
    }

    private static void tick(MinecraftServer server) {
        if (server.getTicks() % CHECK_INTERVAL_TICKS != 0) {
            return;
        }

        TemporaryStorageState state = TemporaryStorageState.get(server);
        for (ServerWorld world : server.getWorlds()) {
            int removals = 0;
            for (TemporaryStorageState.Entry entry : state.getExpired(world.getRegistryKey(), world.getTime())) {
                if (tryRemoveExpiredStorage(world, state, entry.pos())
                        && ++removals >= MAX_REMOVALS_PER_WORLD_CHECK) {
                    break;
                }
            }

            removals = 0;
            for (TemporaryStorageState.EntityEntry entry : state.getExpiredEntities(world.getRegistryKey(), world.getTime())) {
                if (tryRemoveExpiredStorageMinecart(world, state, entry.entityUuid())
                        && ++removals >= MAX_REMOVALS_PER_WORLD_CHECK) {
                    break;
                }
            }
        }
    }

    private static void trackStorageBlock(
            ServerWorld world,
            TemporaryStorageState temporaryStorageState,
            PlayerEntity player,
            BlockPos pos,
            BlockState state,
            long expiresAt
    ) {
        temporaryStorageState.track(world.getRegistryKey(), pos, expiresAt);

        BlockPos connectedChestPos = getConnectedDoubleChestPos(world, pos, state);
        if (connectedChestPos != null && !isInsideAuthorizedClaim(world, player, connectedChestPos)) {
            temporaryStorageState.trackIfEarlier(world.getRegistryKey(), connectedChestPos, expiresAt);
        }
    }

    private static boolean tryRemoveExpiredStorage(ServerWorld world, TemporaryStorageState temporaryStorageState, BlockPos pos) {
        if (!world.isPosLoaded(pos)) {
            return false;
        }

        BlockState state = world.getBlockState(pos);
        if (!isTrackedStorageBlock(state)) {
            temporaryStorageState.remove(world.getRegistryKey(), pos);
            return true;
        }

        BlockPos connectedChestPos = getConnectedDoubleChestPos(world, pos, state);
        removeStorageBlock(world, pos);
        temporaryStorageState.remove(world.getRegistryKey(), pos);

        if (connectedChestPos != null && world.isPosLoaded(connectedChestPos)) {
            BlockState connectedState = world.getBlockState(connectedChestPos);
            if (isTrackedStorageBlock(connectedState)) {
                removeStorageBlock(world, connectedChestPos);
            }
            temporaryStorageState.remove(world.getRegistryKey(), connectedChestPos);
        }

        return true;
    }

    private static boolean tryRemoveExpiredStorageMinecart(
            ServerWorld world,
            TemporaryStorageState temporaryStorageState,
            java.util.UUID entityUuid
    ) {
        Entity entity = world.getEntity(entityUuid);
        if (entity == null) {
            return false;
        }

        if (!(entity instanceof StorageMinecartEntity minecart) || !isTrackedStorageMinecartType(entity.getType())) {
            temporaryStorageState.removeEntity(entityUuid);
            return true;
        }

        minecart.clear();
        minecart.discard();
        temporaryStorageState.removeEntity(entityUuid);
        return true;
    }

    private static void removeStorageBlock(ServerWorld world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof Inventory inventory) {
            inventory.clear();
            inventory.markDirty();
        }

        world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
    }

    private static boolean isInsideAuthorizedClaim(World world, PlayerEntity player, BlockPos pos) {
        return LandlockClaimManager.isInsideAnyClaim(world, pos)
                && LandlockClaimManager.canPlayerModify(world, player, pos);
    }

    private static boolean isTrackedStorageBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof ChestBlock
                || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof HopperBlock
                || block instanceof CrafterBlock;
    }

    private static BlockPos getConnectedDoubleChestPos(ServerWorld world, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock)
                || !state.contains(ChestBlock.CHEST_TYPE)
                || state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
            return null;
        }

        BlockPos connectedPos = ChestBlock.getPosInFrontOf(pos, state).toImmutable();
        BlockState connectedState = world.getBlockState(connectedPos);
        if (!(connectedState.getBlock() instanceof ChestBlock)
                || !connectedState.contains(ChestBlock.CHEST_TYPE)
                || connectedState.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
            return null;
        }

        return connectedPos;
    }

    private static boolean isTrackedStorageMinecartType(EntityType<?> entityType) {
        return entityType == EntityType.CHEST_MINECART || entityType == EntityType.HOPPER_MINECART;
    }

    private static StorageMinecartEntity findPlacedStorageMinecart(
            ServerWorld world,
            TemporaryStorageState temporaryStorageState,
            BlockPos railPos,
            EntityType<?> minecartType
    ) {
        Box searchBox = new Box(railPos).expand(1.25D);
        Vec3d center = railPos.toCenterPos();
        StorageMinecartEntity fallback = null;
        double fallbackDistance = Double.MAX_VALUE;

        for (StorageMinecartEntity minecart : world.getEntitiesByType(
                TypeFilter.instanceOf(StorageMinecartEntity.class),
                searchBox,
                minecart -> minecart.getType() == minecartType && !minecart.isRemoved()
        )) {
            double distance = minecart.squaredDistanceTo(center);
            if (minecart.age <= 1) {
                return minecart;
            }

            if (!temporaryStorageState.isTrackedEntity(minecart.getUuid()) && distance < fallbackDistance) {
                fallback = minecart;
                fallbackDistance = distance;
            }
        }

        return fallback;
    }
}
