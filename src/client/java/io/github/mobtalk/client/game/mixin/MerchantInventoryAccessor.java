package io.github.mobtalk.client.game.mixin;

import net.minecraft.village.MerchantInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MerchantInventory.class)
public interface MerchantInventoryAccessor {
    @Accessor("offerIndex") int mobtalk$offerIndex();
}
