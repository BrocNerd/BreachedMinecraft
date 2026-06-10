package nrd.breached.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.StorageMinecartEntity;
import nrd.breached.storage.TemporaryStorageManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StorageMinecartEntity.class)
public class StorageMinecartEntityMixin {
    @Inject(method = "remove", at = @At("HEAD"))
    private void breached$untrackTemporaryStorageMinecart(Entity.RemovalReason reason, CallbackInfo ci) {
        if (!reason.shouldSave()) {
            TemporaryStorageManager.untrackStorageMinecart((StorageMinecartEntity) (Object) this);
        }
    }
}
