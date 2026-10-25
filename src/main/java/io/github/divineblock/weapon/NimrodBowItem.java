package io.github.divineblock.weapon;

import java.util.List;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** Лук Нимрода (Genesis 10:9, «сильный зверолов пред Господом»): draws in 0.7 s instead of a second, the arrow flies 30 %
 * faster and truer. Beasts take its arrows twofold, and a beast killed by it is pride before the god (both in
 * {@link WeaponHooks}, by the arrow's weapon stack). */
public final class NimrodBowItem extends BowItem {
    static final float DRAW_TICKS = 14f;

    public NimrodBowItem(Settings settings) { super(settings); }

    /** The pull, in 14 ticks rather than 20. */
    public static float pull(int useTicks) {
        float f = useTicks / DRAW_TICKS;
        f = (f * f + f * 2f) / 3f;
        return Math.min(1f, f);
    }

    @Override public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        if (!DivineWeapons.allowed(player, WeaponKind.NIMROD_BOW)) return TypedActionResult.fail(player.getStackInHand(hand));
        return super.use(world, player, hand);
    }

    @Override public void onStoppedUsing(ItemStack stack, World world, LivingEntity user, int remainingUseTicks) {
        if (!(user instanceof PlayerEntity player)) return;
        ItemStack projectile = player.getProjectileType(stack);
        if (projectile.isEmpty()) return;
        float pull = pull(getMaxUseTime(stack, user) - remainingUseTicks);
        if (pull < 0.1f) return;
        List<ItemStack> loaded = load(stack, projectile, player);
        if (world instanceof ServerWorld server && !loaded.isEmpty())
            shootAll(server, player, player.getActiveHand(), stack, loaded, pull * 3.9f, 0.4f, pull == 1f, null);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_ARROW_SHOOT, SoundCategory.PLAYERS, 1f,
                1f / (world.getRandom().nextFloat() * 0.4f + 1.2f) + pull * 0.5f);
        player.incrementStat(Stats.USED.getOrCreateStat(this));
    }
}
