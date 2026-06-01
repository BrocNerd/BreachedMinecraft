package nrd.breached.item;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import nrd.breached.network.OpenBreachedArchivePayload;

public class BreachedArchiveItem extends Item {
    public BreachedArchiveItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (user instanceof ServerPlayerEntity player
                && ServerPlayNetworking.canSend(player, OpenBreachedArchivePayload.ID)) {
            ServerPlayNetworking.send(player, OpenBreachedArchivePayload.INSTANCE);
        }

        return ActionResult.SUCCESS;
    }
}
