package nrd.breached.mixin.client;

import net.minecraft.client.gui.ScreenPos;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import nrd.breached.client.widget.BreachedArchiveButtonWidget;
import nrd.breached.client.widget.BreachedMapButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryScreen.class)
public abstract class InventoryScreenMixin {
    @Unique
    private BreachedMapButtonWidget breached$mapButton;
    @Unique
    private BreachedArchiveButtonWidget breached$archiveButton;

    @Shadow
    protected abstract ScreenPos getRecipeBookButtonPos();

    @Inject(method = "init", at = @At("TAIL"))
    private void breached$addMapButton(CallbackInfo ci) {
        breached$mapButton = new BreachedMapButtonWidget(0, 0);
        breached$archiveButton = new BreachedArchiveButtonWidget(0, 0);
        breached$positionBreachedButtons();
        ((ScreenAccessor) (Object) this).breached$addDrawableChild(breached$mapButton);
        ((ScreenAccessor) (Object) this).breached$addDrawableChild(breached$archiveButton);
    }

    @Inject(method = "onRecipeBookToggled", at = @At("TAIL"))
    private void breached$moveMapButton(CallbackInfo ci) {
        breached$positionBreachedButtons();
    }

    @Unique
    private void breached$positionBreachedButtons() {
        if (breached$mapButton == null || breached$archiveButton == null) {
            return;
        }

        ScreenPos recipeBookButtonPos = getRecipeBookButtonPos();
        breached$mapButton.setPosition(recipeBookButtonPos.x() + 22, recipeBookButtonPos.y());
        breached$archiveButton.setPosition(recipeBookButtonPos.x() + 44, recipeBookButtonPos.y());
    }
}
