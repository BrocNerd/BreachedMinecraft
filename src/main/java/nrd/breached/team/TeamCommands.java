package nrd.breached.team;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import nrd.breached.message.BreachedMessages;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class TeamCommands {
    private static final int MAX_TEAM_NAME_LENGTH = 24;

    private static final List<TeamColorOption> TEAM_COLOR_OPTIONS = List.of(
            new TeamColorOption("white", "White", Formatting.WHITE),
            new TeamColorOption("gray", "Gray", Formatting.GRAY),
            new TeamColorOption("dark_gray", "Dark Gray", Formatting.DARK_GRAY),
            new TeamColorOption("black", "Black", Formatting.BLACK),
            new TeamColorOption("red", "Red", Formatting.RED),
            new TeamColorOption("dark_red", "Dark Red", Formatting.DARK_RED),
            new TeamColorOption("gold", "Gold", Formatting.GOLD),
            new TeamColorOption("yellow", "Yellow", Formatting.YELLOW),
            new TeamColorOption("green", "Green", Formatting.GREEN),
            new TeamColorOption("dark_green", "Dark Green", Formatting.DARK_GREEN),
            new TeamColorOption("aqua", "Aqua", Formatting.AQUA),
            new TeamColorOption("dark_aqua", "Dark Aqua", Formatting.DARK_AQUA),
            new TeamColorOption("blue", "Blue", Formatting.BLUE),
            new TeamColorOption("dark_blue", "Dark Blue", Formatting.DARK_BLUE),
            new TeamColorOption("light_purple", "Pink", Formatting.LIGHT_PURPLE),
            new TeamColorOption("dark_purple", "Purple", Formatting.DARK_PURPLE)
    );
    private static final Map<String, Formatting> TEAM_COLOR_ALIASES = createTeamColorAliases();
    private static final List<String> TEAM_COLOR_SUGGESTIONS = createTeamColorSuggestions();

    private TeamCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register(TeamCommands::registerCommands);
    }

    private static void registerCommands(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {
        replaceRootLiteralCommand(dispatcher, "team");
        replaceRootLiteralCommand(dispatcher, "teammsg");
        replaceRootLiteralCommand(dispatcher, "tm");
        dispatcher.register(createTeamCommandRoot("team"));
        dispatcher.register(literal("breached").then(createTeamCommandRoot("team")));
        dispatcher.register(createTeamChatRoot("t"));
        dispatcher.register(createTeamChatRoot("tc"));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> createTeamCommandRoot(String commandName) {
        return literal(commandName)
                .executes(TeamCommands::showTeamMenu)
                .then(literal("help")
                        .executes(TeamCommands::showTeamHelp))
                .then(literal("create")
                        .executes(context -> sendUsage(context, "/team create <name>"))
                        .then(argument("name", StringArgumentType.word())
                                .executes(TeamCommands::createTeam)))
                .then(literal("invite")
                        .executes(context -> sendUsage(context, "/team invite <player>"))
                        .then(argument("player", EntityArgumentType.player())
                                .executes(TeamCommands::invitePlayer)))
                .then(literal("accept")
                        .executes(TeamCommands::acceptInvite))
                .then(literal("decline")
                        .executes(TeamCommands::declineInvite))
                .then(literal("join")
                        .executes(context -> sendUsage(context, "/team accept"))
                        .then(argument("name", StringArgumentType.word())
                                .executes(TeamCommands::joinTeam)))
                .then(literal("leave")
                        .executes(TeamCommands::leaveTeam))
                .then(literal("info")
                        .executes(TeamCommands::showTeamInfo))
                .then(literal("color")
                        .executes(TeamCommands::showTeamColorMenu)
                        .then(argument("color", StringArgumentType.word())
                                .suggests((context, builder) -> CommandSource.suggestMatching(TEAM_COLOR_SUGGESTIONS, builder))
                                .executes(TeamCommands::setTeamColor)))
                .then(literal("disband")
                        .executes(TeamCommands::disbandTeam))
                .then(literal("kick")
                        .executes(context -> sendUsage(context, "/team kick <player>"))
                        .then(argument("player", EntityArgumentType.player())
                                .executes(TeamCommands::kickPlayer)))
                .then(literal("transfer")
                        .executes(context -> sendUsage(context, "/team transfer <player>"))
                        .then(argument("player", EntityArgumentType.player())
                                .executes(TeamCommands::transferOwnership)))
                .then(literal("chat")
                        .executes(context -> sendUsage(context, "/t <message>"))
                        .then(argument("message", StringArgumentType.greedyString())
                                .executes(TeamCommands::sendTeamChat)))
                .then(literal("list")
                        .executes(TeamCommands::listTeams));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> createTeamChatRoot(String commandName) {
        return literal(commandName)
                .executes(context -> sendUsage(context, "/t <message>"))
                .then(argument("message", StringArgumentType.greedyString())
                        .executes(TeamCommands::sendTeamChat));
    }

    private static int showTeamMenu(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(player.getUuid()).orElse(null);

        if (team == null) {
            List<TeamData> pendingInvites = getPendingInvites(state, player.getUuid());
            player.sendMessage(BreachedMessages.prefixed(Text.literal("Team Menu").formatted(Formatting.WHITE)), false);
            player.sendMessage(Text.literal("You are not in a team.").formatted(Formatting.GRAY), false);
            pendingInvites.stream().findFirst().ifPresent(invite ->
                    player.sendMessage(Text.literal("Pending invite: ")
                            .formatted(Formatting.GRAY)
                            .append(Text.literal(invite.getName()).formatted(invite.getDisplayColor())), false)
            );
            ArrayList<Text> buttons = new ArrayList<>();
            buttons.add(suggestButton("Create Team", "/team create ", Formatting.GREEN, "Suggest /team create <name>"));
            if (!pendingInvites.isEmpty()) {
                buttons.add(runButton("Accept", "/team accept", Formatting.GREEN, "Accept your pending team invite"));
                buttons.add(runButton("Decline", "/team decline", Formatting.RED, "Decline your pending team invite"));
            }
            buttons.add(runButton("Help", "/team help", Formatting.AQUA, "Show team command help"));
            player.sendMessage(Text.empty(), false);
            player.sendMessage(buttonLine(buttons.toArray(Text[]::new)), false);
            return 1;
        }

        player.sendMessage(BreachedMessages.prefixed(Text.literal("Team: ")
                .formatted(Formatting.WHITE)
                .append(Text.literal(team.getName()).formatted(team.getDisplayColor()))), false);
        player.sendMessage(Text.literal("Members: " + memberList(team)).formatted(Formatting.GRAY), false);
        player.sendMessage(Text.empty(), false);
        player.sendMessage(buttonLine(
                suggestButton("Invite Player", "/team invite ", Formatting.GREEN, "Suggest /team invite <player>"),
                runButton("Info", "/team info", Formatting.AQUA, "Show team info"),
                runButton("Color", "/team color", Formatting.LIGHT_PURPLE, "Choose a team color"),
                runButton("Leave", "/team leave", Formatting.YELLOW, "Leave your team")
        ), false);
        if (team.isOwner(player.getUuid())) {
            player.sendMessage(buttonLine(
                    suggestButton("Kick", "/team kick ", Formatting.RED, "Suggest /team kick <player>"),
                    suggestButton("Transfer", "/team transfer ", Formatting.GOLD, "Suggest /team transfer <player>"),
                    runButton("Disband", "/team disband", Formatting.RED, "Disband your team")
            ), false);
        }
        return 1;
    }

    private static int showTeamHelp(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(player.getUuid()).orElse(null);

        player.sendMessage(BreachedMessages.prefixed(Text.literal("Team Help").formatted(Formatting.WHITE)), false);
        if (team == null) {
            player.sendMessage(Text.literal("/team create <name>").formatted(Formatting.GRAY), false);
            player.sendMessage(Text.literal("/team accept").formatted(Formatting.GRAY), false);
            player.sendMessage(Text.literal("/team decline").formatted(Formatting.GRAY), false);
            player.sendMessage(Text.literal("/team").formatted(Formatting.GRAY), false);
            return 1;
        }

        player.sendMessage(Text.literal("/team invite <player>").formatted(Formatting.GRAY), false);
        player.sendMessage(Text.literal("/team info").formatted(Formatting.GRAY), false);
        player.sendMessage(Text.literal("/team color <color>").formatted(Formatting.GRAY), false);
        player.sendMessage(Text.literal("/team leave").formatted(Formatting.GRAY), false);
        player.sendMessage(Text.literal("/t <message>").formatted(Formatting.GRAY), false);
        if (team.isOwner(player.getUuid())) {
            player.sendMessage(Text.literal("/team disband").formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    private static int createTeam(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        String teamName = StringArgumentType.getString(context, "name");
        TeamState state = TeamState.get(context.getSource().getServer());

        if (!isValidTeamName(teamName)) {
            BreachedMessages.error(player, "Team names must be 1-24 letters, numbers, underscores, or hyphens.");
            BreachedMessages.info(player, "Use /team create <name>.");
            return 0;
        }

        if (state.getPlayerTeam(player.getUuid()).isPresent()) {
            BreachedMessages.error(player, "You are already in a team.");
            return 0;
        }

        if (state.hasTeamNamed(teamName)) {
            BreachedMessages.error(player, "A team with that name already exists.");
            return 0;
        }

        state.createTeam(teamName, player.getUuid(), player.getGameProfile().name());
        TeamLocatorBar.refresh(context.getSource().getServer());
        TeamScoreboardSync.sync(context.getSource().getServer());
        BreachedMessages.success(player, "Team created.");
        return 1;
    }

    private static int disbandTeam(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(player.getUuid()).orElse(null);

        if (team == null) {
            BreachedMessages.error(player, "You are not in a team.");
            return 0;
        }

        if (!team.isOwner(player.getUuid())) {
            BreachedMessages.error(player, "Only the team owner can disband the team.");
            return 0;
        }

        state.disbandTeam(team);
        TeamLocatorBar.refresh(context.getSource().getServer());
        TeamScoreboardSync.sync(context.getSource().getServer());
        BreachedMessages.success(player, "Team disbanded.");
        return 1;
    }

    private static int invitePlayer(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity inviter = context.getSource().getPlayerOrThrow();
        ServerPlayerEntity invitee = EntityArgumentType.getPlayer(context, "player");
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(inviter.getUuid()).orElse(null);

        if (team == null) {
            BreachedMessages.error(inviter, "You are not in a team.");
            return 0;
        }

        if (!team.isOwner(inviter.getUuid())) {
            BreachedMessages.error(inviter, "Only the team owner can invite players.");
            return 0;
        }

        UUID inviteeId = invitee.getUuid();
        if (inviteeId.equals(inviter.getUuid())) {
            BreachedMessages.error(inviter, "You cannot invite yourself.");
            return 0;
        }

        if (team.hasMember(inviteeId)) {
            BreachedMessages.warning(inviter, invitee.getGameProfile().name() + " is already in your team.");
            return 0;
        }

        if (state.getPlayerTeam(inviteeId).isPresent()) {
            BreachedMessages.warning(inviter, invitee.getGameProfile().name() + " is already in another team.");
            return 0;
        }

        if (team.hasInvite(inviteeId)) {
            BreachedMessages.warning(inviter, invitee.getGameProfile().name() + " already has an invite to your team.");
            return 0;
        }

        state.addInvite(team, inviteeId);
        BreachedMessages.success(inviter, "Invite sent to " + invitee.getGameProfile().name() + ".");
        sendInviteMessage(inviter, invitee, team);
        return 1;
    }

    private static int acceptInvite(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        TeamState state = TeamState.get(context.getSource().getServer());

        if (state.getPlayerTeam(player.getUuid()).isPresent()) {
            BreachedMessages.error(player, "You are already in a team.");
            return 0;
        }

        TeamData team = getPendingInvites(state, player.getUuid()).stream().findFirst().orElse(null);
        if (team == null) {
            BreachedMessages.error(player, "You do not have a team invite.");
            return 0;
        }

        state.addMember(team, player.getUuid(), player.getGameProfile().name());
        TeamLocatorBar.refresh(context.getSource().getServer());
        TeamScoreboardSync.sync(context.getSource().getServer());
        BreachedMessages.success(player, "Joined team " + team.getName() + ".");
        notifyOnlineTeamMembers(context, team, player.getUuid(), player.getGameProfile().name() + " joined the team.");
        return 1;
    }

    private static int declineInvite(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = getPendingInvites(state, player.getUuid()).stream().findFirst().orElse(null);
        if (team == null) {
            BreachedMessages.error(player, "You do not have a team invite.");
            return 0;
        }

        state.removeInvite(team, player.getUuid());
        BreachedMessages.info(player, "Declined invite to " + team.getName() + ".");
        return 1;
    }

    private static int kickPlayer(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity owner = context.getSource().getPlayerOrThrow();
        ServerPlayerEntity kickedPlayer = EntityArgumentType.getPlayer(context, "player");
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(owner.getUuid()).orElse(null);

        if (team == null) {
            BreachedMessages.error(owner, "You are not in a team.");
            return 0;
        }

        if (!team.isOwner(owner.getUuid())) {
            BreachedMessages.error(owner, "Only the team owner can kick players.");
            return 0;
        }

        UUID kickedPlayerId = kickedPlayer.getUuid();
        if (team.isOwner(kickedPlayerId)) {
            BreachedMessages.error(owner, "Team owners cannot kick themselves. Transfer ownership or disband the team instead.");
            return 0;
        }

        if (!team.hasMember(kickedPlayerId)) {
            BreachedMessages.error(owner, kickedPlayer.getGameProfile().name() + " is not in your team.");
            return 0;
        }

        state.removeMember(team, kickedPlayerId);
        TeamLocatorBar.refresh(context.getSource().getServer());
        TeamScoreboardSync.sync(context.getSource().getServer());
        BreachedMessages.success(owner, "Kicked " + kickedPlayer.getGameProfile().name() + ".");
        BreachedMessages.error(kickedPlayer, "You were kicked from " + team.getName() + ".");
        return 1;
    }

    private static int transferOwnership(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity owner = context.getSource().getPlayerOrThrow();
        ServerPlayerEntity newOwner = EntityArgumentType.getPlayer(context, "player");
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(owner.getUuid()).orElse(null);

        if (team == null) {
            BreachedMessages.error(owner, "You are not in a team.");
            return 0;
        }

        if (!team.isOwner(owner.getUuid())) {
            BreachedMessages.error(owner, "Only the team owner can transfer ownership.");
            return 0;
        }

        UUID newOwnerId = newOwner.getUuid();
        if (team.isOwner(newOwnerId)) {
            BreachedMessages.warning(owner, "You already own this team.");
            return 0;
        }

        if (!team.hasMember(newOwnerId)) {
            BreachedMessages.error(owner, newOwner.getGameProfile().name() + " must be in your team before ownership can be transferred.");
            return 0;
        }

        state.transferOwnership(team, newOwnerId, newOwner.getGameProfile().name());
        TeamScoreboardSync.sync(context.getSource().getServer());
        BreachedMessages.success(owner, "Transferred ownership to " + newOwner.getGameProfile().name() + ".");
        BreachedMessages.success(newOwner, "You are now the owner of " + team.getName() + ".");
        return 1;
    }

    private static int setTeamColor(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity owner = context.getSource().getPlayerOrThrow();
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(owner.getUuid()).orElse(null);

        if (team == null) {
            BreachedMessages.error(owner, "You are not in a team.");
            return 0;
        }

        if (!team.isOwner(owner.getUuid())) {
            BreachedMessages.error(owner, "Only the team owner can change the team color.");
            return 0;
        }

        String colorName = StringArgumentType.getString(context, "color");
        Formatting color = parseTeamColor(colorName);
        if (color == null) {
            BreachedMessages.error(owner, "Unknown team color.");
            BreachedMessages.info(owner, "Use /team color <color>.");
            showTeamColorOptions(owner);
            return 0;
        }

        state.setDisplayColor(team, color);
        TeamScoreboardSync.sync(context.getSource().getServer());
        owner.sendMessage(BreachedMessages.prefixed(Text.literal("Team color set to ")
                .formatted(Formatting.GREEN)
                .append(Text.literal(displayNameForColor(color)).formatted(color))
                .append(Text.literal("."))), false);
        return 1;
    }

    private static int showTeamColorMenu(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(player.getUuid()).orElse(null);

        if (team == null) {
            BreachedMessages.error(player, "You are not in a team.");
            return 0;
        }

        if (!team.isOwner(player.getUuid())) {
            BreachedMessages.error(player, "Only the team owner can change the team color.");
            return 0;
        }

        showTeamColorOptions(player);
        return 1;
    }

    private static int joinTeam(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        String teamName = StringArgumentType.getString(context, "name");
        TeamState state = TeamState.get(context.getSource().getServer());

        if (state.getPlayerTeam(player.getUuid()).isPresent()) {
            BreachedMessages.error(player, "You are already in a team.");
            return 0;
        }

        TeamData team = state.getTeam(teamName).orElse(null);
        if (team == null) {
            BreachedMessages.error(player, "No team named " + teamName + " exists.");
            return 0;
        }

        if (!team.hasInvite(player.getUuid())) {
            BreachedMessages.error(player, "You do not have an invite to that team.");
            return 0;
        }

        state.addMember(team, player.getUuid(), player.getGameProfile().name());
        TeamLocatorBar.refresh(context.getSource().getServer());
        TeamScoreboardSync.sync(context.getSource().getServer());
        BreachedMessages.success(player, "Joined team " + team.getName() + ".");
        notifyOnlineTeamMembers(context, team, player.getUuid(), player.getGameProfile().name() + " joined the team.");
        return 1;
    }

    private static int leaveTeam(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(player.getUuid()).orElse(null);

        if (team == null) {
            BreachedMessages.error(player, "You are not in a team.");
            return 0;
        }

        if (team.isOwner(player.getUuid())) {
            BreachedMessages.error(player, "Team owners must disband the team instead of leaving.");
            BreachedMessages.info(player, "Use /team disband.");
            return 0;
        }

        state.removeMember(team, player.getUuid());
        TeamLocatorBar.refresh(context.getSource().getServer());
        TeamScoreboardSync.sync(context.getSource().getServer());
        BreachedMessages.success(player, "Left team " + team.getName() + ".");
        notifyOnlineTeamMembers(context, team, player.getUuid(), player.getGameProfile().name() + " left the team.");
        return 1;
    }

    private static int sendTeamChat(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity sender = context.getSource().getPlayerOrThrow();
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(sender.getUuid()).orElse(null);

        if (team == null) {
            BreachedMessages.error(sender, "You are not in a team.");
            return 0;
        }

        String message = StringArgumentType.getString(context, "message").trim();
        if (message.isEmpty()) {
            BreachedMessages.info(sender, "Use /t <message>.");
            return 0;
        }

        Text teamMessage = Text.literal("[Team] ").formatted(Formatting.AQUA)
                .append(Text.literal(sender.getGameProfile().name() + ": ").formatted(Formatting.WHITE))
                .append(Text.literal(message).formatted(Formatting.WHITE));
        int recipients = 0;
        for (ServerPlayerEntity teammate : context.getSource().getServer().getPlayerManager().getPlayerList()) {
            if (team.hasMember(teammate.getUuid())) {
                teammate.sendMessage(teamMessage, false);
                recipients++;
            }
        }

        return recipients;
    }

    private static int showTeamInfo(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        TeamState state = TeamState.get(context.getSource().getServer());
        TeamData team = state.getPlayerTeam(player.getUuid()).orElse(null);

        if (team == null) {
            BreachedMessages.error(player, "You are not in a team.");
            return 0;
        }

        Formatting teamColor = team.getDisplayColor();
        player.sendMessage(BreachedMessages.prefixed(Text.literal("Team: ")
                .formatted(teamColor)
                .append(Text.literal(team.getName()).formatted(Formatting.WHITE))), false);
        player.sendMessage(Text.literal("Owner: ")
                .formatted(teamColor)
                .append(Text.literal(team.getOwnerName()).formatted(Formatting.GRAY)), false);
        player.sendMessage(Text.literal("Members: ")
                .formatted(teamColor)
                .append(Text.literal(memberList(team)).formatted(Formatting.GRAY)), false);
        return 1;
    }

    private static int listTeams(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayerOrThrow();
        TeamState state = TeamState.get(context.getSource().getServer());

        if (state.getTeams().isEmpty()) {
            BreachedMessages.info(player, "No teams exist.");
            return 1;
        }

        player.sendMessage(BreachedMessages.prefixed(Text.literal("Teams").formatted(Formatting.WHITE)), false);
        state.getTeams().stream()
                .sorted(Comparator.comparing(TeamData::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(team -> player.sendMessage(Text.literal("")
                        .append(Text.literal(team.getName()).formatted(team.getDisplayColor()))
                        .append(Text.literal(" - " + team.getMembers().size() + " " + memberCountLabel(team.getMembers().size()))
                                .formatted(Formatting.GRAY)), false));
        return state.getTeams().size();
    }

    private static int sendUsage(CommandContext<ServerCommandSource> context, String usage) throws CommandSyntaxException {
        BreachedMessages.info(context.getSource().getPlayerOrThrow(), "Use " + usage + ".");
        return 0;
    }

    private static void sendInviteMessage(ServerPlayerEntity inviter, ServerPlayerEntity invitee, TeamData team) {
        invitee.sendMessage(BreachedMessages.prefixed(Text.literal(inviter.getGameProfile().name())
                .formatted(Formatting.WHITE)
                .append(Text.literal(" invited you to join ").formatted(Formatting.WHITE))
                .append(Text.literal(team.getName()).formatted(team.getDisplayColor()))
                .append(Text.literal("."))), false);
        invitee.sendMessage(buttonLine(
                runButton("Accept", "/team accept", Formatting.GREEN, "Accept this team invite"),
                runButton("Decline", "/team decline", Formatting.RED, "Decline this team invite")
        ), false);
    }

    private static void notifyOnlineTeamMembers(
            CommandContext<ServerCommandSource> context,
            TeamData team,
            UUID excludedPlayerId,
            String message
    ) {
        for (ServerPlayerEntity teammate : context.getSource().getServer().getPlayerManager().getPlayerList()) {
            if (team.hasMember(teammate.getUuid()) && !teammate.getUuid().equals(excludedPlayerId)) {
                BreachedMessages.info(teammate, message);
            }
        }
    }

    private static List<TeamData> getPendingInvites(TeamState state, UUID playerId) {
        return state.getTeams()
                .stream()
                .filter(team -> team.hasInvite(playerId))
                .sorted(Comparator.comparing(TeamData::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static void showTeamColorOptions(ServerPlayerEntity player) {
        player.sendMessage(BreachedMessages.prefixed(Text.literal("Team Colors").formatted(Formatting.WHITE)), false);
        player.sendMessage(Text.literal("Click a color, or use /team color <color>.").formatted(Formatting.GRAY), false);
        for (int index = 0; index < TEAM_COLOR_OPTIONS.size(); index += 4) {
            ArrayList<Text> buttons = new ArrayList<>();
            for (int offset = 0; offset < 4 && index + offset < TEAM_COLOR_OPTIONS.size(); offset++) {
                TeamColorOption option = TEAM_COLOR_OPTIONS.get(index + offset);
                buttons.add(runButton(option.displayName(), "/team color " + option.commandName(), option.color(),
                        "Set team color to " + option.displayName()));
            }
            player.sendMessage(buttonLine(buttons.toArray(Text[]::new)), false);
        }
    }

    private static MutableText buttonLine(Text... buttons) {
        MutableText line = Text.literal("");
        for (int index = 0; index < buttons.length; index++) {
            if (index > 0) {
                line.append(Text.literal(" "));
            }
            line.append(buttons[index]);
        }
        return line;
    }

    private static MutableText runButton(String label, String command, Formatting color, String hoverText) {
        return button(label, new ClickEvent.RunCommand(command), color, hoverText);
    }

    private static MutableText suggestButton(String label, String command, Formatting color, String hoverText) {
        return button(label, new ClickEvent.SuggestCommand(command), color, hoverText);
    }

    private static MutableText button(String label, ClickEvent clickEvent, Formatting color, String hoverText) {
        return Text.literal("[" + label + "]")
                .formatted(color, Formatting.BOLD)
                .styled(style -> style
                        .withClickEvent(clickEvent)
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal(hoverText).formatted(Formatting.GRAY))));
    }

    private static String memberList(TeamData team) {
        return team.getMembers().values()
                .stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.joining(", "));
    }

    private static String memberCountLabel(int memberCount) {
        return memberCount == 1 ? "member" : "members";
    }

    private static boolean isValidTeamName(String teamName) {
        return !teamName.isEmpty()
                && teamName.length() <= MAX_TEAM_NAME_LENGTH
                && teamName.matches("[A-Za-z0-9_-]+");
    }

    private static Formatting parseTeamColor(String colorName) {
        String normalized = normalizeColorName(colorName);
        Formatting alias = TEAM_COLOR_ALIASES.get(normalized);
        if (alias != null) {
            return alias;
        }

        Formatting color = Formatting.byName(normalized);
        return color != null && color.isColor() ? color : null;
    }

    private static String displayNameForColor(Formatting color) {
        return TEAM_COLOR_OPTIONS.stream()
                .filter(option -> option.color() == color)
                .findFirst()
                .map(TeamColorOption::displayName)
                .orElse(color.getName());
    }

    private static Map<String, Formatting> createTeamColorAliases() {
        Map<String, Formatting> aliases = new HashMap<>();
        aliases.put("grey", Formatting.GRAY);
        aliases.put("dark_grey", Formatting.DARK_GRAY);
        aliases.put("silver", Formatting.GRAY);
        aliases.put("pink", Formatting.LIGHT_PURPLE);
        aliases.put("magenta", Formatting.LIGHT_PURPLE);
        aliases.put("purple", Formatting.DARK_PURPLE);
        aliases.put("cyan", Formatting.AQUA);
        aliases.put("teal", Formatting.DARK_AQUA);
        aliases.put("orange", Formatting.GOLD);
        aliases.put("lime", Formatting.GREEN);
        aliases.put("navy", Formatting.DARK_BLUE);
        return Map.copyOf(aliases);
    }

    private static List<String> createTeamColorSuggestions() {
        ArrayList<String> suggestions = new ArrayList<>();
        for (TeamColorOption option : TEAM_COLOR_OPTIONS) {
            suggestions.add(option.commandName());
        }
        suggestions.addAll(TEAM_COLOR_ALIASES.keySet());
        suggestions.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(suggestions);
    }

    private static String normalizeColorName(String colorName) {
        return colorName.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static void replaceRootLiteralCommand(CommandDispatcher<ServerCommandSource> dispatcher, String commandName) {
        try {
            removeCommandNode(dispatcher.getRoot(), "children", commandName);
            removeCommandNode(dispatcher.getRoot(), "literals", commandName);
        } catch (ReflectiveOperationException exception) {
            System.out.println("[Breached] Could not replace /" + commandName
                    + ". Command cleanup or fallback registration may be incomplete.");
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeCommandNode(
            CommandNode<ServerCommandSource> root,
            String fieldName,
            String commandName
    ) throws ReflectiveOperationException {
        Field field = CommandNode.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        Map<String, CommandNode<ServerCommandSource>> nodes = (Map<String, CommandNode<ServerCommandSource>>) field.get(root);
        nodes.remove(commandName);
    }

    private record TeamColorOption(String commandName, String displayName, Formatting color) {
    }
}
