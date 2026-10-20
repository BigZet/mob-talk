package io.github.divineblock.client;

import io.github.divineblock.core.TornadoFunnel;
import io.github.divineblock.game.TornadoPayload;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Draws the god's tornadoes: a funnel of translucent grey cloud puffs on 150 bending, spinning rings, flaring from a thin
 * root to a wide mouth; a dark wall cloud orbiting the mouth; twigs climbing the funnel. Clean-room work from a behavioural
 * description, with this mod's own textures. The client runs the same {@link TornadoFunnel} chain as the server, so the
 * funnel leans and snakes smoothly between packets. */
final class TornadoRenderer {
    private static final Identifier CLOUD = Identifier.of("divineblock", "textures/effect/cloud.png");
    private static final Identifier[] TWIGS = { Identifier.of("divineblock", "textures/effect/twig_0.png"),
            Identifier.of("divineblock", "textures/effect/twig_1.png"), Identifier.of("divineblock", "textures/effect/twig_2.png") };
    private static final int FADE_IN = 80, DISSOLVE = 100, FIRST_LAYER = 6;
    private static final Map<Integer, Funnel> FUNNELS = new HashMap<>();

    private static final class Funnel {
        final TornadoFunnel shape;
        final double[] previousRotation = new double[TornadoFunnel.LAYERS];
        double x, y, z;
        int age, dying = -1, silent;
        Funnel(int stage) { shape = new TornadoFunnel(stage); }
    }

    private TornadoRenderer() { }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(TornadoPayload.ID, (payload, context) -> {
            Funnel funnel = FUNNELS.computeIfAbsent(payload.id(), id -> new Funnel(payload.stage()));
            funnel.x = payload.x(); funnel.y = payload.y(); funnel.z = payload.z(); funnel.silent = 0;
            if (!payload.alive() && funnel.dying < 0) funnel.dying = 0;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.isPaused()) { if (client.world == null) FUNNELS.clear(); return; }
            for (Iterator<Funnel> it = FUNNELS.values().iterator(); it.hasNext(); ) {
                Funnel funnel = it.next();
                System.arraycopy(funnel.shape.rotation, 0, funnel.previousRotation, 0, TornadoFunnel.LAYERS);
                funnel.age++;
                if (funnel.dying >= 0) funnel.dying++;
                else funnel.shape.tick(funnel.x, funnel.y, funnel.z);
                // Lost contact (left the area, the world closed): dissolve as if released.
                if (++funnel.silent > 40 && funnel.dying < 0) funnel.dying = 0;
                if (funnel.dying > DISSOLVE) it.remove();
            }
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(TornadoRenderer::render);
    }

