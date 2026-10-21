package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.WitherSkeletonEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

/** The hook for the sacred bosses of the god (made in their own branch): {@code DivineBosses.spawn(id, world, pos, power)}
 * is called by reflection when that class is in the mod; until then a stand-in, the Black Schemamonk, answers. */
final class Bosses {
    /** The bosses of the wrath groups; the archangel of wrath comes only in the greatest fury. */
    static final List<String> IDS = List.of("divineblock:black_schemnik", "divineblock:bell_ringer", "divineblock:seraph", "divineblock:faceless_icon",
            "divineblock:mourner");
    static final String ARCHANGEL = "divineblock:wrath_archangel";

    private Bosses() { }

    static String summon(ServerWorld world, ServerPlayerEntity player, int power, int severity) {
        Vec3d look = player.getRotationVector().multiply(1, 0, 1);
        if (look.lengthSquared() < 1e-4) look = new Vec3d(0, 0, 1);
        look = look.normalize().multiply(9);
        BlockPos at = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, BlockPos.ofFloored(player.getX() + look.x, 0, player.getZ() + look.z));
        String id = severity >= 4 && world.random.nextBoolean() ? ARCHANGEL : IDS.get(world.random.nextInt(IDS.size()));
        Miracles.bolt(world, at, true);
        Miracles.sound(world, at, SoundEvents.BLOCK_BELL_RESONATE, 4f, 0.5f);
        world.spawnParticles(ParticleTypes.END_ROD, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 120, 0.6, 3, 0.6, 0.05);
        String sacred = sacred(id, world, at, power);
        return sacred != null ? sacred : standIn(world, player, at, power);
    }

    /** The real boss, when the bosses' class is in the mod; null otherwise or when it fails. */
    private static String sacred(String id, ServerWorld world, BlockPos at, int power) {
        try {
            Class<?> bosses = Class.forName("io.github.divineblock.game.DivineBosses");
            Method spawn = bosses.getMethod("spawn", String.class, ServerWorld.class, BlockPos.class, int.class);
            Object result = spawn.invoke(null, id, world, at, power);
            if (result == null || Boolean.FALSE.equals(result)) return null;
            return "священный босс: " + id.substring(id.indexOf(':') + 1);
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            DivineBlockMod.LOG.warn("Божественный блок: священный босс {} не пришёл, вместо него схимник", id, failure);
            return null;
        }
    }

    /** Until the sacred bosses exist: a black schemamonk, a wither skeleton in netherite with a great sword and a lot of life. */
    private static String standIn(ServerWorld world, ServerPlayerEntity player, BlockPos at, int power) {
        WitherSkeletonEntity monk = EntityType.WITHER_SKELETON.create(world);
        if (monk == null) return null;
        monk.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, world.random.nextFloat() * 360, 0);
        monk.setCustomName(Text.literal("Чёрный схимник").formatted(Formatting.DARK_RED));
        monk.setCustomNameVisible(true);
        monk.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.NETHERITE_SWORD));
        monk.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
        monk.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.NETHERITE_CHESTPLATE));
        for (EquipmentSlot slot : EquipmentSlot.values()) monk.setEquipmentDropChance(slot, 0f);
        var health = monk.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (health != null) health.setBaseValue(60 + 40 * power);
        monk.setHealth(monk.getMaxHealth());
        monk.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 20 * 600, 1));
        monk.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 20 * 600, 0));
        monk.setPersistent();
        monk.setTarget(player);
        world.spawnEntity(monk);
        return "священный босс ещё не пришёл в мир — вместо него Чёрный схимник";
    }
}
