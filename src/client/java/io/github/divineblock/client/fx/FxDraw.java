package io.github.divineblock.client.fx;

import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Writes the effects' quads, relative to the camera: sprites that face it, flat sprites, camera-facing ribbons along a path
 * (beams, bolts, trails) and flat rings on the ground. One instance per frame and layer. */
final class FxDraw {
    static final int FULL_BRIGHT = LightmapTextureManager.MAX_LIGHT_COORDINATE;
    final VertexConsumer out;
    final double camX, camY, camZ;
    private final Vector3f right = new Vector3f(), up = new Vector3f(), scratch = new Vector3f();

    FxDraw(VertexConsumer out, Vec3d camera, Quaternionf rotation) {
        this.out = out;
        camX = camera.x; camY = camera.y; camZ = camera.z;
        rotation.transform(right.set(1, 0, 0));
        rotation.transform(up.set(0, 1, 0));
    }

    /** A sprite facing the camera, turned by {@code roll}, its width squeezed by {@code squeeze} (1 = square; tumbling paper). */
    void sprite(FxSprite sprite, double x, double y, double z, float size, float roll, float squeeze, int rgb, float alpha, int light) {
        if (alpha <= 0.003f || size <= 0) return;
        float cos = MathHelper.cos(roll), sin = MathHelper.sin(roll), w = size * squeeze;
        float rx = (right.x * cos + up.x * sin) * w, ry = (right.y * cos + up.y * sin) * w, rz = (right.z * cos + up.z * sin) * w;
        float ux = (up.x * cos - right.x * sin) * size, uy = (up.y * cos - right.y * sin) * size, uz = (up.z * cos - right.z * sin) * size;
        float cx = (float) (x - camX), cy = (float) (y - camY), cz = (float) (z - camZ);
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF, a = alpha(alpha);
        out.vertex(cx - rx - ux, cy - ry - uy, cz - rz - uz).color(r, g, b, a).texture(sprite.u0, sprite.v1).light(light);
        out.vertex(cx - rx + ux, cy - ry + uy, cz - rz + uz).color(r, g, b, a).texture(sprite.u0, sprite.v0).light(light);
        out.vertex(cx + rx + ux, cy + ry + uy, cz + rz + uz).color(r, g, b, a).texture(sprite.u1, sprite.v0).light(light);
        out.vertex(cx + rx - ux, cy + ry - uy, cz + rz - uz).color(r, g, b, a).texture(sprite.u1, sprite.v1).light(light);
    }

    /** A sprite standing in the world (not turned to the camera): turned by {@code yaw} about the vertical, tipped by
     * {@code tilt} and its own {@code roll}; a feather in the air. */
    void oriented(FxSprite sprite, double x, double y, double z, float size, float yaw, float tilt, float roll, int rgb, float alpha, int light) {
        if (alpha <= 0.003f) return;
        Quaternionf q = new Quaternionf().rotationY(yaw).rotateZ(tilt).rotateX(roll);
        Vector3f r = q.transform(new Vector3f(size, 0, 0)), u = q.transform(new Vector3f(0, size, 0));
        float cx = (float) (x - camX), cy = (float) (y - camY), cz = (float) (z - camZ);
        int cr = rgb >> 16 & 0xFF, cg = rgb >> 8 & 0xFF, cb = rgb & 0xFF, a = alpha(alpha);
        out.vertex(cx - r.x - u.x, cy - r.y - u.y, cz - r.z - u.z).color(cr, cg, cb, a).texture(sprite.u0, sprite.v1).light(light);
        out.vertex(cx - r.x + u.x, cy - r.y + u.y, cz - r.z + u.z).color(cr, cg, cb, a).texture(sprite.u0, sprite.v0).light(light);
        out.vertex(cx + r.x + u.x, cy + r.y + u.y, cz + r.z + u.z).color(cr, cg, cb, a).texture(sprite.u1, sprite.v0).light(light);
        out.vertex(cx + r.x - u.x, cy + r.y - u.y, cz + r.z - u.z).color(cr, cg, cb, a).texture(sprite.u1, sprite.v1).light(light);
    }

    /** A sprite lying flat (on the ground), turned by {@code yaw}. */
    void flat(FxSprite sprite, double x, double y, double z, float size, float yaw, int rgb, float alpha, int light) {
        if (alpha <= 0.003f) return;
        float cos = MathHelper.cos(yaw) * size, sin = MathHelper.sin(yaw) * size;
        float cx = (float) (x - camX), cy = (float) (y - camY), cz = (float) (z - camZ);
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF, a = alpha(alpha);
        out.vertex(cx - cos + sin, cy, cz - sin - cos).color(r, g, b, a).texture(sprite.u0, sprite.v0).light(light);
        out.vertex(cx - cos - sin, cy, cz - sin + cos).color(r, g, b, a).texture(sprite.u0, sprite.v1).light(light);
        out.vertex(cx + cos - sin, cy, cz + sin + cos).color(r, g, b, a).texture(sprite.u1, sprite.v1).light(light);
        out.vertex(cx + cos + sin, cy, cz + sin - cos).color(r, g, b, a).texture(sprite.u1, sprite.v0).light(light);
    }

