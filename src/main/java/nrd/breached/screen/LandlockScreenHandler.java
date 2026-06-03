package nrd.breached.screen;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import nrd.breached.Breached;
import nrd.breached.block.LandlockBlockEntity;

public class LandlockScreenHandler extends ScreenHandler {
    public static final int UPKEEP_ROWS = 6;
    public static final int UPKEEP_COLUMNS = 9;
    public static final int UPKEEP_SLOT_COUNT = UPKEEP_ROWS * UPKEEP_COLUMNS;
    public static final int DECAYED_PROPERTY = 0;
    public static final int CLAIM_COST_PROPERTY = 1;
    public static final int DAILY_UPKEEP_PROPERTY = 2;
    public static final int STORED_UPKEEP_PROPERTY = 3;
    public static final int MINUTES_UNTIL_DECAY_PROPERTY = 4;
    public static final int PROPERTY_COUNT = 5;
    public static final int MAX_PROTECTED_DISPLAY_DAYS = 20;
    public static final int MAX_PROTECTED_DISPLAY_MINUTES = MAX_PROTECTED_DISPLAY_DAYS * 24 * 60;
    public static final int PROTECTED_DISPLAY_OVERFLOW_MINUTES = MAX_PROTECTED_DISPLAY_MINUTES + 1;
    public static final int UPKEEP_INVENTORY_X = 8;
    public static final int UPKEEP_INVENTORY_Y = 56;
    public static final int PLAYER_INVENTORY_X = 8;
    public static final int PLAYER_INVENTORY_Y = 180;
    public static final int PLAYER_HOTBAR_Y = 238;

    private final Inventory inventory;
    private final PropertyDelegate properties;

    public LandlockScreenHandler(int syncId, PlayerInventory playerInventory) {
        this(syncId, playerInventory, new SimpleInventory(UPKEEP_SLOT_COUNT), new ArrayPropertyDelegate(PROPERTY_COUNT));
    }

    public LandlockScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory, PropertyDelegate properties) {
        super(Breached.LANDLOCK_SCREEN_HANDLER, syncId);
        checkSize(inventory, UPKEEP_SLOT_COUNT);
        checkDataCount(properties, PROPERTY_COUNT);
        this.inventory = inventory;
        this.properties = properties;

        inventory.onOpen(playerInventory.player);
        addUpkeepInventorySlots(inventory);
        addPlayerInventorySlots(playerInventory);
        addProperties(properties);
    }

    public boolean isDecayed() {
        return properties.get(DECAYED_PROPERTY) != 0;
    }

    public int getClaimCost() {
        return properties.get(CLAIM_COST_PROPERTY);
    }

    public int getDailyUpkeepCost() {
        return properties.get(DAILY_UPKEEP_PROPERTY);
    }

    public int getStoredUpkeepPoints() {
        return properties.get(STORED_UPKEEP_PROPERTY);
    }

    public int getMinutesUntilDecay() {
        return properties.get(MINUTES_UNTIL_DECAY_PROPERTY);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slotIndex) {
        ItemStack movedStack = ItemStack.EMPTY;
        Slot slot = slots.get(slotIndex);
        if (!slot.hasStack()) {
            return movedStack;
        }

        ItemStack originalStack = slot.getStack();
        movedStack = originalStack.copy();
        if (slotIndex < UPKEEP_SLOT_COUNT) {
            if (!insertItem(originalStack, UPKEEP_SLOT_COUNT, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!LandlockBlockEntity.isUpkeepMaterial(originalStack)
                    || !insertItem(originalStack, 0, UPKEEP_SLOT_COUNT, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (originalStack.isEmpty()) {
            slot.setStack(ItemStack.EMPTY);
        } else {
            slot.markDirty();
        }

        return movedStack;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return inventory.canPlayerUse(player);
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        inventory.onClose(player);
    }

    private void addUpkeepInventorySlots(Inventory inventory) {
        for (int row = 0; row < UPKEEP_ROWS; row++) {
            for (int column = 0; column < UPKEEP_COLUMNS; column++) {
                addSlot(new UpkeepSlot(
                        inventory,
                        column + row * UPKEEP_COLUMNS,
                        UPKEEP_INVENTORY_X + column * 18,
                        UPKEEP_INVENTORY_Y + row * 18
                ));
            }
        }
    }

    private void addPlayerInventorySlots(PlayerInventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(
                        playerInventory,
                        column + row * 9 + 9,
                        PLAYER_INVENTORY_X + column * 18,
                        PLAYER_INVENTORY_Y + row * 18
                ));
            }
        }

        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column, PLAYER_INVENTORY_X + column * 18, PLAYER_HOTBAR_Y));
        }
    }

    private static class UpkeepSlot extends Slot {
        private UpkeepSlot(Inventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return LandlockBlockEntity.isUpkeepMaterial(stack);
        }
    }
}
