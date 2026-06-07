package nrd.breached.mixin;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.FireworkRocketRecipe;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FireworkRocketRecipe.class)
public class FireworkRocketRecipeMixin {
    @Inject(method = "matches(Lnet/minecraft/recipe/input/CraftingRecipeInput;Lnet/minecraft/world/World;)Z", at = @At("HEAD"), cancellable = true)
    private void breached$requireNetheriteScrap(CraftingRecipeInput input, World world, CallbackInfoReturnable<Boolean> cir) {
        boolean hasPaper = false;
        boolean hasNetheriteScrap = false;
        int gunpowderCount = 0;

        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }

            if (stack.isOf(Items.PAPER)) {
                if (hasPaper) {
                    cir.setReturnValue(false);
                    return;
                }

                hasPaper = true;
            } else if (stack.isOf(Items.NETHERITE_SCRAP)) {
                if (hasNetheriteScrap) {
                    cir.setReturnValue(false);
                    return;
                }

                hasNetheriteScrap = true;
            } else if (stack.isOf(Items.GUNPOWDER)) {
                gunpowderCount++;
                if (gunpowderCount > 3) {
                    cir.setReturnValue(false);
                    return;
                }
            } else if (!stack.isOf(Items.FIREWORK_STAR)) {
                cir.setReturnValue(false);
                return;
            }
        }

        cir.setReturnValue(hasPaper && hasNetheriteScrap && gunpowderCount >= 1);
    }
}
