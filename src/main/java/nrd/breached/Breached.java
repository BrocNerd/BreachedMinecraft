package nrd.breached;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ToolMaterial;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import nrd.breached.block.DiamondCraftingTableBlock;
import nrd.breached.block.IronCraftingTableBlock;
import nrd.breached.block.LandlockBlock;
import nrd.breached.block.LandlockBlockEntity;
import nrd.breached.block.NetheriteCraftingTableBlock;
import nrd.breached.config.BreachedConfig;
import nrd.breached.item.BreachedArchiveItem;
import nrd.breached.item.BreacherItem;
import nrd.breached.landlock.LandlockClaimManager;
import nrd.breached.map.BreachedMapSnapshotManager;
import nrd.breached.network.OpenBreachedArchivePayload;
import nrd.breached.network.OpenBreachedMapPayload;
import nrd.breached.network.ReinforcementOutlinePayload;
import nrd.breached.network.RequestBreachedMapPayload;
import nrd.breached.reinforcement.ReinforcementManager;
import nrd.breached.reinforcement.ReinforcementTier;
import nrd.breached.respawn.RespawnCooldownManager;
import nrd.breached.team.TeamCommands;
import nrd.breached.team.TeamData;
import nrd.breached.team.TeamState;
import nrd.breached.worldgen.BreachedDimensionRules;
import nrd.breached.worldgen.BreachedStructurePlacementManager;

