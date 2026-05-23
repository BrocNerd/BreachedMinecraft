package nrd.breached;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import nrd.breached.block.DiamondCraftingTableBlock;
import nrd.breached.block.IronCraftingTableBlock;
import nrd.breached.block.NetheriteCraftingTableBlock;

import java.util.function.Function;

public class Breached implements ModInitializer {
    public static final String MOD_ID = "breached";

    public static final Block TIER_1_CRAFTING_BENCH = registerBlock(
            "tier_1_crafting_bench",
            IronCraftingTableBlock::new,
            AbstractBlock.Settings.copy(Blocks.IRON_BLOCK)
    );

    public static final Block TIER_2_CRAFTING_BENCH = registerBlock(
            "tier_2_crafting_bench",
            DiamondCraftingTableBlock::new,
            AbstractBlock.Settings.copy(Blocks.DIAMOND_BLOCK)
    );

    public static final Block TIER_3_CRAFTING_BENCH = registerBlock(
            "tier_3_crafting_bench",
            NetheriteCraftingTableBlock::new,
            AbstractBlock.Settings.copy(Blocks.NETHERITE_BLOCK)
    );

    private static Block registerBlock(String name, Function<AbstractBlock.Settings, Block> blockFactory, AbstractBlock.Settings settings) {
        Identifier id = Identifier.of(MOD_ID, name);
        RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK, id);
        RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, id);
        Block block = blockFactory.apply(settings.registryKey(blockKey));

        Registry.register(
                Registries.ITEM,
                id,
                new BlockItem(block, new Item.Settings().registryKey(itemKey))
        );

        return Registry.register(
                Registries.BLOCK,
                id,
                block
        );
    }

    @Override
    public void onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> {
            entries.add(TIER_1_CRAFTING_BENCH);
            entries.add(TIER_2_CRAFTING_BENCH);
            entries.add(TIER_3_CRAFTING_BENCH);
        });
    }
}
