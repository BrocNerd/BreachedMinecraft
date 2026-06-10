package nrd.breached.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import nrd.breached.worldgen.TownhallHorseTrade;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityType.class)
public class EntityTypeMixin {
    @Inject(method = "spawnFromItemStack", at = @At("RETURN"))
    private void breached$randomizeTownhallHorse(
            ServerWorld world,
            ItemStack stack,
            LivingEntity user,
            BlockPos pos,
            SpawnReason spawnReason,
            boolean alignPosition,
            boolean invertY,
            CallbackInfoReturnable<Entity> cir
    ) {
        Entity entity = cir.getReturnValue();
        if (entity != null) {
            TownhallHorseTrade.randomizeIfTownhallHorseEgg(entity, stack, world.getRandom());
        }
    }
}
