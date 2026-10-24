package io.github.divineblock.weapon;

import io.github.divineblock.boss.SacredBoss;
import io.github.divineblock.game.Fx;
import java.util.List;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;

/** Ослиная челюсть Самсона (Judges 15:15–16): the more foes about, the harder it hits: 5, and one more for each hostile
 * within six blocks (up to eight more); every blow throws back all foes within two blocks, and with three or more of them
 * the ground shakes. It is old bone and breaks after sixty blows. */
public final class SamsonJawboneItem extends SwordItem {
    public SamsonJawboneItem(Settings settings) {
        super(WeaponMaterial.BONE, settings.attributeModifiers(SwordItem.createAttributeModifiers(WeaponMaterial.BONE, 1, -2.6f)));
    }

    @Override public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (attacker.getWorld() instanceof ServerWorld world && attacker instanceof PlayerEntity player) {
            if (!DivineWeapons.allowed(player, WeaponKind.SAMSON_JAWBONE)) return super.postHit(stack, target, attacker);
            List<HostileEntity> foes = world.getEntitiesByClass(HostileEntity.class, new Box(attacker.getBlockPos()).expand(6), LivingEntity::isAlive);
            int crowd = Math.min(8, foes.size());
            if (crowd > 0) DivineWeapons.extra(target, attacker, crowd);
            int thrown = 0;
            for (HostileEntity foe : foes) {
                if (foe.squaredDistanceTo(attacker) > 2.2 * 2.2) continue;
                SacredBoss.push(foe, attacker.getPos(), 0.9, 0.3);
                thrown++;
            }
            Fx.ring(world, attacker.getPos(), 0xFFE8E0C8, 2.2f);
            world.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(), SoundEvents.ENTITY_SKELETON_HURT, SoundCategory.PLAYERS, 1f, 0.5f);
            if (thrown >= 3) Fx.shake(world, attacker.getPos(), 0.5f, 10);
        }
        return super.postHit(stack, target, attacker);
    }
}
