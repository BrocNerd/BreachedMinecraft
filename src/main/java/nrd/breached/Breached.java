package nrd.breached;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import nrd.breached.block.DiamondCraftingTableBlock;
import nrd.breached.block.IronCraftingTableBlock;
import nrd.breached.block.LandlockBlock;
import nrd.breached.block.LandlockBlockEntity;
import nrd.breached.block.NetheriteCraftingTableBlock;
import nrd.breached.item.BreacherItem;
import nrd.breached.landlock.LandlockClaimManager;
import nrd.breached.team.TeamCommands;

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

    public static final Block LANDLOCK_BLOCK = registerBlock(
            "landlock_block",
            LandlockBlock::new,
            AbstractBlock.Settings.copy(Blocks.IRON_BLOCK)
    );

    public static final BlockEntityType<LandlockBlockEntity> LANDLOCK_BLOCK_ENTITY = Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.of(MOD_ID, "landlock_block"),
            FabricBlockEntityTypeBuilder.create(LandlockBlockEntity::new, LANDLOCK_BLOCK).build()
    );

    public static final Item IRON_BREACHER = registerItem(
            "iron_breacher",
            BreacherItem::new,
            new Item.Settings().maxDamage(1)
    );

    public static final Item DIAMOND_BREACHER = registerItem(
            "diamond_breacher",
            BreacherItem::new,
            new Item.Settings().maxDamage(5)
    );

    public static final Item NETHERITE_BREACHER = registerItem(
            "netherite_breacher",
            BreacherItem::new,
            new Item.Settings().maxDamage(20).fireproof()
    );

    public static final Item PROBE = registerItem(
            "probe",
            Item::new,
            new Item.Settings()
    );

    private static Item registerItem(String name, Function<Item.Settings, Item> itemFactory, Item.Settings settings) {
        Identifier id = Identifier.of(MOD_ID, name);
        RegistryKey<Item> itemKey = RegistryKey.of(RegistryKeys.ITEM, id);
        return Registry.register(Registries.ITEM, id, itemFactory.apply(settings.registryKey(itemKey)));
    }

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
        TeamCommands.register();
        registerLandlockDebugEvents();
        registerLandlockProtectionEvents();
        registerLandlockPlacementProtectionEvents();
        registerLandlockDoorProtectionEvents();

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> {
            entries.add(TIER_1_CRAFTING_BENCH);
            entries.add(TIER_2_CRAFTING_BENCH);
            entries.add(TIER_3_CRAFTING_BENCH);
            entries.add(LANDLOCK_BLOCK);
        });

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(IRON_BREACHER);
            entries.add(DIAMOND_BREACHER);
            entries.add(NETHERITE_BREACHER);
            entries.add(PROBE);
        });
    }

    private static void registerLandlockProtectionEvents() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient() || LandlockClaimManager.canPlayerModify(world, player, pos) || canBreachBlock(player.getMainHandStack(), state)) {
                return true;
            }

            player.sendMessage(Text.literal("This area is protected by a Landlock."), false);
            return false;
        });
    }

    private static boolean canBreachBlock(net.minecraft.item.ItemStack stack, net.minecraft.block.BlockState state) {
        return stack.getItem() instanceof BreacherItem
                && !stack.isEmpty()
                && !stack.willBreakNextUse()
                && !BreacherItem.isBlockedBlock(state);
    }

    private static void registerLandlockPlacementProtectionEvents() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient() || !(player.getStackInHand(hand).getItem() instanceof BlockItem)) {
                return ActionResult.PASS;
            }

            ItemPlacementContext placementContext = new ItemPlacementContext(player, hand, player.getStackInHand(hand), hitResult);
            if (!placementContext.canPlace()) {
                return ActionResult.PASS;
            }

            if (player.getStackInHand(hand).isOf(LANDLOCK_BLOCK.asItem())) {
                if (LandlockClaimManager.countPlayerLandlockAuthorizations(world, player.getUuid()) >= LandlockClaimManager.MAX_AUTHORIZED_LANDLOCKS) {
                    player.sendMessage(Text.literal("You are already authorized on the maximum number of Landlocks."), false);
                    return ActionResult.FAIL;
                }

                if (LandlockClaimManager.isTooCloseToExistingLandlock(world, placementContext.getBlockPos())) {
                    player.sendMessage(Text.literal("This Landlock is too close to another Landlock."), false);
                    return ActionResult.FAIL;
                }
            }

            if (LandlockClaimManager.canPlayerModify(world, player, placementContext.getBlockPos())) {
                return ActionResult.PASS;
            }

            player.sendMessage(Text.literal("This area is protected by a Landlock."), false);
            return ActionResult.FAIL;
        });
    }

    private static void registerLandlockDoorProtectionEvents() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) {
                return ActionResult.PASS;
            }

            Block block = world.getBlockState(hitResult.getBlockPos()).getBlock();
            if (!isDoorLikeBlock(block) || LandlockClaimManager.canPlayerModify(world, player, hitResult.getBlockPos())) {
                return ActionResult.PASS;
            }

            player.sendMessage(Text.literal("This door is protected by a Landlock."), false);
            return ActionResult.FAIL;
        });
    }

    private static boolean isDoorLikeBlock(Block block) {
        return block instanceof DoorBlock
                || block instanceof TrapdoorBlock
                || block instanceof FenceGateBlock;
    }

    private static void registerLandlockDebugEvents() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient() || !player.isSneaking()) {
                return ActionResult.PASS;
            }

            if (!player.getMainHandStack().isOf(PROBE) && !player.getOffHandStack().isOf(PROBE)) {
                return ActionResult.PASS;
            }

            if (world.getBlockState(hitResult.getBlockPos()).isOf(LANDLOCK_BLOCK)) {
                return ActionResult.PASS;
            }

            if (LandlockClaimManager.isInsideAnyClaim(world, hitResult.getBlockPos())) {
                player.sendMessage(Text.literal("This block is inside a Landlock claim."), false);
            } else {
                player.sendMessage(Text.literal("This block is not claimed."), false);
            }

            return ActionResult.PASS;
        });
    }
}
