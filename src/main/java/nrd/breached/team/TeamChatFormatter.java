package nrd.breached.team;

import net.minecraft.network.message.MessageType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class TeamChatFormatter {
    private TeamChatFormatter() {
    }

    public static MessageType.Parameters withTeamNamePrefix(ServerPlayerEntity sender, MessageType.Parameters parameters) {
        if (sender == null) {
            return parameters;
        }

        return TeamState.get(sender.getEntityWorld().getServer())
                .getPlayerTeam(sender.getUuid())
                .map(team -> {
                    Text displayName = Text.empty()
                            .append(Text.literal(team.getName()).formatted(team.getDisplayColor()))
                            .append(Text.literal(" "))
                            .append(parameters.name().copy());
                    return new MessageType.Parameters(parameters.type(), displayName, parameters.targetName());
                })
                .orElse(parameters);
    }
}
