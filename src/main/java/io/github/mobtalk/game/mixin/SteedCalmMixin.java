package io.github.mobtalk.game.mixin;

import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.VillagerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A village horse under its horseman does not rear at every blow (the rider pitched forward over its neck, Dmitriy's screenshot
 * 2026-10-25): a war horse keeps its feet. Horses of the guard carry the tag VillageGuards.STEED_TAG. */
@Mixin(AbstractHorseEntity.class)
abstract class SteedCalmMixin {
    @Inject(method = "setAngry", at = @At("HEAD"), cancellable = true)
    private void mobtalk$noRearing(boolean angry, CallbackInfo ci) {
        AbstractHorseEntity horse = (AbstractHorseEntity) (Object) this;
        if (angry && horse.getCommandTags().contains("mobtalk_steed") && horse.getFirstPassenger() instanceof VillagerEntity) ci.cancel();
    }
}
