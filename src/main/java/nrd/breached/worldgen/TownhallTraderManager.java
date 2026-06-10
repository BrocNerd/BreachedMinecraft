package nrd.breached.worldgen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.item.ItemConvertible;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.text.Text;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.TradedItem;
import nrd.breached.mixin.MerchantEntityAccessor;

import java.util.Comparator;
import java.util.Optional;

public final class TownhallTraderManager {
    private static final String TOWNHALL_TRADER_TAG = "breached_townhall_trader";
    private static final String TOWNHALL_STRUCTURE_KEY = BreachedStructureDefinitions.key(BreachedStructureDefinitions.TOWNHALL);
    private static final int ENSURE_INTERVAL_TICKS = 20 * 5;
    private static final int LOOK_INTERVAL_TICKS = 5;
    private static final int MAX_TRADE_USES = 1_000_000;
    private static final int ELYTRA_REMAINING_DURABILITY = 64;
    private static final double LOOK_RANGE = 16.0D;
    private static final double TRADER_SEARCH_MARGIN = 8.0D;
    private static final double TRADER_REPOSITION_DISTANCE_SQUARED = 0.25D;
    private static final float TRADER_YAW = 180.0F;

    private TownhallTraderManager() {
    }

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(TownhallTraderManager::tick);
    }

    public static boolean isTownhallTrader(Entity entity) {
        return entity.getCommandTags().contains(TOWNHALL_TRADER_TAG);
    }

    private static void tick(ServerWorld world) {
        if (!world.getRegistryKey().equals(World.OVERWORLD)) {
            return;
        }

        if (world.getTime() % LOOK_INTERVAL_TICKS == 0) {
            updateTownhallTraderLook(world);
        }
        if (world.getTime() % ENSURE_INTERVAL_TICKS == 0) {
            ensureTownhallTrader(world);
        }
    }

    private static void updateTownhallTraderLook(ServerWorld world) {
        for (WanderingTraderEntity trader : world.getEntitiesByType(
                TypeFilter.instanceOf(WanderingTraderEntity.class),
                entity -> isTownhallTrader(entity) && !entity.isRemoved()
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

    private static void ensureTownhallTrader(ServerWorld world) {
        Optional<BreachedStructurePlacementState.SavedPlacement> townhallPlacement = getActiveTownhallPlacement(world);
        if (townhallPlacement.isEmpty()) {
            return;
        }

        Optional<BlockPos> markerPos = findTownhallTraderMarker(world, townhallPlacement.get());
        if (markerPos.isEmpty() || !isChunkLoaded(world, markerPos.get())) {
            return;
        }
        if (!world.getBlockState(markerPos.get()).isOf(Blocks.MAGENTA_CARPET)) {
            return;
        }

        WanderingTraderEntity trader = findTownhallTrader(world, townhallPlacement.get(), markerPos.get()).orElse(null);
        if (trader == null) {
            spawnTownhallTrader(world, markerPos.get());
            return;
        }

        configureTownhallTrader(
                trader,
                markerPos.get(),
                trader.squaredDistanceTo(getTraderPosition(markerPos.get())) > TRADER_REPOSITION_DISTANCE_SQUARED
        );
    }

    private static Optional<BreachedStructurePlacementState.SavedPlacement> getActiveTownhallPlacement(ServerWorld world) {
        BreachedStructurePlacementState state = BreachedStructurePlacementState.get(world.getServer());
        return state.placements()
                .stream()
                .filter(entry -> entry.getValue().active() && structureKey(entry.getKey()).equals(TOWNHALL_STRUCTURE_KEY))
                .map(java.util.Map.Entry::getValue)
                .findFirst();
    }

    private static Optional<BlockPos> findTownhallTraderMarker(
            ServerWorld world,
            BreachedStructurePlacementState.SavedPlacement placement
    ) {
        Optional<StructureTemplate> template = BreachedStructureSpawnManager.loadTemplate(
                world,
                BreachedStructureDefinitions.TOWNHALL
        );
        if (template.isEmpty()) {
            return Optional.empty();
        }

        BlockPos origin = new BlockPos(placement.originX(), placement.originY(), placement.originZ());
        BlockPos placementCenter = new BlockPos(placement.centerX(), placement.originY(), placement.centerZ());
        return BreachedStructureSpawnManager.getTemplatePlacedBlocks(
                        world,
                        BreachedStructureDefinitions.TOWNHALL,
                        template.get(),
                        origin,
                        BreachedStructureDefinitions.TOWNHALL.mirror(),
                        BreachedStructureDefinitions.TOWNHALL.rotation()
                )
                .stream()
                .filter(block -> block.state().isOf(Blocks.MAGENTA_CARPET))
                .map(BreachedStructureSpawnManager.TemplatePlacedBlock::pos)
                .min(Comparator.comparingLong(pos -> squaredDistance(pos, placementCenter)));
    }

    private static Optional<WanderingTraderEntity> findTownhallTrader(
            ServerWorld world,
            BreachedStructurePlacementState.SavedPlacement placement,
            BlockPos markerPos
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
                entity -> isTownhallTrader(entity) && !entity.isRemoved()
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

    private static void spawnTownhallTrader(ServerWorld world, BlockPos markerPos) {
        WanderingTraderEntity trader = new WanderingTraderEntity(EntityType.WANDERING_TRADER, world);
        configureTownhallTrader(trader, markerPos, true);
        world.spawnEntity(trader);
    }

    private static void configureTownhallTrader(WanderingTraderEntity trader, BlockPos markerPos, boolean reposition) {
        trader.addCommandTag(TOWNHALL_TRADER_TAG);
        trader.setPersistent();
        trader.setDespawnDelay(Integer.MAX_VALUE);
        trader.setAiDisabled(true);
        trader.setInvulnerable(true);
        trader.setCanPickUpLoot(false);
        trader.setCustomName(Text.literal("Town Hall Trader"));
        trader.setCustomNameVisible(true);
        ((MerchantEntityAccessor) trader).breached$setOffers(createOffers());
        trader.setWanderTarget(markerPos);
        if (reposition) {
            Vec3d traderPos = getTraderPosition(markerPos);
            trader.refreshPositionAndAngles(traderPos.x, traderPos.y, traderPos.z, TRADER_YAW, 0.0F);
            trader.setVelocity(Vec3d.ZERO);
        }
    }

    private static TradeOfferList createOffers() {
        TradeOfferList offers = new TradeOfferList();
        offers.add(offer(Items.DIAMOND, 32, Items.HEAVY_CORE, 1));
        offers.add(offer(Items.DIAMOND, 64, lowDurabilityElytra()));
        offers.add(offer(Items.DIAMOND, 4, Items.GOLDEN_APPLE, 1));
        offers.add(offer(Items.DIAMOND, 1, Items.ENDER_PEARL, 2));
        offers.add(offer(Items.DIAMOND, 1, Items.EXPERIENCE_BOTTLE, 8));
        offers.add(offer(Items.DIAMOND, 1, Items.ARROW, 32));
        offers.add(offer(Items.IRON_BLOCK, 1, Items.ARROW, 32));
        offers.add(offer(Items.DIAMOND, 2, Items.GOLDEN_CARROT, 8));
        offers.add(offer(Items.DIAMOND, 1, Items.COOKED_MUTTON, 16));
        offers.add(offer(Items.IRON_INGOT, 1, Items.BREAD, 8));
        offers.add(offer(Items.DIAMOND, 1, Items.IRON_INGOT, 6));
        offers.add(offer(Items.IRON_INGOT, 1, Items.OAK_LOG, 3));
        offers.add(offer(Items.DIAMOND, TownhallHorseTrade.PRICE_DIAMONDS, TownhallHorseTrade.createHorseEgg()));
        offers.add(offer(Items.DIAMOND, 24, Items.DRIED_GHAST, 1));
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
}
