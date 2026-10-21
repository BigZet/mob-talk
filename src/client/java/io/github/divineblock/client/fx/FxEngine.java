package io.github.divineblock.client.fx;

import io.github.divineblock.client.SkyTint;
import io.github.divineblock.game.AltarBlock;
import io.github.divineblock.game.Fx;
import io.github.divineblock.game.FxPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** The god's particle engine: the mod's own sprites (glow, sparks, feathers, confetti, ash, smoke) and bigger effects
 * (rings, bolts, meteors, pillars), ticked with the client and drawn after the translucent world in two layers. The server
 * asks for effects with {@link Fx}; the altar's motes and the ash of a red sky are the client's own. Caps and counts follow
 * {@link FxQuality}; everything is dropped when the world changes. */
public final class FxEngine {
    private static final List<FxParticle> PARTICLES = new ArrayList<>();
    private static final List<FxEffect> EFFECTS = new ArrayList<>(), PENDING = new ArrayList<>();
    /** Altars seen nearby, with the tick they were last confirmed: they get the idle motes. */
    private static final Map<BlockPos, Long> ALTARS = new HashMap<>();
    /** Handed out when over budget: callers set its fields and nothing is drawn. */
    private static final FxParticle SINK = new FxParticle();
    private static final Random RANDOM = new Random();
    private static ClientWorld world;

