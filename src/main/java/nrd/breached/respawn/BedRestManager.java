package nrd.breached.respawn;

import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import nrd.breached.Breached;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BedRestManager {
    private static final int REST_REQUIRED_TICKS = 20 * 30;
    private static final int REST_ABSORPTION_DURATION_TICKS = 20 * 120;
    private static final float REST_ABSORPTION_HEALTH = 2.0F;
    private static final Identifier REST_ABSORPTION_MODIFIER_ID = Identifier.of(Breached.MOD_ID, "bed_rest_absorption");
    private static final Text RESTING_MESSAGE = Text.literal("Resting.");
    private static final Text RESTED_MESSAGE = Text.literal("Rested.");

    private static final Map<UUID, RestState> RESTING_PLAYERS = new HashMap<>();
    private static final Map<UUID, RestAbsorption> REST_ABSORPTION = new HashMap<>();

    private BedRestManager() {
    }

    public static void register() {
        EntitySleepEvents.START_SLEEPING.register((entity, sleepingPos) -> {
            if (!(entity instanceof ServerPlayerEntity player)) {
                return;
            }

            MinecraftServer server = player.getEntityWorld().getServer();
            if (server == null) {
                return;
            }

            RESTING_PLAYERS.put(player.getUuid(), new RestState(server.getTicks()));
        });

        EntitySleepEvents.STOP_SLEEPING.register((entity, sleepingPos) -> {
            if (entity instanceof ServerPlayerEntity player) {
                RESTING_PLAYERS.remove(player.getUuid());
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(BedRestManager::tick);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            RESTING_PLAYERS.remove(handler.player.getUuid());
            removeRestAbsorption(handler.player);
        });
    }

    public static void sendRestingStatus(ServerWorld world) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.isSleeping()) {
                player.sendMessage(RESTING_MESSAGE, true);
            }
        }
    }

    private static void tick(MinecraftServer server) {
        int currentTick = server.getTicks();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            tickRestingPlayer(player, currentTick);
            tickRestAbsorption(player, currentTick);
        }
    }

    private static void tickRestingPlayer(ServerPlayerEntity player, int currentTick) {
        UUID playerId = player.getUuid();
        RestState restState = RESTING_PLAYERS.get(playerId);
        if (restState == null) {
            return;
        }

        if (!player.isAlive() || !player.isSleeping()) {
            RESTING_PLAYERS.remove(playerId);
            return;
        }

        if (currentTick - restState.startedAtTick() < REST_REQUIRED_TICKS) {
            return;
        }

        completeRest(player, currentTick);
        RESTING_PLAYERS.remove(playerId);
    }

    private static void completeRest(ServerPlayerEntity player, int currentTick) {
        player.setHealth(player.getMaxHealth());
        grantRestAbsorption(player, currentTick);
        player.sendMessage(RESTED_MESSAGE, true);
        player.wakeUp(false, true);
    }

    private static void grantRestAbsorption(ServerPlayerEntity player, int currentTick) {
        UUID playerId = player.getUuid();
        RestAbsorption existingAbsorption = REST_ABSORPTION.get(playerId);
        float currentAbsorption = player.getAbsorptionAmount();
        if (existingAbsorption == null && currentAbsorption >= REST_ABSORPTION_HEALTH) {
            return;
        }

        EntityAttributeInstance maxAbsorptionAttribute = player.getAttributeInstance(EntityAttributes.MAX_ABSORPTION);
        if (maxAbsorptionAttribute == null) {
            return;
        }

        float baselineAbsorption = existingAbsorption != null
                ? existingAbsorption.baselineAmount()
                : currentAbsorption;

        maxAbsorptionAttribute.removeModifier(REST_ABSORPTION_MODIFIER_ID);
        maxAbsorptionAttribute.addTemporaryModifier(new EntityAttributeModifier(
                REST_ABSORPTION_MODIFIER_ID,
                REST_ABSORPTION_HEALTH,
                EntityAttributeModifier.Operation.ADD_VALUE
        ));

        if (currentAbsorption < REST_ABSORPTION_HEALTH) {
            player.setAbsorptionAmount(REST_ABSORPTION_HEALTH);
        }

        REST_ABSORPTION.put(
                playerId,
                new RestAbsorption(baselineAbsorption, REST_ABSORPTION_HEALTH, currentTick + REST_ABSORPTION_DURATION_TICKS)
        );
    }

    private static void tickRestAbsorption(ServerPlayerEntity player, int currentTick) {
        RestAbsorption absorption = REST_ABSORPTION.get(player.getUuid());
        if (absorption == null || currentTick < absorption.expiresAtTick()) {
            return;
        }

        removeRestAbsorption(player);
    }

    private static void removeRestAbsorption(ServerPlayerEntity player) {
        RestAbsorption absorption = REST_ABSORPTION.remove(player.getUuid());
        if (absorption == null) {
            return;
        }

        EntityAttributeInstance maxAbsorptionAttribute = player.getAttributeInstance(EntityAttributes.MAX_ABSORPTION);
        if (maxAbsorptionAttribute != null) {
            maxAbsorptionAttribute.removeModifier(REST_ABSORPTION_MODIFIER_ID);
        }

        float currentAbsorption = player.getAbsorptionAmount();
        if (currentAbsorption <= absorption.targetAmount() + 0.01F) {
            player.setAbsorptionAmount(Math.min(currentAbsorption, absorption.baselineAmount()));
        }
    }

    private record RestState(int startedAtTick) {
    }

    private record RestAbsorption(float baselineAmount, float targetAmount, int expiresAtTick) {
    }
}
