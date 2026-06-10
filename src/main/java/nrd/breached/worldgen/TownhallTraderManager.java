package nrd.breached.worldgen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.item.ItemConvertible;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.text.Text;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.village.Merchant;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.TradedItem;
import nrd.breached.mixin.MerchantEntityAccessor;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class TownhallTraderManager {
    private static final String TOWNHALL_TRADER_TAG = "breached_townhall_trader";
    private static final String STABLEHAND_TRADER_TAG = "breached_stablehand_trader";
    private static final String SANCTUARY_TRADER_TAG = "breached_sanctuary_trader";
    private static final String TRADER_OFFER_VERSION_TAG = "breached_structure_trader_offers_v3";
    private static final String SANCTUARY_MYSTERY_BOOK_KEY = "BreachedSanctuaryMysteryBook";
    private static final int ENSURE_INTERVAL_TICKS = 20 * 5;
    private static final int LOOK_INTERVAL_TICKS = 5;
    private static final int MAX_TRADE_USES = 1_000_000;
    private static final int ELYTRA_REMAINING_DURABILITY = 64;
    private static final int SANCTUARY_EXPERIENCE_BOTTLES = 10;
    private static final int MIN_SANCTUARY_BOOK_ENCHANTMENT_LEVEL = 10;
    private static final int MAX_SANCTUARY_BOOK_ENCHANTMENT_LEVEL = 15;
    private static final double LOOK_RANGE = 16.0D;
    private static final double TRADER_SEARCH_MARGIN = 8.0D;
    private static final double TRADER_REPOSITION_DISTANCE_SQUARED = 0.25D;
    private static final float TRADER_YAW = 180.0F;

    private static final List<ItemConvertible> GHAST_HARNESSES = List.of(
            Items.WHITE_HARNESS,
            Items.ORANGE_HARNESS,
            Items.MAGENTA_HARNESS,
            Items.LIGHT_BLUE_HARNESS,
            Items.YELLOW_HARNESS,
            Items.LIME_HARNESS,
            Items.PINK_HARNESS,
            Items.GRAY_HARNESS,
            Items.LIGHT_GRAY_HARNESS,
            Items.CYAN_HARNESS,
            Items.PURPLE_HARNESS,
            Items.BLUE_HARNESS,
            Items.BROWN_HARNESS,
            Items.GREEN_HARNESS,
            Items.RED_HARNESS,
            Items.BLACK_HARNESS
    );

    private static final List<TraderSpot> TRADER_SPOTS = List.of(
            new TraderSpot(
                    TOWNHALL_TRADER_TAG,
                    "Town Hall Trader",
                    BreachedStructureDefinitions.TOWNHALL,
                    Blocks.MAGENTA_CARPET,
                    TownhallTraderManager::createTownhallOffers
            ),
            new TraderSpot(
                    STABLEHAND_TRADER_TAG,
                    "Stablehand",
                    BreachedStructureDefinitions.TOWNHALL,
                    Blocks.BROWN_CARPET,
                    TownhallTraderManager::createStablehandOffers
            ),
            new TraderSpot(
                    SANCTUARY_TRADER_TAG,
                    "Sanctuary Trader",
                    BreachedStructureDefinitions.SANCTUARY,
                    Blocks.RED_CARPET,
                    TownhallTraderManager::createSanctuaryOffers
            )
    );

    private TownhallTraderManager() {
    }

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(TownhallTraderManager::tick);
    }

    public static boolean isBreachedTrader(Entity entity) {
        return TRADER_SPOTS.stream().anyMatch(spot -> entity.getCommandTags().contains(spot.tag()));
    }

    public static boolean isTownhallTrader(Entity entity) {
        return isBreachedTrader(entity);
    }

    private static void tick(ServerWorld world) {
        if (!world.getRegistryKey().equals(World.OVERWORLD)) {
            return;
        }

        if (world.getTime() % LOOK_INTERVAL_TICKS == 0) {
            updateBreachedTraderLook(world);
        }
        if (world.getTime() % ENSURE_INTERVAL_TICKS == 0) {
            ensureBreachedTraders(world);
        }
    }

    private static void updateBreachedTraderLook(ServerWorld world) {
        for (WanderingTraderEntity trader : world.getEntitiesByType(
                TypeFilter.instanceOf(WanderingTraderEntity.class),
                entity -> isBreachedTrader(entity) && !entity.isRemoved()
        )) {
            findNearestLookTarget(world, trader).ifPresent(player ->
                    trader.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, player.getEyePos())
            );
        }
    }

    private static Optional<ServerPlayerEntity> findNearestLookTarget(ServerWorld world, WanderingTraderEntity trader) {
        ServerPlayerEntity nearestPlayer = null;
        double nearestDistance = LOOK_RANGE * LOOK_RANGE;
        for (ServerPlayerEntity player : world.getPlayers(player -> player.isAlive() && !player.isSpectator())) {
            double distance = player.squaredDistanceTo(trader);
            if (distance <= nearestDistance) {
                nearestPlayer = player;
                nearestDistance = distance;
            }
        }
        return Optional.ofNullable(nearestPlayer);
    }

    private static void ensureBreachedTraders(ServerWorld world) {
        for (TraderSpot spot : TRADER_SPOTS) {
            ensureBreachedTrader(world, spot);
        }
    }

    private static void ensureBreachedTrader(ServerWorld world, TraderSpot spot) {
        Optional<BreachedStructurePlacementState.SavedPlacement> placement = getActivePlacement(world, spot.structure());
        if (placement.isEmpty()) {
            return;
        }

        Optional<BlockPos> markerPos = findTraderMarker(world, placement.get(), spot);
        if (markerPos.isEmpty() || !isChunkLoaded(world, markerPos.get())) {
            return;
        }
        if (!world.getBlockState(markerPos.get()).isOf(spot.markerBlock())) {
            return;
        }

        WanderingTraderEntity trader = findBreachedTrader(world, placement.get(), markerPos.get(), spot).orElse(null);
        if (trader == null) {
            spawnBreachedTrader(world, markerPos.get(), spot);
            return;
        }

        configureBreachedTrader(
                world,
                trader,
                markerPos.get(),
                spot,
                trader.squaredDistanceTo(getTraderPosition(markerPos.get())) > TRADER_REPOSITION_DISTANCE_SQUARED
        );
    }

    private static Optional<BreachedStructurePlacementState.SavedPlacement> getActivePlacement(
            ServerWorld world,
            BreachedStructureDefinition definition
    ) {
        String structureKey = BreachedStructureDefinitions.key(definition);
        BreachedStructurePlacementState state = BreachedStructurePlacementState.get(world.getServer());
        return state.placements()
                .stream()
                .filter(entry -> entry.getValue().active() && structureKey(entry.getKey()).equals(structureKey))
                .map(java.util.Map.Entry::getValue)
                .findFirst();
    }

    private static Optional<BlockPos> findTraderMarker(
            ServerWorld world,
            BreachedStructurePlacementState.SavedPlacement placement,
            TraderSpot spot
    ) {
        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(world, spot.structure());
        if (template.isEmpty()) {
            return Optional.empty();
        }

        BlockPos origin = new BlockPos(placement.originX(), placement.originY(), placement.originZ());
        BlockPos placementCenter = new BlockPos(placement.centerX(), placement.originY(), placement.centerZ());
        return BreachedStructureSpawnManager.getTemplatePlacedBlocks(
                        world,
                        spot.structure(),
                        template.get(),
                        origin,
                        spot.structure().mirror(),
                        spot.structure().rotation()
                )
                .stream()
                .filter(block -> block.state().isOf(spot.markerBlock()))
                .map(BreachedStructureSpawnManager.TemplatePlacedBlock::pos)
                .min(Comparator.comparingLong(pos -> squaredDistance(pos, placementCenter)));
    }

    private static Optional<WanderingTraderEntity> findBreachedTrader(
            ServerWorld world,
            BreachedStructurePlacementState.SavedPlacement placement,
            BlockPos markerPos,
            TraderSpot spot
    ) {
        Box searchBox = new Box(
                placement.originX(),
                placement.originY(),
                placement.originZ(),
                placement.originX() + Math.max(1, placement.sizeX()),
                placement.originY() + Math.max(1, placement.sizeY()),
                placement.originZ() + Math.max(1, placement.sizeZ())
        ).expand(TRADER_SEARCH_MARGIN);
        Vec3d markerCenter = markerPos.toCenterPos();
        WanderingTraderEntity closestTrader = null;
        double closestDistance = Double.MAX_VALUE;

        for (WanderingTraderEntity trader : world.getEntitiesByType(
                TypeFilter.instanceOf(WanderingTraderEntity.class),
                searchBox,
                entity -> entity.getCommandTags().contains(spot.tag()) && !entity.isRemoved()
        )) {
            double distance = trader.squaredDistanceTo(markerCenter);
            if (distance < closestDistance) {
                if (closestTrader != null) {
                    closestTrader.discard();
                }
                closestTrader = trader;
                closestDistance = distance;
            } else {
                trader.discard();
            }
        }

        return Optional.ofNullable(closestTrader);
    }

    private static void spawnBreachedTrader(ServerWorld world, BlockPos markerPos, TraderSpot spot) {
        WanderingTraderEntity trader = new WanderingTraderEntity(EntityType.WANDERING_TRADER, world);
        configureBreachedTrader(world, trader, markerPos, spot, true);
        world.spawnEntity(trader);
    }

    private static void configureBreachedTrader(
            ServerWorld world,
            WanderingTraderEntity trader,
            BlockPos markerPos,
            TraderSpot spot,
            boolean reposition
    ) {
        trader.addCommandTag(spot.tag());
        trader.setPersistent();
        trader.setDespawnDelay(Integer.MAX_VALUE);
        trader.setAiDisabled(true);
        trader.setInvulnerable(true);
        trader.setCanPickUpLoot(false);
        trader.setCustomName(Text.literal(spot.displayName()));
        trader.setCustomNameVisible(true);
        if (!trader.getCommandTags().contains(TRADER_OFFER_VERSION_TAG)) {
            ((MerchantEntityAccessor) trader).breached$setOffers(spot.offersFactory().apply(world));
            trader.addCommandTag(TRADER_OFFER_VERSION_TAG);
        }
        trader.setWanderTarget(markerPos);
        if (reposition) {
            Vec3d traderPos = getTraderPosition(markerPos);
            trader.refreshPositionAndAngles(traderPos.x, traderPos.y, traderPos.z, TRADER_YAW, 0.0F);
            trader.setVelocity(Vec3d.ZERO);
        }
    }

    private static TradeOfferList createTownhallOffers(ServerWorld world) {
        TradeOfferList offers = new TradeOfferList();
        offers.add(offer(Items.DIAMOND, 32, Items.HEAVY_CORE, 1));
        offers.add(offer(Items.DIAMOND, 32, lowDurabilityElytra()));
        offers.add(offer(Items.DIAMOND, 1, Items.ENDER_PEARL, 2));
        offers.add(offer(Items.DIAMOND, 1, Items.EXPERIENCE_BOTTLE, 8));
        offers.add(offer(Items.DIAMOND, 1, Items.ARROW, 16));
        offers.add(offer(Items.IRON_BLOCK, 1, Items.ARROW, 16));
        offers.add(offer(Items.DIAMOND, 1, Items.IRON_INGOT, 6));
        offers.add(offer(Items.IRON_INGOT, 1, Items.OAK_LOG, 3));
        return offers;
    }

    private static TradeOfferList createStablehandOffers(ServerWorld world) {
        TradeOfferList offers = new TradeOfferList();
        offers.add(offer(Items.DIAMOND, TownhallHorseTrade.PRICE_DIAMONDS, TownhallHorseTrade.createHorseEgg()));
        offers.add(offer(Items.DIAMOND, 24, Items.DRIED_GHAST, 1));
        offers.add(offer(Items.DIAMOND, 2, Items.GOLDEN_APPLE, 1));
        offers.add(offer(Items.DIAMOND, 2, Items.GOLDEN_CARROT, 8));
        offers.add(offer(Items.DIAMOND, 1, Items.COOKED_MUTTON, 16));
        offers.add(offer(Items.IRON_INGOT, 2, Items.BREAD, 8));
        offers.add(offer(Items.IRON_INGOT, 4, Items.LEATHER_HORSE_ARMOR, 1));
        offers.add(offer(Items.IRON_INGOT, 16, Items.COPPER_HORSE_ARMOR, 1));
        offers.add(offer(Items.DIAMOND, 2, Items.IRON_HORSE_ARMOR, 1));
        offers.add(offer(Items.DIAMOND, 4, Items.GOLDEN_HORSE_ARMOR, 1));
        offers.add(offer(Items.DIAMOND, 16, Items.DIAMOND_HORSE_ARMOR, 1));
        offers.add(offer(Items.DIAMOND, 32, Items.NETHERITE_HORSE_ARMOR, 1));
        for (ItemConvertible harness : GHAST_HARNESSES) {
            offers.add(offer(Items.IRON_INGOT, 8, harness, 1));
        }
        return offers;
    }

    private static TradeOfferList createSanctuaryOffers(ServerWorld world) {
        TradeOfferList offers = new TradeOfferList();
        offers.add(offer(Items.DIAMOND, 1, Items.EXPERIENCE_BOTTLE, SANCTUARY_EXPERIENCE_BOTTLES));
        offers.add(offer(Items.DIAMOND, 3, mysterySanctuaryBook()));
        return offers;
    }

    private static TradeOffer offer(ItemConvertible priceItem, int priceCount, ItemConvertible soldItem, int soldCount) {
        return offer(priceItem, priceCount, new ItemStack(soldItem, soldCount));
    }

    private static TradeOffer offer(ItemConvertible priceItem, int priceCount, ItemStack soldStack) {
        return new TradeOffer(new TradedItem(priceItem, priceCount), soldStack, MAX_TRADE_USES, 0, 0.0F);
    }

    private static ItemStack lowDurabilityElytra() {
        ItemStack elytra = new ItemStack(Items.ELYTRA);
        elytra.setDamage(Math.max(0, elytra.getMaxDamage() - ELYTRA_REMAINING_DURABILITY));
        return elytra;
    }

    private static ItemStack mysterySanctuaryBook() {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        NbtCompound customData = new NbtCompound();
        customData.putBoolean(SANCTUARY_MYSTERY_BOOK_KEY, true);
        book.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(customData));
        return book;
    }

    public static ItemStack resolveMysterySanctuaryTradeOutput(Merchant merchant, ItemStack stack) {
        if (!isMysterySanctuaryBook(stack)) {
            return stack;
        }

        Optional<ServerWorld> world = getServerWorld(merchant);
        if (world.isEmpty()) {
            return stack;
        }

        int enchantmentLevel = MIN_SANCTUARY_BOOK_ENCHANTMENT_LEVEL
                + world.get().getRandom().nextInt(MAX_SANCTUARY_BOOK_ENCHANTMENT_LEVEL - MIN_SANCTUARY_BOOK_ENCHANTMENT_LEVEL + 1);
        return EnchantmentHelper.enchant(
                world.get().getRandom(),
                new ItemStack(Items.BOOK),
                enchantmentLevel,
                world.get().getRegistryManager(),
                Optional.empty()
        );
    }

    private static boolean isMysterySanctuaryBook(ItemStack stack) {
        NbtComponent customData = stack.get(DataComponentTypes.CUSTOM_DATA);
        return customData != null && customData.copyNbt().getBoolean(SANCTUARY_MYSTERY_BOOK_KEY, false);
    }

    private static Optional<ServerWorld> getServerWorld(Merchant merchant) {
        if (merchant instanceof Entity entity && entity.getEntityWorld() instanceof ServerWorld serverWorld) {
            return Optional.of(serverWorld);
        }

        PlayerEntity customer = merchant.getCustomer();
        if (customer != null && customer.getEntityWorld() instanceof ServerWorld serverWorld) {
            return Optional.of(serverWorld);
        }

        return Optional.empty();
    }

    private static Vec3d getTraderPosition(BlockPos markerPos) {
        return new Vec3d(markerPos.getX() + 0.5D, markerPos.getY(), markerPos.getZ() + 0.5D);
    }

    private static boolean isChunkLoaded(ServerWorld world, BlockPos pos) {
        return world.getChunkManager().isChunkLoaded(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16));
    }

    private static String structureKey(String placementKey) {
        int separator = placementKey.indexOf('#');
        return separator >= 0 ? placementKey.substring(0, separator) : placementKey;
    }

    private static long squaredDistance(BlockPos left, BlockPos right) {
        long dx = left.getX() - right.getX();
        long dy = left.getY() - right.getY();
        long dz = left.getZ() - right.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private record TraderSpot(
            String tag,
            String displayName,
            BreachedStructureDefinition structure,
            Block markerBlock,
            Function<ServerWorld, TradeOfferList> offersFactory
    ) {
    }
}
