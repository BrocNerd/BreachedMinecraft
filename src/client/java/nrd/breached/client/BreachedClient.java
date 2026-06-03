package nrd.breached.client;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.render.state.OutlineRenderState;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import nrd.breached.Breached;
import nrd.breached.client.screen.BreachedArchiveScreen;
import nrd.breached.client.screen.BreachedMapScreen;
import nrd.breached.client.screen.LandlockScreen;
import nrd.breached.item.BreacherItem;
import nrd.breached.network.LandlockClaimOutlinePayload;
import nrd.breached.network.OpenBreachedArchivePayload;
import nrd.breached.network.OpenBreachedMapPayload;
import nrd.breached.network.ReinforcementOutlinePayload;
import nrd.breached.network.RequestBreachedMapPayload;
import nrd.breached.network.RequestTownhallRespawnPayload;
import nrd.breached.network.SelectRespawnBedPayload;
import nrd.breached.reinforcement.ReinforcementTier;
import nrd.breached.reinforcement.ReinforcementVisibilityCache;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class BreachedClient implements ClientModInitializer {
    private static List<ReinforcementOutlineTarget> outlineTargets = List.of();
    private static List<LandlockClaimOutlineTarget> claimOutlineTargets = List.of();
    private static final VoxelShape LANDLOCK_CLAIM_OUTLINE_SHAPE = VoxelShapes.cuboid(0.0D, 0.0D, 0.0D, 17.0D, 17.0D, 17.0D);
    private static final RenderPipeline LANDLOCK_CLAIM_OUTLINE_PIPELINE = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
            .withLocation(Identifier.of(Breached.MOD_ID, "landlock_claim_outline"))
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthWrite(false)
            .withVertexFormat(RenderPipelines.LINES.getVertexFormat(), RenderPipelines.LINES.getVertexFormatMode())
            .build());
    private static final RenderLayer LANDLOCK_CLAIM_OUTLINE_LAYER = RenderLayer.of(
            "breached_landlock_claim_outline",
            RenderSetup.builder(LANDLOCK_CLAIM_OUTLINE_PIPELINE)
                    .translucent()
                    .expectedBufferSize(1536)
                    .build()
    );
    private static final KeyBinding.Category BREACHED_KEY_CATEGORY = KeyBinding.Category.create(Identifier.of(Breached.MOD_ID, "breached"));
    private static final long BREACHED_MAP_REQUEST_COOLDOWN_MILLIS = 2_000L;
    private static Screen pendingBreachedMapParentScreen;
    private static boolean pendingBreachedMapRespawnOnBedSelect;
    private static OpenBreachedMapPayload cachedBreachedMapPayload;
    private static long cachedBreachedMapReceivedAtMillis;
    private static long lastBreachedMapServerRequestMillis;
    private static boolean suppressMapKeyOpenUntilReleased;
    private static boolean suppressArchiveKeyOpenUntilReleased;
    private static KeyBinding openBreachedMapKeyBinding;
    private static KeyBinding openBreachedArchiveKeyBinding;

    @Override
    public void onInitializeClient() {
        HandledScreens.register(Breached.LANDLOCK_SCREEN_HANDLER, LandlockScreen::new);
        registerBreachedMapKeyBinding();
        registerBreachedArchiveKeyBinding();
        registerArchiveReceiver();
        registerBreachedMapReceiver();
        registerReinforcementOutlineReceiver();
        registerLandlockClaimOutlineReceiver();
        registerReinforcementOutlineRenderer();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearClientSessionCache());
    }

    private static void registerArchiveReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(OpenBreachedArchivePayload.ID, (payload, context) ->
                context.client().setScreen(new BreachedArchiveScreen()));
    }

    private static void registerBreachedMapReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(OpenBreachedMapPayload.ID, (payload, context) -> {
            Screen parentScreen = pendingBreachedMapParentScreen;
            boolean respawnOnBedSelect = pendingBreachedMapRespawnOnBedSelect;
            pendingBreachedMapParentScreen = null;
            pendingBreachedMapRespawnOnBedSelect = false;
            if (!respawnOnBedSelect) {
                cachedBreachedMapPayload = payload;
                cachedBreachedMapReceivedAtMillis = System.currentTimeMillis();
            }
            context.client().setScreen(new BreachedMapScreen(payload, parentScreen, respawnOnBedSelect));
        });
    }

    public static void requestBreachedMap() {
        Screen parentScreen = MinecraftClient.getInstance().currentScreen;
        if (tryOpenCachedBreachedMap(parentScreen)) {
            return;
        }

        requestBreachedMapFromServer(parentScreen, false);
    }

    public static void requestDeathRespawnMap(Screen parentScreen) {
        requestBreachedMapFromServer(parentScreen, true);
    }

    public static void refreshBreachedMap(Screen parentScreen, boolean respawnOnBedSelect) {
        if (!respawnOnBedSelect && tryOpenCachedBreachedMap(parentScreen)) {
            return;
        }

        requestBreachedMapFromServer(parentScreen, respawnOnBedSelect);
    }

    private static void requestBreachedMapFromServer(Screen parentScreen, boolean respawnOnBedSelect) {
        long now = System.currentTimeMillis();
        if (!respawnOnBedSelect && now - lastBreachedMapServerRequestMillis < BREACHED_MAP_REQUEST_COOLDOWN_MILLIS) {
            return;
        }

        pendingBreachedMapParentScreen = parentScreen;
        pendingBreachedMapRespawnOnBedSelect = respawnOnBedSelect;
        if (!respawnOnBedSelect) {
            lastBreachedMapServerRequestMillis = now;
        }
        ClientPlayNetworking.send(RequestBreachedMapPayload.INSTANCE);
    }

    private static boolean tryOpenCachedBreachedMap(Screen parentScreen) {
        if (cachedBreachedMapPayload == null || !isBreachedMapCacheFresh()) {
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof BreachedMapScreen) {
            return true;
        }

        client.setScreen(new BreachedMapScreen(cachedBreachedMapPayload, parentScreen, false));
        return true;
    }

    private static boolean isBreachedMapCacheFresh() {
        return System.currentTimeMillis() - cachedBreachedMapReceivedAtMillis < BREACHED_MAP_REQUEST_COOLDOWN_MILLIS;
    }

    public static void openBreachedArchive() {
        openBreachedArchive(MinecraftClient.getInstance().currentScreen);
    }

    public static void openBreachedArchive(Screen parentScreen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }

        client.setScreen(new BreachedArchiveScreen(parentScreen));
    }

    public static void requestBedRespawn(int bedIndex) {
        ClientPlayNetworking.send(new SelectRespawnBedPayload(bedIndex));
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.requestRespawn();
        }
    }

    public static void requestTownhallRespawn() {
        ClientPlayNetworking.send(RequestTownhallRespawnPayload.INSTANCE);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.requestRespawn();
        }
    }

    public static boolean matchesOpenBreachedMapKey(KeyInput input) {
        return openBreachedMapKeyBinding != null && openBreachedMapKeyBinding.matchesKey(input);
    }

    public static boolean matchesOpenBreachedArchiveKey(KeyInput input) {
        return openBreachedArchiveKeyBinding != null && openBreachedArchiveKeyBinding.matchesKey(input);
    }

    public static void suppressMapKeyOpenUntilReleased() {
        suppressMapKeyOpenUntilReleased = true;
    }

    public static void suppressArchiveKeyOpenUntilReleased() {
        suppressArchiveKeyOpenUntilReleased = true;
    }

    private static void registerBreachedMapKeyBinding() {
        openBreachedMapKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.breached.open_map",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                BREACHED_KEY_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (suppressMapKeyOpenUntilReleased && !openBreachedMapKeyBinding.isPressed()) {
                suppressMapKeyOpenUntilReleased = false;
            }

            while (openBreachedMapKeyBinding.wasPressed()) {
                if (suppressMapKeyOpenUntilReleased) {
                    continue;
                }

                if (client.player == null || client.world == null) {
                    continue;
                }

                if (client.currentScreen == null) {
                    requestBreachedMap();
                } else if (client.currentScreen instanceof DeathScreen deathScreen) {
                    requestDeathRespawnMap(deathScreen);
                }
            }
        });
    }

    private static void registerBreachedArchiveKeyBinding() {
        openBreachedArchiveKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.breached.open_archive",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                BREACHED_KEY_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (suppressArchiveKeyOpenUntilReleased && !openBreachedArchiveKeyBinding.isPressed()) {
                suppressArchiveKeyOpenUntilReleased = false;
            }

            while (openBreachedArchiveKeyBinding.wasPressed()) {
                if (suppressArchiveKeyOpenUntilReleased) {
                    continue;
                }

                if (client.player == null || client.world == null) {
                    continue;
                }

                if (client.currentScreen == null) {
                    openBreachedArchive(null);
                }
            }
        });
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

    private static void registerLandlockClaimOutlineReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(LandlockClaimOutlinePayload.ID, (payload, context) ->
                claimOutlineTargets = payload.entries()
                        .stream()
                        .map(entry -> new LandlockClaimOutlineTarget(entry.claimCenter().toImmutable(), entry.authorized(), entry.lockdown(), entry.decayed()))
                        .toList());
    }

    private static void clearOutlines() {
        outlineTargets = List.of();
        claimOutlineTargets = List.of();
        ReinforcementVisibilityCache.clear();
    }

    private static void clearClientSessionCache() {
        clearOutlines();
        cachedBreachedMapPayload = null;
        cachedBreachedMapReceivedAtMillis = 0L;
        lastBreachedMapServerRequestMillis = 0L;
        pendingBreachedMapParentScreen = null;
        pendingBreachedMapRespawnOnBedSelect = false;
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
            if (client.player == null || client.world == null) {
                return;
            }

            ItemStack mainHandStack = client.player.getMainHandStack();
            boolean renderReinforcements = !outlineTargets.isEmpty() && isReinforcementOutlineTool(mainHandStack);
            boolean renderClaims = !claimOutlineTargets.isEmpty() && isClaimOutlineProbe(mainHandStack);
            if (!renderReinforcements && !renderClaims) {
                return;
            }

            var consumers = context.consumers();
            if (consumers == null) {
                return;
            }

            Vec3d cameraPos = context.worldState().cameraRenderState.pos;

            if (renderReinforcements) {
                VertexConsumer reinforcementConsumer = consumers.getBuffer(RenderLayers.lines());
                for (ReinforcementOutlineTarget target : outlineTargets) {
                    renderReinforcementOutline(context.matrices(), reinforcementConsumer, cameraPos, client.world, target);
                }
            }

            if (renderClaims) {
                VertexConsumer hiddenClaimConsumer = consumers.getBuffer(LANDLOCK_CLAIM_OUTLINE_LAYER);
                for (LandlockClaimOutlineTarget target : claimOutlineTargets) {
                    renderLandlockClaimOutline(context.matrices(), hiddenClaimConsumer, cameraPos, target, 0.8F, 1.75F);
                }
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

    private static boolean isClaimOutlineProbe(ItemStack stack) {
        return stack.isOf(Breached.PROBE) || stack.isOf(Breached.DIAMOND_PROBE);
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
            case NETHERITE -> ColorHelper.fromFloats(0.95F, 0.38F, 0.22F, 0.30F);
        };
    }

    private static void renderLandlockClaimOutline(
            MatrixStack matrices,
            VertexConsumer consumer,
            Vec3d cameraPos,
            LandlockClaimOutlineTarget target,
            float alpha,
            float lineWidthMultiplier
    ) {
        BlockPos center = target.claimCenter();
        double minX = center.getX() - 8 - cameraPos.x;
        double minY = center.getY() - 8 - cameraPos.y;
        double minZ = center.getZ() - 8 - cameraPos.z;
        int color = claimOutlineColor(target, alpha);
        float lineWidth = MinecraftClient.getInstance().getWindow().getMinimumLineWidth() * lineWidthMultiplier;

        VertexRendering.drawOutline(matrices, consumer, LANDLOCK_CLAIM_OUTLINE_SHAPE, minX, minY, minZ, color, lineWidth);
    }

    private static int claimOutlineColor(LandlockClaimOutlineTarget target, float alpha) {
        if (target.decayed()) {
            return ColorHelper.fromFloats(alpha, 0.55F, 0.55F, 0.55F);
        }

        if (target.lockdown()) {
            return ColorHelper.fromFloats(alpha, 0.42F, 0.0F, 0.0F);
        }

        return target.authorized()
                ? ColorHelper.fromFloats(alpha, 0.22F, 1.0F, 0.42F)
                : ColorHelper.fromFloats(alpha, 1.0F, 0.28F, 0.22F);
    }

    private record ReinforcementOutlineTarget(BlockPos pos, ReinforcementTier tier) {
    }

    private record LandlockClaimOutlineTarget(BlockPos claimCenter, boolean authorized, boolean lockdown, boolean decayed) {
    }
}
