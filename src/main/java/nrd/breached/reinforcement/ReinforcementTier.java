package nrd.breached.reinforcement;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;

import java.util.Locale;
import java.util.Optional;

public enum ReinforcementTier {
    WOOD(0, "Wood", 16, 16),
    IRON(1, "Iron", 64, 4),
    DIAMOND(2, "Diamond", 256, 2),
    NETHERITE(3, "Netherite", 1024, 1);

    private final int level;
    private final String displayName;
    private final int durabilityCost;
    private final int materialCost;

    ReinforcementTier(int level, String displayName, int durabilityCost, int materialCost) {
        this.level = level;
        this.displayName = displayName;
        this.durabilityCost = durabilityCost;
        this.materialCost = materialCost;
    }

    public int level() {
        return level;
    }

    public String displayName() {
        return displayName;
    }

    public int durabilityCost() {
        return durabilityCost;
    }

    public int materialCost() {
        return materialCost;
    }

    public boolean matchesMaterial(ItemStack stack) {
        return switch (this) {
            case WOOD -> stack.isIn(ItemTags.LOGS);
            case IRON -> stack.isOf(Items.IRON_BLOCK);
            case DIAMOND -> stack.isOf(Items.DIAMOND_BLOCK);
            case NETHERITE -> stack.isOf(Items.NETHERITE_INGOT);
        };
    }

    public boolean hasMaterialCost(ItemStack stack) {
        return matchesMaterial(stack) && stack.getCount() >= materialCost;
    }

    public String materialDescription() {
        return switch (this) {
            case WOOD -> "16 logs";
            case IRON -> "4 iron blocks";
            case DIAMOND -> "2 diamond blocks";
            case NETHERITE -> "1 netherite ingot";
        };
    }

    public static Optional<ReinforcementTier> fromMaterial(ItemStack stack) {
        if (NETHERITE.matchesMaterial(stack)) {
            return Optional.of(NETHERITE);
        }
        if (DIAMOND.matchesMaterial(stack)) {
            return Optional.of(DIAMOND);
        }
        if (IRON.matchesMaterial(stack)) {
            return Optional.of(IRON);
        }
        if (WOOD.matchesMaterial(stack)) {
            return Optional.of(WOOD);
        }

        return Optional.empty();
    }

    public static Optional<ReinforcementTier> fromSerializedName(String value) {
        try {
            return Optional.of(ReinforcementTier.valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static Optional<ReinforcementTier> fromLevel(int level) {
        for (ReinforcementTier tier : values()) {
            if (tier.level == level) {
                return Optional.of(tier);
            }
        }

        return Optional.empty();
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
