package nrd.breached.mixin;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import nrd.breached.worldgen.TownhallHorseTrade;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(SpawnEggItem.class)
public class SpawnEggItemMixin {
    @Inject(method = "spawnBaby", at = @At("RETURN"))
    private void breached$randomizeTownhallHorseBaby(
            PlayerEntity player,
            MobEntity entity,
            EntityType<? extends MobEntity> entityType,
            ServerWorld world,
            Vec3d pos,
            ItemStack stack,
            CallbackInfoReturnable<Optional<MobEntity>> cir
    ) {
        cir.getReturnValue().ifPresent(spawned ->
                TownhallHorseTrade.randomizeIfTownhallHorseEgg(spawned, stack, world.getRandom())
        );
    }
}
