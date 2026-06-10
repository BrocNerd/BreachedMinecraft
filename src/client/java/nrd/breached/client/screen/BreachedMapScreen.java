package nrd.breached.client.screen;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import nrd.breached.Breached;
import nrd.breached.client.BreachedClient;
import nrd.breached.mixin.client.KeyBindingAccessor;
import nrd.breached.network.OpenBreachedMapPayload;

import java.util.ArrayList;
import java.util.List;

public class BreachedMapScreen extends Screen {
    private static final int BACKDROP_COLOR = 0xB0000000;
    private static final int MAP_BACKGROUND = 0xFF102232;
    private static final int MAP_BORDER_COLOR = 0xFF86D1FF;
    private static final int TEXT_COLOR = 0xFFE8F3FA;
    private static final int MUTED_TEXT_COLOR = 0xFF9FB8C6;
    private static final int PLAYER_COLOR = 0xFFFFD34D;
    private static final int UNAVAILABLE_BED_COLOR = 0xFF747C83;
    private static final int UNAVAILABLE_BED_TEXT_COLOR = 0xFF929A9F;
    private static final int CLOSE_HOVER_COLOR = 0xFFB94A4A;
    private static final int REFRESH_HOVER_COLOR = 0xFF2F6C87;
    private static final int CLOSE_TEXT_COLOR = 0xFFFFEFEF;
    private static final int CLOSE_BUTTON_SIZE = 16;
    private static final int REFRESH_BUTTON_SIZE = 16;
    private static final int TOWNHALL_BUTTON_WIDTH = 124;
    private static final int TOWNHALL_BUTTON_HEIGHT = 20;
    private static final int TOWNHALL_BUTTON_COLOR = 0xFF1E5E7A;
    private static final int TOWNHALL_BUTTON_HOVER_COLOR = 0xFF27799E;
    private static final int TOWNHALL_BUTTON_BORDER_COLOR = 0xFFC6E8F8;
    private static final int DISABLED_RESPAWN_BUTTON_COLOR = 0xFF2B3840;
    private static final int DISABLED_RESPAWN_BUTTON_BORDER_COLOR = 0xFF5F727C;
    private static final int BED_LIST_ROW_HEIGHT = TOWNHALL_BUTTON_HEIGHT + 5;
    private static final float PLAYER_LABEL_SCALE = 0.75F;
    private static final float BED_LABEL_SCALE = 0.75F;
    private static final float DEATH_LABEL_SCALE = 0.5F;
    private static final double MIN_ZOOM = 1.0D;
    private static final double MAX_ZOOM = 8.0D;
    private static int nextTerrainTextureId;

    private final OpenBreachedMapPayload payload;
    private final Screen parentScreen;
    private final boolean respawnOnBedSelect;
    private final Identifier terrainTextureId;
    private NativeImageBackedTexture terrainTexture;
    private double zoom = MIN_ZOOM;
    private double viewCenterX;
    private double viewCenterZ;
    private final long openedAtMillis = System.currentTimeMillis();

    public BreachedMapScreen(OpenBreachedMapPayload payload, Screen parentScreen) {
        this(payload, parentScreen, false);
    }

    public BreachedMapScreen(OpenBreachedMapPayload payload, Screen parentScreen, boolean respawnOnBedSelect) {
        super(Text.literal(respawnOnBedSelect ? "Choose Respawn Bed" : "Breached Map"));
        this.payload = payload;
        this.parentScreen = parentScreen;
        this.respawnOnBedSelect = respawnOnBedSelect;
        this.terrainTextureId = Identifier.of(Breached.MOD_ID, "dynamic/breached_map/" + nextTerrainTextureId++);
        createTerrainTexture();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        int mapLeft = mapLeft();
        int mapTop = mapTop();
        int mapSize = mapSize();
        int titleY = mapTop - 22;

        context.fill(0, 0, width, height, BACKDROP_COLOR);
        context.drawTextWithShadow(textRenderer, title, mapLeft, titleY, TEXT_COLOR);
        renderRefreshIcon(context, mouseX, mouseY);
        renderCloseIcon(context, mouseX, mouseY);
        context.drawTextWithShadow(
                textRenderer,
                getCoordinateText(),
                mapLeft,
                titleY + 12,
                MUTED_TEXT_COLOR
        );

        renderMap(context, mapLeft, mapTop, mapSize);
        renderTownhallRespawnButton(context, mouseX, mouseY);

        super.render(context, mouseX, mouseY, deltaTicks);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0 && isInsideCloseButton(click.x(), click.y())) {
            close();
            return true;
        }

        if (click.button() == 0 && isInsideRefreshButton(click.x(), click.y())) {
            BreachedClient.refreshBreachedMap(parentScreen, respawnOnBedSelect);
            return true;
        }

