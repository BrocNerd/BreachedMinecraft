package nrd.breached.mixin;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import nrd.breached.item.BreacherItem;
import nrd.breached.reinforcement.ReinforcementManager;
import nrd.breached.reinforcement.ReinforcementVisibilityCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractBlock.AbstractBlockState.class)
public abstract class BlockBreakingDeltaMixin {
    @Shadow
    protected abstract BlockState asBlockState();

    @Inject(method = "calcBlockBreakingDelta", at = @At("HEAD"), cancellable = true)
    private void breached$useFlatBreacherBreakingDelta(PlayerEntity player, BlockView world, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        ItemStack stack = player.getMainHandStack();
        if (!(stack.getItem() instanceof BreacherItem breacherItem)) {
            return;
        }

        BlockState state = asBlockState();
        if (BreacherItem.isBlockedBlock(state)) {
            cir.setReturnValue(0.0F);
            return;
        }

        if (isReinforced(world, pos, state)) {
            cir.setReturnValue(breacherItem.getBlockBreakingDelta());
        }
    }

    private static boolean isReinforced(BlockView world, BlockPos pos, BlockState state) {
        if (!(world instanceof World actualWorld)) {
            return false;
        }

        if (ReinforcementManager.getTier(actualWorld, pos, state).isPresent()) {
            return true;
        }

        return actualWorld.isClient() && ReinforcementVisibilityCache.hasVisibleTier(pos);
    }
}
