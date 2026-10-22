package io.github.rusflora.hogweed;

import io.github.rusflora.RusFlora;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;

/** A phototoxic burn: for a minute after the sap has soaked in, the skin hurts in sunlight, every couple of seconds (more
 * often at higher levels), and the blisters slow one down. Step into shade, under a roof, or wait for night or rain, and the
 * damage stops at once. The damage type ignores armour, the sap is already under it. */
public final class BurnEffect extends StatusEffect {
    public static final int DURATION = 60 * 20;

    BurnEffect() {
        super(StatusEffectCategory.HARMFUL, 0xD9822B);
        addAttributeModifier(EntityAttributes.GENERIC_MOVEMENT_SPEED, RusFlora.id("effect.hogweed_burn"), -0.15,
                EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    }

    @Override public boolean canApplyUpdateEffect(int duration, int amplifier) { return duration % Math.max(10, 40 >> amplifier) == 0; }

    @Override public boolean applyUpdateEffect(LivingEntity entity, int amplifier) {
        if (!entity.getWorld().isClient && SapEffect.inSun(entity)) entity.damage(entity.getDamageSources().create(Hogweed.BURN_DAMAGE), 1.0f);
        return true;
    }
}
