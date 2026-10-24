package io.github.divineblock.weapon;

import io.github.divineblock.game.Fx;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.server.world.ServerWorld;

/** Большой меч Войны (Revelation 6:4): the sword given to the rider on the red horse to take peace from the earth. It
 * strikes for 10, slowly; while it is in the main hand the hostile around are stronger and come for its bearer from afar,
 * the villagers flee from him and no merchant will trade with him (all in {@link WeaponHooks}). Off by default. */
public final class WarSwordItem extends SwordItem {
    public WarSwordItem(Settings settings) {
        super(WeaponMaterial.WAR, settings.attributeModifiers(SwordItem.createAttributeModifiers(WeaponMaterial.WAR, 2, -3.0f)));
    }

    @Override public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (attacker.getWorld() instanceof ServerWorld world && attacker instanceof PlayerEntity player) {
            if (!DivineWeapons.allowed(player, WeaponKind.WAR_SWORD)) return super.postHit(stack, target, attacker);
            Fx.sparks(world, target.getPos().add(0, target.getHeight() * 0.6, 0), Fx.BLOOD, 0.8f);
        }
        return super.postHit(stack, target, attacker);
    }

    @Override public boolean canRepair(ItemStack stack, ItemStack ingredient) { return false; }
}
