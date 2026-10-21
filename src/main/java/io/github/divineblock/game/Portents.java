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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

/** Portents while the god is still speaking, before the great punishments: each one hints at what is coming (the rain
 * before the flood, ash before Sodom, bells before the ban); and the god's own drawn effects ({@link Fx}) the moment such a
 * punishment falls. Effects only; the act itself falls on the last word. The client scales or drops the drawn effects by
 * its "Эффекты" and "Тряска камеры" settings. */
final class Portents {
    private Portents() { }

    static void before(ServerPlayerEntity player, BlockPos altar, Miracle miracle, double seconds) {
        ServerWorld world = player.getServerWorld();
        ParticleEffect sign;
        SoundEvent sound;
        int color;
        switch (miracle) {
            case FLOOD -> { sign = ParticleTypes.FALLING_WATER; sound = SoundEvents.WEATHER_RAIN_ABOVE; color = Fx.SOUL; if (world.getRegistryKey() == World.OVERWORLD) world.setWeather(0, 20 * 60, true, false); }
            case SODOM, METEOR, METEOR_SHOWER, GREAT_CRATER -> { sign = ParticleTypes.ASH; sound = SoundEvents.BLOCK_FIRE_AMBIENT; color = Fx.FIRE; }
            case PLAGUES -> { sign = new DustParticleEffect(new Vector3f(0.6f, 0.02f, 0.02f), 1.4f); sound = SoundEvents.ENTITY_FROG_AMBIENT; color = Fx.BLOOD; }
            case BLIGHT -> { sign = ParticleTypes.SPORE_BLOSSOM_AIR; sound = SoundEvents.BLOCK_SCULK_SPREAD; color = Fx.BLACK; }
            case CAIN -> { sign = new DustParticleEffect(new Vector3f(0.4f, 0f, 0f), 1f); sound = SoundEvents.ENTITY_WOLF_GROWL; color = Fx.BLOOD; }
            case BABEL, EARTHQUAKE, RIFTS -> { sign = ParticleTypes.WHITE_ASH; sound = SoundEvents.BLOCK_DEEPSLATE_BREAK; color = Fx.BLACK; }
            case JUDGEMENT -> { sign = ParticleTypes.END_ROD; sound = SoundEvents.BLOCK_BELL_RESONATE; color = Fx.HOLY; }
            case MEMORY_WIPE -> { sign = ParticleTypes.REVERSE_PORTAL; sound = SoundEvents.BLOCK_PORTAL_AMBIENT; color = Fx.VIOLET; }
            case ETERNAL_BAN -> { sign = ParticleTypes.SOUL; sound = SoundEvents.BLOCK_BELL_USE; color = Fx.SOUL; }
            case SACRED_BOSS -> { sign = ParticleTypes.ENCHANT; sound = SoundEvents.BLOCK_BELL_RESONATE; color = Fx.GOLD; }
            default -> { return; }
        }
        int steps = (int) Math.max(1, Math.round(seconds / 1.5));
        Fx.embers(world, player.getPos(), color, 6, (int) Math.round(seconds * 20));
        Scheduler.repeat(30, steps, i -> {
            if (player.isRemoved() || player.getServerWorld() != world) return;
            world.spawnParticles(sign, player.getX(), player.getY() + 6, player.getZ(), 40, 6, 3, 6, 0.01);
            if (i % 2 == 0) Miracles.sound(world, player.getBlockPos().up(8), sound, 1.2f, 0.5f + world.random.nextFloat() * 0.2f);
        });
    }

    /** The moment a great punishment falls (it already happened in the world): the drawn effects over it. */
    static void fall(ServerWorld world, ServerPlayerEntity player, BlockPos altar, Miracle miracle, int power) {
        Vec3d at = player.getPos();
        switch (miracle) {
            case EARTHQUAKE -> Fx.shake(world, at, 0.9f, 200);
            case SINKHOLE -> { Fx.shake(world, at, 0.7f, 60); Fx.smoke(world, at, Fx.BLACK, 4); }
            case RIFTS, CHUNK_VOID -> { Fx.shake(world, at, 1f, 120); Fx.smoke(world, at, Fx.BLACK, 8); }
            case GREAT_CRATER -> { Fx.flash(world, Vec3d.ofCenter(altar), Fx.FIRE, 1f); Fx.shake(world, at, 1f, 100); Fx.impact(world, Vec3d.ofCenter(altar), Fx.FIRE, 4f); }
            case BLIGHT -> Fx.smoke(world, at, Fx.BLACK, 6);
            case CAIN -> { Fx.ring(world, at, Fx.BLOOD, 10); Fx.sparks(world, at.add(0, 1.8, 0), Fx.BLOOD, 2); }
            case SACRED_BOSS -> { Fx.pillar(world, at, Fx.HOLY, 60); Fx.ring(world, at, Fx.GOLD, 12); }
            case PLAGUES -> { Fx.ring(world, at, Fx.BLOOD, 16); Fx.smoke(world, at, 0xFF3A0A0A, 5); }
            case SODOM -> { Fx.embers(world, at, Fx.FIRE, 24, 20 * 35); Fx.flash(world, at, Fx.FIRE, 0.6f); Fx.shake(world, at, 0.4f, 60); }
            case FLOOD -> Fx.ring(world, at, Fx.SOUL, 30);
            case BABEL -> { Fx.shake(world, at, 0.8f, 160); Fx.smoke(world, at, 0xFF8A8478, 10); }
            case JUDGEMENT -> { Fx.pillar(world, at, Fx.HOLY, 160); Fx.feathers(world, at.add(0, 2, 0), 2); }
            case MEMORY_WIPE -> { Fx.flash(world, at, Fx.VIOLET, 1f); Fx.ring(world, Vec3d.ofCenter(altar), Fx.VIOLET, 6); }
            case ETERNAL_BAN -> { Fx.pillar(world, at, Fx.SOUL, 120); Fx.flash(world, at, Fx.BLACK, 0.8f); Fx.shake(world, at, 0.5f, 120); }
            case HELL_EXILE, END_EXILE -> Fx.flash(world, at, miracle == Miracle.HELL_EXILE ? Fx.FIRE : Fx.VIOLET, 1f);
            case UNDEAD_HORDE, SKELETON_RIDERS -> Fx.ring(world, at, Fx.SOUL, 14);
            case WITHER, DRAGON -> Fx.shake(world, at, 0.6f, 60);
            default -> { }
        }
    }
}
