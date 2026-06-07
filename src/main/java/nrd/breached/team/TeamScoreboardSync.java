package nrd.breached.team;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TeamScoreboardSync {
    private static final String SCOREBOARD_TEAM_PREFIX = "br_";
    private static final int SCOREBOARD_TEAM_ID_HEX_LENGTH = 12;

    private TeamScoreboardSync() {
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sync(server));
    }

    public static void sync(MinecraftServer server) {
        TeamState state = TeamState.get(server);
        Scoreboard scoreboard = server.getScoreboard();
        Map<String, Set<String>> desiredPlayersByScoreboardTeam = new HashMap<>();

        for (TeamData teamData : state.getTeams()) {
            String scoreboardTeamName = getScoreboardTeamName(teamData.getId());
            Team scoreboardTeam = getOrCreateScoreboardTeam(scoreboard, scoreboardTeamName);
            configureScoreboardTeam(scoreboardTeam, teamData);
            desiredPlayersByScoreboardTeam.put(scoreboardTeamName, new HashSet<>());
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            state.getPlayerTeam(player.getUuid()).ifPresent(teamData -> {
                String scoreboardTeamName = getScoreboardTeamName(teamData.getId());
                desiredPlayersByScoreboardTeam
                        .computeIfAbsent(scoreboardTeamName, key -> new HashSet<>())
                        .add(player.getGameProfile().name());
            });
        }

        removeStaleBreachedScoreboardTeams(scoreboard, desiredPlayersByScoreboardTeam.keySet());
        syncScoreboardMembership(scoreboard, desiredPlayersByScoreboardTeam);
    }

    private static Team getOrCreateScoreboardTeam(Scoreboard scoreboard, String scoreboardTeamName) {
        Team scoreboardTeam = scoreboard.getTeam(scoreboardTeamName);
        if (scoreboardTeam == null) {
            scoreboardTeam = scoreboard.addTeam(scoreboardTeamName);
        }

        return scoreboardTeam;
    }

    private static void configureScoreboardTeam(Team scoreboardTeam, TeamData teamData) {
        scoreboardTeam.setDisplayName(Text.literal(teamData.getName()).formatted(teamData.getDisplayColor()));
        scoreboardTeam.setPrefix(Text.literal("[" + teamData.getName() + "] ").formatted(teamData.getDisplayColor()));
        scoreboardTeam.setSuffix(Text.empty());
        scoreboardTeam.setColor(Formatting.RESET);
        scoreboardTeam.setFriendlyFireAllowed(true);
        scoreboardTeam.setShowFriendlyInvisibles(false);
        scoreboardTeam.setNameTagVisibilityRule(AbstractTeam.VisibilityRule.ALWAYS);
        scoreboardTeam.setDeathMessageVisibilityRule(AbstractTeam.VisibilityRule.ALWAYS);
        scoreboardTeam.setCollisionRule(AbstractTeam.CollisionRule.ALWAYS);
    }

    private static void removeStaleBreachedScoreboardTeams(Scoreboard scoreboard, Set<String> desiredScoreboardTeamNames) {
        List<Team> existingTeams = List.copyOf(scoreboard.getTeams());
        for (Team scoreboardTeam : existingTeams) {
            String scoreboardTeamName = scoreboardTeam.getName();
            if (isBreachedScoreboardTeam(scoreboardTeamName) && !desiredScoreboardTeamNames.contains(scoreboardTeamName)) {
                scoreboard.removeTeam(scoreboardTeam);
            }
        }
    }

    private static void syncScoreboardMembership(Scoreboard scoreboard, Map<String, Set<String>> desiredPlayersByScoreboardTeam) {
        for (Map.Entry<String, Set<String>> desiredTeam : desiredPlayersByScoreboardTeam.entrySet()) {
            Team scoreboardTeam = scoreboard.getTeam(desiredTeam.getKey());
            if (scoreboardTeam == null) {
                continue;
            }

            Set<String> desiredPlayers = desiredTeam.getValue();
            for (String currentPlayerName : List.copyOf(scoreboardTeam.getPlayerList())) {
                if (!desiredPlayers.contains(currentPlayerName)) {
                    scoreboard.removeScoreHolderFromTeam(currentPlayerName, scoreboardTeam);
                }
            }

            for (String desiredPlayerName : desiredPlayers) {
                if (scoreboardTeam.getPlayerList().contains(desiredPlayerName)) {
                    continue;
                }

                Team currentTeam = scoreboard.getScoreHolderTeam(desiredPlayerName);
                if (currentTeam != null && isBreachedScoreboardTeam(currentTeam.getName())) {
                    scoreboard.removeScoreHolderFromTeam(desiredPlayerName, currentTeam);
                }
                scoreboard.addScoreHolderToTeam(desiredPlayerName, scoreboardTeam);
            }
        }
    }

    private static boolean isBreachedScoreboardTeam(String scoreboardTeamName) {
        return scoreboardTeamName.startsWith(SCOREBOARD_TEAM_PREFIX);
    }

    private static String getScoreboardTeamName(UUID teamId) {
        return SCOREBOARD_TEAM_PREFIX + teamId.toString()
                .replace("-", "")
                .substring(0, SCOREBOARD_TEAM_ID_HEX_LENGTH);
    }
}
