package nrd.breached.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.inventory.RecipeInputInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import nrd.breached.crafting.CraftingTier;
import nrd.breached.crafting.CraftingTierProvider;
import nrd.breached.crafting.CraftingTierRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CraftingScreenHandler.class)
public class CraftingScreenHandlerMixin {
    @Inject(method = "updateResult", at = @At("RETURN"))
    private static void breached$applyCraftingTier(
            ScreenHandler handler,
            ServerWorld world,
            PlayerEntity player,
            RecipeInputInventory craftingInventory,
            CraftingResultInventory resultInventory,
            RecipeEntry<CraftingRecipe> recipe,
            CallbackInfo ci
    ) {
        ItemStack result = resultInventory.getStack(0);
        if (result.isEmpty()) {
            return;
        }

        CraftingTier tableTier = handler instanceof CraftingTierProvider tierProvider
                ? tierProvider.breached$getCraftingTier()
                : CraftingTier.TIER_0;

        if (result.isOf(Items.FIREWORK_ROCKET) && !breached$hasNetheriteScrap(craftingInventory)) {
            breached$clearCraftingResult(handler, player, resultInventory);
            return;
        }

        if (CraftingTierRules.canCraft(tableTier, result)) {
            return;
        }

        breached$clearCraftingResult(handler, player, resultInventory);
    }

    private static boolean breached$hasNetheriteScrap(RecipeInputInventory craftingInventory) {
        for (int slot = 0; slot < craftingInventory.size(); slot++) {
            if (craftingInventory.getStack(slot).isOf(Items.NETHERITE_SCRAP)) {
                return true;
            }
        }

        return false;
    }

    private static void breached$clearCraftingResult(
            ScreenHandler handler,
            PlayerEntity player,
            CraftingResultInventory resultInventory
    ) {
        resultInventory.setStack(0, ItemStack.EMPTY);
        handler.setReceivedStack(0, ItemStack.EMPTY);

        if (player instanceof ServerPlayerEntity serverPlayer) {
            serverPlayer.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(
                    handler.syncId,
                    handler.nextRevision(),
                    0,
                    ItemStack.EMPTY
            ));
        }
    }
}
