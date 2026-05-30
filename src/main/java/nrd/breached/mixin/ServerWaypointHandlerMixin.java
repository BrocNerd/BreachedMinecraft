package nrd.breached.mixin;

import com.google.common.collect.Table;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerWaypointHandler;
import net.minecraft.world.waypoint.ServerWaypoint;
import nrd.breached.team.TeamLocatorBar;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerWaypointHandler.class)
public class ServerWaypointHandlerMixin {
    @Shadow
    @Final
    private Table<ServerPlayerEntity, ServerWaypoint, ServerWaypoint.WaypointTracker> trackers;

    @Inject(
            method = "refreshTracking(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/world/waypoint/ServerWaypoint;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void breached$filterNewPlayerWaypointTracking(ServerPlayerEntity receiver, ServerWaypoint waypoint, CallbackInfo ci) {
        if (TeamLocatorBar.canTrackWaypoint(receiver, waypoint)) {
            return;
        }

        breached$untrack(receiver, waypoint);
        ci.cancel();
    }

    @Inject(
            method = "refreshTracking(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/world/waypoint/ServerWaypoint;Lnet/minecraft/world/waypoint/ServerWaypoint$WaypointTracker;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void breached$filterExistingPlayerWaypointTracking(
            ServerPlayerEntity receiver,
            ServerWaypoint waypoint,
            ServerWaypoint.WaypointTracker tracker,
            CallbackInfo ci
    ) {
        if (TeamLocatorBar.canTrackWaypoint(receiver, waypoint)) {
            return;
        }

        tracker.untrack();
        trackers.remove(receiver, waypoint);
        ci.cancel();
    }

    @Unique
    private void breached$untrack(ServerPlayerEntity receiver, ServerWaypoint waypoint) {
        ServerWaypoint.WaypointTracker tracker = trackers.remove(receiver, waypoint);
        if (tracker != null) {
            tracker.untrack();
        }
    }
}
