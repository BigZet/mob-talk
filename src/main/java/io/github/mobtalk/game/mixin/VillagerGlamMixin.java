package io.github.mobtalk.game.mixin;

import io.github.mobtalk.game.GlamLook;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.passive.VillagerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Флаг наряда меркантильной горожанки ({@link GlamLook}) в отслеживаемых данных жителя: сервер шлёт его клиентам сам. */
@Mixin(VillagerEntity.class)
abstract class VillagerGlamMixin implements GlamLook {
    @Unique private static final TrackedData<Boolean> MOBTALK_GLAM = DataTracker.registerData(VillagerEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    @Inject(method = "initDataTracker", at = @At("TAIL"))
    private void mobtalk$trackGlam(DataTracker.Builder builder, CallbackInfo ci) { builder.add(MOBTALK_GLAM, false); }

    @Override public boolean mobtalk$glam() { return ((VillagerEntity) (Object) this).getDataTracker().get(MOBTALK_GLAM); }
    @Override public void mobtalk$setGlam(boolean glam) { ((VillagerEntity) (Object) this).getDataTracker().set(MOBTALK_GLAM, glam); }
}
