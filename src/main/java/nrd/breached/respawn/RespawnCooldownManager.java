package nrd.breached.respawn;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RespawnCooldownManager {
    private static final long BED_RESPAWN_COOLDOWN_MILLIS = 60_000L;

    private static final Map<UUID, Long> BED_RESPAWN_COOLDOWNS = new HashMap<>();
    private static final Set<UUID> PENDING_BED_RESPAWNS = new HashSet<>();

    private RespawnCooldownManager() {
    }

    public static boolean isBedRespawnOnCooldown(ServerPlayerEntity player) {
        Long expiresAt = BED_RESPAWN_COOLDOWNS.get(player.getUuid());
        if (expiresAt == null) {
            return false;
        }

        if (System.currentTimeMillis() < expiresAt) {
            return true;
        }

        BED_RESPAWN_COOLDOWNS.remove(player.getUuid());
        return false;
    }

    public static void markPendingBedRespawn(ServerPlayerEntity player) {
        PENDING_BED_RESPAWNS.add(player.getUuid());
    }

    public static void applyPendingBedRespawnCooldown(ServerPlayerEntity player) {
        if (PENDING_BED_RESPAWNS.remove(player.getUuid())) {
            BED_RESPAWN_COOLDOWNS.put(player.getUuid(), System.currentTimeMillis() + BED_RESPAWN_COOLDOWN_MILLIS);
        }
    }

    public static void clearPendingBedRespawn(ServerPlayerEntity player) {
        PENDING_BED_RESPAWNS.remove(player.getUuid());
    }
}