    private static void render(WorldRenderContext context) {
        if (FUNNELS.isEmpty() || context.consumers() == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        float delta = context.tickCounter().getTickDelta(false);
        Vec3d camera = context.camera().getPos();
        Quaternionf facing = context.camera().getRotation();
        Vector3f right = new Vector3f(1, 0, 0).rotate(facing), up = new Vector3f(0, 1, 0).rotate(facing);
        VertexConsumerProvider consumers = context.consumers();
        for (Map.Entry<Integer, Funnel> entry : FUNNELS.entrySet()) {
            Funnel funnel = entry.getValue();
            float fade = Math.min(1f, (funnel.age + delta) / FADE_IN);
            if (funnel.dying >= 0) fade *= Math.max(0f, 1 - (funnel.dying + delta) / DISSOLVE);
            if (fade <= 0.01f) continue;
            drawFunnel(consumers.getBuffer(RenderLayer.getEntityTranslucent(CLOUD)), funnel, entry.getKey(), camera, right, up, delta, fade);
            if (funnel.shape.stage() >= 1) for (int t = 0; t < TWIGS.length; t++)
                drawTwigs(consumers.getBuffer(RenderLayer.getEntityTranslucent(TWIGS[t])), funnel, entry.getKey(), t, camera, right, up, delta, fade);
        }
        if (client.world == null) FUNNELS.clear();
    }

    private static void drawFunnel(VertexConsumer buffer, Funnel funnel, int seed, Vec3d camera, Vector3f right, Vector3f up, float delta, float fade) {
        TornadoFunnel shape = funnel.shape;
        int count = shape.ringCount(1.0);
        double top = shape.topRadius();
        double spread = funnel.dying >= 0 ? (funnel.dying + delta) * 0.4 : 0; // Puffs drift apart as the funnel dies.
        for (int i = FIRST_LAYER; i < TornadoFunnel.LAYERS; i++) {
            double rotation = lerp(funnel.previousRotation[i], shape.rotation[i], delta);
            double ring = shape.ringRadius(i) + spread;
            float half = (float) (10 * shape.layerRadius(i) / top);
            for (int j = 0; j < count; j++) {
                double angle = Math.toRadians(360.0 / count * j + rotation);
                int hash = mix(seed, i, j);
                float grey = 0.1f + (hash & 0xFF) / 255f * 0.6f; // Dark, mottled grey.
                double px = shape.x[i] + Math.cos(angle) * ring - camera.x, py = shape.y[i] - camera.y, pz = shape.z[i] + Math.sin(angle) * ring - camera.z;
                quad(buffer, px, py, pz, right, up, half, grey, grey, grey, fade * 0.9f);
            }
        }
        // The wall cloud: the storm's deck dragged down to the mouth, orbiting it.
        int last = TornadoFunnel.LAYERS - 1;
        double rotation = lerp(funnel.previousRotation[last], shape.rotation[last], delta) * 0.6;
        for (int j = 0; j < 36; j++) {
            int hash = mix(seed, 999, j);
            double angle = Math.toRadians(10.0 * j + rotation + (hash & 31));
            double distance = top * (0.4 + ((hash >> 8) & 0xFF) / 255.0 * 1.4);
            double px = shape.x[last] + Math.cos(angle) * distance - camera.x, py = shape.y[last] + 8 + ((hash >> 16) & 15) - camera.y,
                    pz = shape.z[last] + Math.sin(angle) * distance - camera.z;
            float grey = 0.12f + ((hash >> 4) & 0x3F) / 255f;
            quad(buffer, px, py, pz, right, up, 22f + (hash & 15), grey, grey, grey, fade * 0.85f);
        }
    }

    /** Twigs and splinters climbing the funnel, one more layer every five ticks after it formed. */
    private static void drawTwigs(VertexConsumer buffer, Funnel funnel, int seed, int texture, Vec3d camera, Vector3f right, Vector3f up, float delta, float fade) {
        TornadoFunnel shape = funnel.shape;
        int highest = Math.min(TornadoFunnel.LAYERS - 1, 7 + funnel.age / 5);
        for (int i = 7; i <= highest; i += 2) {
            double rotation = lerp(funnel.previousRotation[i], shape.rotation[i], delta);
            for (int j = texture; j < 6; j += TWIGS.length) {
                int hash = mix(seed + 7, i, j);
                double distance = (shape.ringRadius(i) + 0.5 * i) * (0.4 + 0.6 * ((hash & 0xFF) / 255.0));
                double angle = Math.toRadians(60.0 * j + rotation * 1.1 + (hash >> 8 & 0x3F));
                double px = shape.x[i] + Math.cos(angle) * distance - camera.x, py = shape.y[i] + ((hash >> 16) & 3) - camera.y,
                        pz = shape.z[i] + Math.sin(angle) * distance - camera.z;
                float tint = 1 - ((hash >> 20) & 0x7F) / 255f;
                quad(buffer, px, py, pz, right, up, 1.2f, tint, tint * 0.9f, tint * 0.8f, fade);
            }
        }
    }

    private static void quad(VertexConsumer buffer, double x, double y, double z, Vector3f right, Vector3f up, float half,
                             float r, float g, float b, float a) {
        float rx = right.x * half, ry = right.y * half, rz = right.z * half, ux = up.x * half, uy = up.y * half, uz = up.z * half;
        int light = 0xF000F0;
        int ri = (int) (r * 255), gi = (int) (g * 255), bi = (int) (b * 255), ai = Math.max(0, Math.min(255, (int) (a * 255)));
        vertex(buffer, x - rx - ux, y - ry - uy, z - rz - uz, ri, gi, bi, ai, 0, 1, light);
        vertex(buffer, x + rx - ux, y + ry - uy, z + rz - uz, ri, gi, bi, ai, 1, 1, light);
        vertex(buffer, x + rx + ux, y + ry + uy, z + rz + uz, ri, gi, bi, ai, 1, 0, light);
        vertex(buffer, x - rx + ux, y - ry + uy, z - rz + uz, ri, gi, bi, ai, 0, 0, light);
    }

    private static void vertex(VertexConsumer buffer, double x, double y, double z, int r, int g, int b, int a, float u, float v, int light) {
        buffer.vertex((float) x, (float) y, (float) z).color(r, g, b, a).texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(0, 1, 0);
    }

    private static double lerp(double from, double to, float delta) { return from + (to - from) * delta; }

    /** A cheap stable hash: each puff keeps its own shade and offset from frame to frame. */
    private static int mix(int a, int b, int c) {
        int h = a * 0x9E3779B1 ^ b * 0x85EBCA6B ^ c * 0xC2B2AE35;
        h ^= h >>> 15; h *= 0x2C1B3C6D; h ^= h >>> 12;
        return h & 0x7FFFFFFF;
    }
}
