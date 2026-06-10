package nrd.breached.team;

import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TeamData {
    static final Formatting DEFAULT_DISPLAY_COLOR = Formatting.GRAY;

    private final UUID id;
    private String name;
    private UUID ownerId;
    private String ownerName;
    private Formatting displayColor;
    private final Map<UUID, String> members = new HashMap<>();
    private final Set<UUID> invitedPlayers = new HashSet<>();

    public TeamData(UUID id, String name, UUID ownerId, String ownerName) {
        this(id, name, ownerId, ownerName, DEFAULT_DISPLAY_COLOR);
    }

    public TeamData(UUID id, String name, UUID ownerId, String ownerName, Formatting displayColor) {
        this.id = id;
        this.name = name;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.displayColor = displayColor == null || !displayColor.isColor() ? DEFAULT_DISPLAY_COLOR : displayColor;
        this.members.put(ownerId, ownerName);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public void setOwner(UUID ownerId, String ownerName) {
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.members.put(ownerId, ownerName);
    }

    public String getOwnerName() {
        return ownerName;
    }

    public Formatting getDisplayColor() {
        return displayColor;
    }

    public void setDisplayColor(Formatting displayColor) {
        this.displayColor = displayColor == null || !displayColor.isColor() ? DEFAULT_DISPLAY_COLOR : displayColor;
    }

    public Map<UUID, String> getMembers() {
        return members;
    }

    public Set<UUID> getInvitedPlayers() {
        return invitedPlayers;
    }

    public boolean isOwner(UUID playerId) {
        return ownerId.equals(playerId);
    }

    public boolean hasMember(UUID playerId) {
        return members.containsKey(playerId);
    }

    public void addMember(UUID playerId, String playerName) {
        members.put(playerId, playerName);
        invitedPlayers.remove(playerId);
    }

    public void removeMember(UUID playerId) {
        members.remove(playerId);
    }

    public void invite(UUID playerId) {
        invitedPlayers.add(playerId);
    }

    public void removeInvite(UUID playerId) {
        invitedPlayers.remove(playerId);
    }

    public boolean hasInvite(UUID playerId) {
        return invitedPlayers.contains(playerId);
    }
}