import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class Breached implements ModInitializer {
    public static final String MOD_ID = "breached";
    private static final int REINFORCEMENT_OUTLINE_RADIUS = 16;
    private static final int REINFORCEMENT_OUTLINE_SYNC_INTERVAL_TICKS = 5;
    private static final Map<UUID, Long> LAST_VILLAGER_TRADE_MESSAGE_TICKS = new HashMap<>();
    private static final Map<UUID, ProbeLandlockSelection> PROBE_LANDLOCK_SELECTIONS = new HashMap<>();
    private static final Map<UUID, ReinforcementOutlineTarget> REINFORCEMENT_OUTLINE_TARGETS = new HashMap<>();
    private static final int BREACHED_MAP_TEAMMATE_COLOR = 0xFF5AE6FF;

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
            settings -> new BreacherItem(settings, BreacherItem.IRON_BLOCK_BREAKING_DELTA, ToolMaterial.IRON),
            new Item.Settings().maxDamage(32)
    );

    public static final Item DIAMOND_BREACHER = registerItem(
            "diamond_breacher",
            settings -> new BreacherItem(settings, BreacherItem.DIAMOND_BLOCK_BREAKING_DELTA, ToolMaterial.DIAMOND),
            new Item.Settings().maxDamage(256)
    );

    public static final Item NETHERITE_BREACHER = registerItem(
            "netherite_breacher",
            settings -> new BreacherItem(settings, BreacherItem.NETHERITE_BLOCK_BREAKING_DELTA, ToolMaterial.NETHERITE),
            new Item.Settings().maxDamage(1024).fireproof()
    );

    public static final Item PROBE = registerItem(
            "probe",
            Item::new,
            new Item.Settings()
    );

    public static final Item REINFORCER = registerItem(
            "reinforcer",
            Item::new,
            new Item.Settings()
    );

    public static final Item BREACHED_ARCHIVE = registerItem(
            "breached_archive",
            BreachedArchiveItem::new,
            new Item.Settings().maxCount(1)
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
        BreachedConfig.load();
        PayloadTypeRegistry.playS2C().register(ReinforcementOutlinePayload.ID, ReinforcementOutlinePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenBreachedArchivePayload.ID, OpenBreachedArchivePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenBreachedMapPayload.ID, OpenBreachedMapPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestBreachedMapPayload.ID, RequestBreachedMapPayload.CODEC);
        TeamCommands.register();
        registerLandlockDebugEvents();
        registerLandlockProtectionEvents();
        registerLandlockPlacementProtectionEvents();
        registerLandlockDoorProtectionEvents();
        registerRespawnEvents();
        registerStarterItemEvents();
        registerBreachedMapNetworking();
        registerVillagerTradingLock();
        BreachedDimensionRules.register();
        LowYHealthLimitManager.register();
        BreachedMapSnapshotManager.register();
        registerReinforcementEvents();
        registerReinforcementOutlineSync();

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
            entries.add(REINFORCER);
            entries.add(BREACHED_ARCHIVE);
        });
    }

    private static void registerRespawnEvents() {
        EntitySleepEvents.ALLOW_RESETTING_TIME.register(player -> false);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> RespawnCooldownManager.applyPendingBedRespawnCooldown(newPlayer));
    }

    private static void registerStarterItemEvents() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            giveArchiveIfMissing(handler.player);
        });
    }

    private static void registerBreachedMapNetworking() {
        ServerPlayNetworking.registerGlobalReceiver(RequestBreachedMapPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            MinecraftServer server = player.getEntityWorld().getServer();
            if (server == null || !BreachedDimensionRules.isBreachedIslandWorld(server)) {
                return;
            }

            ServerWorld overworld = server.getOverworld();
            int borderSize = Math.max(1, (int) Math.round(overworld.getWorldBorder().getSize()));
            BreachedMapSnapshotManager.TerrainSnapshot terrainSnapshot = BreachedMapSnapshotManager.getTerrainSnapshot(overworld, borderSize);
            List<OpenBreachedMapPayload.Marker> markers = BreachedStructurePlacementManager.getBreachedMapMarkers(overworld)
                    .stream()
                    .map(marker -> new OpenBreachedMapPayload.Marker(
                            marker.label(),
                            marker.x(),
                            marker.z(),
                            marker.color()
                    ))
                    .toList();
            List<OpenBreachedMapPayload.Teammate> teammates = getBreachedMapTeammates(player, server, overworld);

            ServerPlayNetworking.send(player, new OpenBreachedMapPayload(
                    borderSize,
                    (int) Math.round(player.getX()),
                    (int) Math.round(player.getZ()),
                    terrainSnapshot.resolution(),
                    terrainSnapshot.colors(),
                    markers,
                    teammates
            ));
        });
    }

    private static List<OpenBreachedMapPayload.Teammate> getBreachedMapTeammates(ServerPlayerEntity player, MinecraftServer server, ServerWorld overworld) {
        TeamData team = TeamState.get(server).getPlayerTeam(player.getUuid()).orElse(null);
        if (team == null) {
            return List.of();
        }

        return server.getPlayerManager().getPlayerList()
                .stream()
                .filter(teammate -> !teammate.getUuid().equals(player.getUuid()))
                .filter(teammate -> teammate.getEntityWorld() == overworld)
                .filter(teammate -> team.hasMember(teammate.getUuid()))
                .map(teammate -> new OpenBreachedMapPayload.Teammate(
                        teammate.getGameProfile().name(),
                        (int) Math.round(teammate.getX()),
                        (int) Math.round(teammate.getZ()),
                        BREACHED_MAP_TEAMMATE_COLOR
                ))
                .toList();
    }

    private static void giveArchiveIfMissing(ServerPlayerEntity player) {
        if (hasArchive(player)) {
            return;
        }

        player.giveOrDropStack(new ItemStack(BREACHED_ARCHIVE));
    }

    private static boolean hasArchive(ServerPlayerEntity player) {
        for (int slot = 0; slot < player.getInventory().size(); slot++) {
            if (player.getInventory().getStack(slot).isOf(BREACHED_ARCHIVE)) {
                return true;
            }
        }

        return false;
    }

    private static void registerVillagerTradingLock() {
        // TODO: Revisit village and structure loot table balance after custom POIs are designed.
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() || hand != Hand.MAIN_HAND || !(entity instanceof VillagerEntity || entity instanceof WanderingTraderEntity)) {
                return ActionResult.PASS;
            }

            long worldTime = world.getTime();
            Long lastMessageTime = LAST_VILLAGER_TRADE_MESSAGE_TICKS.get(player.getUuid());
            if (lastMessageTime != null && worldTime - lastMessageTime <= 1) {
                return ActionResult.SUCCESS;
            }

            LAST_VILLAGER_TRADE_MESSAGE_TICKS.put(player.getUuid(), worldTime);
            player.sendMessage(Text.literal("Villager trading is disabled in Breached."), false);
            return ActionResult.SUCCESS;
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

    private static boolean canBreachBlock(ItemStack stack, net.minecraft.block.BlockState state) {
        return stack.getItem() instanceof BreacherItem
                && !stack.isEmpty()
                && !stack.willBreakNextUse()
                && !BreacherItem.isBlockedBlock(state);
    }

    private static void registerReinforcementEvents() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            ItemStack mainHandStack = player.getMainHandStack();
            if (hand != Hand.MAIN_HAND || !mainHandStack.isOf(REINFORCER)) {
                return ActionResult.PASS;
            }

            if (world.isClient()) {
                return ActionResult.SUCCESS;
            }

            BlockPos targetPos = hitResult.getBlockPos();
            net.minecraft.block.BlockState targetState = world.getBlockState(targetPos);
            if (targetState.isOf(LANDLOCK_BLOCK)) {
                player.sendMessage(Text.literal("Landlock Blocks are already wood reinforced and cannot be reinforced higher."), false);
                return ActionResult.SUCCESS;
            }

            if (!ReinforcementManager.canStoreReinforcement(targetState)) {
                player.sendMessage(Text.literal("This block cannot be reinforced."), false);
                return ActionResult.SUCCESS;
            }

            if (!LandlockClaimManager.isInsideAnyClaim(world, targetPos)) {
                player.sendMessage(Text.literal("Blocks can only be reinforced inside a Landlock claim."), false);
                return ActionResult.SUCCESS;
            }

            if (!LandlockClaimManager.canPlayerModify(world, player, targetPos)) {
                player.sendMessage(Text.literal("You must be authorized on this Landlock to reinforce blocks."), false);
                return ActionResult.SUCCESS;
            }

            ItemStack materialStack = player.getOffHandStack();
            java.util.Optional<ReinforcementTier> requestedTier = ReinforcementTier.fromMaterial(materialStack);
            if (requestedTier.isEmpty()) {
                player.sendMessage(Text.literal("Hold 16 logs, 4 iron blocks, or 2 diamond blocks in your offhand."), false);
                return ActionResult.SUCCESS;
            }

            ReinforcementTier tier = requestedTier.get();
            if (!tier.hasMaterialCost(materialStack)) {
                player.sendMessage(Text.literal("Need " + tier.materialDescription() + " to add " + tier.displayName() + " reinforcement."), false);
                return ActionResult.SUCCESS;
            }

            java.util.Optional<ReinforcementTier> currentTier = ReinforcementManager.getTier(world, targetPos, targetState);
            if (currentTier.isPresent() && currentTier.get().level() >= tier.level()) {
                player.sendMessage(Text.literal("This block is already " + currentTier.get().displayName() + " reinforced."), false);
                return ActionResult.SUCCESS;
            }

            ReinforcementManager.setTier((ServerWorld) world, targetPos, tier);
            if (!player.isCreative()) {
                materialStack.decrement(tier.materialCost());
            }

            player.sendMessage(Text.literal("Added " + tier.displayName() + " reinforcement."), false);
            return ActionResult.SUCCESS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient()
                    || player.isCreative()
                    || !(world instanceof ServerWorld serverWorld)
                    || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return true;
            }

            ItemStack stack = player.getMainHandStack();
            java.util.Optional<ReinforcementTier> reinforcementTier = ReinforcementManager.getTier(world, pos, state);
            if (reinforcementTier.isEmpty()) {
                return true;
            }

            if (stack.isOf(REINFORCER)) {
                if (state.isOf(LANDLOCK_BLOCK)) {
                    if (isLandlockOwner(world, pos, blockEntity, player)) {
                        return true;
                    }

                    player.sendMessage(Text.literal("Only the Landlock owner can break it with a Reinforcer."), false);
                    return false;
                }

                if (!LandlockClaimManager.canPlayerModify(world, player, pos)) {
                    player.sendMessage(Text.literal("You must be authorized on this Landlock to remove reinforcement."), false);
                    return false;
                }

                ReinforcementManager.removeStoredTier(serverWorld, pos);
                player.sendMessage(Text.literal("Removed " + reinforcementTier.get().displayName() + " reinforcement."), false);
                return false;
            }

            if (stack.getItem() instanceof BreacherItem) {
                if (ReinforcementManager.hasEnoughDurability(stack, world, pos, state)) {
                    return true;
                }

                ReinforcementManager.breakBreacher(stack, serverWorld, serverPlayer);
                player.sendMessage(Text.literal("Your Breacher broke against the reinforced block."), false);
                return false;
            }

            if (state.isOf(LANDLOCK_BLOCK) && isLandlockOwner(world, pos, blockEntity, player)) {
                player.sendMessage(Text.literal("You must use a Reinforcer to break this Landlock and get it back."), false);
                return false;
            }

            player.sendMessage(Text.literal("Reinforced blocks require a Breacher to break or a Reinforcer to unreinforce."), false);
            return false;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!(world instanceof ServerWorld serverWorld)) {
                return;
            }

            if (player.getMainHandStack().getItem() instanceof BreacherItem && !player.isCreative()) {
                return;
            }

            ReinforcementManager.removeStoredTier(serverWorld, pos);
        });
    }

    private static boolean isLandlockOwner(World world, BlockPos pos, BlockEntity blockEntity, PlayerEntity player) {
        BlockEntity resolvedBlockEntity = blockEntity != null ? blockEntity : world.getBlockEntity(pos);
        return resolvedBlockEntity instanceof LandlockBlockEntity landlock
                && player.getUuid().equals(landlock.getOwnerUuid());
    }

    private static void registerReinforcementOutlineSync() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                REINFORCEMENT_OUTLINE_TARGETS.remove(handler.player.getUuid()));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % REINFORCEMENT_OUTLINE_SYNC_INTERVAL_TICKS != 0) {
                return;
            }

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                syncReinforcementOutlineTargets(player);
            }
        });
    }

    private static void syncReinforcementOutlineTargets(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, ReinforcementOutlinePayload.ID)) {
            REINFORCEMENT_OUTLINE_TARGETS.remove(player.getUuid());
            return;
        }

        ItemStack mainHandStack = player.getMainHandStack();
        if (!isReinforcementOutlineTool(mainHandStack)) {
            sendReinforcementOutlineTargets(player, ReinforcementOutlineTarget.empty());
            return;
        }

        Map<BlockPos, ReinforcementTier> nearbyTiers = ReinforcementManager.getVisibleTiersWithin(
                player.getEntityWorld(),
                player.getBlockPos(),
                REINFORCEMENT_OUTLINE_RADIUS
        );
        List<ReinforcementOutlinePayload.Entry> entries = nearbyTiers.entrySet()
                .stream()
                .map(entry -> new ReinforcementOutlinePayload.Entry(entry.getKey().toImmutable(), entry.getValue().level()))
                .sorted(Comparator
                        .comparingInt((ReinforcementOutlinePayload.Entry entry) -> squaredDistance(entry.pos(), player.getBlockPos()))
                        .thenComparingInt(entry -> entry.pos().getX())
                        .thenComparingInt(entry -> entry.pos().getY())
                        .thenComparingInt(entry -> entry.pos().getZ()))
                .toList();

        sendReinforcementOutlineTargets(player, new ReinforcementOutlineTarget(entries));
    }

    private static boolean isReinforcementOutlineTool(ItemStack stack) {
        return stack.isOf(REINFORCER) || stack.getItem() instanceof BreacherItem;
    }

    private static void sendReinforcementOutlineTargets(ServerPlayerEntity player, ReinforcementOutlineTarget target) {
        UUID playerId = player.getUuid();
        ReinforcementOutlineTarget currentTarget = REINFORCEMENT_OUTLINE_TARGETS.get(playerId);
        if (currentTarget == null && target.isEmpty()) {
            return;
        }

        if (target.equals(currentTarget)) {
            return;
        }

        if (target.isEmpty()) {
            ServerPlayNetworking.send(player, ReinforcementOutlinePayload.empty());
            REINFORCEMENT_OUTLINE_TARGETS.remove(playerId);
            return;
        }

        ServerPlayNetworking.send(player, new ReinforcementOutlinePayload(target.entries()));
        REINFORCEMENT_OUTLINE_TARGETS.put(playerId, target);
    }

    private static int squaredDistance(BlockPos pos, BlockPos center) {
        int dx = pos.getX() - center.getX();
        int dy = pos.getY() - center.getY();
        int dz = pos.getZ() - center.getZ();
        return dx * dx + dy * dy + dz * dz;
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
                if (placementContext.getBlockPos().getY() < LandlockClaimManager.MIN_LANDLOCK_PLACEMENT_Y) {
                    player.sendMessage(Text.literal("Landlock Blocks cannot be placed below Y " + LandlockClaimManager.MIN_LANDLOCK_PLACEMENT_Y + "."), false);
                    return ActionResult.FAIL;
                }

                if (LandlockClaimManager.countPlayerLandlockAuthorizations(world, player.getUuid()) >= LandlockClaimManager.MAX_AUTHORIZED_LANDLOCKS) {
                    player.sendMessage(Text.literal("You are already authorized on the maximum number of Landlocks."), false);
                    return ActionResult.FAIL;
                }

                if (BreachedStructurePlacementManager.isInsideMajorStructureLandlockExclusion(world, placementContext.getBlockPos())) {
                    player.sendMessage(Text.literal("Landlock Blocks cannot be placed within 12 blocks of a protected major structure."), false);
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
            if (world.isClient() || hand != Hand.MAIN_HAND || !player.getMainHandStack().isOf(PROBE)) {
                return ActionResult.PASS;
            }

            BlockPos targetPos = hitResult.getBlockPos();
            if (player.isSneaking() && world.getBlockState(targetPos).isOf(LANDLOCK_BLOCK)) {
                selectLandlockForProbeConfiguration(player, world, targetPos);
                return ActionResult.SUCCESS;
            }

            if (!player.isSneaking() && trySetSelectedLandlockClaimCenter(player, world, targetPos)) {
                return ActionResult.SUCCESS;
            }

            if (LandlockClaimManager.isInsideAnyClaim(world, targetPos)) {
                player.sendMessage(Text.literal("This block is inside a Landlock claim."), false);
            } else {
                player.sendMessage(Text.literal("This block is not claimed."), false);
            }

            return ActionResult.SUCCESS;
        });
    }

    private static void selectLandlockForProbeConfiguration(PlayerEntity player, World world, BlockPos landlockPos) {
        if (!(world.getBlockEntity(landlockPos) instanceof LandlockBlockEntity landlock)) {
            player.sendMessage(Text.literal("This Landlock has no claim data."), false);
            return;
        }

        if (!landlock.isAuthorized(player.getUuid())) {
            player.sendMessage(Text.literal("You must be authorized on this Landlock to configure it."), false);
            return;
        }

        PROBE_LANDLOCK_SELECTIONS.put(player.getUuid(), new ProbeLandlockSelection(world.getRegistryKey(), landlockPos.toImmutable()));
        player.sendMessage(Text.literal("Selected this Landlock. Right-click a block inside its claim with the Probe to set the claim center."), false);
    }

    private static boolean trySetSelectedLandlockClaimCenter(PlayerEntity player, World world, BlockPos newClaimCenter) {
        ProbeLandlockSelection selection = PROBE_LANDLOCK_SELECTIONS.get(player.getUuid());
        if (selection == null) {
            return false;
        }

        if (!selection.worldKey().equals(world.getRegistryKey())) {
            PROBE_LANDLOCK_SELECTIONS.remove(player.getUuid());
            player.sendMessage(Text.literal("Selected Landlock is in another dimension."), false);
            return true;
        }

        if (!(world.getBlockEntity(selection.landlockPos()) instanceof LandlockBlockEntity landlock)) {
            PROBE_LANDLOCK_SELECTIONS.remove(player.getUuid());
            player.sendMessage(Text.literal("Selected Landlock no longer exists."), false);
            return true;
        }

        if (!landlock.isAuthorized(player.getUuid())) {
            PROBE_LANDLOCK_SELECTIONS.remove(player.getUuid());
            player.sendMessage(Text.literal("You are no longer authorized on the selected Landlock."), false);
            return true;
        }

        if (!LandlockClaimManager.canSetClaimCenter(landlock, newClaimCenter)) {
            player.sendMessage(Text.literal("New claim center must be inside the selected Landlock claim and keep the Landlock protected."), false);
            return true;
        }

        landlock.setClaimCenter(newClaimCenter);
        PROBE_LANDLOCK_SELECTIONS.remove(player.getUuid());
        player.sendMessage(Text.literal("Landlock claim center set to x "
                + newClaimCenter.getX()
                + ", y " + newClaimCenter.getY()
                + ", z " + newClaimCenter.getZ() + "."), false);
        return true;
    }

    private record ProbeLandlockSelection(RegistryKey<World> worldKey, BlockPos landlockPos) {
    }

    private record ReinforcementOutlineTarget(List<ReinforcementOutlinePayload.Entry> entries) {
        private ReinforcementOutlineTarget {
            entries = List.copyOf(entries);
        }

        private static ReinforcementOutlineTarget empty() {
            return new ReinforcementOutlineTarget(List.of());
        }

        private boolean isEmpty() {
            return entries.isEmpty();
        }
    }
}
