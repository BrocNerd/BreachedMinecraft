package nrd.breached.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.TeleportTarget;
import nrd.breached.respawn.RespawnCooldownManager;
import nrd.breached.worldgen.BreachedStructurePlacementManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerEntity.class)
public class ServerPlayerRespawnMixin {
    @Inject(method = "getWorldSpawnPos", at = @At("HEAD"), cancellable = true)
    private void breached$getTownhallWorldSpawnPos(ServerWorld world, BlockPos originalSpawnPos, CallbackInfoReturnable<BlockPos> cir) {
        BreachedStructurePlacementManager.getTownhallSpawnPos(world)
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "getRespawnTarget", at = @At("HEAD"), cancellable = true)
    private void breached$getAvailableBedRespawnTarget(boolean alive, TeleportTarget.PostDimensionTransition postDimensionTransition, CallbackInfoReturnable<TeleportTarget> cir) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        RespawnCooldownManager.getAvailableBedRespawnTarget(player, postDimensionTransition)
                .ifPresent(cir::setReturnValue);
    }

    @Inject(method = "getRespawnTarget", at = @At("RETURN"))
    private void breached$markBedRespawn(boolean alive, TeleportTarget.PostDimensionTransition postDimensionTransition, CallbackInfoReturnable<TeleportTarget> cir) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        TeleportTarget target = cir.getReturnValue();
        ServerPlayerEntity.Respawn respawn = player.getRespawn();
        if (target != null && !target.missingRespawnBlock()) {
            RespawnCooldownManager.trackBedRespawnPoint(player, respawn);
            RespawnCooldownManager.markPendingBedRespawn(player, respawn);
        }
    }

    @Inject(method = "setSpawnPoint", at = @At("HEAD"), cancellable = true)
    private void breached$blockBedRespawnPointChangeOnCooldown(ServerPlayerEntity.Respawn respawn, boolean sendMessage, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (!RespawnCooldownManager.isBedRespawnSelectionBlocked(player, respawn)) {
            return;
        }

        if (sendMessage) {
            player.sendMessage(Text.literal("Beds cannot be changed while a saved bed is on cooldown."), false);
        }

        ci.cancel();
    }

    @Inject(method = "setSpawnPoint", at = @At("TAIL"))
    private void breached$trackBedRespawnPoint(ServerPlayerEntity.Respawn respawn, boolean sendMessage, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (!sendMessage) {
            RespawnCooldownManager.trackBedRespawnPoint(player, respawn);
            return;
        }

        RespawnCooldownManager.trackBedRespawnPointAndCreateMessage(player, respawn)
                .ifPresent(message -> player.sendMessage(message, false));
    }

    @Inject(method = "readCustomData", at = @At("TAIL"))
    private void breached$readBedRespawns(ReadView view, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        RespawnCooldownManager.readBedRespawns(player, view);
    }

    @Inject(method = "writeCustomData", at = @At("TAIL"))
    private void breached$writeBedRespawns(WriteView view, CallbackInfo ci) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        RespawnCooldownManager.writeBedRespawns(player, view);
    }
}