        if (click.button() == 0 && isInsideTownhallRespawnButton(click.x(), click.y())) {
            BreachedClient.requestTownhallRespawn();
            return true;
        }

        if (click.button() == 0 && respawnOnBedSelect) {
            int clickedBedListIndex = getClickedBedListIndex(click.x(), click.y());
            if (clickedBedListIndex >= 0) {
                requestBedRespawn(clickedBedListIndex);
                return true;
            }
        }

        if (click.button() == 0 && isInsideMap(click.x(), click.y())) {
            if (respawnOnBedSelect) {
                int clickedBedIndex = getClickedBedIndex(click.x(), click.y());
                if (clickedBedIndex >= 0) {
                    requestBedRespawn(clickedBedIndex);
                }
            }
            return true;
        }

        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (click.button() == 0 && isInsideMap(click.x(), click.y())) {
            double blocksPerScreenPixel = viewWorldSize() / mapSize();
            viewCenterX -= offsetX * blocksPerScreenPixel;
            viewCenterZ -= offsetY * blocksPerScreenPixel;
            return true;
        }

        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (!isInsideMap(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }

        int mapLeft = mapLeft();
        int mapTop = mapTop();
        int mapSize = mapSize();
        double cursorWorldX = screenToWorldX(mouseX, mapLeft, mapSize);
        double cursorWorldZ = screenToWorldZ(mouseY, mapTop, mapSize);
        double previousZoom = zoom;
        zoom = clamp(zoom * (verticalAmount > 0.0D ? 1.25D : 0.8D), MIN_ZOOM, MAX_ZOOM);
        if (zoom == previousZoom) {
            return true;
        }

        double cursorRatioX = (mouseX - mapLeft) / mapSize;
        double cursorRatioZ = (mouseY - mapTop) / mapSize;
        double newViewSize = viewWorldSize();
        viewCenterX = cursorWorldX - (cursorRatioX - 0.5D) * newViewSize;
        viewCenterZ = cursorWorldZ - (cursorRatioZ - 0.5D) * newViewSize;
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (BreachedClient.matchesOpenBreachedMapKey(input)) {
            BreachedClient.suppressMapKeyOpenUntilReleased();
            close();
            return true;
        }

        return super.keyPressed(input);
    }