    private FxEngine() { }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(FxPayload.ID, (payload, context) -> receive(context.client(), payload));
        ClientTickEvents.END_CLIENT_TICK.register(FxEngine::tick);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(FxEngine::render);
        HudRenderCallback.EVENT.register((context, counter) -> FxCamera.drawFlash(context));
        AltarBlock.ambience = (seen, pos) -> { if (seen.isClient) ALTARS.put(pos.toImmutable(), seen.getTime()); };
    }

    // ---- Spawning. ----

    /** A new sprite at a spot, living {@code life} ticks; over the budget a throwaway one that is never drawn. */
    static FxParticle add(FxSprite sprite, boolean glow, double x, double y, double z, int life) {
        if (world == null || PARTICLES.size() >= FxQuality.budget()) return reset(SINK);
        FxParticle p = new FxParticle();
        p.sprite = sprite; p.glow = glow; p.life = Math.max(1, life);
        p.at(x, y, z);
        p.phase = RANDOM.nextFloat() * MathHelper.TAU;
        PARTICLES.add(p);
        return p;
    }

    private static FxParticle reset(FxParticle sink) { sink.endSize = -1; sink.endColor = -1; return sink; }

    static void addEffect(FxEffect effect) { if (world != null) PENDING.add(effect); }

    static double spread(double half) { return (RANDOM.nextDouble() * 2 - 1) * half; }

    private static void receive(MinecraftClient client, FxPayload fx) {
        if (client.world == null || world == null) return;
        if (!FxQuality.on()) { fallback(client.world, fx); return; }
        switch (fx.kind()) {
            case Fx.RING -> addEffect(new FxEffect.Ring(fx.x(), fx.y() + 0.12, fx.z(), fx.size(), fx.color() & 0xFFFFFF, Math.max(10, fx.ticks())));
            case Fx.FEATHERS -> feathers(fx.x(), fx.y(), fx.z(), fx.size());
            case Fx.CONFETTI -> confetti(fx.x(), fx.y(), fx.z(), fx.size());
            case Fx.BOLT -> addEffect(new FxBolt(client.world, fx.x(), fx.y(), fx.z(), fx.color() & 0xFFFFFF));
            case Fx.METEOR -> addEffect(new FxMeteor(new Vec3d(fx.x(), fx.y(), fx.z()), new Vec3d(fx.tx(), fx.ty(), fx.tz()), fx.ticks(), fx.size()));
            case Fx.IMPACT -> impact(fx.x(), fx.y(), fx.z(), fx.color() & 0xFFFFFF, fx.size());
            case Fx.SHAKE -> FxCamera.shakeFrom(fx.x(), fx.y(), fx.z(), fx.size(), Math.max(4, fx.ticks()), 64);
            case Fx.FLASH -> FxCamera.flashFrom(fx.x(), fx.y(), fx.z(), fx.color(), fx.size(), 128);
            case Fx.SPARKS -> sparks(fx.x(), fx.y(), fx.z(), fx.color() & 0xFFFFFF, fx.size());
            case Fx.PILLAR -> addEffect(new FxEffect.Pillar(fx.x(), fx.y(), fx.z(), fx.color() & 0xFFFFFF, fx.ticks()));
            case Fx.EMBERS -> embers(fx.x(), fx.y(), fx.z(), fx.color() & 0xFFFFFF, fx.size(), fx.ticks());
            case Fx.SMOKE -> smoke(fx.x(), fx.y(), fx.z(), fx.color() & 0xFFFFFF, fx.size());
            default -> { }
        }
    }

    /** Effects off: what the god did before, in vanilla particles (meteors draw their own fallback). */
    private static void fallback(ClientWorld world, FxPayload fx) {
        switch (fx.kind()) {
            case Fx.METEOR -> addEffect(new FxMeteor(new Vec3d(fx.x(), fx.y(), fx.z()), new Vec3d(fx.tx(), fx.ty(), fx.tz()), fx.ticks(), fx.size()));
            case Fx.IMPACT -> world.addParticle(ParticleTypes.EXPLOSION_EMITTER, fx.x(), fx.y() + 1, fx.z(), 0, 0, 0);
            default -> { }
        }
    }

    static void feathers(double x, double y, double z, float amount) {
        for (int i = FxQuality.count(22 * amount); i > 0; i--) {
            double a = RANDOM.nextDouble() * MathHelper.TAU, r = 0.6 + RANDOM.nextDouble() * 3.4;
            FxParticle p = add(FxSprite.FEATHER, false, x + Math.cos(a) * r, y + 3.5 + RANDOM.nextDouble() * 4, z + Math.sin(a) * r, 180 + RANDOM.nextInt(100));
            p.mode = FxParticle.FLUTTER; p.gravity = 0.0025f; p.vy = 0.02;
            p.size = 0.22f + RANDOM.nextFloat() * 0.14f; p.color = RANDOM.nextInt(3) == 0 ? 0xFFE9A8 : 0xFFFFFF; p.fadeIn = 0.06f; p.fadeOut = 0.2f;
        }
        for (int i = FxQuality.count(26 * amount); i > 0; i--) {
            FxParticle p = add(RANDOM.nextInt(3) == 0 ? FxSprite.STAR : FxSprite.SPARK, true, x + spread(2.5), y + RANDOM.nextDouble() * 4, z + spread(2.5), 30 + RANDOM.nextInt(40));
            p.vy = 0.01 + RANDOM.nextDouble() * 0.03; p.drag = 0.99f; p.size = 0.1f + RANDOM.nextFloat() * 0.18f; p.color = 0xFFD86A; p.flicker = 0.6f;
        }
    }

    private static final int[] CONFETTI = { 0xFF3B5C, 0xFFC93B, 0x3BD1FF, 0x7CFF5A, 0xC65BFF, 0xFF8A2A, 0xFFFFFF, 0x2A7BFF };

    static void confetti(double x, double y, double z, float amount) {
        for (int i = FxQuality.count(70 * amount); i > 0; i--) {
            FxParticle p = add(RANDOM.nextInt(3) == 0 ? FxSprite.STRIP : FxSprite.CONFETTI, false, x + spread(0.6), y + 2.2, z + spread(0.6), 220 + RANDOM.nextInt(120));
            double a = RANDOM.nextDouble() * MathHelper.TAU, speed = 0.08 + RANDOM.nextDouble() * 0.28;
            p.mode = FxParticle.TUMBLE; p.vx = Math.cos(a) * speed; p.vz = Math.sin(a) * speed; p.vy = 0.3 + RANDOM.nextDouble() * 0.35;
            p.gravity = 0.022f; p.drag = 0.95f; p.size = 0.09f + RANDOM.nextFloat() * 0.05f;
            p.color = CONFETTI[RANDOM.nextInt(CONFETTI.length)]; p.flipSpeed = 0.15f + RANDOM.nextFloat() * 0.3f; p.rollSpeed = (RANDOM.nextFloat() - 0.5f) * 0.3f;
            p.fadeIn = 0.01f; p.fadeOut = 0.15f;
        }
        for (int i = FxQuality.count(24 * amount); i > 0; i--) {
            FxParticle p = add(FxSprite.STAR, true, x + spread(1.5), y + 2.2 + spread(1), z + spread(1.5), 16 + RANDOM.nextInt(16));
            p.vx = spread(0.15); p.vy = 0.1 + RANDOM.nextDouble() * 0.15; p.vz = spread(0.15); p.drag = 0.92f;
            p.size = 0.25f + RANDOM.nextFloat() * 0.2f; p.color = CONFETTI[RANDOM.nextInt(CONFETTI.length)]; p.rollSpeed = 0.1f;
        }
    }

    static void sparks(double x, double y, double z, int color, float amount) {
        for (int i = FxQuality.count(12 * amount); i > 0; i--) {
            FxParticle p = add(FxSprite.SPARK, true, x, y, z, 14 + RANDOM.nextInt(20));
            p.vx = spread(0.4); p.vy = 0.1 + RANDOM.nextDouble() * 0.35; p.vz = spread(0.4); p.gravity = 0.035f; p.drag = 0.93f;
            p.size = 0.08f + RANDOM.nextFloat() * 0.1f; p.color = FxDraw.mix(color, 0xFFFFFF, 0.4f); p.endColor = color;
        }
    }

    static void smoke(double x, double y, double z, int color, float radius) {
        for (int i = FxQuality.count(10 * Math.max(1, radius)); i > 0; i--) {
            FxParticle p = add(FxSprite.SMOKE, false, x + spread(radius), y + RANDOM.nextDouble() * radius * 0.5, z + spread(radius), 60 + RANDOM.nextInt(60));
            p.vy = 0.02 + RANDOM.nextDouble() * 0.03; p.vx = spread(0.02); p.vz = spread(0.02); p.drag = 0.99f;
            p.size = 0.8f + radius * 0.15f; p.endSize = p.size * 3; p.color = color; p.alpha = 0.7f; p.rollSpeed = (RANDOM.nextFloat() - 0.5f) * 0.04f;
        }
    }

    static void embers(double x, double y, double z, int color, float radius, int ticks) {
        addEffect(new FxEffect.Emitter(Math.max(1, ticks), age -> {
            for (int i = FxQuality.count(Math.max(1, radius * 0.6)); i > 0; i--) {
                FxParticle p = add(RANDOM.nextInt(4) == 0 ? FxSprite.SPARK : FxSprite.EMBER, true, x + spread(radius), y + RANDOM.nextDouble() * 1.5, z + spread(radius), 30 + RANDOM.nextInt(50));
                p.vy = 0.03 + RANDOM.nextDouble() * 0.06; p.vx = spread(0.02); p.vz = spread(0.02); p.drag = 0.99f;
                p.size = 0.06f + RANDOM.nextFloat() * 0.08f; p.color = FxDraw.mix(color, 0xFFE0A0, 0.5f); p.endColor = color; p.flicker = 0.5f;
            }
        }));
    }

    /** A blast: a fireball and flash, a shock ring, sparks thrown high, rolling smoke and embers that keep glowing in the pit. */
    public static void impact(double x, double y, double z, int color, float size) {
        if (!FxQuality.on()) return;
        float s = Math.max(0.5f, size);
        addEffect(new FxEffect.Ring(x, y + 0.3, z, 6 + 4 * s, color, 26));
        addEffect(new FxEffect.Ring(x, y + 0.3, z, 3 + 2 * s, 0xFFE8C0, 16));
        FxParticle ball = add(FxSprite.GLOW, true, x, y + 1.5, z, 14);
        ball.size = 2 * s; ball.endSize = 7 * s; ball.color = 0xFFE0A0; ball.endColor = color; ball.fadeIn = 0.01f; ball.fadeOut = 0.8f;
        FxParticle flare = add(FxSprite.FLARE, true, x, y + 1.5, z, 10);
        flare.size = 5 * s; flare.endSize = 2 * s; flare.color = 0xFFFFFF; flare.fadeIn = 0.01f; flare.fadeOut = 0.9f;
        for (int i = FxQuality.count(45 * s); i > 0; i--) {
            FxParticle p = add(RANDOM.nextInt(3) == 0 ? FxSprite.SPARK : FxSprite.EMBER, true, x, y + 0.8, z, 25 + RANDOM.nextInt(35));
            double a = RANDOM.nextDouble() * MathHelper.TAU, speed = (0.2 + RANDOM.nextDouble() * 0.5) * Math.sqrt(s);
            p.vx = Math.cos(a) * speed; p.vz = Math.sin(a) * speed; p.vy = 0.3 + RANDOM.nextDouble() * 0.7 * Math.sqrt(s);
            p.gravity = 0.045f; p.drag = 0.95f; p.size = 0.12f + RANDOM.nextFloat() * 0.18f; p.color = 0xFFD080; p.endColor = 0xB01A04;
        }
        for (int i = FxQuality.count(16 * s); i > 0; i--) {
            FxParticle p = add(FxSprite.SMOKE, false, x + spread(1.5 * s), y + 0.5 + RANDOM.nextDouble() * 2 * s, z + spread(1.5 * s), 90 + RANDOM.nextInt(80));
            p.vx = spread(0.12); p.vy = 0.05 + RANDOM.nextDouble() * 0.1; p.vz = spread(0.12); p.drag = 0.97f;
            p.size = 1.2f * s; p.endSize = 4.5f * s; p.color = RANDOM.nextBoolean() ? 0x2E2A28 : 0x4E4844; p.alpha = 0.8f; p.rollSpeed = (RANDOM.nextFloat() - 0.5f) * 0.05f;
        }
        // Embers smouldering in the pit for a minute.
        for (int i = FxQuality.count(25 * s); i > 0; i--) {
            FxParticle p = add(FxSprite.EMBER, true, x + spread(3 * s), y + RANDOM.nextDouble(), z + spread(3 * s), 600 + RANDOM.nextInt(800));
            p.vy = 0.002; p.drag = 1f; p.size = 0.12f + RANDOM.nextFloat() * 0.12f; p.color = 0xFF7A20; p.flicker = 0.7f; p.fadeIn = 0.02f; p.fadeOut = 0.4f;
        }
        FxCamera.flashFrom(x, y, z, 0xFFE0B0, 0.45f, 70 + 15 * s);
        FxCamera.shakeFrom(x, y, z, 0.35f + 0.15f * s, 16 + (int) (6 * s), 50 + 15 * s);
    }

    // ---- The altar's motes and the ash of wrath, the client's own. ----

    private static void ambient(ClientWorld world, MinecraftClient client) {
        long now = world.getTime();
        Vec3d eye = client.player.getEyePos();
        if (now % 20 == 0)
            ALTARS.entrySet().removeIf(entry -> !(world.getBlockState(entry.getKey()).getBlock() instanceof AltarBlock)
                    || entry.getKey().getSquaredDistance(eye) > 64 * 64);
        for (BlockPos altar : ALTARS.keySet()) {
            for (int i = FxQuality.count(0.6); i > 0; i--) {
                if (RANDOM.nextFloat() > 0.6f) break;
                FxParticle p = add(RANDOM.nextInt(5) == 0 ? FxSprite.STAR : FxSprite.GLOW, true, 0, 0, 0, 80 + RANDOM.nextInt(90));
                p.mode = FxParticle.ORBIT; p.cx = altar.getX() + 0.5; p.cz = altar.getZ() + 0.5;
                p.radius = 0.7f + RANDOM.nextFloat() * 1.6f; p.vx = (RANDOM.nextFloat() - 0.6f) * 0.006f;
                p.spin = (RANDOM.nextBoolean() ? 1 : -1) * (0.02f + RANDOM.nextFloat() * 0.03f);
                p.at(p.cx + MathHelper.cos(p.phase) * p.radius, altar.getY() + 0.2 + RANDOM.nextDouble() * 1.8, p.cz + MathHelper.sin(p.phase) * p.radius);
                p.vy = 0.004 + RANDOM.nextDouble() * 0.012;
                p.size = 0.07f + RANDOM.nextFloat() * 0.08f; p.color = RANDOM.nextInt(3) == 0 ? 0xFFF6DA : 0xFFD266; p.flicker = 0.35f; p.fadeIn = 0.25f; p.fadeOut = 0.4f;
            }
        }
        // Ash and embers falling from a red sky around the player.
        float red = SkyTint.amount();
        int depth = SkyTint.depth();
        if (red > 0 && depth > 0) {
            for (int i = FxQuality.count(depth * 2.5 * red); i > 0; i--) {
                double x = eye.x + spread(26), z = eye.z + spread(26), y = eye.y + 8 + RANDOM.nextDouble() * 14;
                boolean ember = RANDOM.nextInt(6) == 0;
                FxParticle p = add(ember ? FxSprite.EMBER : FxSprite.ASH, ember, x, y, z, 260 + RANDOM.nextInt(120));
                p.mode = FxParticle.TUMBLE; p.gravity = 0.0015f; p.vy = -0.02; p.vx = 0.015 + spread(0.01); p.vz = spread(0.01); p.drag = 1f;
                p.flipSpeed = 0.05f + RANDOM.nextFloat() * 0.1f; p.rollSpeed = (float) spread(0.04);
                p.size = ember ? 0.05f + RANDOM.nextFloat() * 0.05f : 0.06f + RANDOM.nextFloat() * 0.07f;
                p.color = ember ? (depth >= 3 ? 0xFF3A10 : 0xFF7A2A) : (depth >= 3 ? 0x3A3434 : 0x6A6462);
                p.flicker = ember ? 0.6f : 0; p.fadeIn = 0.08f; p.fadeOut = 0.15f;
            }
        }
    }

    // ---- Tick and draw. ----

    private static void tick(MinecraftClient client) {
        if (client.world != world) { world = client.world; PARTICLES.clear(); EFFECTS.clear(); PENDING.clear(); ALTARS.clear(); FxCamera.clear(); }
        FxQuality.tick();
        if (world == null || client.isPaused()) return;
        FxCamera.tick();
        if (client.player != null && FxQuality.on()) ambient(world, client);
        EFFECTS.addAll(PENDING); PENDING.clear();
        EFFECTS.removeIf(effect -> !effect.tick(world));
        EFFECTS.addAll(PENDING); PENDING.clear();
        for (Iterator<FxParticle> it = PARTICLES.iterator(); it.hasNext(); ) if (!it.next().tick(world)) it.remove();
        if (!FxQuality.on()) PARTICLES.clear();
    }

    /** The god's rays out of an altar while he speaks; false when effects are off (the caller draws beacon beams instead). */
    public static boolean rays(BlockPos altar, int ticks, int color, float[] elevations, float[] azimuths, float spin) {
        if (world == null || !FxQuality.on()) return false;
        addEffect(new AltarRays(altar.toImmutable(), ticks, color, elevations, azimuths, spin));
        return true;
    }

    /** His last word: the rays at this altar fade. */
    public static void raysFade(BlockPos altar) {
        for (List<FxEffect> list : List.of(EFFECTS, PENDING))
            for (FxEffect effect : list) if (effect instanceof AltarRays rays && rays.altar.equals(altar)) rays.fade();
    }

    /** For the lightning renderer: a vanilla bolt where one of ours struck is not drawn. */
    public static boolean hidesBolt(net.minecraft.entity.Entity bolt) {
        return world != null && FxQuality.on() && FxBolt.claims(world, bolt.getPos());
    }

    /** For the self-test: how many sprites and effects are alive. */
    public static int alive() { return PARTICLES.size() + EFFECTS.size(); }

    private static void render(WorldRenderContext context) {
        if (world == null || context.consumers() == null || (PARTICLES.isEmpty() && EFFECTS.isEmpty())) return;
        float delta = context.tickCounter().getTickDelta(false);
        Vec3d camera = context.camera().getPos();
        VertexConsumerProvider consumers = context.consumers();
        // See-through sprites first, light added over them.
        FxDraw soft = new FxDraw(consumers.getBuffer(FxLayers.SOFT), camera, context.camera().getRotation());
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (FxParticle p : PARTICLES) if (!p.glow) draw(soft, p, delta, pos);
        for (FxEffect effect : EFFECTS) effect.render(soft, delta, false);
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(FxLayers.SOFT);
        FxDraw glow = new FxDraw(consumers.getBuffer(FxLayers.GLOW), camera, context.camera().getRotation());
        for (FxParticle p : PARTICLES) if (p.glow) draw(glow, p, delta, pos);
        for (FxEffect effect : EFFECTS) effect.render(glow, delta, true);
        if (consumers instanceof VertexConsumerProvider.Immediate immediate) immediate.draw(FxLayers.GLOW);
    }

    private static void draw(FxDraw draw, FxParticle p, float delta, BlockPos.Mutable pos) {
        double x = MathHelper.lerp(delta, p.px, p.x), y = MathHelper.lerp(delta, p.py, p.y), z = MathHelper.lerp(delta, p.pz, p.z);
        int light = p.glow ? FxDraw.FULL_BRIGHT : p.light >= 0 ? p.light : WorldRenderer.getLightmapCoordinates(world, pos.set(x, y, z));
        float alpha = p.alphaAt(delta), size = p.sizeAt(delta);
        int color = p.colorAt(delta);
        if (p.landed) { draw.flat(p.sprite, x, y, z, size, p.flatYaw, color, alpha, light); return; }
        float roll = p.roll + p.rollSpeed * delta;
        float squeeze = p.mode == FxParticle.TUMBLE ? Math.max(0.15f, Math.abs(MathHelper.cos(p.flip + p.flipSpeed * delta))) : 1;
        draw.sprite(p.sprite, x, y, z, size, roll, squeeze, color, alpha, light);
    }

}
