package nrd.breached;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import nrd.breached.worldgen.BreachedDimensionRules;

public final class LowYHealthLimitManager {
    public static final int START_Y = 50;
    public static final int TARGET_MIN_Y = -60;
    public static final float NORMAL_MAX_HEALTH = 20.0F;
    public static final float MIN_MAX_HEALTH = 6.0F;

    private static final int UPDATE_INTERVAL_TICKS = 20;
    private static final Identifier MODIFIER_ID = Identifier.of(Breached.MOD_ID, "low_y_max_health_limit");
    private static final double HEALTH_PER_STEP = 1.0D;
    private static final double BLOCKS_PER_STEP = (double) (START_Y - TARGET_MIN_Y) / (NORMAL_MAX_HEALTH - MIN_MAX_HEALTH);

    private LowYHealthLimitManager() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % UPDATE_INTERVAL_TICKS != 0) {
                return;
            }

            if (!BreachedDimensionRules.isBreachedIslandWorld(server)) {
                return;
            }

            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                updatePlayer(player);
            }
        });
    }

    public static float getMaxHealthLimit(int blockY) {
        if (blockY >= START_Y) {
            return NORMAL_MAX_HEALTH;
        }

        int depth = START_Y - blockY;
        int penaltySteps = (int) Math.floor(depth / BLOCKS_PER_STEP);
        float maxHealth = (float) (NORMAL_MAX_HEALTH - penaltySteps * HEALTH_PER_STEP);
        return Math.max(MIN_MAX_HEALTH, Math.min(NORMAL_MAX_HEALTH, maxHealth));
    }

    private static void updatePlayer(ServerPlayerEntity player) {
        EntityAttributeInstance maxHealthAttribute = player.getAttributeInstance(EntityAttributes.MAX_HEALTH);
        if (maxHealthAttribute == null) {
            return;
        }

        if (!shouldLimitPlayer(player)) {
            removeLimit(player, maxHealthAttribute);
            return;
        }

        double penalty = NORMAL_MAX_HEALTH - getMaxHealthLimit(player.getBlockY());
        if (penalty <= 0.0D) {
            removeLimit(player, maxHealthAttribute);
            return;
        }

        EntityAttributeModifier existingModifier = maxHealthAttribute.getModifier(MODIFIER_ID);
        double modifierValue = -penalty;
        if (existingModifier != null && existingModifier.value() == modifierValue) {
            clampCurrentHealth(player);
            return;
        }

        maxHealthAttribute.removeModifier(MODIFIER_ID);
        maxHealthAttribute.addTemporaryModifier(new EntityAttributeModifier(
                MODIFIER_ID,
                modifierValue,
                EntityAttributeModifier.Operation.ADD_VALUE
        ));
        clampCurrentHealth(player);
    }

    private static boolean shouldLimitPlayer(ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        return world.getRegistryKey().equals(World.OVERWORLD);
    }

    private static void removeLimit(ServerPlayerEntity player, EntityAttributeInstance maxHealthAttribute) {
        if (maxHealthAttribute.removeModifier(MODIFIER_ID)) {
            clampCurrentHealth(player);
        }
    }

    private static void clampCurrentHealth(ServerPlayerEntity player) {
        float maxHealth = player.getMaxHealth();
        if (player.getHealth() > maxHealth) {
            player.setHealth(maxHealth);
        }
    }
}
