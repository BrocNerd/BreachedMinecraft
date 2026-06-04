package nrd.breached.mixin.client;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.SleepingChatScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SleepingChatScreen.class)
public class SleepingChatScreenMixin {
    private static final int STOP_SLEEPING_BUTTON_BOTTOM_OFFSET = 95;
    private static final int MIN_STOP_SLEEPING_BUTTON_Y = 20;

    @Shadow
    private ButtonWidget stopSleepingButton;

    @Inject(method = "init", at = @At("TAIL"))
    private void breached$moveStopSleepingButton(CallbackInfo ci) {
        if (stopSleepingButton == null) {
            return;
        }

        Screen screen = (Screen) (Object) this;
        stopSleepingButton.setY(Math.max(MIN_STOP_SLEEPING_BUTTON_Y, screen.height - STOP_SLEEPING_BUTTON_BOTTOM_OFFSET));
    }
}
