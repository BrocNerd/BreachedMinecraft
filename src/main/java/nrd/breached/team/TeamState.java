package nrd.breached.team;

import com.mojang.serialization.Codec;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class TeamState extends PersistentState {
    private static final Codec<TeamState> CODEC = NbtCompound.CODEC.xmap(TeamState::fromNbt, TeamState::toNbt);
    private static final PersistentStateType<TeamState> TYPE = new PersistentStateType<>(
            "breached_teams",
            TeamState::new,
            CODEC,
            DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private final Map<UUID, TeamData> teamsById = new HashMap<>();
    private final Map<String, UUID> teamIdsByName = new HashMap<>();
    private final Map<UUID, UUID> teamIdsByPlayer = new HashMap<>();

    public static TeamState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
    }

    public Collection<TeamData> getTeams() {
        return teamsById.values();
    }

    public Optional<TeamData> getTeam(UUID teamId) {
        return Optional.ofNullable(teamsById.get(teamId));
    }

    public Optional<TeamData> getTeam(String name) {
        return Optional.ofNullable(teamIdsByName.get(normalizeName(name))).map(teamsById::get);
    }

    public Optional<TeamData> getPlayerTeam(UUID playerId) {
        return Optional.ofNullable(teamIdsByPlayer.get(playerId)).map(teamsById::get);
    }

    public boolean hasTeamNamed(String name) {
        return teamIdsByName.containsKey(normalizeName(name));
    }

    public TeamData createTeam(String name, UUID ownerId, String ownerName) {
        TeamData team = new TeamData(UUID.randomUUID(), name, ownerId, ownerName);
        teamsById.put(team.getId(), team);
        teamIdsByName.put(normalizeName(name), team.getId());
        teamIdsByPlayer.put(ownerId, team.getId());
        markDirty();
        return team;
    }

    public void disbandTeam(TeamData team) {
        teamsById.remove(team.getId());
        teamIdsByName.remove(normalizeName(team.getName()));
        for (UUID memberId : team.getMembers().keySet()) {
            teamIdsByPlayer.remove(memberId);
        }
        markDirty();
    }

    public void addInvite(TeamData team, UUID playerId) {
        team.invite(playerId);
        markDirty();
    }

    public void addMember(TeamData team, UUID playerId, String playerName) {
        team.addMember(playerId, playerName);
        teamIdsByPlayer.put(playerId, team.getId());
        markDirty();
    }

    public void removeMember(TeamData team, UUID playerId) {
        team.removeMember(playerId);
        teamIdsByPlayer.remove(playerId);
        markDirty();
    }

    public void transferOwnership(TeamData team, UUID newOwnerId, String newOwnerName) {
        team.setOwner(newOwnerId, newOwnerName);
        markDirty();
    }

    private NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        NbtList teams = new NbtList();

        for (TeamData team : teamsById.values()) {
            NbtCompound teamNbt = new NbtCompound();
            teamNbt.putString("id", team.getId().toString());
            teamNbt.putString("name", team.getName());
            teamNbt.putString("ownerId", team.getOwnerId().toString());
            teamNbt.putString("ownerName", team.getOwnerName());

            NbtList members = new NbtList();
            for (Map.Entry<UUID, String> member : team.getMembers().entrySet()) {
                NbtCompound memberNbt = new NbtCompound();
                memberNbt.putString("id", member.getKey().toString());
                memberNbt.putString("name", member.getValue());
                members.add(memberNbt);
            }
            teamNbt.put("members", members);

            NbtList invites = new NbtList();
            for (UUID invitedPlayer : team.getInvitedPlayers()) {
                invites.add(NbtString.of(invitedPlayer.toString()));
            }
            teamNbt.put("invites", invites);

            teams.add(teamNbt);
        }

        root.put("teams", teams);
        return root;
    }

    private static TeamState fromNbt(NbtCompound root) {
        TeamState state = new TeamState();
        NbtList teams = root.getListOrEmpty("teams");

        for (int i = 0; i < teams.size(); i++) {
            NbtCompound teamNbt = teams.getCompoundOrEmpty(i);
            UUID teamId = UUID.fromString(teamNbt.getString("id", ""));
            String name = teamNbt.getString("name", "");
            UUID ownerId = UUID.fromString(teamNbt.getString("ownerId", ""));
            String ownerName = teamNbt.getString("ownerName", "");
            TeamData team = new TeamData(teamId, name, ownerId, ownerName);
            team.getMembers().clear();

            NbtList members = teamNbt.getListOrEmpty("members");
            for (int memberIndex = 0; memberIndex < members.size(); memberIndex++) {
                NbtCompound memberNbt = members.getCompoundOrEmpty(memberIndex);
                UUID memberId = UUID.fromString(memberNbt.getString("id", ""));
                String memberName = memberNbt.getString("name", "");
                team.addMember(memberId, memberName);
                state.teamIdsByPlayer.put(memberId, teamId);
            }

            NbtList invites = teamNbt.getListOrEmpty("invites");
            for (int inviteIndex = 0; inviteIndex < invites.size(); inviteIndex++) {
                String invitedPlayerId = invites.getString(inviteIndex, "");
                if (!invitedPlayerId.isEmpty()) {
                    team.invite(UUID.fromString(invitedPlayerId));
                }
            }

            state.teamsById.put(teamId, team);
            state.teamIdsByName.put(normalizeName(name), teamId);
        }

        return state;
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
