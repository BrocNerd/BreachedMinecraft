package nrd.breached.combat;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import nrd.breached.Breached;
import nrd.breached.worldgen.BreachedDimensionRules;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class AdrenalineManager {
    public static final RegistryEntry<StatusEffect> ADRENALINE_EFFECT = Registry.registerReference(
            Registries.STATUS_EFFECT,
            Identifier.of(Breached.MOD_ID, "adrenaline"),
            new AdrenalineStatusEffect()
    );

    private static final int ADRENALINE_DURATION_TICKS = 45 * 20;
    private static final int UPDATE_INTERVAL_TICKS = 20;
    private static final int MINING_FATIGUE_REFRESH_TICKS = 30;
    private static final int MINING_FATIGUE_AMPLIFIER = 1;
    private static final Map<UUID, Long> ADRENALINE_EXPIRES_AT = new HashMap<>();

    private AdrenalineManager() {
    }

    public static void register() {
        CombatLogoutBodyManager.register();
        ServerLivingEntityEvents.AFTER_DAMAGE.register(AdrenalineManager::afterDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayerEntity player) {
                clear(player);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> syncJoinedPlayer(handler.player, server));
        ServerTickEvents.END_SERVER_TICK.register(AdrenalineManager::tick);
    }

    public static void handlePlayerDisconnectBeforeSave(ServerPlayerEntity player) {
        MinecraftServer server = player.getEntityWorld().getServer();
        if (server != null && isActive(player, server)) {
            CombatLogoutBodyManager.spawn(player, server);
        }
    }

    private static void afterDamage(LivingEntity entity, DamageSource source, float baseDamageTaken, float damageTaken, boolean blocked) {
        if (damageTaken <= 0.0F || !(entity instanceof ServerPlayerEntity victim)) {
            return;
        }

        Entity attackerEntity = source.getAttacker();
        if (!(attackerEntity instanceof ServerPlayerEntity attacker) || attacker.getUuid().equals(victim.getUuid())) {
            return;
        }

        MinecraftServer server = victim.getEntityWorld().getServer();
        if (server == null || !BreachedDimensionRules.isBreachedIslandWorld(server)) {
            return;
        }

        tag(attacker, server);
        tag(victim, server);
    }

    private static void tick(MinecraftServer server) {
        if (server.getTicks() % UPDATE_INTERVAL_TICKS != 0) {
            return;
        }

        long now = server.getTicks();
        Iterator<Map.Entry<UUID, Long>> iterator = ADRENALINE_EXPIRES_AT.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            long remainingTicks = entry.getValue() - now;
            if (remainingTicks <= 0L) {
                iterator.remove();
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player != null) {
                    player.removeStatusEffect(ADRENALINE_EFFECT);
                }
                continue;
            }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }

            applyAdrenalineEffect(player, (int) remainingTicks);
            applyMiningFatigue(player);
        }
    }

    private static void tag(ServerPlayerEntity player, MinecraftServer server) {
        ADRENALINE_EXPIRES_AT.put(player.getUuid(), (long) server.getTicks() + ADRENALINE_DURATION_TICKS);
        applyAdrenalineEffect(player, ADRENALINE_DURATION_TICKS);
        applyMiningFatigue(player);
    }

    private static boolean isActive(ServerPlayerEntity player, MinecraftServer server) {
        Long expiresAtTick = ADRENALINE_EXPIRES_AT.get(player.getUuid());
        return expiresAtTick != null && expiresAtTick > server.getTicks();
    }

    private static void syncJoinedPlayer(ServerPlayerEntity player, MinecraftServer server) {
        Long expiresAtTick = ADRENALINE_EXPIRES_AT.get(player.getUuid());
        if (expiresAtTick == null) {
            player.removeStatusEffect(ADRENALINE_EFFECT);
            return;
        }

        int remainingTicks = (int) (expiresAtTick - server.getTicks());
        if (remainingTicks <= 0) {
            ADRENALINE_EXPIRES_AT.remove(player.getUuid());
            player.removeStatusEffect(ADRENALINE_EFFECT);
            return;
        }

        applyAdrenalineEffect(player, remainingTicks);
        applyMiningFatigue(player);
    }

    private static void clear(ServerPlayerEntity player) {
        ADRENALINE_EXPIRES_AT.remove(player.getUuid());
    }

    private static void applyMiningFatigue(ServerPlayerEntity player) {
        player.addStatusEffect(new StatusEffectInstance(
                StatusEffects.MINING_FATIGUE,
                MINING_FATIGUE_REFRESH_TICKS,
                MINING_FATIGUE_AMPLIFIER,
                false,
                false,
                true
        ));
    }

    private static void applyAdrenalineEffect(ServerPlayerEntity player, int durationTicks) {
        player.addStatusEffect(new StatusEffectInstance(
                ADRENALINE_EFFECT,
                durationTicks,
                0,
                false,
                false,
                true
        ));
    }
}
