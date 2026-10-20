package io.github.divineblock.client;

import io.github.divineblock.game.BeamsPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.entity.BeaconBlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/** Beacon beams out of the altar while the god speaks: five or so, each leaning at its own random angle of 45–90° from the
 * ground, all spinning fast around the altar's vertical axis. Drawn with the beacon's own beam renderer. */
final class AltarBeams {
    private record Beam(float elevation, float azimuth) { }
    private record Show(BlockPos altar, long start, long end, int color, List<Beam> beams, float spin) { }
    private static final List<Show> SHOWS = new ArrayList<>();
    private static final int LENGTH = 48;

    private AltarBeams() { }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(BeamsPayload.ID, (payload, context) -> start(context.client(), payload.altar(), payload.ticks(), payload.color()));
        WorldRenderEvents.AFTER_TRANSLUCENT.register(AltarBeams::render);
    }

    static void start(MinecraftClient client, BlockPos altar, int ticks, int color) {
        if (client.world == null) return;
        if (ticks <= 0) {
            // The last word: the beams at this altar fade out over half a second.
            long fade = client.world.getTime() + 10;
            SHOWS.replaceAll(show -> show.altar().equals(altar) && show.end() > fade
                    ? new Show(show.altar(), show.start(), fade, show.color(), show.beams(), show.spin()) : show);
            return;
        }
        Random random = new Random(altar.asLong() ^ client.world.getTime());
        List<Beam> beams = new ArrayList<>();
        int count = 4 + random.nextInt(3);
        for (int i = 0; i < count; i++) beams.add(new Beam(45 + random.nextFloat() * 45, i * 360f / count + random.nextFloat() * 20));
        long now = client.world.getTime();
        SHOWS.add(new Show(altar.toImmutable(), now, now + Math.max(20, ticks), color, beams, random.nextBoolean() ? 9f : -9f));
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || SHOWS.isEmpty()) { SHOWS.clear(); return; }
        long time = client.world.getTime();
        SHOWS.removeIf(show -> time > show.end());
        if (SHOWS.isEmpty() || context.consumers() == null) return;
        float delta = context.tickCounter().getTickDelta(false);
        Vec3d camera = context.camera().getPos();
        MatrixStack matrices = context.matrixStack() != null ? context.matrixStack() : new MatrixStack();
        for (Show show : SHOWS) {
            float age = time - show.start() + delta;
            // The beams grow out over the first second and fade in length over the last half second.
            float grow = Math.min(1f, age / 20f) * Math.min(1f, (show.end() - time - delta) / 10f + 0.05f);
            int length = Math.max(2, Math.round(LENGTH * grow));
            for (Beam beam : show.beams()) {
                matrices.push();
                matrices.translate(show.altar().getX() + 0.5 - camera.x, show.altar().getY() + 1.0 - camera.y, show.altar().getZ() + 0.5 - camera.z);
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(beam.azimuth() + age * show.spin()));
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90 - beam.elevation()));
                matrices.translate(-0.5, 0, -0.5);
                BeaconBlockEntityRenderer.renderBeam(matrices, context.consumers(), BeaconBlockEntityRenderer.BEAM_TEXTURE, delta, 1f, time, 0, length,
                        show.color(), 0.15f, 0.25f);
                matrices.pop();
            }
        }
    }
}
