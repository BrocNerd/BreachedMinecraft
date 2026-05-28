package nrd.breached.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.render.state.OutlineRenderState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import nrd.breached.Breached;
import nrd.breached.item.BreacherItem;
import nrd.breached.network.ReinforcementOutlinePayload;
import nrd.breached.reinforcement.ReinforcementTier;
import nrd.breached.reinforcement.ReinforcementVisibilityCache;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class BreachedClient implements ClientModInitializer {
    private static List<ReinforcementOutlineTarget> outlineTargets = List.of();

    @Override
    public void onInitializeClient() {
        registerReinforcementOutlineReceiver();
        registerReinforcementOutlineRenderer();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearReinforcementOutlines());
    }

    private static void registerReinforcementOutlineReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(ReinforcementOutlinePayload.ID, (payload, context) -> {
            outlineTargets = payload.entries()
                    .stream()
                    .map(BreachedClient::toOutlineTarget)
                    .flatMap(Optional::stream)
                    .toList();
            ReinforcementVisibilityCache.setVisibleTiers(outlineTargets.stream()
                    .collect(Collectors.toMap(ReinforcementOutlineTarget::pos, ReinforcementOutlineTarget::tier, (left, right) -> right)));
        });
    }

    private static void clearReinforcementOutlines() {
        outlineTargets = List.of();
        ReinforcementVisibilityCache.clear();
    }

    private static void registerReinforcementOutlineRenderer() {
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, outlineRenderState) -> {
            if (isReinforcementOutlineTarget(outlineRenderState)) {
                return false;
            }

            return true;
        });

        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null || outlineTargets.isEmpty() || !isReinforcementOutlineTool(client.player.getMainHandStack())) {
                return;
            }

            VertexConsumer consumer = context.consumers().getBuffer(RenderLayers.lines());
            Vec3d cameraPos = context.worldState().cameraRenderState.pos;
            for (ReinforcementOutlineTarget target : outlineTargets) {
                renderReinforcementOutline(context.matrices(), consumer, cameraPos, client.world, target);
            }
        });
    }

    private static Optional<ReinforcementOutlineTarget> toOutlineTarget(ReinforcementOutlinePayload.Entry entry) {
        return ReinforcementTier.fromLevel(entry.tierLevel())
                .map(tier -> new ReinforcementOutlineTarget(entry.pos().toImmutable(), tier));
    }

    private static boolean isReinforcementOutlineTarget(OutlineRenderState outlineRenderState) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || outlineTargets.isEmpty() || outlineRenderState == null || !isReinforcementOutlineTool(client.player.getMainHandStack())) {
            return false;
        }

        return outlineTargets.stream().anyMatch(target -> target.pos().equals(outlineRenderState.pos()));
    }

    private static boolean isReinforcementOutlineTool(ItemStack stack) {
        return stack.isOf(Breached.REINFORCER) || stack.getItem() instanceof BreacherItem;
    }

    private static void renderReinforcementOutline(MatrixStack matrices, VertexConsumer consumer, Vec3d cameraPos, ClientWorld world, ReinforcementOutlineTarget target) {
        BlockPos pos = target.pos();
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return;
        }

        VoxelShape shape = state.getOutlineShape(world, pos);
        if (shape.isEmpty()) {
            return;
        }

        float lineWidth = MinecraftClient.getInstance().getWindow().getMinimumLineWidth() * 1.5F;
        VertexRendering.drawOutline(
                matrices,
                consumer,
                shape,
                pos.getX() - cameraPos.x,
                pos.getY() - cameraPos.y,
                pos.getZ() - cameraPos.z,
                outlineColor(target.tier()),
                lineWidth
        );
    }

    private static int outlineColor(ReinforcementTier tier) {
        return switch (tier) {
            case WOOD -> ColorHelper.fromFloats(0.95F, 0.76F, 0.48F, 0.22F);
            case IRON -> ColorHelper.fromFloats(0.95F, 0.86F, 0.88F, 0.90F);
            case DIAMOND -> ColorHelper.fromFloats(0.95F, 0.16F, 0.78F, 1.0F);
        };
    }

    private record ReinforcementOutlineTarget(BlockPos pos, ReinforcementTier tier) {
    }
}
