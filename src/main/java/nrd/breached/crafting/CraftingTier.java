package nrd.breached.crafting;

public enum CraftingTier {
    TIER_0(0),
    TIER_1(1),
    TIER_2(2),
    TIER_3(3);

    private final int level;

    CraftingTier(int level) {
        this.level = level;
    }

    public boolean allows(CraftingTier requiredTier) {
        return this.level >= requiredTier.level;
    }
}
