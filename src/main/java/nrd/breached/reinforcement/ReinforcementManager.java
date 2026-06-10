package nrd.breached.reinforcement;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import nrd.breached.Breached;
import nrd.breached.item.BreacherItem;
import nrd.breached.landlock.LandlockClaimManager;

import java.util.Map;
import java.util.Optional;

public final class ReinforcementManager {
    private static final int NORMAL_BREACHER_DURABILITY_COST = 4;
    private static final int LOCKDOWN_DURABILITY_MULTIPLIER = 2;

    private ReinforcementManager() {
    }

    public static Optional<ReinforcementTier> getTier(World world, BlockPos pos, BlockState state) {
        if (state.isOf(Breached.LANDLOCK_BLOCK)) {
            return Optional.of(ReinforcementTier.WOOD);
        }

        return getStoredTier(world, pos);
    }

    public static Optional<ReinforcementTier> getStoredTier(World world, BlockPos pos) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return Optional.empty();
        }

        return ReinforcementState.get(serverWorld.getServer()).getTier(serverWorld.getRegistryKey(), pos);
    }

    public static Map<BlockPos, ReinforcementTier> getVisibleTiersWithin(ServerWorld world, BlockPos center, int radius) {
        Map<BlockPos, ReinforcementTier> tiers = ReinforcementState.get(world.getServer()).getTiersWithin(world.getRegistryKey(), center, radius);
        LandlockClaimManager.forEachLoadedLandlockWithin(world, center, radius, (landlockPos, landlock) ->
                tiers.put(landlockPos.toImmutable(), ReinforcementTier.WOOD));
        return tiers;
    }

    public static void setTier(ServerWorld world, BlockPos pos, ReinforcementTier tier) {
        ReinforcementState.get(world.getServer()).setTier(world.getRegistryKey(), pos, tier);
        LandlockClaimManager.refreshClaimCost(world, pos);
    }

    public static void removeStoredTier(ServerWorld world, BlockPos pos) {
        ReinforcementState.get(world.getServer()).remove(world.getRegistryKey(), pos);
        LandlockClaimManager.refreshClaimCost(world, pos);
    }

    public static boolean canStoreReinforcement(BlockState state) {
        return !state.isAir() && !state.isOf(Blocks.SNOW) && !BreacherItem.isBlockedBlock(state);
    }

    public static int getBreacherDurabilityCost(World world, BlockPos pos, BlockState state) {
        return getTier(world, pos, state)
                .map(ReinforcementTier::durabilityCost)
                .orElse(NORMAL_BREACHER_DURABILITY_COST);
    }

    public static int getBreacherDurabilityCost(World world, BlockPos pos, BlockState state, ServerPlayerEntity player) {
        int baseCost = getBreacherDurabilityCost(world, pos, state);
        Optional<LandlockClaimManager.ClaimAccess> claimAccess = LandlockClaimManager.getClaimAccess(world, pos, player.getUuid());
        if (claimAccess.isEmpty()) {
            return baseCost;
        }

        LandlockClaimManager.ClaimAccess access = claimAccess.get();
        if (access.decayed()) {
            return getStoredTier(world, pos).isPresent() ? Math.max(1, baseCost / 2) : baseCost;
        }

        if (!access.authorized() && access.lockdown()) {
            return baseCost * LOCKDOWN_DURABILITY_MULTIPLIER;
        }

        return baseCost;
    }

    public static boolean hasEnoughDurability(ItemStack stack, World world, BlockPos pos, BlockState state) {
        if (!stack.isDamageable()) {
            return true;
        }

        return getRemainingDurability(stack) >= getBreacherDurabilityCost(world, pos, state);
    }

    public static boolean hasEnoughDurability(ItemStack stack, World world, BlockPos pos, BlockState state, ServerPlayerEntity player) {
        if (!stack.isDamageable()) {
            return true;
        }

        return getRemainingDurability(stack) >= getBreacherDurabilityCost(world, pos, state, player);
    }

    public static void breakBreacher(ItemStack stack, ServerWorld world, ServerPlayerEntity player) {
        if (!stack.isDamageable() || stack.isEmpty()) {
            return;
        }

        int damageToBreak = Math.max(1, getRemainingDurability(stack));
        stack.damage(damageToBreak, world, player, item -> player.sendEquipmentBreakStatus(item, EquipmentSlot.MAINHAND));
    }

    private static int getRemainingDurability(ItemStack stack) {
        return Math.max(0, stack.getMaxDamage() - stack.getDamage());
    }
}
