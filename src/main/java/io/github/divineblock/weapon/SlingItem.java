package io.github.divineblock.weapon;

import io.github.divineblock.game.Fx;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

/** Праща Давида (1 Samuel 17:40, 49): hold to wind it up (0.6 s to full speed), let go to throw a smooth stone from the
 * brook (a snowball or an egg will do: Goliath takes them in the forehead already). The stone is {@link SlingStoneEntity}: a
 * projectile, so Goliath's bare forehead opens to it. A righteous slinger's stone flies true and shines; others' strays by
 * a few degrees. */
public final class SlingItem extends Item {
    public static final int WIND_TICKS = 12;

    public SlingItem(Settings settings) { super(settings); }

    @Override public UseAction getUseAction(ItemStack stack) { return UseAction.BOW; }
    @Override public int getMaxUseTime(ItemStack stack, LivingEntity user) { return 72000; }

    /** The stone to throw: the sling's own stones first, then snowballs and eggs. */
    public static ItemStack ammo(PlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(DivineWeapons.SLING_STONE)) return stack;
        }
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(Items.SNOWBALL) || stack.isOf(Items.EGG)) return stack;
        }
        return ItemStack.EMPTY;
    }

    @Override public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (!DivineWeapons.allowed(player, WeaponKind.DAVID_SLING)) return TypedActionResult.fail(stack);
        if (ammo(player).isEmpty() && !player.isCreative()) {
            if (!world.isClient) player.sendMessage(net.minecraft.text.Text.literal("Нет гладких камней: булыжник на верстаке даёт четыре").formatted(net.minecraft.util.Formatting.GOLD), true);
            return TypedActionResult.fail(stack);
        }
        player.setCurrentHand(hand);
        return TypedActionResult.consume(stack);
    }

    @Override public void usageTick(World world, LivingEntity user, ItemStack stack, int remainingUseTicks) {
        int wound = getMaxUseTime(stack, user) - remainingUseTicks;
        if (!world.isClient && wound % 6 == 0 && wound > 0)
            world.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.ENTITY_FISHING_BOBBER_THROW, SoundCategory.PLAYERS, 0.25f, 0.6f + Math.min(1f, wound / (float) WIND_TICKS) * 0.8f);
    }

    @Override public void onStoppedUsing(ItemStack stack, World world, LivingEntity user, int remainingUseTicks) {
        if (!(user instanceof PlayerEntity player)) return;
        int wound = getMaxUseTime(stack, user) - remainingUseTicks;
        float charge = Math.min(1f, wound / (float) WIND_TICKS);
        if (charge < 0.25f) return;
        ItemStack ammo = ammo(player);
        boolean stone = ammo.isOf(DivineWeapons.SLING_STONE);
        if (ammo.isEmpty() && !player.isCreative()) return;
        if (world instanceof ServerWorld server) {
            boolean righteous = DivineWeapons.righteous(player);
            SlingStoneEntity shot = new SlingStoneEntity(server, player, stone || ammo.isEmpty() ? new ItemStack(DivineWeapons.SLING_STONE) : ammo.copyWithCount(1), charge, righteous);
            shot.setVelocity(player, player.getPitch(), player.getYaw(), 0f, 1.2f + 1.3f * charge, righteous ? 0f : 3f);
            server.spawnEntity(shot);
            server.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 1f, 0.5f + charge * 0.4f);
            if (righteous) Fx.sparks(server, player.getEyePos(), Fx.GOLD, 0.5f);
            stack.damage(1, player, player.getActiveHand() == Hand.MAIN_HAND ? net.minecraft.entity.EquipmentSlot.MAINHAND : net.minecraft.entity.EquipmentSlot.OFFHAND);
            if (!player.isCreative() && !ammo.isEmpty()) ammo.decrement(1);
        }
        player.getItemCooldownManager().set(this, 10);
    }
}
