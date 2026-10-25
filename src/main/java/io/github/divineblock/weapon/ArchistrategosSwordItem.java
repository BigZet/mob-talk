package io.github.divineblock.weapon;

import io.github.divineblock.game.Fx;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;

/** Меч архистратига: the sword of the leader of the heavenly host. Against the unclean ({@link WeaponHooks#unclean}) it
 * strikes half again as hard with gold sparks, and every fifth such blow calls a bolt of light for four more. In a sinner's
 * hands it is heavy (Slowness while held), in a saint's light (Speed): see {@link WeaponHooks}. */
public final class ArchistrategosSwordItem extends SwordItem {
    private static final Map<UUID, Integer> BLOWS = new HashMap<>();

    public ArchistrategosSwordItem(Settings settings) {
        super(WeaponMaterial.HOLY, settings.attributeModifiers(SwordItem.createAttributeModifiers(WeaponMaterial.HOLY, 1, -2.4f)));
    }

    @Override public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (attacker.getWorld() instanceof ServerWorld world && attacker instanceof PlayerEntity player) {
            if (!DivineWeapons.allowed(player, WeaponKind.ARCHISTRATEGOS_SWORD)) return super.postHit(stack, target, attacker);
            if (WeaponHooks.unclean(target)) {
                int blows = BLOWS.merge(player.getUuid(), 1, Integer::sum);
                float extra = 4f;
                if (blows % 5 == 0) {
                    extra += 4f;
                    Fx.bolt(world, target.getPos(), Fx.HOLY);
                    world.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.2f, 1.4f);
                } else {
                    Fx.sparks(world, target.getPos().add(0, target.getHeight() * 0.6, 0), Fx.GOLD, 0.8f);
                }
                DivineWeapons.extra(target, attacker, extra);
            }
        }
        return super.postHit(stack, target, attacker);
    }

    @Override public boolean canRepair(ItemStack stack, ItemStack ingredient) { return false; }
}
