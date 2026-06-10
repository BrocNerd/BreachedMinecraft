package nrd.breached.mixin;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.MinecartItem;
import net.minecraft.util.ActionResult;
import nrd.breached.storage.TemporaryStorageManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecartItem.class)
public class MinecartItemMixin {
    @Shadow
    @Final
    private EntityType<? extends AbstractMinecartEntity> type;

    @Inject(method = "useOnBlock", at = @At("RETURN"))
    private void breached$trackTemporaryStorageMinecart(ItemUsageContext context, CallbackInfoReturnable<ActionResult> cir) {
        TemporaryStorageManager.onMinecartItemUsed(context, cir.getReturnValue(), type);
    }
}
