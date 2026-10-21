package io.github.divineblock.mixin;

import io.github.divineblock.boss.BossEffects;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The bosses' afflictions on the server: «Опаление» turns healing into burning, «Обет молчания» forbids the totem. */
@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
    @Inject(method = "heal", at = @At("HEAD"), cancellable = true)
    private void divineblock$scorch(float amount, CallbackInfo info) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (amount <= 0 || self.getWorld().isClient || !BossEffects.scorched(self)) return;
        info.cancel();
        self.damage(self.getDamageSources().onFire(), Math.min(amount, 2f));
    }

    /** «Прах земной»: the earth holds the feet, no jumping. */
    @Inject(method = "jump", at = @At("HEAD"), cancellable = true)
    private void divineblock$dust(CallbackInfo info) {
        if (((LivingEntity) (Object) this).hasStatusEffect(BossEffects.DUST)) info.cancel();
    }

    @Inject(method = "tryUseTotem", at = @At("HEAD"), cancellable = true)
    private void divineblock$vow(DamageSource source, CallbackInfoReturnable<Boolean> info) {
        if (BossEffects.silenced((LivingEntity) (Object) this)) info.setReturnValue(false);
    }
}
