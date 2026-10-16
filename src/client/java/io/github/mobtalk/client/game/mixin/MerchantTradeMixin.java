package io.github.mobtalk.client.game.mixin;

import io.github.mobtalk.client.game.MobGameBridge;
import io.github.mobtalk.client.game.VillagerBargaining;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.village.TradeOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MerchantEntity.class)
abstract class MerchantTradeMixin {
    @Inject(method = "trade", at = @At("RETURN"))
    private void mobtalk$traded(TradeOffer offer, CallbackInfo ci) {
        MerchantEntity merchant = (MerchantEntity) (Object) this;
        if (merchant.getCustomer() instanceof ServerPlayerEntity player) {
            MobGameBridge.helped(merchant, player, "The player completed a trade");
            if (merchant instanceof VillagerEntity villager) VillagerBargaining.traded(villager, player);
        }
    }
}
