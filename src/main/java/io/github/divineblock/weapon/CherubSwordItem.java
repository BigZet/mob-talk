package io.github.divineblock.weapon;

import io.github.divineblock.game.Fx;
import io.github.divineblock.game.Scheduler;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Пламенный меч Херувима (Genesis 3:24): a sword of fire that burns what it strikes; a right click sets «Обращающийся меч»
 * turning round the bearer for six seconds, a blade of flame on a circle of two and a half blocks that burns every hostile
 * it passes through. It cannot be mended on an anvil: when it burns out it goes back to the cherub in a fall of feathers
 * (the god's {@code repair} can still mend it). */
public final class CherubSwordItem extends SwordItem {
    static final int ORBIT_TICKS = 120, COOLDOWN = 600;
    static final double RADIUS = 2.5;
    /** Players with the turning sword about them now: for the renderer's hint and the test. */
    private static final Map<UUID, Integer> ORBITING = new HashMap<>();

    public CherubSwordItem(Settings settings) {
        super(WeaponMaterial.CHERUB, settings.attributeModifiers(SwordItem.createAttributeModifiers(WeaponMaterial.CHERUB, 1, -2.4f)));
    }

    @Override public boolean postHit(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        if (!attacker.getWorld().isClient && attacker instanceof PlayerEntity player && !DivineWeapons.enabled(WeaponKind.CHERUB_SWORD)) {
            DivineWeapons.allowed(player, WeaponKind.CHERUB_SWORD);
            return super.postHit(stack, target, attacker);
        }
        target.setOnFireFor(4);
        if (attacker.getWorld() instanceof ServerWorld world) {
            Fx.sparks(world, target.getPos().add(0, target.getHeight() * 0.6, 0), Fx.FIRE, 0.6f);
            if (stack.getDamage() >= stack.getMaxDamage() - 1 && attacker instanceof ServerPlayerEntity player) burntOut(player);
        }
        return super.postHit(stack, target, attacker);
    }

    @Override public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (!DivineWeapons.allowed(player, WeaponKind.CHERUB_SWORD)) return TypedActionResult.fail(stack);
        if (player.getItemCooldownManager().isCoolingDown(this)) return TypedActionResult.pass(stack);
        if (world instanceof ServerWorld server && player instanceof ServerPlayerEntity bearer) {
            turn(server, bearer);
            if (stack.getDamage() >= stack.getMaxDamage() - 10) burntOut(bearer);
            stack.damage(10, player, hand == Hand.MAIN_HAND ? net.minecraft.entity.EquipmentSlot.MAINHAND : net.minecraft.entity.EquipmentSlot.OFFHAND);
        }
        player.getItemCooldownManager().set(this, COOLDOWN);
        return TypedActionResult.success(stack, world.isClient);
    }

    /** «Обращающийся меч»: a blade of flame runs round the bearer; a hostile it passes through burns and takes 3 (once a second). */
    public static void turn(ServerWorld world, ServerPlayerEntity bearer) {
        Map<UUID, Integer> struck = new HashMap<>();
        ORBITING.put(bearer.getUuid(), ORBIT_TICKS);
        world.playSound(null, bearer.getX(), bearer.getY(), bearer.getZ(), SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 1.5f, 0.8f);
        Fx.ring(world, bearer.getPos(), Fx.FIRE, (float) RADIUS + 0.5f);
        bearer.sendMessage(Text.literal("Обращающийся меч").formatted(Formatting.GOLD), true);
        Scheduler.repeat(1, ORBIT_TICKS, step -> {
            if (!bearer.isAlive()) { ORBITING.remove(bearer.getUuid()); return; }
            ORBITING.put(bearer.getUuid(), ORBIT_TICKS - step);
            double angle = step * 0.35;
            Vec3d centre = bearer.getPos().add(0, 1.0, 0);
            Vec3d tip = centre.add(Math.cos(angle) * RADIUS, 0, Math.sin(angle) * RADIUS);
            for (int k = 1; k <= 3; k++) {
                Vec3d p = centre.add(Math.cos(angle) * RADIUS * k / 3, 0.2 * (k - 2), Math.sin(angle) * RADIUS * k / 3);
                world.spawnParticles(net.minecraft.particle.ParticleTypes.FLAME, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.01);
            }
            if (step % 4 == 0) Fx.sparks(world, tip, Fx.FIRE, 0.25f);
            if (step % 10 == 0) world.playSound(null, tip.x, tip.y, tip.z, SoundEvents.ENTITY_BLAZE_BURN, SoundCategory.PLAYERS, 0.6f, 1.5f);
            for (LivingEntity victim : world.getEntitiesByClass(LivingEntity.class, new Box(tip, tip).expand(1.2), v -> v != bearer && v.isAlive() && v instanceof HostileEntity)) {
                Integer last = struck.get(victim.getUuid());
                if (last != null && step - last < 20) continue;
                struck.put(victim.getUuid(), step);
                victim.setOnFireFor(3);
                DivineWeapons.extra(victim, bearer, 3f);
            }
            if (step == ORBIT_TICKS - 1) { ORBITING.remove(bearer.getUuid()); Fx.ring(world, bearer.getPos(), Fx.FIRE, (float) RADIUS); }
        });
    }

    /** Ticks left of the turning sword about {@code player}, 0 when none. */
    public static int orbiting(UUID player) { return ORBITING.getOrDefault(player, 0); }

    @Override public boolean canRepair(ItemStack stack, ItemStack ingredient) { return false; }

    /** The sword has burnt out: it goes back to its keeper (feathers, a bell), and the bearer is told. */
    static void burntOut(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        Fx.feathers(world, player.getPos(), 2);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 1.2f, 0.7f);
        player.sendMessage(Text.literal("Пламенный меч вернулся к Херувиму").formatted(Formatting.GOLD), false);
    }
}
