package nrd.breached.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.explosion.Explosion;
import net.minecraft.world.explosion.ExplosionBehavior;
import nrd.breached.landlock.LandlockClaimManager;
import nrd.breached.worldgen.BreachedStructurePlacementManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ExplosionBehavior.class)
public class ExplosionBehaviorMixin {
    @Inject(method = "canDestroyBlock", at = @At("HEAD"), cancellable = true)
    private void breached$protectBlocksFromExplosions(
            Explosion explosion,
            BlockView world,
            BlockPos pos,
            BlockState state,
            float power,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (LandlockClaimManager.isInsideAnyClaim(explosion.getWorld(), pos)
                || BreachedStructurePlacementManager.isInsideProtectedStructureExplosionArea(explosion.getWorld(), pos)) {
            cir.setReturnValue(false);
        }
    }
}
