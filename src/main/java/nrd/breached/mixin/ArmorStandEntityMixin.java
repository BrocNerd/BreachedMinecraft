package nrd.breached.mixin;

import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.world.ServerWorld;
import nrd.breached.combat.CombatLogoutBodyManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ArmorStandEntity.class)
public class ArmorStandEntityMixin {
    @Inject(method = "breakAndDropItem", at = @At("HEAD"), cancellable = true)
    private void breached$preventCombatLogoutBodyItemDrop(ServerWorld world, DamageSource source, CallbackInfo ci) {
        if (CombatLogoutBodyManager.isLogoutBody((ArmorStandEntity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "onBreak", at = @At("HEAD"), cancellable = true)
    private void breached$preventCombatLogoutBodyEquipmentDrop(ServerWorld world, DamageSource source, CallbackInfo ci) {
        if (CombatLogoutBodyManager.isLogoutBody((ArmorStandEntity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "kill", at = @At("HEAD"))
    private void breached$handleCombatLogoutBodyKilled(ServerWorld world, CallbackInfo ci) {
        CombatLogoutBodyManager.handleKilledBody((ArmorStandEntity) (Object) this);
    }
}
