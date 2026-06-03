package nrd.breached.block;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import nrd.breached.Breached;
import nrd.breached.reinforcement.ReinforcementManager;
import nrd.breached.reinforcement.ReinforcementTier;
import nrd.breached.screen.LandlockScreenHandler;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class LandlockBlockEntity extends BlockEntity implements Inventory, NamedScreenHandlerFactory {
    public static final int UPKEEP_INVENTORY_SIZE = 54;
    private static final int REAL_TIME_DAY_TICKS = 20 * 60 * 60 * 24;
    private static final int UPKEEP_TICK_INTERVAL = 20;
    private static final int UPKEEP_PROGRESS_SAVE_INTERVAL_TICKS = 20 * 60;
    private static final int CLAIM_COST_RECALC_INTERVAL_TICKS = 20 * 60 * 5;
    private static final String OWNER_UUID_KEY = "owner_uuid";
    private static final String OWNER_NAME_KEY = "owner_name";
    private static final String AUTHORIZED_PLAYERS_KEY = "authorized_players";
    private static final String CLAIM_CENTER_X_KEY = "claim_center_x";
    private static final String CLAIM_CENTER_Y_KEY = "claim_center_y";
    private static final String CLAIM_CENTER_Z_KEY = "claim_center_z";
    private static final String DECAYED_KEY = "decayed";
    private static final String UPKEEP_DRAIN_PROGRESS_KEY = "upkeep_drain_progress";
    private static final String UPKEEP_CREDIT_POINTS_KEY = "upkeep_credit_points";
    private static final String CACHED_CLAIM_COST_KEY = "cached_claim_cost";
    private static final String LAST_CLAIM_COST_SCAN_TIME_KEY = "last_claim_cost_scan_time";
    private static final String LAST_UPKEEP_TICK_KEY = "last_upkeep_tick";

    private UUID ownerUuid;
    private String ownerName;
    private BlockPos claimCenter;
    private final Set<UUID> authorizedPlayers = new HashSet<>();
    private final DefaultedList<ItemStack> upkeepInventory = DefaultedList.ofSize(UPKEEP_INVENTORY_SIZE, ItemStack.EMPTY);
    private boolean decayed;
    private double upkeepDrainProgress;
    private int upkeepCreditPoints;
    private int cachedClaimCost;
    private long lastClaimCostScanTime = Long.MIN_VALUE;
    private long lastUpkeepTick = Long.MIN_VALUE;
    private final PropertyDelegate upkeepProperties = new PropertyDelegate() {
        @Override
        public int get(int index) {
            return switch (index) {
                case LandlockScreenHandler.DECAYED_PROPERTY -> decayed ? 1 : 0;
                case LandlockScreenHandler.CLAIM_COST_PROPERTY -> getCachedClaimCost();
                case LandlockScreenHandler.DAILY_UPKEEP_PROPERTY -> getDailyUpkeepCost();
                case LandlockScreenHandler.STORED_UPKEEP_PROPERTY -> getStoredUpkeepPoints();
                case LandlockScreenHandler.MINUTES_UNTIL_DECAY_PROPERTY -> getMinutesUntilDecay();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {
        }

        @Override
        public int size() {
            return LandlockScreenHandler.PROPERTY_COUNT;
        }
    };

    public LandlockBlockEntity(BlockPos pos, BlockState state) {
        super(Breached.LANDLOCK_BLOCK_ENTITY, pos, state);
    }

    public static void tick(World world, BlockPos pos, BlockState state, LandlockBlockEntity landlock) {
        if (world.isClient() || world.getTime() % UPKEEP_TICK_INTERVAL != 0) {
            return;
        }

        landlock.tickUpkeep(world);
    }

    public static int getUpkeepValue(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }

        return getUpkeepValue(stack.getItem());
    }

    private static int getUpkeepValue(Item item) {
        if (item == Items.COPPER_BLOCK
                || item == Items.IRON_BLOCK
                || item == Items.GOLD_BLOCK
                || item == Items.DIAMOND_BLOCK
                || item == Items.EMERALD_BLOCK
                || item == Items.LAPIS_BLOCK
                || item == Items.REDSTONE_BLOCK) {
            return 9;
        }

        if (item == Items.COPPER_INGOT
                || item == Items.IRON_INGOT
                || item == Items.GOLD_INGOT
                || item == Items.DIAMOND
                || item == Items.EMERALD
                || item == Items.LAPIS_LAZULI
                || item == Items.REDSTONE) {
            return 1;
        }

        return 0;
    }

    public static boolean isUpkeepMaterial(ItemStack stack) {
        return getUpkeepValue(stack) > 0;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwner(PlayerEntity owner) {
        ownerUuid = owner.getUuid();
        ownerName = owner.getGameProfile().name();
        authorizedPlayers.add(ownerUuid);
        markDirty();
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
        authorizedPlayers.add(ownerUuid);
        markDirty();
    }

    public void updateOwnerName(PlayerEntity player) {
        if (!player.getUuid().equals(ownerUuid)) {
            return;
        }

        String name = player.getGameProfile().name();
        if (!name.equals(ownerName)) {
            ownerName = name;
            markDirty();
        }
    }

    public boolean isAuthorized(UUID playerUuid) {
        return playerUuid.equals(ownerUuid) || authorizedPlayers.contains(playerUuid);
    }

    public void addAuthorizedPlayer(UUID playerUuid) {
        authorizedPlayers.add(playerUuid);
        markDirty();
    }

    public boolean removeAuthorizedPlayer(UUID playerUuid) {
        if (playerUuid.equals(ownerUuid)) {
            return false;
        }

        boolean removed = authorizedPlayers.remove(playerUuid);
        if (removed) {
            markDirty();
        }

        return removed;
    }

    public Set<UUID> getAuthorizedPlayers() {
        return authorizedPlayers;
    }

    public BlockPos getClaimCenter() {
        return claimCenter == null ? getPos() : claimCenter;
    }

    public void setClaimCenter(BlockPos claimCenter) {
        this.claimCenter = claimCenter.toImmutable();
        refreshClaimCost();
        markDirty();
    }

    public boolean isDecayed() {
        return decayed;
    }

    public int getCachedClaimCost() {
        return cachedClaimCost;
    }

    public int getDailyUpkeepCost() {
        return Math.ceilDiv(Math.max(0, cachedClaimCost), 20);
    }

    public int getStoredUpkeepPoints() {
        int points = upkeepCreditPoints;
        for (ItemStack stack : upkeepInventory) {
            points += getUpkeepValue(stack) * stack.getCount();
        }
        return points;
    }

    public int getMinutesUntilDecay() {
        if (decayed) {
            return 0;
        }

        int dailyUpkeepCost = getDailyUpkeepCost();
        if (dailyUpkeepCost <= 0) {
            return -1;
        }

        double pointsUntilDecay = getStoredUpkeepPoints() + Math.max(0.0D, 1.0D - upkeepDrainProgress);
        double ticksUntilDecay = pointsUntilDecay * REAL_TIME_DAY_TICKS / dailyUpkeepCost;
        int minutesUntilDecay = Math.max(0, (int) Math.ceil(ticksUntilDecay / (20.0D * 60.0D)));
        if (minutesUntilDecay > LandlockScreenHandler.MAX_PROTECTED_DISPLAY_MINUTES) {
            return LandlockScreenHandler.PROTECTED_DISPLAY_OVERFLOW_MINUTES;
        }

        return minutesUntilDecay;
    }

    public void refreshClaimCost() {
        if (world == null) {
            return;
        }

        cachedClaimCost = calculateClaimCost(world, getClaimCenter());
        lastClaimCostScanTime = world.getTime();
        markDirty();
    }

    private void tickUpkeep(World world) {
        long elapsedUpkeepTicks = getElapsedUpkeepTicks(world);
        refreshClaimCostIfStale(world);

        if (decayed) {
            if (recoverFromDecayIfFunded()) {
                markDirty();
            } else if (shouldSaveUpkeepProgress(world)) {
                markDirty();
            }
            return;
        }

        int dailyUpkeepCost = getDailyUpkeepCost();
        if (dailyUpkeepCost <= 0) {
            if (upkeepDrainProgress != 0.0D) {
                upkeepDrainProgress = 0.0D;
                markDirty();
            } else if (shouldSaveUpkeepProgress(world)) {
                markDirty();
            }
            return;
        }

        upkeepDrainProgress += dailyUpkeepCost * (double) elapsedUpkeepTicks / REAL_TIME_DAY_TICKS;
        int pointsToConsume = (int) Math.min(Integer.MAX_VALUE, Math.floor(upkeepDrainProgress));
        boolean consumedUpkeep = pointsToConsume > 0;
        if (pointsToConsume > 0) {
            if (!consumeUpkeepPoints(pointsToConsume)) {
                decayed = true;
                upkeepDrainProgress = 0.0D;
                markDirty();
                return;
            }

            upkeepDrainProgress -= pointsToConsume;
        }

        if (consumedUpkeep || shouldSaveUpkeepProgress(world)) {
            markDirty();
        }
    }

    private long getElapsedUpkeepTicks(World world) {
        long currentTime = world.getTime();
        if (lastUpkeepTick == Long.MIN_VALUE || lastUpkeepTick > currentTime) {
            lastUpkeepTick = currentTime;
            markDirty();
            return 0L;
        }

        long elapsedTicks = currentTime - lastUpkeepTick;
        lastUpkeepTick = currentTime;
        return Math.max(0L, elapsedTicks);
    }

    private void refreshClaimCostIfStale(World world) {
        if (cachedClaimCost <= 0 || world.getTime() - lastClaimCostScanTime >= CLAIM_COST_RECALC_INTERVAL_TICKS) {
            refreshClaimCost();
        }
    }

    private int calculateClaimCost(World world, BlockPos center) {
        int cost = 0;
        BlockPos.Mutable blockPos = new BlockPos.Mutable();
        for (int x = center.getX() - 8; x <= center.getX() + 8; x++) {
            for (int y = center.getY() - 8; y <= center.getY() + 8; y++) {
                for (int z = center.getZ() - 8; z <= center.getZ() + 8; z++) {
                    blockPos.set(x, y, z);
                    BlockState state = world.getBlockState(blockPos);
                    if (state.isAir() || state.isOf(Blocks.WATER) || state.isOf(Blocks.LAVA)) {
                        continue;
                    }

                    cost += ReinforcementManager.getTier(world, blockPos, state)
                            .map(this::getReinforcedBlockClaimCost)
                            .orElse(1);
                }
            }
        }

        return cost;
    }

    private boolean shouldSaveUpkeepProgress(World world) {
        return Math.floorMod(world.getTime() + getPos().asLong(), UPKEEP_PROGRESS_SAVE_INTERVAL_TICKS) == 0;
    }

    private int getReinforcedBlockClaimCost(ReinforcementTier tier) {
        return switch (tier) {
            case WOOD -> 2;
            case IRON -> 4;
            case DIAMOND -> 8;
            case NETHERITE -> 16;
        };
    }

    private boolean consumeUpkeepPoints(int points) {
        if (points <= 0) {
            return true;
        }

        if (upkeepCreditPoints >= points) {
            upkeepCreditPoints -= points;
            return true;
        }

        points -= upkeepCreditPoints;
        upkeepCreditPoints = 0;

        for (int slot = 0; slot < upkeepInventory.size(); slot++) {
            ItemStack stack = upkeepInventory.get(slot);
            int value = getUpkeepValue(stack);
            if (value <= 0) {
                continue;
            }

            int itemsToConsume = Math.min(stack.getCount(), Math.ceilDiv(points, value));
            stack.decrement(itemsToConsume);
            if (stack.isEmpty()) {
                upkeepInventory.set(slot, ItemStack.EMPTY);
            }

            int consumedValue = itemsToConsume * value;
            points -= consumedValue;
            if (points <= 0) {
                upkeepCreditPoints += -points;
                return true;
            }
        }

        return false;
    }

    private boolean recoverFromDecayIfFunded() {
        if (!decayed || getStoredUpkeepPoints() <= 0 || !consumeUpkeepPoints(1)) {
            return false;
        }

        decayed = false;
        upkeepDrainProgress = 0.0D;
        return true;
    }

    @Override
    public Text getDisplayName() {
        if (ownerName == null || ownerName.isBlank()) {
            return Text.translatable("block.breached.landlock_block");
        }

        return Text.literal(ownerName + "'s Landlock Block");
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        refreshClaimCost();
        return new LandlockScreenHandler(syncId, playerInventory, this, upkeepProperties);
    }

    @Override
    public int size() {
        return upkeepInventory.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : upkeepInventory) {
            if (!stack.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public ItemStack getStack(int slot) {
        return upkeepInventory.get(slot);
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        ItemStack stack = Inventories.splitStack(upkeepInventory, slot, amount);
        if (!stack.isEmpty()) {
            markDirty();
        }

        return stack;
    }

    @Override
    public ItemStack removeStack(int slot) {
        ItemStack stack = Inventories.removeStack(upkeepInventory, slot);
        if (!stack.isEmpty()) {
            markDirty();
        }

        return stack;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        upkeepInventory.set(slot, stack);
        stack.capCount(getMaxCount(stack));
        recoverFromDecayIfFunded();
        markDirty();
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        return Inventory.canPlayerUse(this, player);
    }

    @Override
    public boolean isValid(int slot, ItemStack stack) {
        return isUpkeepMaterial(stack);
    }

    @Override
    public void clear() {
        upkeepInventory.clear();
        markDirty();
    }

    @Override
    public void markDirty() {
        super.markDirty();
    }

    @Override
    protected void readData(ReadView view) {
        super.readData(view);
        ownerUuid = view.getOptionalString(OWNER_UUID_KEY)
                .map(LandlockBlockEntity::parseUuid)
                .orElse(null);
        ownerName = view.getOptionalString(OWNER_NAME_KEY)
                .filter(name -> !name.isBlank())
                .orElse(null);
        claimCenter = new BlockPos(
                view.getInt(CLAIM_CENTER_X_KEY, getPos().getX()),
                view.getInt(CLAIM_CENTER_Y_KEY, getPos().getY()),
                view.getInt(CLAIM_CENTER_Z_KEY, getPos().getZ())
        );
        authorizedPlayers.clear();
        authorizedPlayers.addAll(view.read(AUTHORIZED_PLAYERS_KEY, Uuids.SET_CODEC).orElse(Set.of()));
        Inventories.readData(view, upkeepInventory);
        decayed = view.getBoolean(DECAYED_KEY, false);
        upkeepDrainProgress = view.getDouble(UPKEEP_DRAIN_PROGRESS_KEY, 0.0D);
        upkeepCreditPoints = Math.max(0, view.getInt(UPKEEP_CREDIT_POINTS_KEY, 0));
        cachedClaimCost = Math.max(0, view.getInt(CACHED_CLAIM_COST_KEY, 0));
        lastClaimCostScanTime = view.getLong(LAST_CLAIM_COST_SCAN_TIME_KEY, Long.MIN_VALUE);
        lastUpkeepTick = view.getLong(LAST_UPKEEP_TICK_KEY, Long.MIN_VALUE);
    }

    @Override
    protected void writeData(WriteView view) {
        super.writeData(view);

        if (ownerUuid != null) {
            view.putString(OWNER_UUID_KEY, ownerUuid.toString());
        }

        if (ownerName != null && !ownerName.isBlank()) {
            view.putString(OWNER_NAME_KEY, ownerName);
        }

        view.put(AUTHORIZED_PLAYERS_KEY, Uuids.SET_CODEC, authorizedPlayers);

        BlockPos center = getClaimCenter();
        view.putInt(CLAIM_CENTER_X_KEY, center.getX());
        view.putInt(CLAIM_CENTER_Y_KEY, center.getY());
        view.putInt(CLAIM_CENTER_Z_KEY, center.getZ());
        Inventories.writeData(view, upkeepInventory);
        view.putBoolean(DECAYED_KEY, decayed);
        view.putDouble(UPKEEP_DRAIN_PROGRESS_KEY, upkeepDrainProgress);
        view.putInt(UPKEEP_CREDIT_POINTS_KEY, upkeepCreditPoints);
        view.putInt(CACHED_CLAIM_COST_KEY, cachedClaimCost);
        view.putLong(LAST_CLAIM_COST_SCAN_TIME_KEY, lastClaimCostScanTime);
        view.putLong(LAST_UPKEEP_TICK_KEY, lastUpkeepTick);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
