package io.github.divineblock.weapon;

import io.github.divineblock.game.Fx;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

/** Копьё Георгия: the lance of «Чудо Георгия о змии». In the hand it strikes for 7 with a little more reach than a sword and
 * pins its mark (Slowness III for three seconds); held and let go it flies as {@link ThrownSpearEntity} and always comes
 * back. Serpents ({@link WeaponHooks#serpent}: Leviathan, the Beast, dragons, the wither) take it threefold. */
public final class GeorgeSpearItem extends Item {
    public GeorgeSpearItem(Settings settings) {
        super(settings.attributeModifiers(AttributeModifiersComponent.builder()
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, new EntityAttributeModifier(BASE_ATTACK_DAMAGE_MODIFIER_ID, 6.0, EntityAttributeModifier.Operation.ADD_VALUE), net.minecraft.component.type.AttributeModifierSlot.MAINHAND)
                .add(EntityAttributes.GENERIC_ATTACK_SPEED, new EntityAttributeModifier(BASE_ATTACK_SPEED_MODIFIER_ID, -2.8, EntityAttributeModifier.Operation.ADD_VALUE), net.minecraft.component.type.AttributeModifierSlot.MAINHAND)
                .add(EntityAttributes.PLAYER_ENTITY_INTERACTION_RANGE, new EntityAttributeModifier(net.minecraft.util.Identifier.of("divineblock", "spear_reach"), 0.5, EntityAttributeModifier.Operation.ADD_VALUE), net.minecraft.component.type.AttributeModifierSlot.MAINHAND)
                .build()));
    }

    @Override public UseAction getUseAction(ItemStack stack) { return UseAction.SPEAR; }
    @Override public int getMaxUseTime(ItemStack stack, LivingEntity user) { return 72000; }

    @Override public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (attacker.getWorld() instanceof ServerWorld world && attacker instanceof PlayerEntity player) {
            if (!DivineWeapons.allowed(player, WeaponKind.GEORGE_SPEAR)) return false;
            strike(world, target, attacker, 7f);
        }
        stack.damage(1, attacker, EquipmentSlot.MAINHAND);
        return true;
    }

    /** What any blow of the spear does beyond its damage: pins the mark, and runs a serpent through. */
    static void strike(ServerWorld world, LivingEntity target, LivingEntity attacker, float base) {
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 2, false, true));
        if (WeaponHooks.serpent(target)) {
            DivineWeapons.extra(target, attacker, base * 2f);
            Fx.pillar(world, target.getPos(), Fx.HOLY, 30);
            Fx.ring(world, target.getPos(), Fx.HOLY, 3f);
            world.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 1.2f, 1.6f);
        } else {
            Fx.sparks(world, target.getPos().add(0, target.getHeight() * 0.6, 0), Fx.HOLY, 0.5f);
        }
    }

    @Override public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (!DivineWeapons.allowed(player, WeaponKind.GEORGE_SPEAR)) return TypedActionResult.fail(stack);
        if (stack.getDamage() >= stack.getMaxDamage() - 1) return TypedActionResult.fail(stack);
        player.setCurrentHand(hand);
        return TypedActionResult.consume(stack);
    }

    @Override public void onStoppedUsing(ItemStack stack, World world, LivingEntity user, int remainingUseTicks) {
        if (!(user instanceof PlayerEntity player)) return;
        int held = getMaxUseTime(stack, user) - remainingUseTicks;
        if (held < 10) return;
        if (world instanceof ServerWorld server) {
            stack.damage(1, player, player.getActiveHand() == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
            ThrownSpearEntity spear = new ThrownSpearEntity(server, player, stack);
            spear.setVelocity(player, player.getPitch(), player.getYaw(), 0f, 2.6f, 0.5f);
            if (player.isCreative()) spear.pickupType = net.minecraft.entity.projectile.PersistentProjectileEntity.PickupPermission.CREATIVE_ONLY;
            server.spawnEntity(spear);
            server.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_TRIDENT_THROW, SoundCategory.PLAYERS, 1f, 1.1f);
            if (!player.isCreative()) player.getInventory().removeOne(stack);
        }
        player.getItemCooldownManager().set(this, 10);
    }
}
