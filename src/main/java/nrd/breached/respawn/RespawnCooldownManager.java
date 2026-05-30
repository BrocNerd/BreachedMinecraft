package nrd.breached.respawn;

import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.WorldProperties;
import net.minecraft.world.attribute.EnvironmentAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class RespawnCooldownManager {
    private static final long BED_RESPAWN_COOLDOWN_MILLIS = 60_000L;
    private static final int MAX_BED_RESPAWN_POINTS = 3;
    private static final String BED_RESPAWN_POINTS_KEY = "breached_bed_respawns";

    private static final Map<UUID, List<WorldProperties.SpawnPoint>> BED_RESPAWN_POINTS = new HashMap<>();
    private static final Map<UUID, Map<BedRespawnKey, Long>> BED_RESPAWN_COOLDOWNS = new HashMap<>();
    private static final Map<UUID, BedRespawnKey> PENDING_BED_RESPAWNS = new HashMap<>();

    private RespawnCooldownManager() {
    }

    public static void readBedRespawns(ServerPlayerEntity player, ReadView view) {
        List<WorldProperties.SpawnPoint> points = view.read(
                BED_RESPAWN_POINTS_KEY,
                WorldProperties.SpawnPoint.CODEC.listOf()
        ).orElse(List.of());

        setBedRespawnPoints(player.getUuid(), points);
    }

    public static void writeBedRespawns(ServerPlayerEntity player, WriteView view) {
        List<WorldProperties.SpawnPoint> points = BED_RESPAWN_POINTS.get(player.getUuid());
        if (points == null || points.isEmpty()) {
            view.remove(BED_RESPAWN_POINTS_KEY);
            return;
        }

        view.put(BED_RESPAWN_POINTS_KEY, WorldProperties.SpawnPoint.CODEC.listOf(), points);
    }

    public static boolean trackBedRespawnPoint(ServerPlayerEntity player, ServerPlayerEntity.Respawn respawn) {
        if (!hasValidBedRespawnPoint(player, respawn) || hasActiveBedRespawnCooldown(player)) {
            return false;
        }

        addBedRespawnPoint(player.getUuid(), respawn.respawnData());
        return true;
    }

    public static Optional<Text> trackBedRespawnPointAndCreateMessage(ServerPlayerEntity player, ServerPlayerEntity.Respawn respawn) {
        if (!trackBedRespawnPoint(player, respawn)) {
            return Optional.empty();
        }

        return createBedRespawnSelectionMessage(player);
    }

    public static boolean isBedRespawnSelectionBlocked(ServerPlayerEntity player, ServerPlayerEntity.Respawn respawn) {
        return hasValidBedRespawnPoint(player, respawn) && hasActiveBedRespawnCooldown(player);
    }

    public static Optional<TeleportTarget> getAvailableBedRespawnTarget(
            ServerPlayerEntity player,
            TeleportTarget.PostDimensionTransition postDimensionTransition
    ) {
        ServerPlayerEntity.Respawn currentRespawn = player.getRespawn();
        trackBedRespawnPoint(player, currentRespawn);

        List<WorldProperties.SpawnPoint> points = BED_RESPAWN_POINTS.get(player.getUuid());
        if (points == null || points.isEmpty()) {
            return Optional.empty();
        }

        List<BedRespawnTarget> validTargets = new ArrayList<>();

        for (WorldProperties.SpawnPoint point : points) {
            Optional<TeleportTarget> target = createBedRespawnTarget(player, point, postDimensionTransition);
            target.ifPresent(teleportTarget -> validTargets.add(new BedRespawnTarget(point, teleportTarget)));
        }

        setBedRespawnPoints(
                player.getUuid(),
                validTargets.stream()
                        .map(BedRespawnTarget::point)
                        .toList()
        );

        for (BedRespawnTarget target : validTargets) {
            if (!isBedRespawnOnCooldown(player, target.point())) {
                markPendingBedRespawn(player, target.point());
                return Optional.of(target.teleportTarget());
            }
        }

        clearPendingBedRespawn(player);

        if (!validTargets.isEmpty()) {
            player.sendMessage(Text.literal("All saved beds are on cooldown. Respawning at world spawn."), false);
            return Optional.of(TeleportTarget.noRespawnPointSet(player, postDimensionTransition));
        }

        return Optional.empty();
    }

    public static void markPendingBedRespawn(ServerPlayerEntity player, ServerPlayerEntity.Respawn respawn) {
        if (!hasValidBedRespawnPoint(player, respawn)) {
            return;
        }

        markPendingBedRespawn(player, respawn.respawnData());
    }

    public static void applyPendingBedRespawnCooldown(ServerPlayerEntity player) {
        BedRespawnKey key = PENDING_BED_RESPAWNS.remove(player.getUuid());
        if (key == null) {
            return;
        }

        BED_RESPAWN_COOLDOWNS
                .computeIfAbsent(player.getUuid(), ignored -> new HashMap<>())
                .put(key, System.currentTimeMillis() + BED_RESPAWN_COOLDOWN_MILLIS);
    }

    public static void clearPendingBedRespawn(ServerPlayerEntity player) {
        PENDING_BED_RESPAWNS.remove(player.getUuid());
    }

    private static void setBedRespawnPoints(UUID playerUuid, List<WorldProperties.SpawnPoint> points) {
        if (points.isEmpty()) {
            BED_RESPAWN_POINTS.remove(playerUuid);
            return;
        }

        List<WorldProperties.SpawnPoint> trimmedPoints = new ArrayList<>();
        for (WorldProperties.SpawnPoint point : points) {
            if (trimmedPoints.stream().noneMatch(existingPoint -> isSameBed(existingPoint, point))) {
                trimmedPoints.add(point);
            }

            if (trimmedPoints.size() >= MAX_BED_RESPAWN_POINTS) {
                break;
            }
        }

        if (trimmedPoints.isEmpty()) {
            BED_RESPAWN_POINTS.remove(playerUuid);
            return;
        }

        BED_RESPAWN_POINTS.put(playerUuid, List.copyOf(trimmedPoints));
    }

    private static void addBedRespawnPoint(UUID playerUuid, WorldProperties.SpawnPoint point) {
        List<WorldProperties.SpawnPoint> points = new ArrayList<>(BED_RESPAWN_POINTS.getOrDefault(playerUuid, List.of()));
        points.removeIf(existingPoint -> isSameBed(existingPoint, point));
        points.add(0, point);
        setBedRespawnPoints(playerUuid, points);
    }

    private static Optional<Text> createBedRespawnSelectionMessage(ServerPlayerEntity player) {
        List<WorldProperties.SpawnPoint> points = BED_RESPAWN_POINTS.get(player.getUuid());
        if (points == null || points.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder message = new StringBuilder("New primary bed selected");
        if (points.size() >= 2) {
            message.append(", fallback 1 at ").append(formatCoordinates(points.get(1)));
        }
        if (points.size() >= 3) {
            message.append(", fallback 2 at ").append(formatCoordinates(points.get(2)));
        }
        message.append(".");

        return Optional.of(Text.literal(message.toString()));
    }

    private static String formatCoordinates(WorldProperties.SpawnPoint point) {
        BlockPos pos = point.getPos();
        return "x " + pos.getX() + ", y " + pos.getY() + ", z " + pos.getZ();
    }

    private static Optional<TeleportTarget> createBedRespawnTarget(
            ServerPlayerEntity player,
            WorldProperties.SpawnPoint point,
            TeleportTarget.PostDimensionTransition postDimensionTransition
    ) {
        ServerWorld world = player.getEntityWorld().getServer().getWorld(point.getDimension());
        if (world == null || !canSetBedSpawn(world, point)) {
            return Optional.empty();
        }

        BlockState state = world.getBlockState(point.getPos());
        if (!(state.getBlock() instanceof BedBlock)) {
            return Optional.empty();
        }

        Direction direction = state.get(BedBlock.FACING);
        return BedBlock.findWakeUpPosition(EntityType.PLAYER, world, point.getPos(), direction, point.yaw())
                .map(pos -> new TeleportTarget(world, pos, Vec3d.ZERO, point.yaw(), point.pitch(), postDimensionTransition));
    }

    private static boolean isValidBedRespawnPoint(ServerPlayerEntity player, WorldProperties.SpawnPoint point) {
        ServerWorld world = player.getEntityWorld().getServer().getWorld(point.getDimension());
        return world != null
                && canSetBedSpawn(world, point)
                && world.getBlockState(point.getPos()).getBlock() instanceof BedBlock;
    }

    private static boolean hasValidBedRespawnPoint(ServerPlayerEntity player, ServerPlayerEntity.Respawn respawn) {
        return respawn != null && respawn.respawnData() != null && isValidBedRespawnPoint(player, respawn.respawnData());
    }

    private static boolean canSetBedSpawn(ServerWorld world, WorldProperties.SpawnPoint point) {
        return world.getEnvironmentAttributes()
                .getAttributeValue(EnvironmentAttributes.BED_RULE_GAMEPLAY, point.getPos())
                .canSetSpawn(world);
    }

    private static boolean hasActiveBedRespawnCooldown(ServerPlayerEntity player) {
        Map<BedRespawnKey, Long> playerCooldowns = BED_RESPAWN_COOLDOWNS.get(player.getUuid());
        if (playerCooldowns == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        playerCooldowns.values().removeIf(expiresAt -> now >= expiresAt);
        if (playerCooldowns.isEmpty()) {
            BED_RESPAWN_COOLDOWNS.remove(player.getUuid());
            return false;
        }

        return true;
    }

    private static boolean isBedRespawnOnCooldown(ServerPlayerEntity player, WorldProperties.SpawnPoint point) {
        Map<BedRespawnKey, Long> playerCooldowns = BED_RESPAWN_COOLDOWNS.get(player.getUuid());
        if (playerCooldowns == null) {
            return false;
        }

        BedRespawnKey key = BedRespawnKey.from(point);
        Long expiresAt = playerCooldowns.get(key);
        if (expiresAt == null) {
            return false;
        }

        if (System.currentTimeMillis() < expiresAt) {
            return true;
        }

        playerCooldowns.remove(key);
        if (playerCooldowns.isEmpty()) {
            BED_RESPAWN_COOLDOWNS.remove(player.getUuid());
        }

        return false;
    }

    private static void markPendingBedRespawn(ServerPlayerEntity player, WorldProperties.SpawnPoint point) {
        PENDING_BED_RESPAWNS.put(player.getUuid(), BedRespawnKey.from(point));
    }

    private static boolean isSameBed(WorldProperties.SpawnPoint left, WorldProperties.SpawnPoint right) {
        return left.getDimension().equals(right.getDimension()) && left.getPos().equals(right.getPos());
    }

    private record BedRespawnTarget(WorldProperties.SpawnPoint point, TeleportTarget teleportTarget) {
    }

    private record BedRespawnKey(String world, int x, int y, int z) {
        private static BedRespawnKey from(WorldProperties.SpawnPoint point) {
            return new BedRespawnKey(
                    point.getDimension().getValue().toString(),
                    point.getPos().getX(),
                    point.getPos().getY(),
                    point.getPos().getZ()
            );
        }
    }
}
