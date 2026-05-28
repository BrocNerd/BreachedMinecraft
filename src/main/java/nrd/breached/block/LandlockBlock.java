package nrd.breached.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import nrd.breached.landlock.LandlockClaimManager;

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
    protected BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack) {
        super.onPlaced(world, pos, state, placer, itemStack);

        if (!world.isClient() && placer instanceof PlayerEntity player && world.getBlockEntity(pos) instanceof LandlockBlockEntity landlock) {
            landlock.setOwnerUuid(player.getUuid());
        }
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
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
            handleSneakUse(player, landlock, ownerUuid);
        } else if (ownerUuid.equals(player.getUuid())) {
            player.sendMessage(Text.literal("You own this Landlock."), false);
        } else if (landlock.isAuthorized(player.getUuid())) {
            player.sendMessage(Text.literal("You are authorized on this Landlock."), false);
        } else if (LandlockClaimManager.countPlayerLandlockAuthorizations(world, player.getUuid()) < LandlockClaimManager.MAX_AUTHORIZED_LANDLOCKS) {
            landlock.addAuthorizedPlayer(player.getUuid());
            player.sendMessage(Text.literal("You are now authorized on this Landlock."), false);
        } else {
            player.sendMessage(Text.literal("You are already authorized on the maximum number of Landlocks."), false);
        }

        player.sendMessage(Text.literal("Claim size: " + LandlockClaimManager.CLAIM_SIZE + "x" + LandlockClaimManager.CLAIM_SIZE + "x" + LandlockClaimManager.CLAIM_SIZE + " blocks."), false);
        return ActionResult.SUCCESS;
    }

    private static void handleSneakUse(PlayerEntity player, LandlockBlockEntity landlock, UUID ownerUuid) {
        if (ownerUuid.equals(player.getUuid())) {
            player.sendMessage(Text.literal("You own this Landlock. Break it to remove it from your authorization count."), false);
        } else if (landlock.removeAuthorizedPlayer(player.getUuid())) {
            player.sendMessage(Text.literal("You are no longer authorized on this Landlock."), false);
        } else {
            player.sendMessage(Text.literal("You are not authorized on this Landlock."), false);
        }
    }
}
