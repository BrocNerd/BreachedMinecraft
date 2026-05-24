package nrd.breached.worldgen;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Optional;

public final class CentralSpawnPoiManager {
    private CentralSpawnPoiManager() {
    }

    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(CentralSpawnPoiManager::placeCentralSpawnPoiOnce);
        registerProtectionEvents();
    }

    private static void placeCentralSpawnPoiOnce(ServerWorld world) {
        if (!world.getRegistryKey().equals(World.OVERWORLD)) {
            return;
        }

        CentralSpawnPoiState state = CentralSpawnPoiState.get(world.getServer());
        if (state.isPlaced()) {
            return;
        }

        if (world.getServer().getPlayerManager().getPlayerList().isEmpty()) {
            return;
        }

        List<BreachedStructureSpawnManager.RadiusCandidate> candidates = BreachedStructureSpawnManager.generateRadiusCandidates(
                world.getSeed(),
                BreachedStructureSpawnManager.CENTRAL_SPAWN
        );

        BreachedStructureSpawnManager.RadiusCandidate candidate = candidates.get(0);
        Optional<BreachedStructurePlacement> placement = BreachedStructureSpawnManager.placeRadiusCandidate(
                world,
                BreachedStructureSpawnManager.CENTRAL_SPAWN,
                candidate
        );
        if (placement.isEmpty()) {
            return;
        }

        int protectedCenterX = BreachedStructureSpawnManager.getProtectedCenterX(placement.get());
        int protectedCenterZ = BreachedStructureSpawnManager.getProtectedCenterZ(placement.get());
        state.markPlaced(protectedCenterX, protectedCenterZ);

        System.out.println("[Breached] Registered central spawn protection around x "
                + protectedCenterX + ", z " + protectedCenterZ
                + " radius " + BreachedStructureSpawnManager.CENTRAL_SPAWN.protectionRadius() + ".");
    }

    private static void registerProtectionEvents() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!shouldProtect(world, player, pos)) {
                return true;
            }

            player.sendMessage(Text.literal("The central spawn area cannot be modified."), false);
            return false;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient() || canBypassProtection(player) || !(player.getStackInHand(hand).getItem() instanceof BlockItem)) {
                return ActionResult.PASS;
            }

            ItemPlacementContext placementContext = new ItemPlacementContext(player, hand, player.getStackInHand(hand), hitResult);
            if (!placementContext.canPlace() || !isInsideProtectedArea(world, placementContext.getBlockPos())) {
                return ActionResult.PASS;
            }

            player.sendMessage(Text.literal("The central spawn area cannot be modified."), false);
            return ActionResult.FAIL;
        });
    }

    public static boolean isInsideProtectedArea(World world, BlockPos pos) {
        if (world.isClient() || !world.getRegistryKey().equals(World.OVERWORLD)) {
            return false;
        }

        if (!(world instanceof ServerWorld serverWorld)) {
            return false;
        }

        CentralSpawnPoiState state = CentralSpawnPoiState.get(serverWorld.getServer());
        if (!state.isPlaced()) {
            return false;
        }

        return BreachedStructureSpawnManager.isInsideProtectionRadius(
                BreachedStructureSpawnManager.CENTRAL_SPAWN,
                state.getCenterX(),
                state.getCenterZ(),
                pos
        );
    }

    private static boolean shouldProtect(World world, PlayerEntity player, BlockPos pos) {
        return !world.isClient()
                && !canBypassProtection(player)
                && isInsideProtectedArea(world, pos);
    }

    private static boolean canBypassProtection(PlayerEntity player) {
        return player.isCreative() || player.isCreativeLevelTwoOp();
    }
}
