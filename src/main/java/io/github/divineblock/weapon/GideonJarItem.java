package io.github.divineblock.weapon;

import io.github.divineblock.game.Fx;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** Кувшин Гедеона (Judges 7:16–22): an earthen pitcher with a torch inside. Broken, it gives a blast of the horn, a flash
 * and a ring of light, and every hostile within twelve blocks is put to «Смятение» for ten seconds: they glow and turn on
 * one another ({@link WeaponHooks}). No harm of its own; one use; twenty seconds between pitchers. */
public final class GideonJarItem extends Item {
    static final double RADIUS = 12;
    static final int ROUT_TICKS = 200, COOLDOWN = 400;

    public GideonJarItem(Settings settings) { super(settings); }

    @Override public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (!DivineWeapons.allowed(player, WeaponKind.GIDEON_JAR)) return TypedActionResult.fail(stack);
        if (player.getItemCooldownManager().isCoolingDown(this)) return TypedActionResult.pass(stack);
        if (world instanceof ServerWorld server) {
            rout(server, player);
            if (!player.isCreative()) stack.decrement(1);
        }
        player.getItemCooldownManager().set(this, COOLDOWN);
        return TypedActionResult.success(stack, world.isClient);
    }

    /** The pitcher breaks: the blast, the light, and the rout of every hostile within reach. Returns how many were routed. */
    public static int rout(ServerWorld world, PlayerEntity player) {
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_DECORATED_POT_SHATTER, SoundCategory.PLAYERS, 1.2f, 0.8f);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.GOAT_HORN_SOUNDS.get(0), SoundCategory.PLAYERS, 2.5f, 0.9f);
        Fx.flash(world, player.getPos(), Fx.HOLY, 0.7f);
        Fx.ring(world, player.getPos(), Fx.GOLD, (float) RADIUS);
        Fx.sparks(world, player.getPos().add(0, 1.2, 0), Fx.GOLD, 25f); // three hundred, as Gideon's men
        Fx.smoke(world, player.getPos().add(0, 1, 0), 0xFF806040, 1.5f);
        int routed = 0;
        for (HostileEntity foe : world.getEntitiesByClass(HostileEntity.class, new Box(player.getBlockPos()).expand(RADIUS), m -> m.isAlive() && m.squaredDistanceTo(player) <= RADIUS * RADIUS)) {
            foe.addStatusEffect(new StatusEffectInstance(DivineWeapons.ROUT, ROUT_TICKS, 0, false, true));
            foe.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, ROUT_TICKS, 0, false, false));
            WeaponHooks.routTarget(foe);
            routed++;
        }
        return routed;
    }
}
