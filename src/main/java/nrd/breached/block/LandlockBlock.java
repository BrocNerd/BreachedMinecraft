package nrd.breached.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import nrd.breached.Breached;
import nrd.breached.landlock.LandlockClaimManager;
import nrd.breached.landlock.LandlockMapState;

import java.util.UUID;

public class LandlockBlock extends BlockWithEntity {
    public static final MapCodec<LandlockBlock> CODEC = createCodec(LandlockBlock::new);

    public LandlockBlock(AbstractBlock.Settings settings) {
        super(settings);
    }

    @Override
    protected MapCodec<? extends LandlockBlock> getCodec() {
        return CODEC;
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new LandlockBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return validateTicker(type, Breached.LANDLOCK_BLOCK_ENTITY, LandlockBlockEntity::tick);
    }

    @Override
    protected BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);

        if (!world.isClient() && placer instanceof PlayerEntity player && world.getBlockEntity(pos) instanceof LandlockBlockEntity landlock) {
            landlock.setOwner(player);
            updateLandlockMapState(world, landlock);
        }
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (player.isSneaking() && isClaimOutlineProbe(player.getMainHandStack())) {
            return ActionResult.PASS;
        }

        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }

        if (!(world.getBlockEntity(pos) instanceof LandlockBlockEntity landlock)) {
            player.sendMessage(Text.literal("This Landlock has no owner."), false);
            return ActionResult.SUCCESS;
        }

        UUID ownerUuid = landlock.getOwnerUuid();
        if (ownerUuid == null) {
            player.sendMessage(Text.literal("This Landlock has no owner."), false);
        } else if (player.isSneaking()) {
            handleSneakUse(world, player, landlock, ownerUuid);
        } else if (ownerUuid.equals(player.getUuid())) {
            landlock.updateOwnerName(player);
            openUpkeepInventory(player, landlock);
        } else if (landlock.isAuthorized(player.getUuid())) {
            openUpkeepInventory(player, landlock);
        } else {
            player.sendMessage(Text.literal("Sneak right-click this Landlock to authorize yourself."), false);
        }

        return ActionResult.SUCCESS;
    }

    @Override
    protected void onStateReplaced(BlockState state, ServerWorld world, BlockPos pos, boolean moved) {
        if (!moved && world.getBlockEntity(pos) instanceof LandlockBlockEntity landlock) {
            ItemScatterer.spawn(world, pos, landlock);
        }

        super.onStateReplaced(state, world, pos, moved);
    }

    private static void openUpkeepInventory(PlayerEntity player, LandlockBlockEntity landlock) {
        player.openHandledScreen(landlock);
    }

    private static void handleSneakUse(World world, PlayerEntity player, LandlockBlockEntity landlock, UUID ownerUuid) {
        if (ownerUuid.equals(player.getUuid())) {
            landlock.updateOwnerName(player);
            player.sendMessage(Text.literal("You own this Landlock. Break it to remove it from your authorization count."), false);
        } else if (landlock.removeAuthorizedPlayer(player.getUuid())) {
            updateLandlockMapState(world, landlock);
            player.sendMessage(Text.literal("You are no longer authorized on this Landlock."), false);
        } else if (LandlockClaimManager.countPlayerLandlockAuthorizations(world, player.getUuid()) < LandlockClaimManager.MAX_AUTHORIZED_LANDLOCKS) {
            landlock.addAuthorizedPlayer(player.getUuid());
            updateLandlockMapState(world, landlock);
            player.sendMessage(Text.literal("You are now authorized on this Landlock."), false);
        } else {
            player.sendMessage(Text.literal("You are already authorized on the maximum number of Landlocks."), false);
        }
    }

    private static boolean isClaimOutlineProbe(ItemStack stack) {
        return stack.isOf(Breached.PROBE) || stack.isOf(Breached.DIAMOND_PROBE);
    }

    private static void updateLandlockMapState(World world, LandlockBlockEntity landlock) {
        if (world instanceof ServerWorld serverWorld) {
            LandlockMapState.update(serverWorld, landlock);
        }
    }
}
