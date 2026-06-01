package nrd.breached.client.screen;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import nrd.breached.Breached;
import nrd.breached.client.BreachedClient;
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
    private static final int CLOSE_HOVER_COLOR = 0xFFB94A4A;
    private static final int CLOSE_TEXT_COLOR = 0xFFFFEFEF;
    private static final int CLOSE_BUTTON_SIZE = 16;
    private static final float PLAYER_LABEL_SCALE = 0.75F;
    private static final double MIN_ZOOM = 1.0D;
    private static final double MAX_ZOOM = 8.0D;
    private static int nextTerrainTextureId;

    private final OpenBreachedMapPayload payload;
    private final Screen parentScreen;
    private final Identifier terrainTextureId;
    private NativeImageBackedTexture terrainTexture;
    private double zoom = MIN_ZOOM;
    private double viewCenterX;
    private double viewCenterZ;

    public BreachedMapScreen(OpenBreachedMapPayload payload, Screen parentScreen) {
        super(Text.literal("Breached Map"));
        this.payload = payload;
        this.parentScreen = parentScreen;
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
        renderCloseIcon(context, mouseX, mouseY);
        context.drawTextWithShadow(
                textRenderer,
                Text.literal("Zoom: " + (int) Math.round(zoom * 100.0D) + "%"),
                mapLeft,
                titleY + 12,
                MUTED_TEXT_COLOR
        );

        renderMap(context, mapLeft, mapTop, mapSize);
        context.drawTextWithShadow(
                textRenderer,
                Text.literal("You: X " + payload.playerX() + "  Z " + payload.playerZ()),
                mapLeft,
                mapTop + mapSize + 8,
                MUTED_TEXT_COLOR
        );

        super.render(context, mouseX, mouseY, deltaTicks);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0 && isInsideCloseButton(click.x(), click.y())) {
            close();
            return true;
        }

        if (click.button() == 0 && isInsideMap(click.x(), click.y())) {
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
            close();
            return true;
        }

        return super.keyPressed(input);
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

    private void renderMap(DrawContext context, int left, int top, int size) {
        context.enableScissor(left, top, left + size, top + size);
        context.fill(left, top, left + size, top + size, MAP_BACKGROUND);
        if (hasTerrain()) {
            renderTerrain(context, left, top, size);
        }

        List<LabelBounds> occupiedBounds = new ArrayList<>();
        renderMarkers(context, left, top, size, occupiedBounds);
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

            int labelWidth = scaledPlayerLabelWidth(teammate.name());
            int labelHeight = scaledPlayerLabelHeight();
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
        if (!isInsideBorder(payload.playerX(), payload.playerZ())) {
            return;
        }

        int x = (int) Math.round(worldToScreenX(payload.playerX(), left, size));
        int y = (int) Math.round(worldToScreenZ(payload.playerZ(), top, size));
        if (!isInsideMap(x, y)) {
            return;
        }

        renderPlayerMarker(context, x, y, PLAYER_COLOR);
        occupiedBounds.add(new LabelBounds(x - 6, y - 6, 12, 12));

        String label = "You";
        int labelWidth = scaledPlayerLabelWidth(label);
        int labelHeight = scaledPlayerLabelHeight();
        LabelBounds labelBounds = chooseLabelBounds(x, y, labelWidth, labelHeight, left, top, size, occupiedBounds);
        drawScaledTextWithShadow(context, label, labelBounds.x(), labelBounds.y(), PLAYER_COLOR, PLAYER_LABEL_SCALE);
        occupiedBounds.add(labelBounds.expand(2));
    }

    private int scaledPlayerLabelWidth(String label) {
        return Math.max(1, (int) Math.ceil(textRenderer.getWidth(label) * PLAYER_LABEL_SCALE));
    }

    private static int scaledPlayerLabelHeight() {
        return Math.max(1, (int) Math.ceil(10 * PLAYER_LABEL_SCALE));
    }

    private void drawScaledTextWithShadow(DrawContext context, String label, int x, int y, int color, float scale) {
        context.getMatrices().pushMatrix();
        context.getMatrices().translate((float) x, (float) y);
        context.getMatrices().scale(scale, scale);
        context.drawTextWithShadow(textRenderer, Text.literal(label), 0, 0, color);
        context.getMatrices().popMatrix();
    }

    private static void renderPlayerMarker(DrawContext context, int centerX, int centerY, int color) {
        fillCircle(context, centerX, centerY, 5, 0xFF050505);
        fillCircle(context, centerX, centerY, 4, color);
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

    private boolean hasTerrain() {
        int terrainResolution = payload.terrainResolution();
        return terrainResolution > 0 && payload.terrainColors().length >= terrainResolution * terrainResolution * 2;
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
