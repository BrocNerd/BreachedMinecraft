package nrd.breached.mixin;

import net.minecraft.item.ItemStack;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.village.Merchant;
import net.minecraft.village.MerchantInventory;
import nrd.breached.worldgen.TownhallTraderManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MerchantScreenHandler.class)
public class MerchantScreenHandlerMixin {
    @Shadow
    @Final
    private Merchant merchant;

    @Shadow
    @Final
    private MerchantInventory merchantInventory;

    @Inject(method = "quickMove", at = @At("HEAD"))
    private void breached$resolveMysterySanctuaryTradeOutput(
            net.minecraft.entity.player.PlayerEntity player,
            int slot,
            CallbackInfoReturnable<ItemStack> cir
    ) {
        if (slot != 2) {
            return;
        }

        ItemStack outputStack = merchantInventory.getStack(2);
        ItemStack resolvedStack = TownhallTraderManager.resolveMysterySanctuaryTradeOutput(merchant, outputStack);
        if (resolvedStack != outputStack) {
            merchantInventory.setStack(2, resolvedStack);
        }
    }
}
