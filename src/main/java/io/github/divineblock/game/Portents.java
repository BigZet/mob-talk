package io.github.divineblock.game;

import io.github.divineblock.core.Miracle;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.joml.Vector3f;

/** Portents while the god is still speaking, before the great punishments: each one hints at what is coming (the rain
 * before the flood, ash before Sodom, bells before the ban). Effects only; the act itself falls on the last word. */
final class Portents {
    private Portents() { }

    static void before(ServerPlayerEntity player, BlockPos altar, Miracle miracle, double seconds) {
        ServerWorld world = player.getServerWorld();
        ParticleEffect sign;
        SoundEvent sound;
        switch (miracle) {
            case FLOOD -> { sign = ParticleTypes.FALLING_WATER; sound = SoundEvents.WEATHER_RAIN_ABOVE; if (world.getRegistryKey() == World.OVERWORLD) world.setWeather(0, 20 * 60, true, false); }
            case SODOM, METEOR, METEOR_SHOWER, GREAT_CRATER -> { sign = ParticleTypes.ASH; sound = SoundEvents.BLOCK_FIRE_AMBIENT; }
            case PLAGUES -> { sign = new DustParticleEffect(new Vector3f(0.6f, 0.02f, 0.02f), 1.4f); sound = SoundEvents.ENTITY_FROG_AMBIENT; }
            case BLIGHT -> { sign = ParticleTypes.SPORE_BLOSSOM_AIR; sound = SoundEvents.BLOCK_SCULK_SPREAD; }
            case CAIN -> { sign = new DustParticleEffect(new Vector3f(0.4f, 0f, 0f), 1f); sound = SoundEvents.ENTITY_WOLF_GROWL; }
            case BABEL, EARTHQUAKE, RIFTS -> { sign = ParticleTypes.WHITE_ASH; sound = SoundEvents.BLOCK_DEEPSLATE_BREAK; }
            case JUDGEMENT -> { sign = ParticleTypes.END_ROD; sound = SoundEvents.BLOCK_BELL_RESONATE; }
            case MEMORY_WIPE -> { sign = ParticleTypes.REVERSE_PORTAL; sound = SoundEvents.BLOCK_PORTAL_AMBIENT; }
            case ETERNAL_BAN -> { sign = ParticleTypes.SOUL; sound = SoundEvents.BLOCK_BELL_USE; }
            case SACRED_BOSS -> { sign = ParticleTypes.ENCHANT; sound = SoundEvents.BLOCK_BELL_RESONATE; }
            default -> { return; }
        }
        int steps = (int) Math.max(1, Math.round(seconds / 1.5));
        Scheduler.repeat(30, steps, i -> {
            if (player.isRemoved() || player.getServerWorld() != world) return;
            world.spawnParticles(sign, player.getX(), player.getY() + 6, player.getZ(), 40, 6, 3, 6, 0.01);
            if (i % 2 == 0) Miracles.sound(world, player.getBlockPos().up(8), sound, 1.2f, 0.5f + world.random.nextFloat() * 0.2f);
        });
    }
}
