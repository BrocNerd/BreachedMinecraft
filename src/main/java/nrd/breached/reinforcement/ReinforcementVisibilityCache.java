package nrd.breached.reinforcement;

import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

public final class ReinforcementVisibilityCache {
    private static Map<BlockPos, ReinforcementTier> visibleTiers = Map.of();

    private ReinforcementVisibilityCache() {
    }

    public static void setVisibleTiers(Map<BlockPos, ReinforcementTier> tiers) {
        Map<BlockPos, ReinforcementTier> copiedTiers = new HashMap<>();
        tiers.forEach((pos, tier) -> copiedTiers.put(pos.toImmutable(), tier));
        visibleTiers = Map.copyOf(copiedTiers);
    }

    public static void clear() {
        visibleTiers = Map.of();
    }

    public static boolean hasVisibleTier(BlockPos pos) {
        return visibleTiers.containsKey(pos);
    }
}
