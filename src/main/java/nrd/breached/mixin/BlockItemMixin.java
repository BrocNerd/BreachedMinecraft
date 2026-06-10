package nrd.breached.mixin;

import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.ActionResult;
import nrd.breached.storage.TemporaryStorageManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public class BlockItemMixin {
    @Inject(method = "place", at = @At("RETURN"))
    private void breached$trackTemporaryStoragePlacement(ItemPlacementContext context, CallbackInfoReturnable<ActionResult> cir) {
        TemporaryStorageManager.onBlockItemPlaced(context, cir.getReturnValue());
    }
}