    @Override
    public void tick() {
        super.tick();
        updateMovementKeybinds();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void removed() {
        destroyTerrainTexture();
    }

    @Override
    public void close() {
        if (client != null && parentScreen != null) {
            client.setScreen(parentScreen);
            return;
        }

        super.close();
    }

    private void updateMovementKeybinds() {
        if (respawnOnBedSelect || client == null || client.player == null) {
            return;
        }

        updateMovementKey(client.options.forwardKey);
        updateMovementKey(client.options.backKey);
        updateMovementKey(client.options.leftKey);
        updateMovementKey(client.options.rightKey);
        updateMovementKey(client.options.jumpKey);
        updateMovementKey(client.options.sneakKey);
        updateMovementKey(client.options.sprintKey);
    }

    private void updateMovementKey(KeyBinding keyBinding) {
        KeyBindingAccessor accessor = (KeyBindingAccessor) keyBinding;
        if (!accessor.breached$shouldSetOnGameFocus()) {
            return;
        }

        InputUtil.Key boundKey = accessor.breached$getBoundKey();
        keyBinding.setPressed(InputUtil.isKeyPressed(client.getWindow(), boundKey.getCode()));
    }

    private void renderCloseIcon(DrawContext context, int mouseX, int mouseY) {
        int closeLeft = closeButtonLeft();
        int closeTop = closeButtonTop();
        boolean hovered = isInsideCloseButton(mouseX, mouseY);
        if (hovered) {
            context.fill(closeLeft, closeTop, closeLeft + CLOSE_BUTTON_SIZE, closeTop + CLOSE_BUTTON_SIZE, CLOSE_HOVER_COLOR);
        }

        context.drawStrokedRectangle(closeLeft, closeTop, CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE, hovered ? CLOSE_TEXT_COLOR : MAP_BORDER_COLOR);
        context.drawTextWithShadow(
                textRenderer,
                Text.literal("X"),
                closeLeft + 5,
                closeTop + 4,
                CLOSE_TEXT_COLOR
        );
    }

    private void renderRefreshIcon(DrawContext context, int mouseX, int mouseY) {
        int refreshLeft = refreshButtonLeft();
        int refreshTop = refreshButtonTop();
        boolean hovered = isInsideRefreshButton(mouseX, mouseY);
        if (hovered) {
            context.fill(refreshLeft, refreshTop, refreshLeft + REFRESH_BUTTON_SIZE, refreshTop + REFRESH_BUTTON_SIZE, REFRESH_HOVER_COLOR);
        }

        context.drawStrokedRectangle(refreshLeft, refreshTop, REFRESH_BUTTON_SIZE, REFRESH_BUTTON_SIZE, hovered ? CLOSE_TEXT_COLOR : MAP_BORDER_COLOR);
        context.drawTextWithShadow(
                textRenderer,
                Text.literal("R"),
                refreshLeft + 5,
                refreshTop + 4,
                CLOSE_TEXT_COLOR
        );
    }

    private void renderTownhallRespawnButton(DrawContext context, int mouseX, int mouseY) {
        if (!shouldShowTownhallRespawnButton()) {
            return;
        }

        int left = townhallRespawnButtonLeft();
        int top = townhallRespawnButtonTop();
        renderRespawnButton(
                context,
                "Respawn in Townhall",
                left,
                top,
                isInsideTownhallRespawnButton(mouseX, mouseY),
                true
        );
        renderBedCooldownList(context, left, top + TOWNHALL_BUTTON_HEIGHT + 7, mouseX, mouseY);
    }

    private void renderBedCooldownList(DrawContext context, int left, int top, int mouseX, int mouseY) {
        if (payload.beds().isEmpty()) {
            context.drawTextWithShadow(textRenderer, Text.literal("No saved beds"), left, top, MUTED_TEXT_COLOR);
            return;
        }

        for (int index = 0; index < payload.beds().size(); index++) {
            OpenBreachedMapPayload.Bed bed = payload.beds().get(index);
            int remainingTicks = getRemainingBedCooldownTicks(bed);
            String cooldownText = remainingTicks <= 0 ? "available" : formatCooldown(remainingTicks);
            int rowTop = top + index * BED_LIST_ROW_HEIGHT;
            boolean bedReady = isBedReady(bed);
            String label = bedReady ? "Respawn at " + bed.label() : bed.label() + ": " + cooldownText;
            renderRespawnButton(
                    context,
                    label,
                    left,
                    rowTop,
                    bedReady && isInsideBedListRow(mouseX, mouseY, left, rowTop),
                    bedReady
            );
        }
    }

    private void renderRespawnButton(DrawContext context, String label, int left, int top, boolean hovered, boolean active) {
        int fillColor = active
                ? (hovered ? TOWNHALL_BUTTON_HOVER_COLOR : TOWNHALL_BUTTON_COLOR)
                : DISABLED_RESPAWN_BUTTON_COLOR;
        int borderColor = active ? TOWNHALL_BUTTON_BORDER_COLOR : DISABLED_RESPAWN_BUTTON_BORDER_COLOR;
        int labelColor = active ? TEXT_COLOR : UNAVAILABLE_BED_TEXT_COLOR;
        String fittedLabel = textRenderer.trimToWidth(label, TOWNHALL_BUTTON_WIDTH - 12);
        context.fill(
                left,
                top,
                left + TOWNHALL_BUTTON_WIDTH,
                top + TOWNHALL_BUTTON_HEIGHT,
                fillColor
        );
        context.drawStrokedRectangle(
                left,
                top,
                TOWNHALL_BUTTON_WIDTH,
                TOWNHALL_BUTTON_HEIGHT,
                borderColor
        );
        context.drawTextWithShadow(
                textRenderer,
                Text.literal(fittedLabel),
                left + (TOWNHALL_BUTTON_WIDTH - textRenderer.getWidth(fittedLabel)) / 2,
                top + 6,
                labelColor
        );
    }

    private static String formatCooldown(int remainingTicks) {
        int seconds = Math.max(1, (remainingTicks + 19) / 20);
        int minutes = seconds / 60;
        int secondsPart = seconds % 60;
        return minutes + ":" + (secondsPart < 10 ? "0" : "") + secondsPart;
    }

    private void renderMap(DrawContext context, int left, int top, int size) {
        context.enableScissor(left, top, left + size, top + size);
        context.fill(left, top, left + size, top + size, MAP_BACKGROUND);
        if (hasTerrain()) {
            renderTerrain(context, left, top, size);
        }

        List<LabelBounds> occupiedBounds = new ArrayList<>();
        renderMarkers(context, left, top, size, occupiedBounds);
        renderDeathMarkers(context, left, top, size, occupiedBounds);
        renderLandlocks(context, left, top, size, occupiedBounds);
        renderBeds(context, left, top, size, occupiedBounds);
        renderTeammates(context, left, top, size, occupiedBounds);
        renderPlayer(context, left, top, size, occupiedBounds);
        context.disableScissor();
    }

    private void renderTerrain(DrawContext context, int left, int top, int size) {
        int resolution = payload.terrainResolution();
        if (terrainTexture == null || resolution <= 0) {
            context.fill(left, top, left + size, top + size, MAP_BACKGROUND);
            return;
        }

        double halfBorder = payload.borderSize() / 2.0D;
        double visibleWorldLeft = Math.max(viewLeftWorld(), -halfBorder);
        double visibleWorldTop = Math.max(viewTopWorld(), -halfBorder);
        double visibleWorldRight = Math.min(viewRightWorld(), halfBorder);
        double visibleWorldBottom = Math.min(viewBottomWorld(), halfBorder);
        if (visibleWorldLeft >= visibleWorldRight || visibleWorldTop >= visibleWorldBottom) {
            return;
        }

        int drawLeft = clamp((int) Math.floor(worldToScreenX(visibleWorldLeft, left, size)), left, left + size);
        int drawTop = clamp((int) Math.floor(worldToScreenZ(visibleWorldTop, top, size)), top, top + size);
        int drawRight = clamp((int) Math.ceil(worldToScreenX(visibleWorldRight, left, size)), left, left + size);
        int drawBottom = clamp((int) Math.ceil(worldToScreenZ(visibleWorldBottom, top, size)), top, top + size);
        if (drawLeft >= drawRight || drawTop >= drawBottom) {
            return;
        }

        float u = (float) ((visibleWorldLeft + halfBorder) / payload.borderSize() * resolution);
        float v = (float) ((visibleWorldTop + halfBorder) / payload.borderSize() * resolution);
        int regionWidth = Math.max(1, (int) Math.ceil((visibleWorldRight - visibleWorldLeft) / payload.borderSize() * resolution));
        int regionHeight = Math.max(1, (int) Math.ceil((visibleWorldBottom - visibleWorldTop) / payload.borderSize() * resolution));
        context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                terrainTextureId,
                drawLeft,
                drawTop,
                u,
                v,
                drawRight - drawLeft,
                drawBottom - drawTop,
                regionWidth,
                regionHeight,
                resolution,
                resolution
        );
    }

