package nrd.breached.team;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.waypoint.ServerWaypoint;

import java.util.List;
import java.util.Optional;

public final class TeamLocatorBar {
    private TeamLocatorBar() {
    }

    public static boolean canTrackWaypoint(ServerPlayerEntity receiver, ServerWaypoint waypoint) {
        if (!(waypoint instanceof ServerPlayerEntity trackedPlayer) || receiver == trackedPlayer) {
            return true;
        }

        Optional<TeamData> receiverTeam = TeamState.get(receiver.getEntityWorld().getServer()).getPlayerTeam(receiver.getUuid());
        return receiverTeam.isPresent() && receiverTeam.get().hasMember(trackedPlayer.getUuid());
    }

    public static void refresh(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            List<ServerWaypoint> waypoints = List.copyOf(world.getWaypointHandler().getWaypoints());
            for (ServerWaypoint waypoint : waypoints) {
                world.getWaypointHandler().refreshTracking(waypoint);
            }
        }
    }
}
