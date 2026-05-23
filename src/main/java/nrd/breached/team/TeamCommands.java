package nrd.breached.team;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandRegistryAccess;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class TeamCommands {
    private static final int MAX_TEAM_NAME_LENGTH = 24;

    private TeamCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register(TeamCommands::registerCommands);
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(literal("breached")
                .then(literal("team")
                        .then(literal("create")
                                .then(argument("name", StringArgumentType.word())
                                        .executes(TeamCommands::createTeam)))
                        .then(literal("disband")
                                .executes(TeamCommands::disbandTeam))
                        .then(literal("invite")
                                .then(argument("player", EntityArgumentType.player())
                                        .executes(TeamCommands::invitePlayer)))
                        .then(literal("kick")
                                .then(argument("player", EntityArgumentType.player())
                                        .executes(TeamCommands::kickPlayer)))
                        .then(literal("transfer")
                                .then(argument("player", EntityArgumentType.player())
                                        .executes(TeamCommands::transferOwnership)))
                        .then(literal("join")
                                .then(argument("name", StringArgumentType.word())
                                        .executes(TeamCommands::joinTeam)))
                        .then(literal("leave")
                                .executes(TeamCommands::leaveTeam))
                        .then(literal("info")
                                .executes(TeamCommands::showTeamInfo))
                        .then(literal("list")
                                .executes(TeamCommands::listTeams))
                )
        );
    }

    private static int createTeam(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        String teamName = StringArgumentType.getString(context, "name");
        TeamState state = TeamState.get(context.getSource().getServer());

        if (!isValidTeamName(teamName)) {
            context.getSource().sendError(Text.literal("Team names must be 1-24 letters, numbers, underscores, or hyphens."));
            return 0;
        }

        if (state.getPlayerTeam(player.getUuid()).isPresent()) {
            context.getSource().sendError(Text.literal("You are already in a team."));
            return 0;
        }

        if (state.hasTeamNamed(teamName)) {
            context.getSource().sendError(Text.literal("A team with that name already exists."));
            return 0;
        }

        TeamData team = state.createTeam(teamName, player.getUuid(), player.getGameProfile().name());
        context.getSource().sendFeedback(() -> Text.literal("Created team " + team.getName() + "."), false);
        return 1;
    }

    private static int disbandTeam(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(player.getUuid()).orElse(null);

        if (team == null) {
            context.getSource().sendError(Text.literal("You are not in a team."));
            return 0;
        }

        if (!team.isOwner(player.getUuid())) {
            context.getSource().sendError(Text.literal("Only the team owner can disband the team."));
            return 0;
        }

        String teamName = team.getName();
        state.disbandTeam(team);
        context.getSource().sendFeedback(() -> Text.literal("Disbanded team " + teamName + "."), false);
        return 1;
    }

    private static int invitePlayer(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity inviter = context.getSource().getPlayerOrThrow();
        ServerPlayerEntity invitee = EntityArgumentType.getPlayer(context, "player");
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(inviter.getUuid()).orElse(null);

        if (team == null) {
            context.getSource().sendError(Text.literal("You are not in a team."));
            return 0;
        }

        if (!team.isOwner(inviter.getUuid())) {
            context.getSource().sendError(Text.literal("Only the team owner can invite players."));
            return 0;
        }

        UUID inviteeId = invitee.getUuid();
        if (team.hasMember(inviteeId)) {
            context.getSource().sendError(Text.literal(invitee.getGameProfile().name() + " is already in your team."));
            return 0;
        }

        if (state.getPlayerTeam(inviteeId).isPresent()) {
            context.getSource().sendError(Text.literal(invitee.getGameProfile().name() + " is already in another team."));
            return 0;
        }

        state.addInvite(team, inviteeId);
        context.getSource().sendFeedback(() -> Text.literal("Invited " + invitee.getGameProfile().name() + " to " + team.getName() + "."), false);
        invitee.sendMessage(Text.literal("You were invited to join " + team.getName() + ". Use /breached team join " + team.getName() + " to accept."));
        return 1;
    }

    private static int kickPlayer(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity owner = context.getSource().getPlayerOrThrow();
        ServerPlayerEntity kickedPlayer = EntityArgumentType.getPlayer(context, "player");
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(owner.getUuid()).orElse(null);

        if (team == null) {
            context.getSource().sendError(Text.literal("You are not in a team."));
            return 0;
        }

        if (!team.isOwner(owner.getUuid())) {
            context.getSource().sendError(Text.literal("Only the team owner can kick players."));
            return 0;
        }

        UUID kickedPlayerId = kickedPlayer.getUuid();
        if (team.isOwner(kickedPlayerId)) {
            context.getSource().sendError(Text.literal("Team owners cannot kick themselves. Transfer ownership or disband the team instead."));
            return 0;
        }

        if (!team.hasMember(kickedPlayerId)) {
            context.getSource().sendError(Text.literal(kickedPlayer.getGameProfile().name() + " is not in your team."));
            return 0;
        }

        state.removeMember(team, kickedPlayerId);
        context.getSource().sendFeedback(() -> Text.literal("Kicked " + kickedPlayer.getGameProfile().name() + " from " + team.getName() + "."), false);
        kickedPlayer.sendMessage(Text.literal("You were kicked from " + team.getName() + "."));
        return 1;
    }

    private static int transferOwnership(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity owner = context.getSource().getPlayerOrThrow();
        ServerPlayerEntity newOwner = EntityArgumentType.getPlayer(context, "player");
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(owner.getUuid()).orElse(null);

        if (team == null) {
            context.getSource().sendError(Text.literal("You are not in a team."));
            return 0;
        }

        if (!team.isOwner(owner.getUuid())) {
            context.getSource().sendError(Text.literal("Only the team owner can transfer ownership."));
            return 0;
        }

        UUID newOwnerId = newOwner.getUuid();
        if (team.isOwner(newOwnerId)) {
            context.getSource().sendError(Text.literal("You already own this team."));
            return 0;
        }

        if (!team.hasMember(newOwnerId)) {
            context.getSource().sendError(Text.literal(newOwner.getGameProfile().name() + " must be in your team before ownership can be transferred."));
            return 0;
        }

        state.transferOwnership(team, newOwnerId, newOwner.getGameProfile().name());
        context.getSource().sendFeedback(() -> Text.literal("Transferred ownership of " + team.getName() + " to " + newOwner.getGameProfile().name() + "."), false);
        newOwner.sendMessage(Text.literal("You are now the owner of " + team.getName() + "."));
        return 1;
    }

    private static int joinTeam(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        String teamName = StringArgumentType.getString(context, "name");
        TeamState state = TeamState.get(context.getSource().getServer());

        if (state.getPlayerTeam(player.getUuid()).isPresent()) {
            context.getSource().sendError(Text.literal("You are already in a team."));
            return 0;
        }

        TeamData team = state.getTeam(teamName).orElse(null);
        if (team == null) {
            context.getSource().sendError(Text.literal("No team named " + teamName + " exists."));
            return 0;
        }

        if (!team.hasInvite(player.getUuid())) {
            context.getSource().sendError(Text.literal("You do not have an invite to that team."));
            return 0;
        }

        state.addMember(team, player.getUuid(), player.getGameProfile().name());
        context.getSource().sendFeedback(() -> Text.literal("Joined team " + team.getName() + "."), false);
        return 1;
    }

    private static int leaveTeam(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(player.getUuid()).orElse(null);

        if (team == null) {
            context.getSource().sendError(Text.literal("You are not in a team."));
            return 0;
        }

        if (team.isOwner(player.getUuid())) {
            context.getSource().sendError(Text.literal("Team owners must disband the team instead of leaving."));
            return 0;
        }

        state.removeMember(team, player.getUuid());
        context.getSource().sendFeedback(() -> Text.literal("Left team " + team.getName() + "."), false);
        return 1;
    }

    private static int showTeamInfo(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(player.getUuid()).orElse(null);

        if (team == null) {
            context.getSource().sendError(Text.literal("You are not in a team."));
            return 0;
        }

        String members = team.getMembers().values().stream()
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(", "));
        context.getSource().sendFeedback(() -> Text.literal("Team " + team.getName() + " | Owner: " + team.getOwnerName() + " | Members: " + members), false);
        return 1;
    }

    private static int listTeams(CommandContext<ServerCommandSource> context) {
        TeamState state = TeamState.get(context.getSource().getServer());

        if (state.getTeams().isEmpty()) {
            context.getSource().sendFeedback(() -> Text.literal("No teams exist."), false);
            return 1;
        }

        context.getSource().sendFeedback(() -> Text.literal("Teams:"), false);
        state.getTeams().stream()
                .sorted(Comparator.comparing(TeamData::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(team -> context.getSource().sendFeedback(
                        () -> Text.literal(team.getName() + " - " + team.getMembers().size() + " " + memberCountLabel(team.getMembers().size())),
                        false
                ));
        return state.getTeams().size();
    }

    private static String memberCountLabel(int memberCount) {
        return memberCount == 1 ? "member" : "members";
    }

    private static boolean isValidTeamName(String teamName) {
        return !teamName.isEmpty()
                && teamName.length() <= MAX_TEAM_NAME_LENGTH
                && teamName.matches("[A-Za-z0-9_-]+");
    }
}