    private void createTerrainTexture() {
        if (!hasTerrain()) {
            return;
        }

        int resolution = payload.terrainResolution();
        byte[] colors = payload.terrainColors();
        NativeImage image = new NativeImage(resolution, resolution, false);
        for (int y = 0; y < resolution; y++) {
            for (int x = 0; x < resolution; x++) {
                image.setColorArgb(x, y, decodeRgb565(colors, y * resolution + x));
            }
        }

        terrainTexture = new NativeImageBackedTexture(() -> "Breached Map Terrain", image);
        MinecraftClient.getInstance().getTextureManager().registerTexture(terrainTextureId, terrainTexture);
    }

    private void destroyTerrainTexture() {
        if (terrainTexture == null) {
            return;
        }

        MinecraftClient.getInstance().getTextureManager().destroyTexture(terrainTextureId);
        terrainTexture = null;
    }

    private void renderMarkers(DrawContext context, int left, int top, int size, List<LabelBounds> occupiedBounds) {
        for (OpenBreachedMapPayload.Marker marker : payload.markers()) {
            if (!isInsideBorder(marker.x(), marker.z())) {
                continue;
            }

            int x = (int) Math.round(worldToScreenX(marker.x(), left, size));
            int y = (int) Math.round(worldToScreenZ(marker.z(), top, size));
            if (!isInsideMap(x, y)) {
                continue;
            }

            context.fill(x - 3, y - 3, x + 4, y + 4, marker.color());
            context.drawStrokedRectangle(x - 4, y - 4, 8, 8, 0xFF050505);
            occupiedBounds.add(new LabelBounds(x - 5, y - 5, 10, 10));

            int labelWidth = textRenderer.getWidth(marker.label());
            LabelBounds labelBounds = chooseLabelBounds(x, y, labelWidth, left, top, size, occupiedBounds);
            context.drawTextWithShadow(textRenderer, Text.literal(marker.label()), labelBounds.x(), labelBounds.y(), TEXT_COLOR);
            occupiedBounds.add(labelBounds.expand(2));
        }
    }

    private void renderDeathMarkers(DrawContext context, int left, int top, int size, List<LabelBounds> occupiedBounds) {
        for (OpenBreachedMapPayload.DeathMarker deathMarker : payload.deathMarkers()) {
            if (!isDeathMarkerActive(deathMarker) || !isInsideBorder(deathMarker.x(), deathMarker.z())) {
                continue;
            }

            int x = (int) Math.round(worldToScreenX(deathMarker.x(), left, size));
            int y = (int) Math.round(worldToScreenZ(deathMarker.z(), top, size));
            if (!isInsideMap(x, y)) {
                continue;
            }

            renderDeathMarker(context, x, y, deathMarker.color());
            occupiedBounds.add(new LabelBounds(x - 4, y - 4, 8, 8));

            int labelWidth = scaledLabelWidth(deathMarker.label(), DEATH_LABEL_SCALE);
            int labelHeight = scaledLabelHeight(DEATH_LABEL_SCALE);
            LabelBounds labelBounds = chooseLabelBounds(x, y, labelWidth, labelHeight, left, top, size, occupiedBounds);
            drawScaledTextWithShadow(context, deathMarker.label(), labelBounds.x(), labelBounds.y(), deathMarker.color(), DEATH_LABEL_SCALE);
            occupiedBounds.add(labelBounds.expand(2));
        }
    }

