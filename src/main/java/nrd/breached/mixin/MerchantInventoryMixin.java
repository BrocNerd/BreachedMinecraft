package nrd.breached.mixin;

import net.minecraft.item.ItemStack;
import net.minecraft.village.Merchant;
import net.minecraft.village.MerchantInventory;
import nrd.breached.worldgen.TownhallTraderManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MerchantInventory.class)
public class MerchantInventoryMixin {
    @Shadow
    @Final
    private Merchant merchant;

    @Inject(method = "removeStack(II)Lnet/minecraft/item/ItemStack;", at = @At("RETURN"), cancellable = true)
    private void breached$resolveMysterySanctuaryTradeOutput(
            int slot,
            int amount,
            CallbackInfoReturnable<ItemStack> cir
    ) {
        if (slot != 2) {
            return;
        }

        cir.setReturnValue(TownhallTraderManager.resolveMysterySanctuaryTradeOutput(merchant, cir.getReturnValue()));
    }
}
