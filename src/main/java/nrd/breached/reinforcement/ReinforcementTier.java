package nrd.breached.reinforcement;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;

import java.util.Locale;
import java.util.Optional;

public enum ReinforcementTier {
    WOOD(0, 0, "Wood", 16, 8),
    COPPER(1, 0, "Copper", 16, 4),
    IRON(2, 1, "Iron", 64, 2),
    GOLD(3, 1, "Gold", 64, 2),
    DIAMOND(4, 2, "Diamond", 256, 1),
    NETHERITE(5, 3, "Netherite", 512, 1);

    private final int level;
    private final int strengthLevel;
    private final String displayName;
    private final int durabilityCost;
    private final int materialCost;

    ReinforcementTier(int level, int strengthLevel, String displayName, int durabilityCost, int materialCost) {
        this.level = level;
        this.strengthLevel = strengthLevel;
        this.displayName = displayName;
        this.durabilityCost = durabilityCost;
        this.materialCost = materialCost;
    }

    public int level() {
        return level;
    }

    public int strengthLevel() {
        return strengthLevel;
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
            case COPPER -> stack.isOf(Items.COPPER_BLOCK);
            case IRON -> stack.isOf(Items.IRON_BLOCK);
            case GOLD -> stack.isOf(Items.GOLD_BLOCK);
            case DIAMOND -> stack.isOf(Items.DIAMOND_BLOCK);
            case NETHERITE -> stack.isOf(Items.NETHERITE_INGOT);
        };
    }

    public boolean hasMaterialCost(ItemStack stack) {
        return matchesMaterial(stack) && stack.getCount() >= materialCost;
    }

    public String materialDescription() {
        return switch (this) {
            case WOOD -> "8 logs";
            case COPPER -> "4 copper blocks";
            case IRON -> "2 iron blocks";
            case GOLD -> "2 gold blocks";
            case DIAMOND -> "1 diamond block";
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
        if (GOLD.matchesMaterial(stack)) {
            return Optional.of(GOLD);
        }
        if (IRON.matchesMaterial(stack)) {
            return Optional.of(IRON);
        }
        if (COPPER.matchesMaterial(stack)) {
            return Optional.of(COPPER);
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