    private boolean isDeathMarkerActive(OpenBreachedMapPayload.DeathMarker deathMarker) {
        long markerDurationMillis = deathMarker.remainingTicks() * 50L;
        return System.currentTimeMillis() - openedAtMillis < markerDurationMillis;
    }

    private void renderLandlocks(DrawContext context, int left, int top, int size, List<LabelBounds> occupiedBounds) {
        for (OpenBreachedMapPayload.Landlock landlock : payload.landlocks()) {
            if (!isInsideBorder(landlock.x(), landlock.z())) {
                continue;
            }

            int x = (int) Math.round(worldToScreenX(landlock.x(), left, size));
            int y = (int) Math.round(worldToScreenZ(landlock.z(), top, size));
            if (!isInsideMap(x, y)) {
                continue;
            }

            renderLandlockMarker(context, x, y, landlock.color());
            occupiedBounds.add(new LabelBounds(x - 4, y - 4, 8, 8));

            int labelWidth = textRenderer.getWidth(landlock.label());
            LabelBounds labelBounds = chooseLabelBounds(x, y, labelWidth, left, top, size, occupiedBounds);
            context.drawTextWithShadow(textRenderer, Text.literal(landlock.label()), labelBounds.x(), labelBounds.y(), landlock.color());
            occupiedBounds.add(labelBounds.expand(2));
        }
    }

    private void renderBeds(DrawContext context, int left, int top, int size, List<LabelBounds> occupiedBounds) {
        for (int index = 0; index < payload.beds().size(); index++) {
            OpenBreachedMapPayload.Bed bed = payload.beds().get(index);
            if (!isInsideBorder(bed.x(), bed.z())) {
                continue;
            }

            int x = (int) Math.round(worldToScreenX(bed.x(), left, size));
            int y = (int) Math.round(worldToScreenZ(bed.z(), top, size));
            if (!isInsideMap(x, y)) {
                continue;
            }

            boolean bedReady = isBedReady(bed);
            int bedColor = bedReady ? bed.color() : UNAVAILABLE_BED_COLOR;
            renderBedMarker(context, x, y, bedColor);
            occupiedBounds.add(new LabelBounds(x - 5, y - 3, 10, 6));

            int labelWidth = scaledLabelWidth(bed.label(), BED_LABEL_SCALE);
            int labelHeight = scaledLabelHeight(BED_LABEL_SCALE);
            LabelBounds labelBounds = chooseLabelBounds(x, y, labelWidth, labelHeight, left, top, size, occupiedBounds);
            drawScaledTextWithShadow(
                    context,
                    bed.label(),
                    labelBounds.x(),
                    labelBounds.y(),
                    bedReady ? bed.color() : UNAVAILABLE_BED_TEXT_COLOR,
                    BED_LABEL_SCALE
            );
            occupiedBounds.add(labelBounds.expand(2));
        }
    }

    private void renderTeammates(DrawContext context, int left, int top, int size, List<LabelBounds> occupiedBounds) {
        for (OpenBreachedMapPayload.Teammate teammate : payload.teammates()) {
            if (!isInsideBorder(teammate.x(), teammate.z())) {
                continue;
            }

            int x = (int) Math.round(worldToScreenX(teammate.x(), left, size));
            int y = (int) Math.round(worldToScreenZ(teammate.z(), top, size));
            if (!isInsideMap(x, y)) {
                continue;
            }

            renderPlayerMarker(context, x, y, teammate.color());
            occupiedBounds.add(new LabelBounds(x - 7, y - 7, 14, 14));

            int labelWidth = scaledLabelWidth(teammate.name(), PLAYER_LABEL_SCALE);
            int labelHeight = scaledLabelHeight(PLAYER_LABEL_SCALE);
            LabelBounds labelBounds = chooseLabelBounds(x, y, labelWidth, labelHeight, left, top, size, occupiedBounds);
            drawScaledTextWithShadow(context, teammate.name(), labelBounds.x(), labelBounds.y(), teammate.color(), PLAYER_LABEL_SCALE);
            occupiedBounds.add(labelBounds.expand(2));
        }
    }

    private LabelBounds chooseLabelBounds(
            int markerX,
            int markerY,
            int labelWidth,
            int mapLeft,
            int mapTop,
            int mapSize,
            List<LabelBounds> occupiedBounds
    ) {
        return chooseLabelBounds(markerX, markerY, labelWidth, 10, mapLeft, mapTop, mapSize, occupiedBounds);
    }

