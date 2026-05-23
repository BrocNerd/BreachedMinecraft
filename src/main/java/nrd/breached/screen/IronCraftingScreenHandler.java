package nrd.breached.screen;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import nrd.breached.Breached;

public class IronCraftingScreenHandler extends CraftingScreenHandler {
    private final ScreenHandlerContext context;

    public IronCraftingScreenHandler(int syncId, PlayerInventory playerInventory, ScreenHandlerContext context) {
        super(syncId, playerInventory, context);
        this.context = context;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return canUse(this.context, player, Breached.TIER_1_CRAFTING_BENCH);
    }
}
