package io.github.mobtalk.game;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

/** A drink bought from the bartender ({@link BarMerchant}): drunk like a potion, its effects come from its food component. A
 * strong drink makes the head spin, and each one more on top of the last spins it longer. */
public final class BarDrinkItem extends Item {
    /** Seconds of spinning each strong drink adds on top of what is left. */
    private final int hops;

    public BarDrinkItem(Settings settings, int hops) { super(settings); this.hops = hops; }

    @Override public UseAction getUseAction(ItemStack stack) { return UseAction.DRINK; }
    @Override public SoundEvent getDrinkSound() { return SoundEvents.ENTITY_GENERIC_DRINK; }
    @Override public SoundEvent getEatSound() { return SoundEvents.ENTITY_GENERIC_DRINK; }

    @Override public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        if (!world.isClient && hops > 0) {
            StatusEffectInstance spinning = user.getStatusEffect(StatusEffects.NAUSEA);
            int left = spinning == null ? 0 : spinning.getDuration();
            user.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, Math.min(left + hops * 20, 90 * 20), 0, false, false, true));
        }
        return super.finishUsing(stack, world, user);
    }
}
