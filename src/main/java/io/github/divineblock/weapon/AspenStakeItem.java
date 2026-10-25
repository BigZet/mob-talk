package io.github.divineblock.weapon;

import io.github.divineblock.game.Fx;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import java.util.List;
import net.minecraft.item.Item;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Осиновый кол (a folk belief, not church teaching, and the tooltip says so): 3 of harm, threefold against the unclean
 * with fire, fourfold at night on the god's cemetery; the unclean it kills are laid to rest with grey smoke and a quiet
 * bell ({@link WeaponHooks}). Fifty blows and it splinters. */
public final class AspenStakeItem extends SwordItem {
    public AspenStakeItem(Settings settings) {
        super(WeaponMaterial.WOOD, settings.attributeModifiers(SwordItem.createAttributeModifiers(WeaponMaterial.WOOD, 1, -2.0f)));
    }

    @Override public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (attacker.getWorld() instanceof ServerWorld world && attacker instanceof PlayerEntity player) {
            if (!DivineWeapons.allowed(player, WeaponKind.ASPEN_STAKE)) return super.postHit(stack, target, attacker);
            if (WeaponHooks.unclean(target)) {
                boolean graveyardNight = world.isNight() && WeaponHooks.onCemetery(world, target.getBlockPos());
                DivineWeapons.extra(target, attacker, graveyardNight ? 9f : 6f);
                target.setOnFireFor(3);
                Fx.sparks(world, target.getPos().add(0, target.getHeight() * 0.6, 0), 0xFFE0D8B0, 0.6f);
            }
        }
        return super.postHit(stack, target, attacker);
    }

    @Override public void appendTooltip(ItemStack stack, Item.TooltipContext context, List<Text> tooltip, TooltipType type) {
        tooltip.add(Text.translatable("item.divineblock.aspen_stake.tooltip").formatted(Formatting.GRAY, Formatting.ITALIC));
        super.appendTooltip(stack, context, tooltip, type);
    }
}
