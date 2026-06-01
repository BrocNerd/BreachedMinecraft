package nrd.breached.mixin.client;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import nrd.breached.LowYHealthLimitManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    private static final Identifier LOCKED_HEART_TEXTURE = Identifier.ofVanilla("hud/heart/full");
    private static final int HEARTS_PER_ROW = 10;
    private static final int HEART_SIZE = 9;
    private static final int HEART_SPACING = 8;
    private static final int LOCKED_HEART_COLOR = 0x99000000;

    @Inject(method = "renderHealthBar", at = @At("TAIL"))
    private void breached$renderLowYLockedHearts(
            DrawContext context,
            PlayerEntity player,
            int x,
            int y,
            int lines,
            int regeneratingHeartIndex,
            float maxHealth,
            int lastHealth,
            int health,
            int absorption,
            boolean blinking,
            CallbackInfo ci
    ) {
        if (player.getBlockY() >= LowYHealthLimitManager.START_Y || maxHealth >= LowYHealthLimitManager.NORMAL_MAX_HEALTH) {
            return;
        }

        int activeHeartSlots = MathHelper.ceil(maxHealth / 2.0F);
        if (activeHeartSlots > 0 && activeHeartSlots <= HEARTS_PER_ROW && MathHelper.floor(maxHealth) % 2 == 1) {
            int halfLockedSlot = activeHeartSlots - 1;
            int heartX = x + halfLockedSlot % HEARTS_PER_ROW * HEART_SPACING;
            int heartY = y - halfLockedSlot / HEARTS_PER_ROW * lines;
            renderRightHalfLockedHeart(context, heartX, heartY);
        }

        for (int slot = activeHeartSlots; slot < HEARTS_PER_ROW; slot++) {
            int heartX = x + slot % HEARTS_PER_ROW * HEART_SPACING;
            int heartY = y - slot / HEARTS_PER_ROW * lines;
            renderLockedHeart(context, heartX, heartY);
        }
    }

    private static void renderRightHalfLockedHeart(DrawContext context, int x, int y) {
        context.enableScissor(x + HEART_SIZE / 2, y, x + HEART_SIZE, y + HEART_SIZE);
        renderLockedHeart(context, x, y);
        context.disableScissor();
    }

    private static void renderLockedHeart(DrawContext context, int x, int y) {
        context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, LOCKED_HEART_TEXTURE, x, y, HEART_SIZE, HEART_SIZE, LOCKED_HEART_COLOR);
    }
}
