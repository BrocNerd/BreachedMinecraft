package nrd.breached.mixin;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import nrd.breached.item.BreacherItem;
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
        if (!(stack.getItem() instanceof BreacherItem)) {
            return;
        }

        BlockState state = asBlockState();
        if (BreacherItem.isBlockedBlock(state)) {
            cir.setReturnValue(0.0F);
            return;
        }

        cir.setReturnValue(BreacherItem.BLOCK_BREAKING_DELTA);
    }
}
