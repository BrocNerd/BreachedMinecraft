package nrd.breached.item;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ToolMaterial;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import nrd.breached.breach.BreachNotificationManager;
import nrd.breached.reinforcement.ReinforcementManager;

public class BreacherItem extends Item {
    public static final float IRON_BLOCK_BREAKING_DELTA = 1.0F / 300.0F;
    public static final float DIAMOND_BLOCK_BREAKING_DELTA = 1.0F / 200.0F;
    public static final float NETHERITE_BLOCK_BREAKING_DELTA = 1.0F / 100.0F;

    private final float blockBreakingDelta;
    private final ToolMaterial toolMaterial;

    public BreacherItem(Settings settings, float blockBreakingDelta, ToolMaterial toolMaterial) {
        super(settings);
        this.blockBreakingDelta = blockBreakingDelta;
        this.toolMaterial = toolMaterial;
    }

    @Override
    public float getMiningSpeed(ItemStack stack, BlockState state) {
        if (isPaxelMineable(state)) {
            return toolMaterial.speed();
        }

        return super.getMiningSpeed(stack, state);
    }

    public float getBlockBreakingDelta() {
        return blockBreakingDelta;
    }

    @Override
    public boolean isCorrectForDrops(ItemStack stack, BlockState state) {
        return !state.isIn(toolMaterial.incorrectBlocksForDrops());
    }

    @Override
    public boolean canMine(ItemStack stack, BlockState state, World world, BlockPos pos, LivingEntity miner) {
        return !isBlockedBlock(state);
    }

    @Override
    public boolean postMine(ItemStack stack, World world, BlockState state, BlockPos pos, LivingEntity miner) {
        if (world instanceof ServerWorld serverWorld && miner instanceof ServerPlayerEntity player && !isBlockedBlock(state)) {
            BreachNotificationManager.tryPlayBreachAlarm(serverWorld, pos, state, player);
            stack.damage(
                    ReinforcementManager.getBreacherDurabilityCost(world, pos, state, player),
                    serverWorld,
                    player,
                    item -> player.sendEquipmentBreakStatus(item, EquipmentSlot.MAINHAND)
            );
            ReinforcementManager.removeStoredTier(serverWorld, pos);
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

    private static boolean isPaxelMineable(BlockState state) {
        return state.isIn(BlockTags.PICKAXE_MINEABLE)
                || state.isIn(BlockTags.AXE_MINEABLE)
                || state.isIn(BlockTags.SHOVEL_MINEABLE);
    }
}
