package nrd.breached.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import nrd.breached.combat.CombatLogoutBodyManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerInventory.class)
public class PlayerInventoryMixin {
    @Shadow
    @Final
    public PlayerEntity player;

    @Inject(method = "dropAll", at = @At("HEAD"), cancellable = true)
    private void breached$suppressCombatLogoutPenaltyDrops(CallbackInfo ci) {
        if (!(player instanceof ServerPlayerEntity serverPlayer) || !CombatLogoutBodyManager.shouldSuppressDeathDrops(serverPlayer)) {
            return;
        }

        PlayerInventory inventory = (PlayerInventory) (Object) this;
        for (int slot = 0; slot < inventory.size(); slot++) {
            inventory.setStack(slot, ItemStack.EMPTY);
        }
        inventory.markDirty();
        ci.cancel();
    }
}
