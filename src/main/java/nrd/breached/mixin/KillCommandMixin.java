package nrd.breached.mixin;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.KillCommand;
import net.minecraft.server.command.ServerCommandSource;
import nrd.breached.command.SelfKillCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KillCommand.class)
public class KillCommandMixin {
    @Inject(method = "register", at = @At("HEAD"), cancellable = true)
    private static void breached$registerPlayerKillCommand(CommandDispatcher<ServerCommandSource> dispatcher, CallbackInfo ci) {
        dispatcher.register(CommandManager.literal("kill")
                .executes(context -> SelfKillCommand.executeSelfKill(context.getSource()))
                .then(CommandManager.argument("targets", EntityArgumentType.entities())
                        .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                        .executes(context -> SelfKillCommand.executeKill(
                                context.getSource(),
                                EntityArgumentType.getEntities(context, "targets")
                        ))
                )
        );
        ci.cancel();
    }
}
