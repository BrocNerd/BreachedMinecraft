package nrd.breached.mixin;

import net.minecraft.server.world.ServerWorld;
import nrd.breached.respawn.BedRestManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerWorld.class)
public class ServerWorldSleepStatusMixin {
    @Inject(method = "sendSleepingStatus", at = @At("HEAD"), cancellable = true)
    private void breached$replaceSleepingStatus(CallbackInfo ci) {
        BedRestManager.sendRestingStatus((ServerWorld) (Object) this);
        ci.cancel();
    }
}
