package nrd.breached.crafting;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import nrd.breached.Breached;

import java.util.HashMap;
import java.util.Map;

public final class CraftingTierRules {
    private static final Map<Item, CraftingTier> REQUIRED_TIERS = new HashMap<>();

    static {
        require(CraftingTier.TIER_1,
                Items.BOW,
                Items.GOLDEN_SWORD, Items.GOLDEN_SHOVEL, Items.GOLDEN_PICKAXE, Items.GOLDEN_AXE, Items.GOLDEN_HOE,
                Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS,
                Items.IRON_SWORD, Items.IRON_SHOVEL, Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_HOE,
                Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
                Items.IRON_SPEAR, Items.GOLDEN_SPEAR,
                Breached.IRON_BREACHER,
                Breached.TIER_2_CRAFTING_BENCH.asItem()
        );

        require(CraftingTier.TIER_2,
                Items.DIAMOND_SWORD, Items.DIAMOND_SHOVEL, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_HOE,
                Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
                Items.DIAMOND_SPEAR,
                Items.ENCHANTING_TABLE,
                Items.BREWING_STAND,
                Breached.DIAMOND_BREACHER,
                Breached.TIER_3_CRAFTING_BENCH.asItem()
        );

        require(CraftingTier.TIER_3,
                Items.BOOKSHELF,
                Items.CHISELED_BOOKSHELF,
                Items.NETHERITE_SWORD, Items.NETHERITE_SHOVEL, Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_HOE,
                Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
                Items.NETHERITE_SPEAR,
                Breached.NETHERITE_BREACHER
        );
    }

    private CraftingTierRules() {
    }

    public static boolean canCraft(CraftingTier tableTier, ItemStack result) {
        CraftingTier requiredTier = REQUIRED_TIERS.getOrDefault(result.getItem(), CraftingTier.TIER_0);
        return tableTier.allows(requiredTier);
    }

    private static void require(CraftingTier tier, Item... items) {
        for (Item item : items) {
            REQUIRED_TIERS.put(item, tier);
        }
    }
}
