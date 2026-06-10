package nrd.breached.command;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SelfKillCommand {
    private static final int SELF_KILL_COOLDOWN_TICKS = 20 * 60;
    private static final Map<UUID, Integer> LAST_SELF_KILL_TICKS = new HashMap<>();

    private SelfKillCommand() {
    }

    public static int executeSelfKill(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        int currentTick = source.getServer().getTicks();
        Integer lastKillTick = LAST_SELF_KILL_TICKS.get(player.getUuid());
        if (lastKillTick != null) {
            int elapsedTicks = currentTick - lastKillTick;
            if (elapsedTicks >= 0 && elapsedTicks < SELF_KILL_COOLDOWN_TICKS) {
                int remainingSeconds = ticksToSeconds(SELF_KILL_COOLDOWN_TICKS - elapsedTicks);
                source.sendError(Text.literal("You can use /kill again in " + remainingSeconds + " " + secondLabel(remainingSeconds) + "."));
                return 0;
            }
        }

        LAST_SELF_KILL_TICKS.put(player.getUuid(), currentTick);
        return executeKill(source, List.of(player));
    }

    public static int executeKill(ServerCommandSource source, Collection<? extends Entity> targets) {
        for (Entity target : targets) {
            target.kill(source.getWorld());
        }

        if (targets.size() == 1) {
            source.sendFeedback(
                    () -> Text.translatable("commands.kill.success.single", targets.iterator().next().getDisplayName()),
                    true
            );
        } else {
            source.sendFeedback(
                    () -> Text.translatable("commands.kill.success.multiple", targets.size()),
                    true
            );
        }
        return targets.size();
    }

    private static int ticksToSeconds(int ticks) {
        return (ticks + 19) / 20;
    }

    private static String secondLabel(int seconds) {
        return seconds == 1 ? "second" : "seconds";
    }
}
