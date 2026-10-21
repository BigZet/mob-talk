package io.github.divineblock.client.boss;

import io.github.divineblock.boss.GodAvatarEntity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** The cumulus bank the god comes out of: some two hundred soft puffs facing the camera, drawn back to front with smooth
 * filtering (GodLayers), lit on top and storm-grey and violet underneath, golden light glowing inside the bank and behind him, and
 * shafts of light piercing down. The bank is laid out round the way he faces: a low, thin band before him hides where his
 * body ends without hiding him from the mortal below, while the flanks and the back pile up high and dark, rimmed with his
 * light. The puffs drift and roll slowly; they gather from afar, part as he comes down through them, and close over him
 * and scatter when he goes. Everything here is in blocks from his waist. */
final class GodClouds {
    private static final Identifier SKY = Identifier.of("divineblock", "textures/entity/boss/god_sky.png");
    private static final Identifier RAY = Identifier.of("divineblock", "textures/entity/boss/god_ray.png");
    private static final RenderLayer CLOUDS = GodLayers.clouds(SKY), GLOW = GodLayers.light(SKY), RAYS = GodLayers.light(RAY);
    private static final int BRIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;

    /** One puff: where it sits in the bank (angle from his facing, radius, height), how big, which sprite, how it drifts,
     * how lit, and whether it is part of the band before him (which parts most when he comes down). */
    private record Puff(float angle, float radius, float height, float size, int sprite, float phase, float light, boolean front) { }
    private record Shaft(float angle, float radius, float width, float length, float phase) { }

    private final Puff[] puffs;
    private final Shaft[] shafts;
    private final float[] distance;
    private final Integer[] order;

    GodClouds(long seed) {
        Random random = new Random(seed);
        List<Puff> list = new ArrayList<>();
        // The band before him: low and small, parted right under his chest and arms so it never hides them.
        for (int i = 0; i < 26; i++)
            list.add(puff(random, (random.nextBoolean() ? 1 : -1) * rnd(random, 0.4f, 1.3f), rnd(random, 8.5f, 14), rnd(random, -4.5f, -0.5f), rnd(random, 4, 7),
                    rnd(random, 0.8f, 1f), true));
        // Round the waist at the sides and behind.
        for (int i = 0; i < 30; i++)
            list.add(puff(random, rnd(random, 1.0f, 5.28f), rnd(random, 8, 15), rnd(random, -3, 3), rnd(random, 8, 12), rnd(random, 0.75f, 1f), false));
        // The flanks and the back, piling up.
        for (int i = 0; i < 56; i++)
            list.add(puff(random, rnd(random, 0.9f, 5.38f), rnd(random, 14, 36), rnd(random, -4, 7), rnd(random, 9, 17), rnd(random, 0.65f, 1f), false));
        // Towering cumulus behind him, rimmed with his light.
        for (int i = 0; i < 16; i++)
            list.add(puff(random, (float) Math.PI + rnd(random, -0.8f, 0.8f), rnd(random, 14, 28), rnd(random, 6, 17), rnd(random, 10, 16), 1f, false));
        // A wide low skirt far out at the sides and behind (never between him and the mortal before him).
        for (int i = 0; i < 40; i++)
            list.add(puff(random, rnd(random, 1.1f, 5.18f), rnd(random, 24, 44), rnd(random, -6, -1), rnd(random, 10, 16), rnd(random, 0.55f, 0.85f), false));
        // The heavy underside, only at the sides and behind (the mortal below must still see him).
        for (int i = 0; i < 30; i++)
            list.add(puff(random, rnd(random, 1.1f, 5.18f), rnd(random, 6, 32), rnd(random, -10, -6), rnd(random, 9, 13), rnd(random, 0.35f, 0.55f), false));
        puffs = list.toArray(Puff[]::new);
        shafts = new Shaft[9];
        for (int i = 0; i < shafts.length; i++)
            shafts[i] = new Shaft(rnd(random, 0, 6.283f), rnd(random, 6, 26), rnd(random, 2.5f, 6), rnd(random, 30, 46), rnd(random, 0, 6.283f));
        distance = new float[puffs.length];
        order = new Integer[puffs.length];
    }

    private static Puff puff(Random random, float angle, float radius, float height, float size, float light, boolean front) {
        return new Puff(angle, radius, height, size, random.nextInt(3), rnd(random, 0, 6.283f), light, front);
    }
    private static float rnd(Random random, float low, float high) { return low + random.nextFloat() * (high - low); }