    private LabelBounds chooseLabelBounds(
            int markerX,
            int markerY,
            int labelWidth,
            int labelHeight,
            int mapLeft,
            int mapTop,
            int mapSize,
            List<LabelBounds> occupiedBounds
    ) {
        int[][] offsets = {
                {8, -4},
                {-labelWidth - 8, -4},
                {-labelWidth / 2, -18},
                {-labelWidth / 2, 10},
                {8, 8},
                {-labelWidth - 8, 8},
                {8, -16},
                {-labelWidth - 8, -16},
                {-labelWidth / 2, -30},
                {-labelWidth / 2, 22}
        };

        LabelBounds bestBounds = null;
        int bestPenalty = Integer.MAX_VALUE;
        for (int index = 0; index < offsets.length; index++) {
            LabelBounds candidate = new LabelBounds(
                    clamp(markerX + offsets[index][0], mapLeft + 2, mapLeft + mapSize - labelWidth - 2),
                    clamp(markerY + offsets[index][1], mapTop + 2, mapTop + mapSize - labelHeight - 2),
                    labelWidth,
                    labelHeight
            );
            int penalty = getLabelCollisionPenalty(candidate, occupiedBounds) + index;
            if (penalty == 0) {
                return candidate;
            }
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestBounds = candidate;
            }
        }

