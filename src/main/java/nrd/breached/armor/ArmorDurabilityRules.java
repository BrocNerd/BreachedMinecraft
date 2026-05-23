package nrd.breached.armor;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

public final class ArmorDurabilityRules {
    private static final Map<Item, Integer> MAX_DURABILITY = new HashMap<>();

    static {
        set(200,
                Items.COPPER_HELMET, Items.COPPER_CHESTPLATE, Items.COPPER_LEGGINGS, Items.COPPER_BOOTS
        );
        set(250,
                Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS
        );
        set(150,
                Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS
        );
        set(200,
                Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS
        );
        set(250,
                Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS
        );
        set(300,
                Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS
        );
        set(500,
                Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS
        );
    }

    private ArmorDurabilityRules() {
    }

    public static OptionalInt getMaxDurability(Item item) {
        Integer durability = MAX_DURABILITY.get(item);
        return durability == null ? OptionalInt.empty() : OptionalInt.of(durability);
    }

    private static void set(int durability, Item... items) {
        for (Item item : items) {
            MAX_DURABILITY.put(item, durability);
        }
    }
}
