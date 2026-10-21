package io.github.divineblock.client.fx;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;

/** One sprite of the god's effects. Plain fields, set by whoever spawns it; the engine ticks and draws it. Motions:
 * {@link #DRIFT} flies with gravity and drag, {@link #FLUTTER} sways down like a feather, {@link #TUMBLE} flips like
 * paper, {@link #ORBIT} circles a point. Fluttering and tumbling sprites settle flat on the ground and fade there. */
final class FxParticle {
    static final int DRIFT = 0, FLUTTER = 1, TUMBLE = 2, ORBIT = 3;

    FxSprite sprite = FxSprite.GLOW;
    boolean glow = true;
    int mode = DRIFT;
    double x, y, z, px, py, pz, vx, vy, vz;
    float size = 0.2f, endSize = -1, roll, rollSpeed, gravity, drag = 0.98f, alpha = 1f, flicker;
    /** 0xRRGGBB; {@code endColor} (when set) is blended in over the life. */
    int color = 0xFFFFFF, endColor = -1;
    int age, life = 20;
    /** Packed light for the see-through layer; -1 takes the world's light where the sprite is. */
    int light = -1;
    /** Fraction of the life spent fading in and out. */
    float fadeIn = 0.1f, fadeOut = 0.35f;
    // Orbit: the centre, radius and angular speed; fluttering and tumbling: phase of the sway or flip.
    double cx, cz;
    float radius, spin, phase, flip, flipSpeed;
    boolean landed;
    float flatYaw, tilt;

    void at(double x, double y, double z) { this.x = px = x; this.y = py = y; this.z = pz = z; }

    /** False when it is done. */
    boolean tick(ClientWorld world) {
        px = x; py = y; pz = z;
        if (++age >= life) return false;
        if (landed) return true;
        roll += rollSpeed;
        switch (mode) {
            case ORBIT -> {
                phase += spin;
                radius = Math.max(0.05f, radius + (float) vx);
                x = cx + MathHelper.cos(phase) * radius;
                z = cz + MathHelper.sin(phase) * radius;
                y += vy;
                return true;
            }
            case FLUTTER -> {
                // A feather rocks like a pendulum: it slides sideways, tips up at each end of the swing and drops fastest
                // through the middle, while slowly turning about itself.
                float swing = (age + phase * 10) * 0.16f;
                float side = MathHelper.cos(swing);
                vy = Math.max(vy - gravity, -0.035);
                x += vx + MathHelper.cos(spin) * side * 0.05; z += vz + MathHelper.sin(spin) * side * 0.05;
                y += vy * (0.4f + 0.9f * Math.abs(side)) + (1 - Math.abs(side)) * 0.006;
                tilt = MathHelper.sin(swing) * 0.75f;
                spin += rollSpeed;
            }
            case TUMBLE -> {
                vy = Math.max(vy - gravity, -0.11);
                vx *= drag; vz *= drag;
                if (vy < 0) { vx += MathHelper.sin(phase + age * 0.2f) * 0.004; vz += MathHelper.cos(phase + age * 0.17f) * 0.004; }
                x += vx; y += vy; z += vz;
                flip += flipSpeed;
            }
            default -> {
                vy -= gravity;
                vx *= drag; vy *= drag; vz *= drag;
                x += vx; y += vy; z += vz;
            }
        }
        if ((mode == FLUTTER || mode == TUMBLE) && vy < 0 && solidAt(world)) {
            landed = true;
            flatYaw = roll + flip + spin;
            // Lie a while, then fade: the rest of the life, at most five seconds.
            life = Math.min(life, age + 100);
        }
        return true;
    }

    private boolean solidAt(ClientWorld world) {
        BlockPos pos = BlockPos.ofFloored(x, y, z);
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return false;
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) return !state.getFluidState().isEmpty() && settleOn(pos.getY() + 0.9);
        return settleOn(pos.getY() + shape.getMax(net.minecraft.util.math.Direction.Axis.Y));
    }

    private boolean settleOn(double top) {
        if (y > top + 0.05) return false;
        y = py = top + 0.02;
        return true;
    }

    float alphaAt(float delta) {
        float t = (age + delta) / life;
        float a = alpha;
        if (t < fadeIn) a *= t / fadeIn;
        else if (t > 1 - fadeOut) a *= Math.max(0, (1 - t) / fadeOut);
        if (flicker > 0) a *= 1 - flicker * (0.5f + 0.5f * MathHelper.sin((age + delta) * 1.7f + phase * 5));
        return a;
    }

    float sizeAt(float delta) {
        if (endSize < 0) return size;
        return MathHelper.lerp((age + delta) / life, size, endSize);
    }

    int colorAt(float delta) {
        if (endColor < 0) return color;
        return FxDraw.mix(color, endColor, (age + delta) / life);
    }
}
