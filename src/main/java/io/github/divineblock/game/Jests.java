package io.github.divineblock.game;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;

/** The god's bodily jests: the world turned upside down, the days racing, sheep, fish, a pumpkin for a crown. Humiliating,
 * never lethal. */
final class Jests {

    private Jests() { }

    /** The mortal and every creature around him walk upside down for a while (seen by the client, see FlipPayload). */
    static String flip(ServerWorld world, ServerPlayerEntity player, int power) {
        List<Integer> ids = new ArrayList<>();
        ids.add(player.getId());
        for (LivingEntity creature : world.getEntitiesByClass(LivingEntity.class, new Box(player.getBlockPos()).expand(32), LivingEntity::isAlive))
            if (creature != player) ids.add(creature.getId());
        int ticks = 20 * (power >= 2 ? 60 : 30);
        if (ServerPlayNetworking.canSend(player, FlipPayload.ID)) ServerPlayNetworking.send(player, new FlipPayload(ids, ticks));
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, 2f, 0.7f);
        world.spawnParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1, player.getZ(), 80, 2, 1, 2, 0.1);
        return "мир перевернулся: " + ids.size() + " существ вверх ногами";
    }

    /** One day and night every three seconds: the sun and moon race across the sky, then the world stops where it began. */
    static String timeRush(ServerWorld world, ServerPlayerEntity player, int power) {
        // The Nether and the End have no sun to race: there the god picks another jest.
        if (world.getRegistryKey() != net.minecraft.world.World.OVERWORLD) return null;
        int cycles = power >= 2 ? 8 : 5, perTick = 24_000 / 60;
        boolean daylight = world.getGameRules().getBoolean(net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE);
        Scheduler.repeat(1, cycles * 60, i -> {
            world.setTimeOfDay(world.getTimeOfDay() + perTick);
            // The client is told every tick, or it would see the sky jump once a second.
            var packet = new net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket(world.getTime(), world.getTimeOfDay(), daylight);
            for (ServerPlayerEntity viewer : world.getPlayers()) viewer.networkHandler.sendPacket(packet);
        });
        Miracles.sound(world, player.getBlockPos(), SoundEvents.BLOCK_BEACON_POWER_SELECT, 2f, 0.5f);
        return "круговорот дней: " + cycles + " суток за " + cycles * 3 + " с";
    }

    /** Rainbow sheep (named jeb_, as the game paints them) dance round the mortal, then vanish in a puff. */
    static String sheepParty(ServerWorld world, ServerPlayerEntity player, int power) {
        int count = 6 * power;
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var sheep = net.minecraft.entity.EntityType.SHEEP.create(world);
            if (sheep == null) continue;
            double angle = Math.PI * 2 * i / count;
            sheep.refreshPositionAndAngles(player.getX() + Math.cos(angle) * 4, player.getY() + 0.5, player.getZ() + Math.sin(angle) * 4, (float) Math.toDegrees(angle), 0);
            sheep.setCustomName(net.minecraft.text.Text.literal("jeb_"));
            world.spawnEntity(sheep);
            ids.add(sheep.getId());
        }
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_SHEEP_AMBIENT, 2f, 1.4f);
        Scheduler.later(20 * 60, () -> ids.forEach(id -> {
            var sheep = world.getEntityById(id);
            if (sheep != null && sheep.isAlive()) { world.spawnParticles(ParticleTypes.POOF, sheep.getX(), sheep.getY() + 0.5, sheep.getZ(), 6, 0.3, 0.3, 0.3, 0.02); sheep.discard(); }
        }));
        return "хоровод из " + ids.size() + " радужных овец";
    }

    /** Raw cod and salmon slap down from the sky around the mortal. */
    static String fishRain(ServerWorld world, ServerPlayerEntity player, int power) {
        int count = 10 * power;
        Scheduler.repeat(2, count, i -> Miracles.drop(world, player.getPos().add(world.random.nextGaussian() * 3, 10 + world.random.nextInt(5), world.random.nextGaussian() * 3),
                new net.minecraft.item.ItemStack(i % 3 == 0 ? net.minecraft.item.Items.SALMON : i % 7 == 0 ? net.minecraft.item.Items.PUFFERFISH : net.minecraft.item.Items.COD)));
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_FISH_SWIM, 2f, 0.8f);
        return count + " рыбин с неба";
    }

    /** A creeper's hiss right behind him, a puff of smoke, and nothing more. */
    static String creeperPrank(ServerWorld world, ServerPlayerEntity player) {
        var behind = player.getPos().subtract(player.getRotationVector().multiply(1.5, 0, 1.5));
        world.playSound(null, behind.x, behind.y, behind.z, SoundEvents.ENTITY_CREEPER_PRIMED, net.minecraft.sound.SoundCategory.HOSTILE, 1.5f, 0.9f);
        Scheduler.later(30, () -> {
            world.spawnParticles(ParticleTypes.POOF, behind.x, behind.y + 1, behind.z, 40, 0.6, 0.6, 0.6, 0.05);
            world.playSound(null, behind.x, behind.y, behind.z, SoundEvents.ENTITY_WITCH_CELEBRATE, net.minecraft.sound.SoundCategory.AMBIENT, 1.5f, 1.2f);
        });
        return "шипение крипера за спиной";
    }

    /** A carved pumpkin for a crown: the helmet he wore goes into his bags (or at his feet). */
    static String pumpkinHead(ServerWorld world, ServerPlayerEntity player) {
        var head = player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD);
        if (!head.isEmpty()) Miracles.give(world, player, head.copy());
        player.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new net.minecraft.item.ItemStack(net.minecraft.item.Items.CARVED_PUMPKIN));
        Miracles.sound(world, player.getBlockPos(), SoundEvents.BLOCK_PUMPKIN_CARVE, 1.5f, 0.8f);
        world.spawnParticles(ParticleTypes.WITCH, player.getX(), player.getY() + 2, player.getZ(), 30, 0.3, 0.3, 0.3, 0.05);
        return "тыква на голове";
    }

    /** Lifted into the air for a few seconds, then let down softly. */
    static String levitate(ServerWorld world, ServerPlayerEntity player) {
        Miracles.effect(player, net.minecraft.entity.effect.StatusEffects.LEVITATION, 20 * 5, 1);
        Miracles.effect(player, net.minecraft.entity.effect.StatusEffects.SLOW_FALLING, 20 * 25, 0);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_SHULKER_SHOOT, 1.5f, 0.6f);
        return "вознесён на пять секунд";
    }

    /** The heavenly wine: the world sways and his legs run on their own. */
    static String dizzy(ServerWorld world, ServerPlayerEntity player) {
        Miracles.effect(player, net.minecraft.entity.effect.StatusEffects.NAUSEA, 20 * 25, 0);
        Miracles.effect(player, net.minecraft.entity.effect.StatusEffects.SPEED, 20 * 25, 2);
        Miracles.effect(player, net.minecraft.entity.effect.StatusEffects.JUMP_BOOST, 20 * 25, 1);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_GENERIC_DRINK, 1.5f, 0.7f);
        return "небесный хмель на 25 с";
    }
}
