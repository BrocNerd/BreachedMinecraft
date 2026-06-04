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
import net.minecraft.world.World;
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
    private static final String LEGACY_SELECTED_BED_RESPAWN_KEY = "breached_selected_bed_respawn";

    private static final Map<UUID, List<WorldProperties.SpawnPoint>> BED_RESPAWN_POINTS = new HashMap<>();
    private static final Map<UUID, Map<BedRespawnKey, Long>> BED_RESPAWN_COOLDOWNS = new HashMap<>();
    private static final Map<UUID, BedRespawnKey> PENDING_BED_RESPAWNS = new HashMap<>();
    private static final Map<UUID, BedRespawnKey> REQUESTED_BED_RESPAWNS = new HashMap<>();
    private static final Map<UUID, Boolean> PENDING_TOWNHALL_RESPAWNS = new HashMap<>();

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
        view.remove(LEGACY_SELECTED_BED_RESPAWN_KEY);
        List<WorldProperties.SpawnPoint> points = BED_RESPAWN_POINTS.get(player.getUuid());
        if (points == null || points.isEmpty()) {
            view.remove(BED_RESPAWN_POINTS_KEY);
            return;
        }

        view.put(BED_RESPAWN_POINTS_KEY, WorldProperties.SpawnPoint.CODEC.listOf(), points);
    }

    public static List<BlockPos> getSavedOverworldBedPositions(ServerPlayerEntity player) {
        List<WorldProperties.SpawnPoint> points = BED_RESPAWN_POINTS.get(player.getUuid());
        if (points == null || points.isEmpty()) {
            return List.of();
        }

        return points.stream()
                .filter(point -> point.getDimension().equals(World.OVERWORLD))
                .map(point -> point.getPos().toImmutable())
                .toList();
    }

    public static List<BedRespawnStatus> getSavedOverworldBedStatuses(ServerPlayerEntity player) {
        List<WorldProperties.SpawnPoint> points = BED_RESPAWN_POINTS.get(player.getUuid());
        if (points == null || points.isEmpty()) {
            return List.of();
        }

        List<WorldProperties.SpawnPoint> validPoints = points.stream()
                .filter(point -> isValidBedRespawnPoint(player, point))
                .toList();
        if (validPoints.size() != points.size()) {
            setBedRespawnPoints(player.getUuid(), validPoints);
        }

        if (validPoints.isEmpty()) {
            return List.of();
        }

        List<BedRespawnStatus> statuses = new ArrayList<>();
        for (int index = 0; index < validPoints.size(); index++) {
            WorldProperties.SpawnPoint point = validPoints.get(index);
            if (!point.getDimension().equals(World.OVERWORLD)) {
                continue;
            }

            int cooldownRemainingTicks = getBedRespawnCooldownRemainingTicks(player, point);
            statuses.add(new BedRespawnStatus(
                    index,
                    point.getPos().toImmutable(),
                    cooldownRemainingTicks <= 0,
                    cooldownRemainingTicks
            ));
        }

        return statuses;
    }

    public static void requestBedRespawn(ServerPlayerEntity player, int bedIndex) {
        List<WorldProperties.SpawnPoint> points = BED_RESPAWN_POINTS.get(player.getUuid());
        if (points == null || points.isEmpty()) {
            REQUESTED_BED_RESPAWNS.remove(player.getUuid());
            return;
        }

        if (bedIndex < 0 || bedIndex >= points.size()) {
            REQUESTED_BED_RESPAWNS.remove(player.getUuid());
            return;
        }

        WorldProperties.SpawnPoint requestedPoint = points.get(bedIndex);
        if (!isValidBedRespawnPoint(player, requestedPoint)) {
            setBedRespawnPoints(player.getUuid(), points.stream()
                    .filter(point -> isValidBedRespawnPoint(player, point))
                    .toList());
            REQUESTED_BED_RESPAWNS.remove(player.getUuid());
            return;
        }

        if (isBedRespawnOnCooldown(player, requestedPoint)) {
            REQUESTED_BED_RESPAWNS.remove(player.getUuid());
            return;
        }

        REQUESTED_BED_RESPAWNS.put(player.getUuid(), BedRespawnKey.from(requestedPoint));
        PENDING_TOWNHALL_RESPAWNS.remove(player.getUuid());
    }

    public static boolean trackBedRespawnPoint(ServerPlayerEntity player, ServerPlayerEntity.Respawn respawn) {
        if (!hasValidBedRespawnPoint(player, respawn) || hasActiveBedRespawnCooldown(player)) {
            return false;
        }

        addBedRespawnPoint(player.getUuid(), respawn.respawnData());
        return true;
    }

    public static Optional<Text> trackBedRespawnPointAndCreateMessage(ServerPlayerEntity player, ServerPlayerEntity.Respawn respawn) {
        if (!hasValidBedRespawnPoint(player, respawn) || hasActiveBedRespawnCooldown(player)) {
            return Optional.empty();
        }

        boolean alreadySaved = hasSavedBedRespawnPoint(player.getUuid(), respawn.respawnData());
        boolean removedOldBed = addBedRespawnPoint(player.getUuid(), respawn.respawnData());
        if (alreadySaved) {
            return Optional.empty();
        }

        return Optional.of(createBedRespawnSelectionMessage(removedOldBed));
    }

    public static boolean isBedRespawnSelectionBlocked(ServerPlayerEntity player, ServerPlayerEntity.Respawn respawn) {
        return hasValidBedRespawnPoint(player, respawn) && hasActiveBedRespawnCooldown(player);
    }

    public static Optional<TeleportTarget> getAvailableBedRespawnTarget(
            ServerPlayerEntity player,
            TeleportTarget.PostDimensionTransition postDimensionTransition
    ) {
        if (PENDING_TOWNHALL_RESPAWNS.remove(player.getUuid()) != null) {
            clearPendingBedRespawn(player);
            REQUESTED_BED_RESPAWNS.remove(player.getUuid());
            return Optional.of(TeleportTarget.noRespawnPointSet(player, postDimensionTransition));
        }

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

        Optional<BedRespawnTarget> requestedTarget = getRequestedBedRespawnTarget(player, validTargets);
        if (requestedTarget.isPresent() && !isBedRespawnOnCooldown(player, requestedTarget.get().point())) {
            markPendingBedRespawn(player, requestedTarget.get().point());
            return Optional.of(requestedTarget.get().teleportTarget());
        }

        clearPendingBedRespawn(player);

        if (!validTargets.isEmpty()) {
            return Optional.of(TeleportTarget.noRespawnPointSet(player, postDimensionTransition));
        }

        return Optional.empty();
    }

    public static void requestTownhallRespawn(ServerPlayerEntity player) {
        PENDING_TOWNHALL_RESPAWNS.put(player.getUuid(), true);
        REQUESTED_BED_RESPAWNS.remove(player.getUuid());
        clearPendingBedRespawn(player);
    }

    public static void markPendingBedRespawn(ServerPlayerEntity player, ServerPlayerEntity.Respawn respawn) {
        if (PENDING_BED_RESPAWNS.containsKey(player.getUuid())) {
            return;
        }

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

    private static boolean addBedRespawnPoint(UUID playerUuid, WorldProperties.SpawnPoint point) {
        List<WorldProperties.SpawnPoint> points = new ArrayList<>(BED_RESPAWN_POINTS.getOrDefault(playerUuid, List.of()));
        boolean alreadySaved = points.stream().anyMatch(existingPoint -> isSameBed(existingPoint, point));
        boolean removesOldBed = !alreadySaved && points.size() >= MAX_BED_RESPAWN_POINTS;
        points.removeIf(existingPoint -> isSameBed(existingPoint, point));
        points.add(0, point);
        setBedRespawnPoints(playerUuid, points);
        return removesOldBed;
    }

    private static boolean hasSavedBedRespawnPoint(UUID playerUuid, WorldProperties.SpawnPoint point) {
        return BED_RESPAWN_POINTS.getOrDefault(playerUuid, List.of())
                .stream()
                .anyMatch(existingPoint -> isSameBed(existingPoint, point));
    }

    private static Text createBedRespawnSelectionMessage(boolean removedOldBed) {
        return Text.literal(removedOldBed ? "New bed saved. Oldest bed removed." : "New bed saved.");
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
        return getBedRespawnCooldownRemainingTicks(player, point) > 0;
    }

    private static int getBedRespawnCooldownRemainingTicks(ServerPlayerEntity player, WorldProperties.SpawnPoint point) {
        Map<BedRespawnKey, Long> playerCooldowns = BED_RESPAWN_COOLDOWNS.get(player.getUuid());
        if (playerCooldowns == null) {
            return 0;
        }

        BedRespawnKey key = BedRespawnKey.from(point);
        Long expiresAt = playerCooldowns.get(key);
        if (expiresAt == null) {
            return 0;
        }

        long now = System.currentTimeMillis();
        if (now < expiresAt) {
            return Math.max(1, (int) Math.ceil((expiresAt - now) / 50.0D));
        }

        playerCooldowns.remove(key);
        if (playerCooldowns.isEmpty()) {
            BED_RESPAWN_COOLDOWNS.remove(player.getUuid());
        }

        return 0;
    }

    private static void markPendingBedRespawn(ServerPlayerEntity player, WorldProperties.SpawnPoint point) {
        PENDING_BED_RESPAWNS.put(player.getUuid(), BedRespawnKey.from(point));
    }

    private static Optional<BedRespawnTarget> getRequestedBedRespawnTarget(ServerPlayerEntity player, List<BedRespawnTarget> targets) {
        BedRespawnKey requestedKey = REQUESTED_BED_RESPAWNS.remove(player.getUuid());
        if (requestedKey == null || targets.isEmpty()) {
            return Optional.empty();
        }

        return targets.stream()
                .filter(target -> BedRespawnKey.from(target.point()).equals(requestedKey))
                .findFirst();
    }

    public record BedRespawnStatus(int bedIndex, BlockPos pos, boolean available, int cooldownRemainingTicks) {
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
