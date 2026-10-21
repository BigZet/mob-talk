package io.github.divineblock.game;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/** The god's own drawn effects, shared by every act, punishment and boss: call one of these on the server thread and each
 * player within sight gets it drawn with the mod's particles (glowing sprites, feathers, confetti, ash, bolts, meteors).
 * The client scales them by its "Эффекты" quality and, when effects are off, falls back to plain vanilla particles or
 * nothing. Effects only: nothing here touches blocks or entities. */
public final class Fx {
    /** Kinds, as the client knows them. New kinds go at the end. */
    public static final int RING = 1, FEATHERS = 2, CONFETTI = 3, BOLT = 4, METEOR = 5, IMPACT = 6, SHAKE = 7, FLASH = 8, SPARKS = 9,
            PILLAR = 10, EMBERS = 11, SMOKE = 12;
    /** Colours the acts use, so a punishment and a boss look of one family. */
    public static final int GOLD = 0xFFFFD24A, HOLY = 0xFFFFF4D6, FIRE = 0xFFFF6A1A, BLOOD = 0xFFC8101A, SOUL = 0xFF4AD6FF,
            VIOLET = 0xFFB050FF, BLACK = 0xFF140608;
    private static final double SIGHT = 192;

    private Fx() { }

    /** A shining ring that runs across the ground from {@code at} out to {@code radius} blocks. */
    public static void ring(ServerWorld world, Vec3d at, int color, float radius) { send(world, RING, at, at, color, radius, 30); }
    /** Golden feathers drifting down around {@code at} for a few seconds; {@code amount} 1..3. */
    public static void feathers(ServerWorld world, Vec3d at, int amount) { send(world, FEATHERS, at, at, GOLD, amount, 0); }
    /** A burst of confetti over {@code at}; {@code amount} 1..3. */
    public static void confetti(ServerWorld world, Vec3d at, int amount) { send(world, CONFETTI, at, at, 0, amount, 0); }
    /** A branching bolt from the sky into {@code at}, with a flash; a vanilla lightning entity at the same spot is not drawn. */
    public static void bolt(ServerWorld world, Vec3d at, int color) { send(world, BOLT, at, at, color, 1, 12); }
    /** A burning stone from {@code from} (far off in the sky) to {@code to} over {@code ticks}, quickening as it comes
     * (0.55t + 0.45t² of the way), with a fire tail and smoke; the client draws the impact when it lands. */
    public static void meteor(ServerWorld world, Vec3d from, Vec3d to, int ticks, float size) { send(world, METEOR, from, to, FIRE, size, ticks); }
    /** A blast: flash, shock ring, embers and smoke; {@code size} about the explosion's power. */
    public static void impact(ServerWorld world, Vec3d at, int color, float size) { send(world, IMPACT, at, at, color, size, 0); }
    /** The camera shakes for players near {@code at}, {@code strength} 0..1 at the centre, fading over {@code ticks}. */
    public static void shake(ServerWorld world, Vec3d at, float strength, int ticks) { send(world, SHAKE, at, at, 0, strength, ticks); }
    /** The screen flashes in {@code color} for players near {@code at}. */
    public static void flash(ServerWorld world, Vec3d at, int color, float strength) { send(world, FLASH, at, at, color, strength, 8); }
    /** Glowing sparks thrown out from {@code at}; {@code amount} about a dozen per unit. */
    public static void sparks(ServerWorld world, Vec3d at, int color, float amount) { send(world, SPARKS, at, at, color, amount, 0); }
    /** A column of light from the sky down onto {@code at}, rings sliding down it, for {@code ticks}. */
    public static void pillar(ServerWorld world, Vec3d at, int color, int ticks) { send(world, PILLAR, at, at, color, 1, ticks); }
    /** Embers rising and drifting around {@code at} within {@code radius} for {@code ticks}. */
    public static void embers(ServerWorld world, Vec3d at, int color, float radius, int ticks) { send(world, EMBERS, at, at, color, radius, ticks); }
    /** Rolling smoke around {@code at} within {@code radius}. */
    public static void smoke(ServerWorld world, Vec3d at, int color, float radius) { send(world, SMOKE, at, at, color, radius, 0); }

    /** Sends any kind to every player in this world within sight of {@code at} or {@code to}. */
    public static void send(ServerWorld world, int kind, Vec3d at, Vec3d to, int color, float size, int ticks) {
        FxPayload payload = new FxPayload(kind, at.x, at.y, at.z, to.x, to.y, to.z, color, size, ticks);
        for (ServerPlayerEntity player : world.getPlayers())
            if (Math.min(player.squaredDistanceTo(at), player.squaredDistanceTo(to)) < SIGHT * SIGHT && ServerPlayNetworking.canSend(player, FxPayload.ID))
                ServerPlayNetworking.send(player, payload);
    }
}