    /** {@code facing} is the god's yaw in degrees; {@code emerge} 0..1 how far he has come out; {@code gather} 0..1 how
     * gathered the bank is. Draws in the entity's matrix space (blocks from his waist, unrotated). */
    void render(GodAvatarEntity god, float tickDelta, float facing, float emerge, float gather, MatrixStack matrices, VertexConsumerProvider consumers,
                Quaternionf camera, Vec3d cameraFromWaist, Vec3d head) {
        if (gather <= 0.01f) return;
        float age = god.age + tickDelta, p = god.stageProgress(tickDelta);
        int stage = god.stage();
        MinecraftClient client = MinecraftClient.getInstance();
        float day = client.world == null ? 1f : MathHelper.clamp((client.world.getSkyBrightness(tickDelta) - 0.2f) / 0.8f, 0f, 1f);
        float lit = 0.35f + 0.65f * day;
        // Gathering from afar, parting as he comes down, closing over him as he goes and then scattering.
        float spread = 1f + (1f - gather) * 1.3f;
        float part = stage == GodAvatarEntity.ARRIVE ? MathHelper.sin(MathHelper.clamp((p - 0.25f) / 0.75f, 0f, 1f) * MathHelper.PI) : 0f;
        float close = stage == GodAvatarEntity.LEAVE ? MathHelper.clamp(1f - emerge, 0f, 1f) * (1f - MathHelper.clamp((p - 0.6f) / 0.4f, 0f, 1f)) : 0f;
        float fade = MathHelper.clamp(gather, 0f, 1f);
        // The way he faces, as an angle on the ground plane (0 along +X, as cos/sin below).
        float front = (float) Math.toRadians(facing + 90f);

        Vector3f right = camera.transform(new Vector3f(1, 0, 0)), up = camera.transform(new Vector3f(0, 1, 0));
        Vec3d[] at = new Vec3d[puffs.length];
        for (int i = 0; i < puffs.length; i++) {
            Puff puff = puffs[i];
            // Slow churning: each puff sways round the bank and rolls up and down on its own beat.
            float sway = MathHelper.sin(age * 0.006f + puff.phase) * 0.12f;
            float radius = puff.radius * spread + (puff.front ? part * 6f - close * 4f : part * 2f) + MathHelper.sin(age * 0.01f + puff.phase * 2) * 0.8f;
            float angle = front + puff.angle + sway;
            float y = puff.height + MathHelper.sin(age * 0.02f + puff.phase) * 0.7f - (puff.front ? part * 2f : 0f);
            at[i] = new Vec3d(MathHelper.cos(angle) * radius, y, MathHelper.sin(angle) * radius);
            distance[i] = (float) at[i].squaredDistanceTo(cameraFromWaist);
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Float.compare(distance[b], distance[a]));
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        // The golden light: behind him and inside the bank (added, never darkening).
        VertexConsumer glow = consumers.getBuffer(GLOW);
        float behindX = -MathHelper.cos(front) * 7, behindZ = -MathHelper.sin(front) * 7;
        float pulse = (0.85f + 0.15f * MathHelper.sin(age * 0.05f)) * fade;
        quad(glow, matrix, right, up, behindX, 16, behindZ, 36 * gather, 3, 0xFFD27A, 0.75f * pulse, 0xFFD27A, 0.75f * pulse);
        // A golden rim round his head: a glow just behind it, as the mortal sees it, framing the face from below.
        Vec3d away = head.subtract(cameraFromWaist).normalize().multiply(4);
        quad(glow, matrix, right, up, (float) (head.x + away.x), (float) (head.y + away.y), (float) (head.z + away.z), 17 * emerge, 3,
                0xFFE6A0, 0.9f * pulse, 0xFFE6A0, 0.9f * pulse);
        quad(glow, matrix, right, up, behindX * 0.6f, 3, behindZ * 0.6f, 24 * gather, 3, 0xFFC860, 0.6f * pulse, 0xFFC860, 0.6f * pulse);
        for (int k = 0; k < 7; k++) {
            float a = front + 1.0f + k * 0.62f + MathHelper.sin(age * 0.01f + k) * 0.1f;
            quad(glow, matrix, right, up, MathHelper.cos(a) * 18, -1 + (k % 3) * 2, MathHelper.sin(a) * 18, 15, 3, 0xFFB850, 0.45f * pulse, 0xFFB850, 0.45f * pulse);
        }
        // God rays: soft shafts hanging under the bank, turned to the camera about the vertical.
        VertexConsumer rays = consumers.getBuffer(RAYS);
        for (Shaft shaft : shafts) {
            float a = shaft.angle + age * 0.002f;
            float flicker = 0.55f + 0.45f * MathHelper.sin(age * 0.03f + shaft.phase);
            shaft(rays, matrix, cameraFromWaist, MathHelper.cos(a) * shaft.radius, -6.5f, MathHelper.sin(a) * shaft.radius, shaft.width, shaft.length,
                    0xFFDD8A, 0.5f * fade * flicker * emerge * (0.4f + 0.6f * day));
        }
        // The puffs, back to front.
        VertexConsumer soft = consumers.getBuffer(CLOUDS);
        int top = shade(0xF4F1EE, lit), under = shade(0x4E4864, lit), rim = 0xFFE2A8;
        for (int index : order) {
            Puff puff = puffs[index];
            Vec3d pos = at[index];
            float size = puff.size * (0.92f + 0.08f * MathHelper.sin(age * 0.015f + puff.phase)) * (0.7f + 0.3f * gather);
            // Near him the tops catch his golden light; the bottoms are storm-dark, darker the lower they hang.
            float warm = MathHelper.clamp(1f - (float) pos.horizontalLength() / 28f, 0f, 1f) * 0.55f + (pos.y > 6 ? 0.3f : 0f);
            int topColour = mix(shade(top, puff.light), rim, Math.min(0.8f, warm));
            int bottomColour = shade(under, puff.light * (pos.y < -5 ? 0.75f : 1f));
            float alpha = fade * 0.92f;
            quad(soft, matrix, right, up, (float) pos.x, (float) pos.y, (float) pos.z, size, puff.sprite, topColour, alpha, bottomColour, alpha);
        }
    }

