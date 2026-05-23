package nrd.breached.item;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class BreacherItem extends Item {
    public static final float BLOCK_BREAKING_DELTA = 1.0F / 300.0F;
    private static final float MINING_SPEED = 1.0F;

    public BreacherItem(Settings settings) {
        super(settings);
    }

    @Override
    public float getMiningSpeed(ItemStack stack, BlockState state) {
        return MINING_SPEED;
    }

    @Override
    public boolean isCorrectForDrops(ItemStack stack, BlockState state) {
        return true;
    }

    @Override
    public boolean canMine(ItemStack stack, BlockState state, World world, BlockPos pos, LivingEntity miner) {
        return !isBlockedBlock(state);
    }

    @Override
    public boolean postMine(ItemStack stack, World world, BlockState state, BlockPos pos, LivingEntity miner) {
        if (world instanceof ServerWorld serverWorld && miner instanceof ServerPlayerEntity player && !isBlockedBlock(state)) {
            stack.damage(1, serverWorld, player, item -> player.sendEquipmentBreakStatus(item, EquipmentSlot.MAINHAND));
        }

        return true;
    }

    public static boolean isBlockedBlock(BlockState state) {
        return state.isOf(Blocks.BEDROCK)
                || state.isOf(Blocks.COMMAND_BLOCK)
                || state.isOf(Blocks.CHAIN_COMMAND_BLOCK)
                || state.isOf(Blocks.REPEATING_COMMAND_BLOCK)
                || state.isOf(Blocks.STRUCTURE_BLOCK)
                || state.isOf(Blocks.JIGSAW)
                || state.isOf(Blocks.BARRIER)
                || state.isOf(Blocks.END_PORTAL_FRAME);
    }
}
