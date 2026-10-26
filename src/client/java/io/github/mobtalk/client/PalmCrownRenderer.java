package io.github.mobtalk.client;

import io.github.mobtalk.game.CasinoBlocks;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

/** The fronds of a casino palm's crown ({@link CasinoBlocks.PalmCrown}): long leaves drawn as curved strips of the palm_leaf
 * texture, the midrib along the strip, the leaflets across it. Twelve long fronds arch out and down about three blocks and
 * six short ones rise in the middle, each a little turned, bent and sized its own way (from the block's position), so no two
 * palms look alike. The strips fold a little along the midrib like real fronds and are drawn from both sides. */
public final class PalmCrownRenderer implements BlockEntityRenderer<CasinoBlocks.PalmCrownEntity> {
    private static final Identifier LEAF = Identifier.of("mobtalk", "textures/block/casino/palm_leaf.png");
    private static final int SEGMENTS = 10;

    @Override public void render(CasinoBlocks.PalmCrownEntity crown, float tickDelta, MatrixStack matrices, VertexConsumerProvider buffers, int light, int overlay) {
        long seed = crown.getPos().asLong() * 0x9E3779B97F4A7C15L;
        VertexConsumer out = buffers.getBuffer(RenderLayer.getEntityCutoutNoCull(LEAF));
        Matrix4f m = matrices.peek().getPositionMatrix();
        MatrixStack.Entry entry = matrices.peek();
        float turn = rand(seed, 99) * 360f;
        for (int i = 0; i < 16; i++) {  // the long fronds
            float yaw = turn + i * 22.5f + (rand(seed, i) - 0.5f) * 10f;
            float length = 3.8f + rand(seed, i + 20) * 1.0f;
            float rise = 22f + rand(seed, i + 40) * 14f, fall = 115f + rand(seed, i + 60) * 30f;
            frond(out, m, entry, light, yaw, length, 0.78f, rise, fall, 0.82f + rand(seed, i + 80) * 0.18f);
        }
        for (int i = 0; i < 8; i++) {  // the young fronds rising in the middle
            float yaw = turn + 22.5f + i * 45f + (rand(seed, i + 100) - 0.5f) * 20f;
            frond(out, m, entry, light, yaw, 2.1f + rand(seed, i + 120) * 0.6f, 0.5f, 62f, 70f, 1.0f);
        }
    }

    /** One frond from the top of the knob: it leaves at rise degrees above level and bends down by fall degrees over its length;
     * width is its greatest width in blocks, shade darkens it (the lower, older fronds are darker). */
    private static void frond(VertexConsumer out, Matrix4f m, MatrixStack.Entry entry, int light, float yaw, float length, float width,
                              float rise, float fall, float shade) {
        float hx = MathHelper.cos(yaw * MathHelper.RADIANS_PER_DEGREE), hz = MathHelper.sin(yaw * MathHelper.RADIANS_PER_DEGREE);
        float sx = -hz, sz = hx;  // across the frond
        float x = 0.5f + hx * 0.12f, y = 0.42f, z = 0.5f + hz * 0.12f;
        float step = length / SEGMENTS;
        float[] px = new float[SEGMENTS + 1], py = new float[SEGMENTS + 1], pz = new float[SEGMENTS + 1];
        for (int k = 0; k <= SEGMENTS; k++) {
            px[k] = x; py[k] = y; pz[k] = z;
            float t = (k + 0.5f) / SEGMENTS;
            float pitch = (rise - fall * t * t) * MathHelper.RADIANS_PER_DEGREE;  // bends more and more towards the tip
            x += hx * MathHelper.cos(pitch) * step;
            y += MathHelper.sin(pitch) * step;
            z += hz * MathHelper.cos(pitch) * step;
        }
        int c = (int) (255 * shade);
        for (int k = 0; k < SEGMENTS; k++) {
            float t0 = (float) k / SEGMENTS, t1 = (float) (k + 1) / SEGMENTS;
            float w0 = halfWidth(t0) * width, w1 = halfWidth(t1) * width;
            // each half of the strip: from the midrib out to one edge, the edge a little lower (the frond folds along its rib)
            for (int side = -1; side <= 1; side += 2) {
                float v = side < 0 ? 0f : 1f;
                quad(out, m, entry, light, c,
                        px[k], py[k], pz[k], t0, 0.5f,
                        px[k + 1], py[k + 1], pz[k + 1], t1, 0.5f,
                        px[k + 1] + sx * w1 * side, py[k + 1] - w1 * 0.3f, pz[k + 1] + sz * w1 * side, t1, v,
                        px[k] + sx * w0 * side, py[k] - w0 * 0.3f, pz[k] + sz * w0 * side, t0, v);
            }
        }
    }

    /** The frond's half width along it, 0 at the base and the tip, widest a third of the way out. */
    private static float halfWidth(float t) {
        return t < 0.3f ? 0.25f + 0.75f * (t / 0.3f) : (float) Math.pow(1 - (t - 0.3f) / 0.7f, 0.8);
    }

    private static void quad(VertexConsumer out, Matrix4f m, MatrixStack.Entry entry, int light, int c,
                             float x0, float y0, float z0, float u0, float v0, float x1, float y1, float z1, float u1, float v1,
                             float x2, float y2, float z2, float u2, float v2, float x3, float y3, float z3, float u3, float v3) {
        vertex(out, m, entry, light, c, x0, y0, z0, u0, v0);
        vertex(out, m, entry, light, c, x1, y1, z1, u1, v1);
        vertex(out, m, entry, light, c, x2, y2, z2, u2, v2);
        vertex(out, m, entry, light, c, x3, y3, z3, u3, v3);
    }

    private static void vertex(VertexConsumer out, Matrix4f m, MatrixStack.Entry entry, int light, int c, float x, float y, float z, float u, float v) {
        out.vertex(m, x, y, z).color(c, c, c, 255).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(entry, 0, 1, 0);
    }

    private static float rand(long seed, int salt) {
        long h = seed ^ (salt * 0xBF58476D1CE4E5B9L);
        h ^= h >>> 31; h *= 0x94D049BB133111EBL; h ^= h >>> 29;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    @Override public boolean rendersOutsideBoundingBox(CasinoBlocks.PalmCrownEntity crown) { return true; }
    @Override public int getRenderDistance() { return 128; }
}
