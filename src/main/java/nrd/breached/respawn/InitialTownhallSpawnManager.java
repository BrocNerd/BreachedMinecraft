package nrd.breached.respawn;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stats;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProperties;
import net.minecraft.world.level.ServerWorldProperties;
import nrd.breached.worldgen.BreachedDimensionRules;
import nrd.breached.worldgen.BreachedStructurePlacementManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class InitialTownhallSpawnManager {
    private static final String INITIAL_TOWNHALL_SPAWN_DONE_KEY = "breached_initial_townhall_spawn_done";
    private static final int INITIAL_TOWNHALL_SPAWN_DELAY_TICKS = 20;
    private static final int INITIAL_TOWNHALL_SPAWN_RETRY_INTERVAL_TICKS = 5;
    private static final int MAX_INITIAL_TOWNHALL_SPAWN_ATTEMPTS = 20 * 10 / INITIAL_TOWNHALL_SPAWN_RETRY_INTERVAL_TICKS;
    private static final int FIRST_LOGIN_PLAYTIME_GRACE_TICKS = 20 * 5;
    private static final Set<UUID> COMPLETED_PLAYERS = new HashSet<>();
    private static final Map<UUID, PendingSpawnCorrection> PENDING_PLAYERS = new HashMap<>();

    private InitialTownhallSpawnManager() {
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> queueIfNeeded(handler.player, server));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PENDING_PLAYERS.remove(handler.player.getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(InitialTownhallSpawnManager::tick);
    }

    public static void readInitialTownhallSpawnFlag(ServerPlayerEntity player, ReadView view) {
        UUID playerId = player.getUuid();
        if (view.getBoolean(INITIAL_TOWNHALL_SPAWN_DONE_KEY, false)) {
            COMPLETED_PLAYERS.add(playerId);
            return;
        }

        COMPLETED_PLAYERS.remove(playerId);
    }

    public static void writeInitialTownhallSpawnFlag(ServerPlayerEntity player, WriteView view) {
        if (!COMPLETED_PLAYERS.contains(player.getUuid())) {
            view.remove(INITIAL_TOWNHALL_SPAWN_DONE_KEY);
            return;
        }

        view.putBoolean(INITIAL_TOWNHALL_SPAWN_DONE_KEY, true);
    }

    private static void queueIfNeeded(ServerPlayerEntity player, MinecraftServer server) {
        UUID playerId = player.getUuid();
        if (!BreachedDimensionRules.isBreachedIslandWorld(server) || COMPLETED_PLAYERS.contains(playerId)) {
            return;
        }

        if (!isWithinFirstLoginWindow(player)) {
            COMPLETED_PLAYERS.add(playerId);
            return;
        }

        PENDING_PLAYERS.put(playerId, new PendingSpawnCorrection(
                server.getTicks() + INITIAL_TOWNHALL_SPAWN_DELAY_TICKS,
                0
        ));
    }

    private static boolean isWithinFirstLoginWindow(ServerPlayerEntity player) {
        return player.getStatHandler().getStat(Stats.CUSTOM, Stats.PLAY_TIME) <= FIRST_LOGIN_PLAYTIME_GRACE_TICKS;
    }

    private static void tick(MinecraftServer server) {
        if (PENDING_PLAYERS.isEmpty()) {
            return;
        }

        long currentTick = server.getTicks();
        Iterator<Map.Entry<UUID, PendingSpawnCorrection>> iterator = PENDING_PLAYERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, PendingSpawnCorrection> entry = iterator.next();
            PendingSpawnCorrection pending = entry.getValue();
            if (currentTick < pending.nextAttemptTick()) {
                continue;
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }

            if (!BreachedDimensionRules.isBreachedIslandWorld(server)) {
                iterator.remove();
                continue;
            }

            ServerWorld overworld = server.getWorld(World.OVERWORLD);
            if (overworld == null) {
                iterator.remove();
                continue;
            }

            Optional<BlockPos> spawnPos = BreachedStructurePlacementManager.ensureTownhallSpawnReady(overworld);
            if (spawnPos.isPresent()) {
                movePlayerToTownhallSpawn(player, overworld, spawnPos.get());
                COMPLETED_PLAYERS.add(player.getUuid());
                iterator.remove();
                continue;
            }

            int nextAttempts = pending.attempts() + 1;
            if (nextAttempts >= MAX_INITIAL_TOWNHALL_SPAWN_ATTEMPTS) {
                System.out.println("[Breached] Could not correct first login spawn for "
                        + player.getGameProfile().name()
                        + ": Town Hall spawn was not ready.");
                iterator.remove();
                continue;
            }

            entry.setValue(new PendingSpawnCorrection(
                    currentTick + INITIAL_TOWNHALL_SPAWN_RETRY_INTERVAL_TICKS,
                    nextAttempts
            ));
        }
    }

    private static void movePlayerToTownhallSpawn(ServerPlayerEntity player, ServerWorld world, BlockPos spawnPos) {
        world.getChunk(Math.floorDiv(spawnPos.getX(), 16), Math.floorDiv(spawnPos.getZ(), 16));
        ((ServerWorldProperties) world.getLevelProperties()).setSpawnPoint(WorldProperties.SpawnPoint.create(
                world.getRegistryKey(),
                spawnPos,
                0.0F,
                0.0F
        ));
        player.stopRiding();
        player.teleport(
                world,
                spawnPos.getX() + 0.5D,
                spawnPos.getY(),
                spawnPos.getZ() + 0.5D,
                Set.<PositionFlag>of(),
                0.0F,
                0.0F,
                false
        );
        player.fallDistance = 0.0F;
    }

    private record PendingSpawnCorrection(long nextAttemptTick, int attempts) {
    }
}
