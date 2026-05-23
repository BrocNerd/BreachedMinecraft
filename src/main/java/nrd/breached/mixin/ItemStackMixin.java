package nrd.breached.mixin;

import net.minecraft.item.ItemStack;
import nrd.breached.armor.ArmorDurabilityRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

@Mixin(ItemStack.class)
public class ItemStackMixin {
    @Inject(method = "getMaxDamage", at = @At("HEAD"), cancellable = true)
    private void breached$getArmorMaxDamage(CallbackInfoReturnable<Integer> cir) {
        ItemStack stack = (ItemStack) (Object) this;
        OptionalInt durability = ArmorDurabilityRules.getMaxDurability(stack.getItem());
        if (durability.isPresent()) {
            cir.setReturnValue(durability.getAsInt());
        }
    }
}
