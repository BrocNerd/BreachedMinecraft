package nrd.breached.mixin;

import net.minecraft.entity.Entity;
import nrd.breached.combat.CombatLogoutBodyManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityMixin {
    @Inject(method = "shouldSave", at = @At("HEAD"), cancellable = true)
    private void breached$doNotSaveCombatLogoutBodies(CallbackInfoReturnable<Boolean> cir) {
        if (CombatLogoutBodyManager.isLogoutBody((Entity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
