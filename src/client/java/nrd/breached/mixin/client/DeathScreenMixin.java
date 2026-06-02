package nrd.breached.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import nrd.breached.client.BreachedClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DeathScreen.class)
public class DeathScreenMixin {
    @Unique
    private boolean breached$requestedRespawnMap;

    @Inject(method = "init", at = @At("TAIL"))
    private void breached$requestRespawnMap(CallbackInfo ci) {
        breached$addOpenMapButton();
        if (breached$requestedRespawnMap) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }

        breached$requestedRespawnMap = true;
        BreachedClient.requestDeathRespawnMap((Screen) (Object) this);
    }

    @Unique
    private void breached$addOpenMapButton() {
        Screen screen = (Screen) (Object) this;
        ((ScreenAccessor) screen).breached$addDrawableChild(ButtonWidget.builder(
                        Text.literal("Open Breached Map"),
                        button -> BreachedClient.requestDeathRespawnMap(screen)
                )
                .dimensions(screen.width / 2 - 100, screen.height / 4 + 120, 200, 20)
                .build());
    }
}
