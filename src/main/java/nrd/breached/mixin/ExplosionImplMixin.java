package nrd.breached.mixin;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.explosion.ExplosionImpl;
import nrd.breached.landlock.LandlockClaimManager;
import nrd.breached.worldgen.BreachedStructurePlacementManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(ExplosionImpl.class)
public abstract class ExplosionImplMixin {
    @Shadow
    public abstract ServerWorld getWorld();

    @Inject(method = "getBlocksToDestroy", at = @At("RETURN"), cancellable = true)
    private void breached$removeProtectedBlocksFromExplosion(CallbackInfoReturnable<List<BlockPos>> cir) {
        ServerWorld world = getWorld();
        List<BlockPos> blocksToDestroy = cir.getReturnValue();
        if (blocksToDestroy.isEmpty()) {
            return;
        }

        List<BlockPos> filteredBlocks = new ArrayList<>(blocksToDestroy);
        filteredBlocks.removeIf(pos -> LandlockClaimManager.isInsideAnyClaim(world, pos)
                || BreachedStructurePlacementManager.isInsideProtectedStructure(world, pos));
        cir.setReturnValue(filteredBlocks);
    }
}
