package nrd.breached.client.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import nrd.breached.screen.LandlockScreenHandler;

public class LandlockScreen extends HandledScreen<LandlockScreenHandler> {
    private static final int BACKGROUND_COLOR = 0xFF20242B;
    private static final int PANEL_COLOR = 0xFF2A3038;
    private static final int SLOT_BORDER_COLOR = 0xFF111418;
    private static final int SLOT_COLOR = 0xFF39414C;
    private static final int TEXT_COLOR = 0xFFE8EEF6;
    private static final int MUTED_TEXT_COLOR = 0xFFB7C0CC;
    private static final int STABLE_COLOR = 0xFF7CFF8A;
    private static final int DECAYED_COLOR = 0xFFFF5A52;
    private static final int LEFT_STATUS_X = 8;
    private static final int RIGHT_STATUS_X = 84;

    public LandlockScreen(LandlockScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        backgroundWidth = 176;
        backgroundHeight = 264;
        titleX = 8;
        titleY = 7;
        playerInventoryTitleX = LandlockScreenHandler.PLAYER_INVENTORY_X;
        playerInventoryTitleY = LandlockScreenHandler.PLAYER_INVENTORY_Y - 11;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }

    @Override
    protected void drawBackground(DrawContext context, float deltaTicks, int mouseX, int mouseY) {
        context.fill(x, y, x + backgroundWidth, y + backgroundHeight, BACKGROUND_COLOR);
        context.fill(x + 4, y + 4, x + backgroundWidth - 4, y + 52, PANEL_COLOR);
        drawSlotGrid(context, LandlockScreenHandler.UPKEEP_INVENTORY_X, LandlockScreenHandler.UPKEEP_INVENTORY_Y, LandlockScreenHandler.UPKEEP_COLUMNS, LandlockScreenHandler.UPKEEP_ROWS);
        drawSlotGrid(context, LandlockScreenHandler.PLAYER_INVENTORY_X, LandlockScreenHandler.PLAYER_INVENTORY_Y, 9, 3);
        drawSlotGrid(context, LandlockScreenHandler.PLAYER_INVENTORY_X, LandlockScreenHandler.PLAYER_HOTBAR_Y, 9, 1);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        context.drawText(textRenderer, title, titleX, titleY, TEXT_COLOR, false);
        String state = handler.isDecayed() ? "Decayed" : "Stable";
        int stateColor = handler.isDecayed() ? DECAYED_COLOR : STABLE_COLOR;
        context.drawText(textRenderer, "State: " + state, 8, 20, stateColor, false);
        context.drawText(textRenderer, "Size: " + handler.getClaimCost(), LEFT_STATUS_X, 32, MUTED_TEXT_COLOR, false);
        context.drawText(textRenderer, "Cost: " + handler.getDailyUpkeepCost() + "/day", RIGHT_STATUS_X, 32, MUTED_TEXT_COLOR, false);
        context.drawText(textRenderer, "Stored: " + formatUpkeepPoints(handler.getStoredUpkeepUnits()), LEFT_STATUS_X, 44, MUTED_TEXT_COLOR, false);
        context.drawText(textRenderer, "Protected: " + getTimeUntilDecayText(), RIGHT_STATUS_X, 44, MUTED_TEXT_COLOR, false);
        context.drawText(textRenderer, playerInventoryTitle, playerInventoryTitleX, playerInventoryTitleY, TEXT_COLOR, false);
    }

    private void drawSlotGrid(DrawContext context, int relativeX, int relativeY, int columns, int rows) {
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int slotX = x + relativeX + column * 18;
                int slotY = y + relativeY + row * 18;
                context.fill(slotX - 1, slotY - 1, slotX + 17, slotY + 17, SLOT_BORDER_COLOR);
                context.fill(slotX, slotY, slotX + 16, slotY + 16, SLOT_COLOR);
            }
        }
    }

    private String getTimeUntilDecayText() {
        int minutes = handler.getMinutesUntilDecay();
        if (handler.isDecayed()) {
            return "Decayed";
        }

        if (minutes < 0) {
            return "None";
        }

        if (minutes > LandlockScreenHandler.MAX_PROTECTED_DISPLAY_MINUTES) {
            return LandlockScreenHandler.MAX_PROTECTED_DISPLAY_DAYS + "d+";
        }

        if (minutes < 60) {
            return minutes + "m";
        }

        int hours = minutes / 60;
        if (hours < 24) {
            return hours + "h";
        }

        return hours / 24 + "d";
    }

    private static String formatUpkeepPoints(int units) {
        int points = Math.max(0, units);
        int whole = points / 100;
        int fraction = points % 100;
        if (fraction == 0) {
            return Integer.toString(whole);
        }

        if (fraction % 10 == 0) {
            return whole + "." + (fraction / 10);
        }

        return whole + "." + (fraction < 10 ? "0" : "") + fraction;
    }
}
