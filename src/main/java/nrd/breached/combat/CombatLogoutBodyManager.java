package nrd.breached.combat;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;
import nrd.breached.message.BreachedMessages;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CombatLogoutBodyManager {
    private static final int LOGOUT_BODY_DURATION_TICKS = 60 * 20;
    private static final int EXPIRY_CHECK_INTERVAL_TICKS = 20;
    private static final String LOGOUT_BODY_TAG = "breached_combat_logout_body";
    private static final String LOGOUT_BODY_OWNER_TAG_PREFIX = "breached_combat_logout_owner:";
    private static final Map<UUID, LogoutBody> BODIES_BY_ENTITY = new HashMap<>();
    private static final Map<UUID, UUID> BODY_ENTITY_BY_PLAYER = new HashMap<>();

    private CombatLogoutBodyManager() {
    }

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register(CombatLogoutBodyManager::afterDeath);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID playerUuid = handler.player.getUuid();
            removeExistingBodyAndRestoreLoot(handler.player, server);
            if (CombatLogoutLootState.get(server).hasPendingDeath(playerUuid)) {
                BreachedMessages.error(handler.player, "Your combat logout body was killed.");
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(CombatLogoutBodyManager::tick);
    }

    public static void spawn(ServerPlayerEntity player, MinecraftServer server) {
        ServerWorld world = player.getEntityWorld();
        removeExistingBodyAndRestoreLoot(player, server);
        List<CombatLogoutLootState.StoredStack> loot = captureAndClearLoot(player, server);

        ArmorStandEntity body = EntityType.ARMOR_STAND.create(world, SpawnReason.TRIGGERED);
        if (body == null) {
            restoreLoot(player, server, loot);
            return;
        }

        body.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        body.setCustomName(Text.literal(player.getGameProfile().name() + "'s Combat Body"));
        body.setCustomNameVisible(true);
        body.setNoGravity(true);
        body.setInvulnerable(false);
        body.setSilent(true);
        body.setShowArms(true);
        body.setHideBasePlate(true);
        body.setHealth(Math.max(1.0F, Math.min(player.getHealth(), body.getMaxHealth())));
        body.addCommandTag(LOGOUT_BODY_TAG);
        body.addCommandTag(LOGOUT_BODY_OWNER_TAG_PREFIX + player.getUuid());

        if (!world.spawnEntity(body)) {
            restoreLoot(player, server, loot);
            return;
        }

        LogoutBody logoutBody = new LogoutBody(
                body.getUuid(),
                player.getUuid(),
                player.getGameProfile().name(),
                world.getRegistryKey(),
                server.getTicks() + LOGOUT_BODY_DURATION_TICKS
        );
        BODIES_BY_ENTITY.put(body.getUuid(), logoutBody);
        BODY_ENTITY_BY_PLAYER.put(player.getUuid(), body.getUuid());
        CombatLogoutLootState.get(server).put(new CombatLogoutLootState.Entry(
                player.getUuid(),
                body.getUuid(),
                player.getGameProfile().name(),
                world.getRegistryKey(),
                loot
        ));
    }

    private static void afterDeath(LivingEntity entity, net.minecraft.entity.damage.DamageSource source) {
        handleKilledBody(entity);
    }

    public static void handleKilledBody(Entity entity) {
        if (!entity.getCommandTags().contains(LOGOUT_BODY_TAG)) {
            return;
        }

        CombatLogoutLootState lootState = CombatLogoutLootState.get(entity.getEntityWorld().getServer());
        CombatLogoutLootState.Entry lootEntry = lootState.removeByEntity(entity.getUuid());
        LogoutBody body = BODIES_BY_ENTITY.remove(entity.getUuid());
        if (body == null) {
            body = getOrphanLogoutBody(entity);
            if (body == null) {
                return;
            }
        }

        BODY_ENTITY_BY_PLAYER.remove(body.playerUuid(), body.entityUuid());
        MinecraftServer server = entity.getEntityWorld().getServer();
        if (lootEntry != null) {
            dropLoot(server, entity, lootEntry.stacks());
        }
        lootState.addPendingDeath(body.playerUuid());
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(body.playerUuid());
        if (player != null) {
            BreachedMessages.error(player, "Your combat logout body was killed.");
        }

        server.getPlayerManager().broadcast(Text.literal(body.playerName()
                + "'s combat logout body was killed.").formatted(Formatting.RED), false);
    }

    public static boolean isLogoutBody(Entity entity) {
        return entity.getCommandTags().contains(LOGOUT_BODY_TAG);
    }

    private static void tick(MinecraftServer server) {
        killPendingOnlinePlayers(server);

        if (server.getTicks() % EXPIRY_CHECK_INTERVAL_TICKS != 0) {
            return;
        }

        long now = server.getTicks();
        Iterator<Map.Entry<UUID, LogoutBody>> iterator = BODIES_BY_ENTITY.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, LogoutBody> entry = iterator.next();
            LogoutBody body = entry.getValue();
            if (body.expiresAtServerTick() > now) {
                continue;
            }

            Entity entity = findBodyEntity(server, body);
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
            BODY_ENTITY_BY_PLAYER.remove(body.playerUuid());
            iterator.remove();
        }
    }

    private static void killPendingOnlinePlayers(MinecraftServer server) {
        CombatLogoutLootState lootState = CombatLogoutLootState.get(server);
        Set<UUID> pendingDeaths = lootState.pendingDeaths();
        if (pendingDeaths.isEmpty()) {
            return;
        }

        Iterator<UUID> iterator = pendingDeaths.iterator();
        while (iterator.hasNext()) {
            UUID playerUuid = iterator.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
            if (player == null) {
                continue;
            }

            ServerWorld world = player.getEntityWorld();
            player.damage(world, world.getDamageSources().genericKill(), Float.MAX_VALUE);
            if (!player.isAlive() || player.getHealth() <= 0.0F) {
                lootState.clearPendingDeath(playerUuid);
            }
        }
    }

    public static boolean shouldSuppressDeathDrops(ServerPlayerEntity player) {
        return CombatLogoutLootState.get(player.getEntityWorld().getServer()).hasPendingDeath(player.getUuid());
    }

    private static void removeExistingBodyAndRestoreLoot(ServerPlayerEntity player, MinecraftServer server) {
        removeExistingBody(player.getUuid(), server);
        CombatLogoutLootState.Entry lootEntry = CombatLogoutLootState.get(server).removeByPlayer(player.getUuid());
        if (lootEntry != null) {
            restoreLoot(player, server, lootEntry.stacks());
        }
    }

    private static void removeExistingBody(UUID playerUuid, MinecraftServer server) {
        UUID existingBodyUuid = BODY_ENTITY_BY_PLAYER.remove(playerUuid);
        if (existingBodyUuid != null) {
            LogoutBody body = BODIES_BY_ENTITY.remove(existingBodyUuid);
            if (body != null) {
                Entity entity = findBodyEntity(server, body);
                if (entity != null && !entity.isRemoved()) {
                    entity.discard();
                }
            }
        }

        ArrayList<Entity> staleBodies = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            for (Entity entity : world.iterateEntities()) {
                if (entity.isRemoved() || !isLogoutBody(entity)) {
                    continue;
                }

                UUID ownerUuid = getLogoutBodyOwnerUuid(entity);
                if (ownerUuid == null || ownerUuid.equals(playerUuid)) {
                    staleBodies.add(entity);
                }
            }
        }

        for (Entity entity : staleBodies) {
            UUID ownerUuid = getLogoutBodyOwnerUuid(entity);
            BODIES_BY_ENTITY.remove(entity.getUuid());
            if (ownerUuid != null) {
                BODY_ENTITY_BY_PLAYER.remove(ownerUuid, entity.getUuid());
            }
            entity.discard();
        }
    }

    private static Entity findBodyEntity(MinecraftServer server, LogoutBody body) {
        ServerWorld world = server.getWorld(body.worldKey());
        if (world != null) {
            return world.getEntityAnyDimension(body.entityUuid());
        }

        return null;
    }

    private static List<CombatLogoutLootState.StoredStack> captureAndClearLoot(ServerPlayerEntity player, MinecraftServer server) {
        PlayerInventory inventory = player.getInventory();
        ArrayList<CombatLogoutLootState.StoredStack> loot = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }

            net.minecraft.nbt.NbtCompound stackNbt = CombatLogoutLootState.encodeStack(server, stack);
            if (stackNbt != null) {
                loot.add(new CombatLogoutLootState.StoredStack(slot, stackNbt));
            }
        }

        for (CombatLogoutLootState.StoredStack storedStack : loot) {
            inventory.setStack(storedStack.slot(), ItemStack.EMPTY);
        }
        inventory.markDirty();
        player.currentScreenHandler.sendContentUpdates();
        return loot;
    }

    private static void restoreLoot(ServerPlayerEntity player, MinecraftServer server, List<CombatLogoutLootState.StoredStack> loot) {
        PlayerInventory inventory = player.getInventory();
        for (CombatLogoutLootState.StoredStack storedStack : loot) {
            ItemStack stack = CombatLogoutLootState.decodeStack(server, storedStack.stackNbt());
            if (stack.isEmpty()) {
                continue;
            }

            int slot = storedStack.slot();
            if (slot >= 0 && slot < inventory.size()) {
                ItemStack existingStack = inventory.getStack(slot);
                if (!existingStack.isEmpty()) {
                    inventory.setStack(slot, ItemStack.EMPTY);
                    inventory.offerOrDrop(existingStack);
                }
                inventory.setStack(slot, stack);
            } else {
                inventory.offerOrDrop(stack);
            }
        }
        inventory.markDirty();
        player.currentScreenHandler.sendContentUpdates();
    }

    private static void dropLoot(MinecraftServer server, Entity bodyEntity, List<CombatLogoutLootState.StoredStack> loot) {
        ServerWorld world = server.getWorld(bodyEntity.getEntityWorld().getRegistryKey());
        if (world == null) {
            return;
        }

        for (CombatLogoutLootState.StoredStack storedStack : loot) {
            ItemStack stack = CombatLogoutLootState.decodeStack(server, storedStack.stackNbt());
            if (!stack.isEmpty()) {
                Block.dropStack(world, bodyEntity.getBlockPos(), stack);
            }
        }
    }

    private static LogoutBody getOrphanLogoutBody(Entity entity) {
        UUID playerUuid = getLogoutBodyOwnerUuid(entity);
        if (playerUuid == null) {
            return null;
        }

        String playerName = "A player";
        Text customName = entity.getCustomName();
        if (customName != null) {
            String customNameString = customName.getString();
            String suffix = "'s Combat Body";
            playerName = customNameString.endsWith(suffix)
                    ? customNameString.substring(0, customNameString.length() - suffix.length())
                    : customNameString;
        }

        return new LogoutBody(
                entity.getUuid(),
                playerUuid,
                playerName,
                entity.getEntityWorld().getRegistryKey(),
                0L
        );
    }

    private static UUID getLogoutBodyOwnerUuid(Entity entity) {
        for (String tag : entity.getCommandTags()) {
            if (!tag.startsWith(LOGOUT_BODY_OWNER_TAG_PREFIX)) {
                continue;
            }

            try {
                return UUID.fromString(tag.substring(LOGOUT_BODY_OWNER_TAG_PREFIX.length()));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        return null;
    }

    private record LogoutBody(
            UUID entityUuid,
            UUID playerUuid,
            String playerName,
            net.minecraft.registry.RegistryKey<World> worldKey,
            long expiresAtServerTick
    ) {
    }
}