    /** A strip along the points, turned to face the camera, with the sprite's profile across it (use {@link FxSprite#BEAM}).
     * Width, colour and alpha per point; {@code count} points of the arrays are used. */
    void ribbon(FxSprite sprite, double[] xs, double[] ys, double[] zs, float[] widths, int[] colors, float[] alphas, int count, int light) {
        if (count < 2) return;
        float lastAx = 0, lastAy = 0, lastAz = 0, lastBx = 0, lastBy = 0, lastBz = 0;
        for (int i = 0; i < count; i++) {
            int prev = Math.max(0, i - 1), next = Math.min(count - 1, i + 1);
            float dx = (float) (xs[next] - xs[prev]), dy = (float) (ys[next] - ys[prev]), dz = (float) (zs[next] - zs[prev]);
            float px = (float) (xs[i] - camX), py = (float) (ys[i] - camY), pz = (float) (zs[i] - camZ);
            // Side = direction × (point − camera): across the path, square to the view.
            scratch.set(dy * pz - dz * py, dz * px - dx * pz, dx * py - dy * px);
            float length = scratch.length();
            if (length < 1e-5f) scratch.set(right); else scratch.div(length);
            float half = widths[i] / 2;
            float ax = px - scratch.x * half, ay = py - scratch.y * half, az = pz - scratch.z * half;
            float bx = px + scratch.x * half, by = py + scratch.y * half, bz = pz + scratch.z * half;
            if (i > 0) {
                int c0 = colors[i - 1], c1 = colors[i];
                int a0 = alpha(alphas[i - 1]), a1 = alpha(alphas[i]);
                float v0 = sprite.v0 + (sprite.v1 - sprite.v0) * (i - 1) / (count - 1), v1 = sprite.v0 + (sprite.v1 - sprite.v0) * i / (count - 1);
                if (a0 > 0 || a1 > 0) {
                    out.vertex(lastAx, lastAy, lastAz).color(c0 >> 16 & 0xFF, c0 >> 8 & 0xFF, c0 & 0xFF, a0).texture(sprite.u0, v0).light(light);
                    out.vertex(ax, ay, az).color(c1 >> 16 & 0xFF, c1 >> 8 & 0xFF, c1 & 0xFF, a1).texture(sprite.u0, v1).light(light);
                    out.vertex(bx, by, bz).color(c1 >> 16 & 0xFF, c1 >> 8 & 0xFF, c1 & 0xFF, a1).texture(sprite.u1, v1).light(light);
                    out.vertex(lastBx, lastBy, lastBz).color(c0 >> 16 & 0xFF, c0 >> 8 & 0xFF, c0 & 0xFF, a0).texture(sprite.u1, v0).light(light);
                }
            }
            lastAx = ax; lastAy = ay; lastAz = az; lastBx = bx; lastBy = by; lastBz = bz;
        }
    }

    /** A flat ring of the given radius and width at height {@code y}, with the beam's profile across its width. */
    void ring(double x, double y, double z, float radius, float width, int rgb, float alpha, int segments) {
        if (alpha <= 0.003f || radius <= 0) return;
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF, a = alpha(alpha);
        float cx = (float) (x - camX), cy = (float) (y - camY), cz = (float) (z - camZ);
        float inner = Math.max(0, radius - width / 2), outer = radius + width / 2;
        FxSprite sprite = FxSprite.BEAM;
        for (int i = 0; i < segments; i++) {
            float a0 = MathHelper.TAU * i / segments, a1 = MathHelper.TAU * (i + 1) / segments;
            float c0 = MathHelper.cos(a0), s0 = MathHelper.sin(a0), c1 = MathHelper.cos(a1), s1 = MathHelper.sin(a1);
            float v0 = sprite.v0 + (sprite.v1 - sprite.v0) * i / segments, v1 = sprite.v0 + (sprite.v1 - sprite.v0) * (i + 1) / segments;
            out.vertex(cx + c0 * inner, cy, cz + s0 * inner).color(r, g, b, a).texture(sprite.u0, v0).light(FULL_BRIGHT);
            out.vertex(cx + c1 * inner, cy, cz + s1 * inner).color(r, g, b, a).texture(sprite.u0, v1).light(FULL_BRIGHT);
            out.vertex(cx + c1 * outer, cy, cz + s1 * outer).color(r, g, b, a).texture(sprite.u1, v1).light(FULL_BRIGHT);
            out.vertex(cx + c0 * outer, cy, cz + s0 * outer).color(r, g, b, a).texture(sprite.u1, v0).light(FULL_BRIGHT);
        }
    }

    private static int alpha(float alpha) { return MathHelper.clamp((int) (alpha * 255), 0, 255); }

    /** Blends two 0xRRGGBB colours, {@code t} 0..1. */
    static int mix(int from, int to, float t) {
        t = MathHelper.clamp(t, 0, 1);
        int r = (int) MathHelper.lerp(t, from >> 16 & 0xFF, to >> 16 & 0xFF), g = (int) MathHelper.lerp(t, from >> 8 & 0xFF, to >> 8 & 0xFF),
                b = (int) MathHelper.lerp(t, from & 0xFF, to & 0xFF);
        return r << 16 | g << 8 | b;
    }
}
