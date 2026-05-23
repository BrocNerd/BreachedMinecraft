package nrd.breached.mixin;

import net.minecraft.block.BedBlock;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.TeleportTarget;
import nrd.breached.respawn.RespawnCooldownManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerEntity.class)
public class ServerPlayerRespawnMixin {
    @Inject(method = "getRespawnTarget", at = @At("HEAD"), cancellable = true)
    private void breached$redirectBedRespawnOnCooldown(boolean alive, TeleportTarget.PostDimensionTransition postDimensionTransition, CallbackInfoReturnable<TeleportTarget> cir) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        if (!hasBedRespawnPoint(player) || !RespawnCooldownManager.isBedRespawnOnCooldown(player)) {
            return;
        }

        RespawnCooldownManager.clearPendingBedRespawn(player);
        player.sendMessage(Text.literal("Your bed is on cooldown. Respawning at world spawn."), false);
        cir.setReturnValue(TeleportTarget.noRespawnPointSet(player, postDimensionTransition));
    }

    @Inject(method = "getRespawnTarget", at = @At("RETURN"))
    private void breached$markBedRespawn(boolean alive, TeleportTarget.PostDimensionTransition postDimensionTransition, CallbackInfoReturnable<TeleportTarget> cir) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        TeleportTarget target = cir.getReturnValue();
        if (hasBedRespawnPoint(player) && target != null && !target.missingRespawnBlock()) {
            RespawnCooldownManager.markPendingBedRespawn(player);
        }
    }

    private static boolean hasBedRespawnPoint(ServerPlayerEntity player) {
        ServerPlayerEntity.Respawn respawn = player.getRespawn();
        if (respawn == null || respawn.respawnData() == null) {
            return false;
        }

        ServerWorld respawnWorld = player.getEntityWorld().getServer().getWorld(respawn.respawnData().getDimension());
        return respawnWorld != null && respawnWorld.getBlockState(respawn.respawnData().getPos()).getBlock() instanceof BedBlock;
    }
}
