package nrd.breached.mixin;

import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.village.TradeOfferList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MerchantEntity.class)
public interface MerchantEntityAccessor {
    @Accessor("offers")
    void breached$setOffers(TradeOfferList offers);
}
