package nrd.breached.mixin;

import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import nrd.breached.combat.AdrenalineManager;
import nrd.breached.team.TeamChatFormatter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {
    @Inject(
            method = "remove",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/PlayerManager;savePlayerData(Lnet/minecraft/server/network/ServerPlayerEntity;)V"
            )
    )
    private void breached$spawnCombatLogoutBodyBeforeSave(ServerPlayerEntity player, CallbackInfo ci) {
        AdrenalineManager.handlePlayerDisconnectBeforeSave(player);
    }

    @ModifyVariable(
            method = "broadcast(Lnet/minecraft/network/message/SignedMessage;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/network/message/MessageType$Parameters;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private MessageType.Parameters breached$addTeamNameToChatName(
            MessageType.Parameters parameters,
            SignedMessage message,
            ServerPlayerEntity sender
    ) {
        return TeamChatFormatter.withTeamNamePrefix(sender, parameters);
    }
}