        return bestBounds == null
                ? new LabelBounds(
                clamp(markerX + 8, mapLeft + 2, mapLeft + mapSize - labelWidth - 2),
                clamp(markerY - 4, mapTop + 2, mapTop + mapSize - labelHeight - 2),
                labelWidth,
                labelHeight
        )
                : bestBounds;
    }

    private static int getLabelCollisionPenalty(LabelBounds candidate, List<LabelBounds> occupiedBounds) {
        int penalty = 0;
        for (LabelBounds occupied : occupiedBounds) {
            penalty += candidate.intersectionArea(occupied);
        }

        return penalty;
    }

    private void renderPlayer(DrawContext context, int left, int top, int size, List<LabelBounds> occupiedBounds) {
        double playerX = currentPlayerX();
        double playerZ = currentPlayerZ();
        if (!isInsideBorder(playerX, playerZ)) {
            return;
        }

        int x = (int) Math.round(worldToScreenX(playerX, left, size));
        int y = (int) Math.round(worldToScreenZ(playerZ, top, size));
        if (!isInsideMap(x, y)) {
            return;
        }

        renderPlayerMarker(context, x, y, PLAYER_COLOR);
        occupiedBounds.add(new LabelBounds(x - 4, y - 4, 8, 8));

        String label = "You";
        int labelWidth = scaledLabelWidth(label, PLAYER_LABEL_SCALE);
        int labelHeight = scaledLabelHeight(PLAYER_LABEL_SCALE);
        LabelBounds labelBounds = chooseLabelBounds(x, y, labelWidth, labelHeight, left, top, size, occupiedBounds);
        drawScaledTextWithShadow(context, label, labelBounds.x(), labelBounds.y(), PLAYER_COLOR, PLAYER_LABEL_SCALE);
        occupiedBounds.add(labelBounds.expand(2));
    }

    private int scaledLabelWidth(String label, float scale) {
        return Math.max(1, (int) Math.ceil(textRenderer.getWidth(label) * scale));
    }

    private static int scaledLabelHeight(float scale) {
        return Math.max(1, (int) Math.ceil(10 * scale));
    }

    private void drawScaledTextWithShadow(DrawContext context, String label, int x, int y, int color, float scale) {
        context.getMatrices().pushMatrix();
        context.getMatrices().translate((float) x, (float) y);
        context.getMatrices().scale(scale, scale);
        context.drawTextWithShadow(textRenderer, Text.literal(label), 0, 0, color);
        context.getMatrices().popMatrix();
    }

    private static void renderPlayerMarker(DrawContext context, int centerX, int centerY, int color) {
        fillCircle(context, centerX, centerY, 3, 0xFF050505);
        fillCircle(context, centerX, centerY, 2, color);
    }

    private static void renderLandlockMarker(DrawContext context, int centerX, int centerY, int color) {
        fillDiamond(context, centerX, centerY, 6, 0xFF050505);
        fillDiamond(context, centerX, centerY, 5, color);
    }

    private static void fillDiamond(DrawContext context, int centerX, int centerY, int radius, int color) {
        for (int offsetY = -radius; offsetY <= radius; offsetY++) {
            int offsetX = radius - Math.abs(offsetY);
            context.fill(centerX - offsetX, centerY + offsetY, centerX + offsetX + 1, centerY + offsetY + 1, color);
        }
    }

    private static void renderDeathMarker(DrawContext context, int centerX, int centerY, int color) {
        context.fill(centerX - 3, centerY - 3, centerX + 4, centerY + 4, 0xFF050505);
        for (int offset = -2; offset <= 2; offset++) {
            context.fill(centerX + offset, centerY + offset, centerX + offset + 1, centerY + offset + 1, color);
            context.fill(centerX + offset, centerY - offset, centerX + offset + 1, centerY - offset + 1, color);
        }
    }

    private static void renderBedMarker(DrawContext context, int centerX, int centerY, int color) {
        context.fill(centerX - 4, centerY - 3, centerX + 5, centerY + 3, 0xFF050505);
        context.fill(centerX - 3, centerY - 2, centerX + 4, centerY + 2, color);
        context.fill(centerX - 3, centerY - 2, centerX, centerY + 2, 0xFFFFEFF3);
    }

    private static void fillCircle(DrawContext context, int centerX, int centerY, int radius, int color) {
        for (int offsetY = -radius; offsetY <= radius; offsetY++) {
            int offsetX = (int) Math.floor(Math.sqrt(radius * radius - offsetY * offsetY));
            context.fill(centerX - offsetX, centerY + offsetY, centerX + offsetX + 1, centerY + offsetY + 1, color);
        }
    }

    private double worldToScreenX(double worldX, int left, int size) {
        double normalized = (worldX - viewLeftWorld()) / viewWorldSize();
        return left + normalized * size;
    }

    private double worldToScreenZ(double worldZ, int top, int size) {
        double normalized = (worldZ - viewTopWorld()) / viewWorldSize();
        return top + normalized * size;
    }

    private double screenToWorldX(double screenX, int left, int size) {
        return viewLeftWorld() + ((screenX - left) / size) * viewWorldSize();
    }

    private double screenToWorldZ(double screenY, int top, int size) {
        return viewTopWorld() + ((screenY - top) / size) * viewWorldSize();
    }

    private boolean isInsideBorder(double worldX, double worldZ) {
        double halfBorder = payload.borderSize() / 2.0D;
        return worldX >= -halfBorder && worldX <= halfBorder && worldZ >= -halfBorder && worldZ <= halfBorder;
    }

    private boolean isInsideMap(double mouseX, double mouseY) {
        int mapLeft = mapLeft();
        int mapTop = mapTop();
        int mapSize = mapSize();
        return mouseX >= mapLeft && mouseX < mapLeft + mapSize && mouseY >= mapTop && mouseY < mapTop + mapSize;
    }

    private Text getCoordinateText() {
        return Text.literal("You: X " + currentPlayerBlockX() + "  Z " + currentPlayerBlockZ());
    }

    private double currentPlayerX() {
        if (!respawnOnBedSelect && client != null && client.player != null) {
            return client.player.getX();
        }

        return payload.playerX();
    }

    private double currentPlayerZ() {
        if (!respawnOnBedSelect && client != null && client.player != null) {
            return client.player.getZ();
        }

        return payload.playerZ();
    }

    private int currentPlayerBlockX() {
        return (int) Math.floor(currentPlayerX());
    }

    private int currentPlayerBlockZ() {
        return (int) Math.floor(currentPlayerZ());
    }

    private int getClickedBedListIndex(double mouseX, double mouseY) {
        if (!shouldShowTownhallRespawnButton() || payload.beds().isEmpty()) {
            return -1;
        }

        int left = townhallRespawnButtonLeft();
        int top = townhallRespawnButtonTop() + TOWNHALL_BUTTON_HEIGHT + 7;
        for (int index = 0; index < payload.beds().size(); index++) {
            OpenBreachedMapPayload.Bed bed = payload.beds().get(index);
            if (!isBedReady(bed)) {
                continue;
            }

            int rowTop = top + index * BED_LIST_ROW_HEIGHT;
            if (isInsideBedListRow(mouseX, mouseY, left, rowTop)) {
                return bed.bedIndex();
            }
        }

        return -1;
    }

    private int getClickedBedIndex(double mouseX, double mouseY) {
        int mapLeft = mapLeft();
        int mapTop = mapTop();
        int mapSize = mapSize();
        for (int index = payload.beds().size() - 1; index >= 0; index--) {
            OpenBreachedMapPayload.Bed bed = payload.beds().get(index);
            if (!isBedReady(bed) || !isInsideBorder(bed.x(), bed.z())) {
                continue;
            }

            int x = (int) Math.round(worldToScreenX(bed.x(), mapLeft, mapSize));
            int y = (int) Math.round(worldToScreenZ(bed.z(), mapTop, mapSize));
            if (!isInsideMap(x, y)) {
                continue;
            }

            if (mouseX >= x - 7 && mouseX <= x + 7 && mouseY >= y - 6 && mouseY <= y + 6) {
                return bed.bedIndex();
            }
        }

        return -1;
    }

    private void requestBedRespawn(int bedIndex) {
        BreachedClient.requestBedRespawn(bedIndex);
    }

    private boolean hasTerrain() {
        int terrainResolution = payload.terrainResolution();
        return terrainResolution > 0 && payload.terrainColors().length >= terrainResolution * terrainResolution * 2;
    }

    private boolean shouldShowTownhallRespawnButton() {
        return respawnOnBedSelect;
    }

    private boolean isBedReady(OpenBreachedMapPayload.Bed bed) {
        return bed.available() || getRemainingBedCooldownTicks(bed) <= 0;
    }

    private int getRemainingBedCooldownTicks(OpenBreachedMapPayload.Bed bed) {
        long elapsedTicks = (System.currentTimeMillis() - openedAtMillis) / 50L;
        return Math.max(0, bed.cooldownRemainingTicks() - (int) Math.min(Integer.MAX_VALUE, elapsedTicks));
    }

    private double viewWorldSize() {
        return payload.borderSize() / zoom;
    }

    private double viewLeftWorld() {
        return viewCenterX - viewWorldSize() / 2.0D;
    }

    private double viewRightWorld() {
        return viewCenterX + viewWorldSize() / 2.0D;
    }

    private double viewTopWorld() {
        return viewCenterZ - viewWorldSize() / 2.0D;
    }

    private double viewBottomWorld() {
        return viewCenterZ + viewWorldSize() / 2.0D;
    }

    private int mapSize() {
        return Math.max(150, Math.min(720, Math.min(width - 32, height - 64)));
    }

    private int mapLeft() {
        return (width - mapSize()) / 2;
    }

    private int mapTop() {
        return (height - mapSize()) / 2 + 14;
    }

    private int closeButtonLeft() {
        return mapLeft() + mapSize() - CLOSE_BUTTON_SIZE;
    }

    private int closeButtonTop() {
        return mapTop() - 24;
    }

    private boolean isInsideCloseButton(double mouseX, double mouseY) {
        int closeLeft = closeButtonLeft();
        int closeTop = closeButtonTop();
        return mouseX >= closeLeft
                && mouseX < closeLeft + CLOSE_BUTTON_SIZE
                && mouseY >= closeTop
                && mouseY < closeTop + CLOSE_BUTTON_SIZE;
    }

    private int refreshButtonLeft() {
        return closeButtonLeft() - REFRESH_BUTTON_SIZE - 4;
    }

    private int refreshButtonTop() {
        return closeButtonTop();
    }

    private boolean isInsideRefreshButton(double mouseX, double mouseY) {
        int refreshLeft = refreshButtonLeft();
        int refreshTop = refreshButtonTop();
        return mouseX >= refreshLeft
                && mouseX < refreshLeft + REFRESH_BUTTON_SIZE
                && mouseY >= refreshTop
                && mouseY < refreshTop + REFRESH_BUTTON_SIZE;
    }

    private int townhallRespawnButtonLeft() {
        int sideLeft = mapLeft() + mapSize() + 10;
        if (sideLeft + TOWNHALL_BUTTON_WIDTH <= width - 8) {
            return sideLeft;
        }

        return mapLeft() + mapSize() - TOWNHALL_BUTTON_WIDTH - 8;
    }

    private int townhallRespawnButtonTop() {
        return mapTop() + 8;
    }

    private boolean isInsideTownhallRespawnButton(double mouseX, double mouseY) {
        if (!shouldShowTownhallRespawnButton()) {
            return false;
        }

        int left = townhallRespawnButtonLeft();
        int top = townhallRespawnButtonTop();
        return mouseX >= left
                && mouseX < left + TOWNHALL_BUTTON_WIDTH
                && mouseY >= top
                && mouseY < top + TOWNHALL_BUTTON_HEIGHT;
    }

    private static boolean isInsideBedListRow(double mouseX, double mouseY, int left, int rowTop) {
        return mouseX >= left
                && mouseX < left + TOWNHALL_BUTTON_WIDTH
                && mouseY >= rowTop
                && mouseY < rowTop + TOWNHALL_BUTTON_HEIGHT;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private static int decodeRgb565(byte[] colors, int colorIndex) {
        int byteIndex = colorIndex * 2;
        int rgb565 = (colors[byteIndex] & 0xFF) << 8 | colors[byteIndex + 1] & 0xFF;
        int red = (rgb565 >> 11) & 0x1F;
        int green = (rgb565 >> 5) & 0x3F;
        int blue = rgb565 & 0x1F;
        red = red << 3 | red >> 2;
        green = green << 2 | green >> 4;
        blue = blue << 3 | blue >> 2;
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private record LabelBounds(int x, int y, int width, int height) {
        private LabelBounds expand(int padding) {
            return new LabelBounds(x - padding, y - padding, width + padding * 2, height + padding * 2);
        }

        private int intersectionArea(LabelBounds other) {
            int overlapWidth = Math.max(0, Math.min(x + width, other.x() + other.width()) - Math.max(x, other.x()));
            int overlapHeight = Math.max(0, Math.min(y + height, other.y() + other.height()) - Math.max(y, other.y()));
            return overlapWidth * overlapHeight;
        }
    }
}