    // ---- Quads. ----

    private static void quad(VertexConsumer out, Matrix4f matrix, Vector3f right, Vector3f up, float x, float y, float z, float size, int sprite,
                             int top, float topAlpha, int bottom, float bottomAlpha) {
        if (Math.max(topAlpha, bottomAlpha) <= 0.003f || size <= 0) return;
        float u0 = (sprite % 2) * 0.5f + 0.002f, v0 = (sprite / 2) * 0.5f + 0.002f, u1 = u0 + 0.496f, v1 = v0 + 0.496f;
        float rx = right.x * size, ry = right.y * size, rz = right.z * size, ux = up.x * size, uy = up.y * size, uz = up.z * size;
        vertex(out, matrix, x - rx - ux, y - ry - uy, z - rz - uz, bottom, bottomAlpha, u0, v1);
        vertex(out, matrix, x + rx - ux, y + ry - uy, z + rz - uz, bottom, bottomAlpha, u1, v1);
        vertex(out, matrix, x + rx + ux, y + ry + uy, z + rz + uz, top, topAlpha, u1, v0);
        vertex(out, matrix, x - rx + ux, y - ry + uy, z - rz + uz, top, topAlpha, u0, v0);
    }

    /** A vertical shaft turned to face the camera about the world's vertical, hanging down from (x, y, z). */
    private static void shaft(VertexConsumer out, Matrix4f matrix, Vec3d camera, float x, float y, float z, float width, float length, int rgb, float alpha) {
        if (alpha <= 0.003f) return;
        double dx = camera.x - x, dz = camera.z - z, len = Math.max(1e-4, Math.hypot(dx, dz));
        float sx = (float) (-dz / len) * width, sz = (float) (dx / len) * width;
        vertex(out, matrix, x - sx, y - length, z - sz, rgb, alpha, 0, 1);
        vertex(out, matrix, x + sx, y - length, z + sz, rgb, alpha, 1, 1);
        vertex(out, matrix, x + sx, y, z + sz, rgb, alpha, 1, 0);
        vertex(out, matrix, x - sx, y, z - sz, rgb, alpha, 0, 0);
    }

    private static void vertex(VertexConsumer out, Matrix4f matrix, float x, float y, float z, int rgb, float alpha, float u, float v) {
        out.vertex(matrix, x, y, z).color(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, (int) (255 * MathHelper.clamp(alpha, 0f, 1f)))
                .texture(u, v).light(BRIGHT);
    }

    private static int shade(int rgb, float k) {
        return clamp((rgb >> 16 & 0xFF) * k) << 16 | clamp((rgb >> 8 & 0xFF) * k) << 8 | clamp((rgb & 0xFF) * k);
    }
    private static int mix(int a, int b, float t) {
        return clamp((a >> 16 & 0xFF) * (1 - t) + (b >> 16 & 0xFF) * t) << 16 | clamp((a >> 8 & 0xFF) * (1 - t) + (b >> 8 & 0xFF) * t) << 8
                | clamp((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
    }
    private static int clamp(float v) { return Math.max(0, Math.min(255, (int) v)); }
}
