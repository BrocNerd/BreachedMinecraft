package nrd.breached.mixin;

import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import nrd.breached.team.TeamChatFormatter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(PlayerManager.class)
public class PlayerManagerMixin {
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
