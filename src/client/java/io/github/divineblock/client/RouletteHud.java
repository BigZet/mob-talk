package io.github.divineblock.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.Roulette;
import io.github.divineblock.core.RouletteSymbol;
import io.github.divineblock.game.RouletteCancelPayload;
import io.github.divineblock.game.RouletteSpinPayload;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/** The punishment roulette on the screen ({@link Roulette}), a slot machine in the manner of our iconostasis and of an online
 * casino at once: the gilded frame with the eight-pointed cross and lampadas rolls down on a spring, a golden hand pulls the
 * lever, chase lights run round the frame as fast as the reels, the reels blur, flash white and bounce as they stop, the
 * last one is awaited with a darkened screen, a heartbeat and a burning frame; then the win line runs with light, the
 * symbols pulse, god rays turn behind the machine, coins and sparks fly, big words slam onto the screen and the ribbon names
 * the punishment. 777 floods the screen with gold, throws sevens at the player and rains coins; the ban blackens it all; a
 * pardon jams the reels into doves under falling feathers. Timed from the server's start tick and drawn from its seed:
 * every screen shows the same spin, the client only draws it and plays its sounds. How much show is the player's own
 * «Интенсивность эффектов»; hidden with the HUD (F1) and by «Показывать у меня», the chat line stays. */
public final class RouletteHud {
    private static final Identifier SYMBOLS = id("symbols"), LEVER = id("lever"), HAND = id("hand"), LAMPS = id("lamps"), RAYS = id("rays"),
            VIGNETTE = id("vignette"), GLOW = id("glow"), COIN = id("coin"), SPARK = id("spark"), FEATHER = id("feather"), SHINE = id("shine");
    private static final Identifier[] FRAMES = { id("frame_silver"), id("frame_gold"), id("frame_hot"), id("frame_black") };
    /** The machine's texture and its parts, in its own pixels. */
    private static final int W = 176, H = 112, WIN_L = 34, WIN_T = 38, WIN_B = 98, WIN_R = 142, REEL = 34, REEL_STEP = 37, LINE = 68, CELL = 36;
    /** How long a pardoned machine stays with its doves, in ticks. */
    private static final int PARDON_HOLD = 50;
    private static final SoundEvent S_LEVER = sound("lever"), S_TICK = sound("tick"), S_THUD = sound("thud"), S_BELL = sound("bell"),
            S_BIG_BELL = sound("big_bell"), S_CHIME = sound("chime"), S_CHOIR = sound("choir"), S_THUNDER = sound("thunder"),
            S_FANFARE = sound("fanfare"), S_COINS = sound("coins"), S_RISER = sound("riser"), S_HEART = sound("heartbeat"), S_WHOOSH = sound("whoosh");
    private static final float[] NOTES = { 0.75f, 1.0f, 1.26f };
    private static final float[] FX = { 0f, 0.35f, 0.7f, 1f };
    /** The chase lights round the frame, in its pixels, clockwise from the top left. */
    private static final int[][] BULBS = bulbs();

    private static final List<Show> SHOWS = new ArrayList<>();
    private static final List<Particle> PARTICLES = new ArrayList<>();
    private static final Random RANDOM = new Random();
    /** The player's own switches, read now and then (reading the config copies it). */
    private static boolean show = true, jackpotFx = true;
    private static float volume = 1f, fx = 1f;
    private static int sinceRead = 1_000;
    /** Where the machine was last drawn: particles are spawned from it in the tick. */
    private static double lastX, lastY, lastScale = 1;
    private static int lastWidth = 320, lastHeight = 240;

    /** For the self-test: the moment each show passes (start, stop 1..3, final, 777, gates, pardon, gone). */
    public static volatile java.util.function.Consumer<String> moment = name -> { };

    private RouletteHud() { }

    /** One spin on this screen. */
    static final class Show {
        final RouletteSpinPayload spin;
        final Roulette.Timing timing;
        final RouletteSymbol symbol;
        final List<List<RouletteSymbol>> strips = new ArrayList<>();
        final int[] landing;
        final boolean own;
        /** Server tick it was stopped at, and whether by a pardon. */
        long cancelledAt = -1;
        boolean pardon;
        double[] jammed;
        // What was already played and announced.
        boolean leverPlayed, finPlayed, riserPlayed;
        int stopsPlayed, lastBeat = -100, rained;
        double[] last;

        Show(RouletteSpinPayload spin, boolean own) {
            this.spin = spin; this.own = own;
            Roulette.Mode mode = Roulette.Mode.values()[MathHelper.clamp(spin.mode(), 0, Roulette.Mode.values().length - 1)];
            timing = Roulette.Timing.of(mode, spin.severity());
            symbol = RouletteSymbol.values()[MathHelper.clamp(spin.symbol(), 0, RouletteSymbol.values().length - 1)];
            landing = new int[timing.reels()];
            last = new double[timing.reels()];
            for (int reel = 0; reel < timing.reels(); reel++) {
                List<RouletteSymbol> strip = Roulette.strip(spin.seed(), reel, symbol, spin.tease() && reel == timing.reels() - 1);
                strips.add(strip);
                landing[reel] = Math.max(0, strip.indexOf(symbol));
                last[reel] = Double.NaN;
            }
        }

        boolean mini() { return timing.mode() == Roulette.Mode.MINI; }
        boolean doom() { return symbol == RouletteSymbol.SEVEN; }
        boolean ban() { return symbol == RouletteSymbol.GATE; }
        /** Ticks since its start, by this world's clock (the server's overworld time). */
        double at(MinecraftClient client, float delta) {
            return client.world == null ? 0 : Math.max(0, client.world.getTime() + delta - spin.start());
        }
        double cancelledTick() { return cancelledAt < 0 ? Double.NaN : Math.max(0, cancelledAt - spin.start()); }
        boolean stopped(double t) { return cancelledAt >= 0 && t >= cancelledTick(); }
        boolean pardoned(double t) { return pardon && t >= cancelledTick(); }
        /** When it is gone from the screen. */
        double over() { return cancelledAt < 0 ? timing.end() : cancelledTick() + (pardon ? PARDON_HOLD + Roulette.Timing.EXIT : Roulette.Timing.EXIT); }
        /** The moment it starts to leave. */
        double leave() { return cancelledAt >= 0 ? cancelledTick() + (pardon ? PARDON_HOLD : 0) : timing.act(); }
        /** Between the second reel's stop and the last one's: everything waits for it. */
        boolean anticipating(double t) { return !mini() && !stopped(t) && t >= timing.stops()[1] && t < timing.stops()[2]; }
        /** Where reel {@code reel} stands at {@code t}: as the server laid it, or jammed where the pardon caught it. */
        double position(int reel, double t) {
            if (jammed != null && t >= cancelledTick()) {
                double x = t - cancelledTick();
                return jammed[reel] + (x < 5 ? Math.sin(x * 2.7 + reel) * 0.08 * (5 - x) / 5 : 0);
            }
            return Roulette.position(timing, reel, spin.tease() && reel == timing.reels() - 1, landing[reel], t);
        }
        double speed(int reel, double t) { return Math.abs(position(reel, t + 0.5) - position(reel, Math.max(0, t - 0.5))); }
    }

    /** A spark, a coin, a feather or an ember flying over the screen, in screen pixels. */
    private static final class Particle {
        static final int SPARK = 0, COIN = 1, FEATHER = 2, EMBER = 3, SEVEN = 4;
        int kind, age, life, color;
        double x, y, px, py, vx, vy, gravity, drag = 0.98, size = 1, spin, rot, prot;
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(RouletteSpinPayload.ID, (payload, context) -> {
            boolean own = context.client().player != null && context.client().player.getUuid().equals(payload.sinner());
            SHOWS.add(new Show(payload, own));
        });
        ClientPlayNetworking.registerGlobalReceiver(RouletteCancelPayload.ID, (payload, context) -> {
            for (Show one : SHOWS) if (one.spin.id() == payload.id() && one.cancelledAt < 0) {
                one.cancelledAt = payload.at();
                one.pardon = payload.pardon();
                double t = one.cancelledTick();
                one.jammed = new double[one.timing.reels()];
                for (int reel = 0; reel < one.jammed.length; reel++) one.jammed[reel] = Math.round(one.position(reel, t));
                if (one.pardon) {
                    play(one, S_CHIME, 1f, 0.9f);
                    play(one, S_WHOOSH, 1.2f, 0.4f);
                    if (shown(one)) for (int i = 0; i < 70 * fx; i++) feather(lastWidth * RANDOM.nextDouble(), -10 - RANDOM.nextDouble() * 60);
                    moment.accept("pardon");
                }
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> { SHOWS.clear(); PARTICLES.clear(); }));
        ClientTickEvents.END_CLIENT_TICK.register(RouletteHud::tick);
        HudRenderCallback.EVENT.register(RouletteHud::render);
    }

    /** The show that is drawn: the player's own first, else the earliest; the others' are in chat. */
    private static Show current() {
        if (SHOWS.isEmpty()) return null;
        return SHOWS.stream().filter(s -> s.own).findFirst().orElse(SHOWS.get(0));
    }
    private static boolean shown(Show one) { return show && current() == one; }

    // ---- Sound, particles and the moments, every client tick. ----

    private static void tick(MinecraftClient client) {
        if (++sinceRead >= 40) {
            sinceRead = 0;
            DivineConfig config = DivineBlockMod.config();
            show = config.rouletteShow; jackpotFx = config.rouletteJackpotFx; volume = config.rouletteVolume / 100f; fx = FX[config.rouletteFx];
        }
        tickParticles();
        if (client.world == null) { SHOWS.clear(); return; }
        boolean ticked = false;
        for (Show one : new ArrayList<>(SHOWS)) {
            double t = one.at(client, 0);
            if (t >= one.over()) { SHOWS.remove(one); moment.accept("gone"); continue; }
            if (one.stopped(t)) continue;
            boolean drawn = shown(one);
            if (!one.leverPlayed) {
                one.leverPlayed = true;
                if (!one.mini()) play(one, S_LEVER, 1f, 0.8f);
                moment.accept("start");
            }
            if (!one.mini() && drawn && t >= 5 && t < 6) burst(machine(160, 50), 8, 0xFFE070, 1.6, false);
            // The ratchet: a click for each cell that passed the line, so it slows down with the reels.
            for (int reel = 0; reel < one.timing.reels(); reel++) {
                if (t >= one.timing.stops()[reel]) continue;
                double p = one.position(reel, t);
                if (!Double.isNaN(one.last[reel]) && Math.floor(p + 0.5) != Math.floor(one.last[reel] + 0.5) && !ticked) {
                    ticked = true;
                    play(one, S_TICK, (one.anticipating(t) ? 1.15f : 0.9f) + RANDOM.nextFloat() * 0.2f, 0.35f);
                }
                one.last[reel] = p;
            }
            while (one.stopsPlayed < one.timing.reels() && t >= one.timing.stops()[one.stopsPlayed]) {
                int reel = one.stopsPlayed++;
                play(one, S_THUD, 1f, 0.8f);
                play(one, S_BELL, one.mini() ? 1f : NOTES[Math.min(reel, NOTES.length - 1)], 0.55f);
                if (drawn) {
                    double rx = one.mini() ? 24 : WIN_L + reel * REEL_STEP + REEL / 2.0;
                    double[] top = machine(rx, one.mini() ? 6 : WIN_T), bottom = machine(rx, one.mini() ? 42 : WIN_B);
                    int sparks = (int) (14 * fx);
                    burst(top, sparks, one.ban() ? 0xC02020 : 0xFFE9A0, 2.2, false);
                    burst(bottom, sparks, one.ban() ? 0xC02020 : 0xFFD050, 2.2, false);
                }
                moment.accept("stop" + (reel + 1));
            }
            // Waiting for the last reel: a rising tone and a heartbeat.
            if (one.anticipating(t)) {
                if (!one.riserPlayed) { one.riserPlayed = true; play(one, S_RISER, 1f, 0.45f); }
                int beat = (int) ((t - one.timing.stops()[1]) / 11);
                if (beat != one.lastBeat) { one.lastBeat = beat; play(one, S_HEART, 1f, 0.9f); }
            }
            if (!one.finPlayed && t >= one.timing.fin()) {
                one.finPlayed = true;
                finale(one, drawn);
            }
            // After the final: coins keep raining on 777, embers rise from a burning frame.
            if (drawn && one.finPlayed && t < one.leave()) {
                double since = t - one.timing.fin();
                if (one.doom() && since < 40) for (int i = 0; i < 7 * fx; i++) coin(RANDOM.nextDouble() * lastWidth, -12, (RANDOM.nextDouble() - 0.5) * 1.2, 1 + RANDOM.nextDouble() * 2);
                if (one.ban() && RANDOM.nextFloat() < fx) ember(machine(RANDOM.nextDouble() * W, H));
            }
            if (drawn && !one.mini() && one.spin.severity() >= 5 && RANDOM.nextFloat() < 0.5f * fx) ember(machine(RANDOM.nextDouble() * W, 26 + RANDOM.nextDouble() * 80));
        }
    }

    /** The final: bells, fanfare and coins; the big bell and thunder; on 777 a chord, the choir, a long thunder, gold and a
     * shaking camera; the ban: a low bell and thunder. */
    private static void finale(Show one, boolean drawn) {
        if (one.mini()) {
            play(one, S_BIG_BELL, 1.3f, 0.35f);
            play(one, S_COINS, 1.2f, 0.25f);
            if (drawn) burst(machine(24, 48), (int) (16 * fx), 0xFFD050, 2.5, true);
            moment.accept("final");
            return;
        }
        play(one, S_WHOOSH, 1f, 0.5f);
        if (one.doom()) {
            for (float note : new float[] { 1.0f, 1.26f, 1.5f }) play(one, S_BELL, note, 0.5f);
            play(one, S_FANFARE, 1f, 0.8f);
            play(one, S_BIG_BELL, 1f, 0.9f);
            play(one, S_CHOIR, 1f, 0.75f);
            play(one, S_THUNDER, 0.55f, 0.9f);
            play(one, S_COINS, 1f, 0.7f);
            if (one.own && jackpotFx) io.github.divineblock.client.fx.FxCamera.shake(0.85f, 50);
            moment.accept("777");
        } else if (one.ban()) {
            play(one, S_BIG_BELL, 0.7f, 1f);
            play(one, S_THUNDER, 0.5f, 0.9f);
            play(one, S_HEART, 0.8f, 1f);
            moment.accept("gates");
        } else {
            play(one, S_FANFARE, 0.9f + 0.05f * Math.min(4, one.spin.severity()), 0.55f);
            play(one, S_BIG_BELL, 1f, 0.8f);
            play(one, S_THUNDER, 0.8f, 0.6f);
            play(one, S_COINS, 1.1f, 0.4f);
        }
        if (drawn) {
            // A fountain of coins and sparks out of the plaque under the reels.
            double[] from = machine(W / 2.0, WIN_B + 6);
            int coins = (int) ((one.doom() ? 60 : one.ban() ? 0 : 26) * fx);
            for (int i = 0; i < coins; i++) coin(from[0], from[1], (RANDOM.nextDouble() - 0.5) * 7, -4 - RANDOM.nextDouble() * 6);
            burst(from, (int) (40 * fx), one.ban() ? 0xB01818 : 0xFFE070, 4, true);
            if (one.doom() && fx > 0) for (int reel = 0; reel < 3; reel++) seven(machine(WIN_L + reel * REEL_STEP + REEL / 2.0, LINE), reel * 3);
            if (one.ban()) for (int i = 0; i < 40 * fx; i++) ember(machine(RANDOM.nextDouble() * W, 30 + RANDOM.nextDouble() * 80));
        }
        moment.accept("final");
    }

    private static void play(Show one, SoundEvent sound, float pitch, float loudness) {
        float gain = loudness * volume * (one.own ? 0.5f : 0.3f);
        if (gain <= 0.001f) return;
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(sound, MathHelper.clamp(pitch, 0.5f, 2f), Math.min(1f, gain)));
    }

    // ---- Particles. ----

    /** A point of the machine (its own pixels) on the screen, where it was last drawn. */
    private static double[] machine(double x, double y) { return new double[] { lastX + x * lastScale, lastY + y * lastScale }; }

    private static Particle add(int kind, double x, double y, double vx, double vy, int life) {
        if (PARTICLES.size() > 900 || fx <= 0) return null;
        Particle p = new Particle();
        p.kind = kind; p.x = p.px = x; p.y = p.py = y; p.vx = vx; p.vy = vy; p.life = life;
        PARTICLES.add(p);
        return p;
    }

    private static void burst(double[] at, int count, int color, double force, boolean up) {
        for (int i = 0; i < count; i++) {
            double angle = up ? -Math.PI / 2 + (RANDOM.nextDouble() - 0.5) * 2.2 : RANDOM.nextDouble() * Math.PI * 2;
            double speed = force * (0.4 + RANDOM.nextDouble());
            Particle p = add(Particle.SPARK, at[0], at[1], Math.cos(angle) * speed, Math.sin(angle) * speed, 14 + RANDOM.nextInt(16));
            if (p == null) return;
            p.gravity = 0.12; p.color = RANDOM.nextFloat() < 0.3f ? 0xFFFFFF : color; p.size = 0.6 + RANDOM.nextDouble() * 0.8;
        }
    }

    private static void coin(double x, double y, double vx, double vy) {
        Particle p = add(Particle.COIN, x, y, vx, vy, 60 + RANDOM.nextInt(40));
        if (p == null) return;
        p.gravity = 0.25; p.drag = 0.99; p.size = 0.8 + RANDOM.nextDouble() * 0.7; p.spin = RANDOM.nextInt(8);
    }

    private static void feather(double x, double y) {
        Particle p = add(Particle.FEATHER, x, y, (RANDOM.nextDouble() - 0.5) * 0.6, 0.6 + RANDOM.nextDouble() * 0.8, 120 + RANDOM.nextInt(60));
        if (p == null) return;
        p.gravity = 0.0; p.drag = 1; p.size = 0.7 + RANDOM.nextDouble() * 0.6; p.spin = (RANDOM.nextDouble() - 0.5) * 8; p.rot = RANDOM.nextDouble() * 360;
    }

    private static void ember(double[] at) {
        Particle p = add(Particle.EMBER, at[0], at[1], (RANDOM.nextDouble() - 0.5) * 0.6, -0.4 - RANDOM.nextDouble() * 1.2, 30 + RANDOM.nextInt(30));
        if (p == null) return;
        p.gravity = -0.01; p.color = RANDOM.nextBoolean() ? 0xFF5020 : 0xFFA030; p.size = 0.5 + RANDOM.nextDouble() * 0.6;
    }

    /** A big seven flying at the player. */
    private static void seven(double[] at, int delay) {
        Particle p = add(Particle.SEVEN, at[0], at[1], (at[0] - lastWidth / 2.0) * 0.02, -0.4, 22);
        if (p == null) return;
        p.age = -delay; p.drag = 1;
    }

    private static void tickParticles() {
        for (Iterator<Particle> it = PARTICLES.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            p.px = p.x; p.py = p.y; p.prot = p.rot;
            p.age++;
            if (p.age < 0) continue;
            if (p.kind == Particle.FEATHER) { p.vx = Math.sin((p.age + p.rot) * 0.08) * 0.9; p.rot += p.spin; }
            p.vy += p.gravity; p.vx *= p.drag; p.vy *= p.drag;
            p.x += p.vx; p.y += p.vy;
            if (p.age >= p.life || p.y > lastHeight + 40) it.remove();
        }
    }

    private static void drawParticles(DrawContext context, float delta) {
        var matrices = context.getMatrices();
        for (Particle p : PARTICLES) {
            if (p.age < 0) continue;
            double x = MathHelper.lerp(delta, p.px, p.x), y = MathHelper.lerp(delta, p.py, p.y);
            float life = (float) (p.age + delta) / p.life, fade = MathHelper.clamp(1 - life * life, 0, 1);
            matrices.push();
            matrices.translate(x, y, 0);
            switch (p.kind) {
                case Particle.SPARK -> {
                    matrices.scale((float) p.size, (float) p.size, 1);
                    color(context, p.color, fade * 0.7f);
                    context.drawTexture(GLOW, -6, -6, 12, 12, 0, 0, 64, 64, 64, 64);
                    color(context, 0xFFFFFF, fade);
                    context.drawTexture(SPARK, -4, -4, (p.age / 3 % 2) * 9, 0, 9, 9, 18, 9);
                }
                case Particle.COIN -> {
                    matrices.scale((float) p.size, (float) p.size, 1);
                    color(context, 0xFFFFFF, Math.min(1, fade * 2));
                    int frame = (int) ((p.age + p.spin) / 2) % 8;
                    context.drawTexture(COIN, -8, -8, frame * 16, 0, 16, 16, 128, 16);
                }
                case Particle.FEATHER -> {
                    matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) MathHelper.lerp(delta, p.prot, p.rot)));
                    matrices.scale((float) p.size, (float) p.size, 1);
                    color(context, 0xFFFFFF, Math.min(1, fade * 3));
                    context.drawTexture(FEATHER, -8, -8, 0, 0, 16, 16, 16, 16);
                }
                case Particle.EMBER -> {
                    matrices.scale((float) p.size, (float) p.size, 1);
                    color(context, p.color, fade);
                    context.drawTexture(GLOW, -5, -5, 10, 10, 0, 0, 64, 64, 64, 64);
                    context.fill(-1, -1, 1, 1, (int) (fade * 255) << 24 | 0xFFF0C0);
                }
                case Particle.SEVEN -> {
                    float u = (float) (p.age + delta) / p.life;
                    float scale = 1.2f + 7f * u * u;
                    matrices.scale(scale * (float) lastScale, scale * (float) lastScale, 1);
                    color(context, 0xFFFFFF, MathHelper.clamp(1.2f - u * 1.2f, 0, 1));
                    context.drawTexture(SYMBOLS, -16, -16, RouletteSymbol.SEVEN.ordinal() * 32, 0, 32, 32, 32 * RouletteSymbol.values().length, 32);
                }
                default -> { }
            }
            context.setShaderColor(1f, 1f, 1f, 1f);
            matrices.pop();
        }
    }

    private static void color(DrawContext context, int rgb, float alpha) {
        context.setShaderColor((rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f, MathHelper.clamp(alpha, 0, 1));
    }

    // ---- The picture. ----

    private static void render(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden || client.world == null) return;
        float delta = counter.getTickDelta(false);
        int sw = context.getScaledWindowWidth(), sh = context.getScaledWindowHeight();
        lastWidth = sw; lastHeight = sh;
        Show one = current();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (one != null && show) {
            double t = one.at(client, delta);
            if (t < one.over()) {
                if (one.mini()) drawMini(context, client.textRenderer, one, t, sw);
                else drawMachine(context, client.textRenderer, one, t, sw, sh);
            }
        }
        if (!PARTICLES.isEmpty()) drawParticles(context, delta);
        context.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    private static void drawMachine(DrawContext context, TextRenderer text, Show one, double t, int sw, int sh) {
        int severity = MathHelper.clamp(one.spin.severity(), 1, 6);
        float base = (float) MathHelper.clamp(Math.min(sw * 0.44 / W, sh * 0.38 / H), 0.45, 3.2);
        // Rolling in on a spring, leaving upwards; a zoom while the last reel is awaited and a kick at the final.
        double in = easeOutBack(Math.min(1, t / 12.0), 1.9);
        double leave = t > one.leave() ? Math.min(1, (t - one.leave()) / Roulette.Timing.EXIT) : 0;
        double zoom = 0.82 + 0.18 * in;
        if (one.anticipating(t)) zoom *= 1 + 0.07 * smooth((t - one.timing.stops()[1]) / Math.max(1, one.timing.stops()[2] - one.timing.stops()[1]));
        else if (t >= one.timing.fin() && !one.stopped(t)) zoom *= 1 + 0.07 * Math.max(0, 1 - (t - one.timing.fin()) / 10) + 0.0;
        float scale = (float) (base * zoom);
        double[] shake = shake(one, t);
        double cx = sw / 2.0 + shake[0] * base, top = 4 + (in - 1) * (H + 30) * base - leave * leave * (H + 60) * base + shake[1] * base;
        double x0 = cx - W * scale / 2.0, y0 = top + (H * base - H * scale) / 2.0;
        lastX = x0; lastY = y0; lastScale = scale;

        backdrop(context, one, t, sw, sh);
        rays(context, one, t, x0 + W * scale / 2.0, y0 + LINE * scale, scale);
        flash(context, one, t, sw, sh);

        var matrices = context.getMatrices();
        // The reels, clipped to the window.
        matrices.push();
        matrices.translate(x0, y0, 0);
        matrices.scale(scale, scale, 1);
        boolean doves = one.pardoned(t);
        for (int reel = 0; reel < 3; reel++) {
            int x = WIN_L + reel * REEL_STEP;
            context.fillGradient(x, WIN_T, x + REEL, WIN_B, doves ? 0xFFF8FBFF : 0xFFFBF3DC, doves ? 0xFFDCE8F4 : 0xFFE6D6AE);
        }
        matrices.pop();
        context.enableScissor((int) Math.floor(x0 + WIN_L * scale), (int) Math.floor(y0 + WIN_T * scale), (int) Math.ceil(x0 + WIN_R * scale), (int) Math.ceil(y0 + WIN_B * scale));
        matrices.push();
        matrices.translate(x0, y0, 0);
        matrices.scale(scale, scale, 1);
        for (int reel = 0; reel < 3; reel++) drawReel(context, one, reel, t, WIN_L + reel * REEL_STEP, LINE);
        glass(context, one, t);
        matrices.pop();
        context.disableScissor();

        matrices.push();
        matrices.translate(x0, y0, 0);
        matrices.scale(scale, scale, 1);
        anticipation(context, one, t);
        winLine(context, one, t);
        Identifier frame = FRAMES[severity <= 2 ? 0 : severity <= 4 ? 1 : severity == 5 ? 2 : 3];
        context.drawTexture(frame, 0, 0, 0, 0, W, H, W, H);
        frameShine(context, one, t);
        chaseLights(context, one, t);
        lamps(context, one, t, severity);
        leverAndHand(context, one, t);
        ribbon(context, text, one, t);
        matrices.pop();
        bigWords(context, text, one, t, sw, y0 + (H + 34) * scale, base);
    }

    /** The screen darkens round the machine while it spins, most of all while the last reel is awaited. */
    private static void backdrop(DrawContext context, Show one, double t, int sw, int sh) {
        if (fx <= 0 || !one.own) return;
        double dim = Math.min(1, t / 10) * 0.35;
        if (one.anticipating(t)) dim = 0.35 + 0.35 * smooth((t - one.timing.stops()[1]) / Math.max(1, one.timing.stops()[2] - one.timing.stops()[1]));
        else if (t >= one.timing.fin()) dim = 0.7 * Math.max(0.4, 1 - (t - one.timing.fin()) / 20);
        if (t > one.leave()) dim *= Math.max(0, 1 - (t - one.leave()) / Roulette.Timing.EXIT);
        if (one.stopped(t) && !one.pardon) dim *= Math.max(0, 1 - (t - one.cancelledTick()) / Roulette.Timing.EXIT);
        dim *= fx;
        if (dim <= 0.01) return;
        int tint = one.ban() ? 0x200000 : one.pardoned(t) ? 0x001830 : 0x000000;
        context.fill(0, 0, sw, sh, (int) (dim * 0.35 * 255) << 24 | tint);
        context.setShaderColor(1f, 1f, 1f, (float) dim);
        context.drawTexture(VIGNETTE, 0, 0, sw, sh, 0, 0, 256, 256, 256, 256);
        context.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** God rays turning behind the machine: faint while it spins, blazing at the final. */
    private static void rays(DrawContext context, Show one, double t, double cx, double cy, float scale) {
        if (fx <= 0) return;
        double strength = 0.18 + (one.anticipating(t) ? 0.2 : 0);
        double size = 230;
        if (t >= one.timing.fin() && !one.stopped(t)) {
            double x = t - one.timing.fin();
            strength = 0.95 * Math.max(0.55, 1 - x / 30);
            size = 230 + 140 * Math.min(1, x / 6);
        }
        if (one.pardoned(t)) { strength = 0.8; size = 300; }
        else if (one.stopped(t)) return;
        if (t > one.leave()) strength *= Math.max(0, 1 - (t - one.leave()) / Roulette.Timing.EXIT);
        strength *= Math.min(1, t / 8) * fx;
        int color = one.pardoned(t) ? 0xD8F0FF : one.doom() ? 0xFFD040 : one.ban() ? 0xB00818 : switch (Math.min(5, one.spin.severity())) {
            case 1, 2 -> 0xD0E0FF; case 3, 4 -> 0xFFC850; default -> 0xFF7020; };
        var matrices = context.getMatrices();
        for (int layer = 0; layer < 2; layer++) {
            matrices.push();
            matrices.translate(cx, cy, 0);
            float speed = (float) (t >= one.timing.fin() ? 2.2 : 0.8);
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) (layer == 0 ? t * speed : -t * speed * 0.7 + 15)));
            float s = (float) (size * scale / 256.0 * (layer == 0 ? 1 : 0.8));
            matrices.scale(s, s, 1);
            color(context, color, (float) strength * (layer == 0 ? 0.7f : 0.5f));
            context.drawTexture(RAYS, -128, -128, 0, 0, 256, 256, 256, 256);
            matrices.pop();
        }
        context.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** The machine jolts: when the lever is pulled, at each stop, at the final. */
    private static double[] shake(Show one, double t) {
        double x = 0, y = 0;
        double[][] kicks = one.mini() ? new double[0][] : new double[][] { { 5, 1.2 }, { one.timing.stops()[0], 1.8 }, { one.timing.stops()[1], 2.0 },
                { one.timing.stops()[2], 3.0 }, { one.timing.fin(), one.doom() ? 5.0 : one.ban() ? 4.0 : 2.5 } };
        for (double[] kick : kicks) {
            double since = t - kick[0];
            if (since < 0 || since > 14) continue;
            double amp = kick[1] * Math.exp(-since / 3.5) * (0.4 + 0.6 * fx);
            x += amp * Math.sin(since * 2.9 + kick[0]);
            y += amp * 0.7 * Math.cos(since * 3.7 + kick[0]);
        }
        if (one.anticipating(t)) { x += Math.sin(t * 7.1) * 0.35 * fx; y += Math.cos(t * 6.3) * 0.25 * fx; }
        return new double[] { x, y };
    }

    private static void drawReel(DrawContext context, Show one, int reel, double t, int x, int line) {
        double p = one.position(reel, t);
        // The last reel twitches just before it stops.
        int last = one.timing.reels() - 1;
        double stop = one.timing.stops()[reel];
        if (reel == last && !one.mini() && t > stop - 22 && t < stop && !one.stopped(t)) p += Math.sin(t * 3.1) * 0.05 * fx;
        boolean doves = one.pardoned(t);
        double speed = one.stopped(t) ? 0 : one.speed(reel, t);
        List<RouletteSymbol> strip = one.strips.get(reel);
        boolean won = t >= one.timing.fin() && !one.stopped(t);
        int base = (int) Math.floor(p);
        var matrices = context.getMatrices();
        for (int k = base - 2; k <= base + 2; k++) {
            RouletteSymbol symbol = doves ? RouletteSymbol.DOVE : strip.get(Math.floorMod(k, strip.size()));
            double y = line + (p - k) * CELL;
            boolean centre = Math.abs(y - line) < CELL / 2.0;
            matrices.push();
            matrices.translate(x + 17, y, 0);
            if (won && centre) {
                // The winning symbols pulse over a glow.
                double since = t - one.timing.fin();
                float pulse = (float) (1 + 0.14 * Math.abs(Math.sin(since * 0.32)) + 0.25 * Math.max(0, 1 - since / 5));
                color(context, one.ban() ? 0xC01020 : one.doom() ? 0xFFD040 : 0xFFF0B0, 0.55f + 0.3f * (float) Math.abs(Math.sin(since * 0.32)));
                context.drawTexture(GLOW, -24, -24, 48, 48, 0, 0, 64, 64, 64, 64);
                context.setShaderColor(1f, 1f, 1f, 1f);
                matrices.scale(pulse, pulse, 1);
            } else if (doves && centre) {
                color(context, 0xFFFFFF, 0.6f);
                context.drawTexture(GLOW, -24, -24, 48, 48, 0, 0, 64, 64, 64, 64);
                context.setShaderColor(1f, 1f, 1f, 1f);
            }
            if (speed > 0.25 && !doves) {
                // Motion blur: the symbol stretched along the reel, with faint ghosts behind it.
                float stretch = (float) (1 + Math.min(1.6, (speed - 0.25) * 1.6));
                color(context, 0xFFFFFF, 0.22f);
                drawSymbol(context, symbol, 0, -speed * 10, stretch);
                drawSymbol(context, symbol, 0, -speed * 20, stretch);
                dark(context, one, symbol, doves, 0.85f);
                drawSymbol(context, symbol, 0, 0, stretch);
            } else {
                dark(context, one, symbol, doves, 1f);
                drawSymbol(context, symbol, 0, 0, 1);
            }
            context.setShaderColor(1f, 1f, 1f, 1f);
            matrices.pop();
        }
        // Streaks of light down the reel at full speed.
        if (speed > 0.6 && fx > 0) {
            int alpha = (int) (Math.min(1, (speed - 0.6) * 2) * 60 * fx);
            for (int i = 0; i < 3; i++) {
                int sx = x + 6 + i * 10 + (int) (Math.sin(t * 1.7 + i * 2 + reel) * 2);
                context.fill(sx, WIN_T, sx + 1, WIN_B, alpha << 24 | 0xFFFFFF);
            }
        }
        // A white flash on the reel as it stops.
        double since = t - stop;
        if (since >= 0 && since < 7 && !one.stopped(t)) context.fill(x, WIN_T, x + REEL, WIN_B, (int) ((1 - since / 7) * 200) << 24 | 0xFFFFFF);
        // The cylinder: darker at the top and bottom of the window.
        context.fillGradient(x, WIN_T, x + REEL, WIN_T + 16, 0xD0180C06, 0x00180C06);
        context.fillGradient(x, WIN_B - 16, x + REEL, WIN_B, 0x00180C06, 0xD0180C06);
    }

    /** The ban blackens every symbol but the gates. */
    private static void dark(DrawContext context, Show one, RouletteSymbol symbol, boolean doves, float alpha) {
        if (one.ban() && symbol != RouletteSymbol.GATE && !doves) context.setShaderColor(0.32f, 0.26f, 0.27f, alpha);
        else context.setShaderColor(1f, 1f, 1f, alpha);
    }

    /** A symbol centred on the current point, stretched vertically by {@code stretch}. */
    private static void drawSymbol(DrawContext context, RouletteSymbol symbol, double dx, double dy, float stretch) {
        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(dx, dy, 0);
        matrices.scale(1, stretch, 1);
        context.drawTexture(SYMBOLS, -16, -16, symbol.ordinal() * 32, 0, 32, 32, 32 * RouletteSymbol.values().length, 32);
        matrices.pop();
    }

    /** The glass over the reels: a highlight at the top and a glint sweeping across now and then. */
    private static void glass(DrawContext context, Show one, double t) {
        context.fillGradient(WIN_L, WIN_T, WIN_R, WIN_T + 8, 0x40FFFFFF, 0x00FFFFFF);
        if (fx <= 0) return;
        double period = 34, phase = (t % period) / period;
        double gx = WIN_L - 40 + phase * (WIN_R - WIN_L + 80);
        context.setShaderColor(1f, 1f, 1f, 0.55f * fx);
        context.drawTexture(SHINE, (int) gx, WIN_T - 2, 0, 0, 32, 64, 32, 64);
        context.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** While the last reel is awaited its window burns: a pulsing frame and light over the two symbols already standing. */
    private static void anticipation(DrawContext context, Show one, double t) {
        if (!one.anticipating(t)) return;
        double x = t - one.timing.stops()[1];
        float pulse = (float) (0.5 + 0.5 * Math.sin(x * 0.9));
        int color = one.ban() ? 0xE01020 : one.spin.severity() >= 5 ? 0xFF6020 : 0xFFD040;
        int l = WIN_L + 2 * REEL_STEP - 2, r = l + REEL + 4;
        for (int i = 0; i < 3; i++) {
            int a = (int) ((0.9f - i * 0.25f) * (0.5f + 0.5f * pulse) * 255 * Math.max(0.4f, fx));
            context.fill(l - i, WIN_T - 2 - i, r + i, WIN_T - 1 - i, a << 24 | color);
            context.fill(l - i, WIN_B + 1 + i, r + i, WIN_B + 2 + i, a << 24 | color);
            context.fill(l - 1 - i, WIN_T - 2 - i, l - i, WIN_B + 2 + i, a << 24 | color);
            context.fill(r + i, WIN_T - 2 - i, r + 1 + i, WIN_B + 2 + i, a << 24 | color);
        }
        context.fill(WIN_L + 2 * REEL_STEP, WIN_T, WIN_L + 2 * REEL_STEP + REEL, WIN_B, (int) (pulse * 40) << 24 | color);
    }

    /** The win line: it flashes when the third reel stands, then light runs along it. */
    private static void winLine(DrawContext context, Show one, double t) {
        boolean pardoned = one.pardoned(t);
        if ((t < one.timing.fin() || one.stopped(t)) && !pardoned) return;
        double x = pardoned ? t - one.cancelledTick() : t - one.timing.fin();
        int color = one.doom() ? 0xFFD040 : one.ban() ? 0xC01020 : pardoned ? 0x9FE0FF : 0xFFE080;
        int top = LINE - 19, bottom = LINE + 19, left = WIN_L - 2, right = WIN_R + 2;
        int alpha = (int) ((x < 14 ? 0.55 + 0.45 * Math.abs(Math.sin(x * 0.8)) : 0.7) * 255);
        context.fill(left, top, right, top + 1, alpha << 24 | color);
        context.fill(left, bottom - 1, right, bottom, alpha << 24 | color);
        context.fill(left, top, left + 1, bottom, alpha << 24 | color);
        context.fill(right - 1, top, right, bottom, alpha << 24 | color);
        if (x < 10) context.fill(left, top, right, bottom, (int) ((1 - x / 10) * 110) << 24 | color);
        if (fx > 0) {
            // A bright bead of light running round the line.
            int perimeter = 2 * (right - left) + 2 * (bottom - top);
            for (int bead = 0; bead < 2; bead++) {
                double along = ((x * 6 + bead * perimeter / 2.0) % perimeter);
                double bx, by;
                if (along < right - left) { bx = left + along; by = top; }
                else if (along < right - left + bottom - top) { bx = right; by = top + along - (right - left); }
                else if (along < 2 * (right - left) + bottom - top) { bx = right - (along - (right - left) - (bottom - top)); by = bottom; }
                else { bx = left; by = bottom - (along - 2 * (right - left) - (bottom - top)); }
                color(context, color, 0.9f * fx);
                context.drawTexture(GLOW, (int) bx - 6, (int) by - 6, 12, 12, 0, 0, 64, 64, 64, 64);
                context.setShaderColor(1f, 1f, 1f, 1f);
            }
        }
    }

    /** A glint sweeping over the gilded frame. */
    private static void frameShine(DrawContext context, Show one, double t) {
        if (fx <= 0) return;
        double period = one.finPlayed ? 22 : 50, phase = ((t + 20) % period) / period;
        int gx = (int) (-40 + phase * (W + 80));
        context.setShaderColor(1f, 0.95f, 0.8f, 0.35f * fx);
        for (int band = 0; band < 2; band++) {
            int bx = gx - band * 6;
            if (bx < -32 || bx > W) continue;
            // Only over the frame's band at the top and bottom, not across the reels.
            context.drawTexture(SHINE, bx, 24, 32, 12, 0, 0, 32, 64, 32, 64);
            context.drawTexture(SHINE, bx, H - 12, 32, 12, 0, 0, 32, 64, 32, 64);
        }
        context.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** Lights running round the frame: as fast as the reels, all blinking at the final, soft blue for a pardon. */
    private static void chaseLights(DrawContext context, Show one, double t) {
        double travel = t * 0.3;
        for (int reel = 0; reel < one.timing.reels(); reel++) travel += Math.max(0, one.position(reel, Math.min(t, one.timing.stops()[reel])) - one.position(reel, 0)) * 0.25;
        boolean won = t >= one.timing.fin() && !one.stopped(t), pardoned = one.pardoned(t);
        int color = pardoned ? 0xB8E8FF : one.ban() ? 0xFF2020 : one.doom() ? 0xFFE060 : one.spin.severity() <= 2 ? 0xE8F0FF : 0xFFD878;
        for (int i = 0; i < BULBS.length; i++) {
            boolean on;
            if (pardoned) on = true;
            else if (won) on = ((int) ((t - one.timing.fin()) / 3) + (one.doom() ? i : 0)) % 2 == 0;
            else on = Math.floorMod(i - (int) Math.floor(travel), 4) < 2;
            int bx = BULBS[i][0], by = BULBS[i][1];
            if (on) {
                if (fx > 0) {
                    color(context, color, 0.75f * fx);
                    context.drawTexture(GLOW, bx - 5, by - 5, 10, 10, 0, 0, 64, 64, 64, 64);
                    context.setShaderColor(1f, 1f, 1f, 1f);
                }
                context.fill(bx - 1, by - 1, bx + 1, by + 1, 0xFF000000 | brighten(color));
            } else context.fill(bx - 1, by - 1, bx + 1, by + 1, 0xFF3A2A18);
        }
    }

    private static int brighten(int rgb) {
        int r = Math.min(255, (rgb >> 16 & 255) + 40), g = Math.min(255, (rgb >> 8 & 255) + 40), b = Math.min(255, (rgb & 255) + 40);
        return r << 16 | g << 8 | b;
    }

    private static int[][] bulbs() {
        List<int[]> out = new ArrayList<>();
        for (int x = 8; x <= W - 9; x += 8) out.add(new int[] { x, 28 });
        for (int y = 36; y <= H - 12; y += 8) out.add(new int[] { W - 5, y });
        for (int x = W - 9; x >= 8; x -= 8) out.add(new int[] { x, H - 5 });
        for (int y = H - 12; y >= 36; y -= 8) out.add(new int[] { 4, y });
        return out.toArray(new int[0][]);
    }

    /** The fury scale: lamps light from the bottom while the reels spin, up to the punishment's weight. */
    private static void lamps(DrawContext context, Show one, double t, int severity) {
        int level = Math.min(5, severity);
        double from = one.timing.pull() + one.timing.spinUp(), to = one.timing.stops()[2];
        int lit = t <= from ? 0 : (int) Math.min(level, Math.floor((t - from) / Math.max(1, to - from) * level) + 1);
        for (int j = 0; j < 5; j++) {
            int y = 86 - j * 12;
            boolean on = j < lit;
            if (on && fx > 0) {
                float flicker = 0.75f + 0.25f * (float) Math.sin(t * 1.3 + j * 2.1);
                color(context, severity >= 5 ? 0xFF5020 : 0xFFB040, 0.8f * flicker * fx);
                context.drawTexture(GLOW, 19 - 12, y + 3 - 12, 24, 24, 0, 0, 64, 64, 64, 64);
                context.setShaderColor(1f, 1f, 1f, 1f);
            }
            context.drawTexture(LAMPS, 12, y, on ? 14 : 0, 0, 14, 12, 28, 12);
        }
    }

    /** The lever on the right, pulled by a golden hand from a cloud at the start; it springs back with a bounce. */
    private static void leverAndHand(DrawContext context, Show one, double t) {
        boolean down = t >= 4 && t < 11;
        double bounce = t >= 11 && t < 17 ? Math.sin((t - 11) * 1.6) * 2.5 * (1 - (t - 11) / 6) : 0;
        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(0, bounce, 0);
        context.drawTexture(LEVER, 145, 15, down ? 24 : 0, 0, 24, 80, 48, 80);
        matrices.pop();
        if (t >= 16) return;
        // The knob: up at (155, 29), down at (166, 49).
        double kx, ky, alpha = 1;
        if (t < 3) { double u = easeOutBack(t / 3, 1.4); kx = 155; ky = 29 - (1 - u) * 34; }
        else if (t < 8) { double u = Math.min(1, (t - 3) / 2.5); kx = 155 + u * 11; ky = 29 + u * 20; }
        else { double u = (t - 8) / 8; kx = 166; ky = 49 - u * 40; alpha = 1 - u; }
        if (fx > 0) {
            color(context, 0xFFE8A0, (float) (0.6 * alpha * fx));
            context.drawTexture(GLOW, (int) kx - 18, (int) ky - 30, 36, 36, 0, 0, 64, 64, 64, 64);
        }
        context.setShaderColor(1f, 1f, 1f, (float) MathHelper.clamp(alpha, 0, 1));
        matrices.push();
        matrices.translate(kx - 16, ky - 26, 0);
        context.drawTexture(HAND, 0, 0, 0, 0, 32, 32, 32, 32);
        matrices.pop();
        context.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** Under the machine: whose punishment it is (for the others) and, after the final, the ribbon with its name lighting up
     * letter by letter and the reason under it. */
    private static void ribbon(DrawContext context, TextRenderer text, Show one, double t) {
        int y = H + 2;
        if (!one.own) {
            context.drawCenteredTextWithShadow(text, "Кара для " + one.spin.name(), W / 2, y, 0xFFE0D0B0);
            y += 11;
        }
        boolean pardoned = one.pardoned(t);
        double since = pardoned ? t - one.cancelledTick() : t - one.timing.fin();
        if (since < 0 || one.stopped(t) && !pardoned) return;
        double open = easeOutBack(Math.min(1, since / 7.0), 2.2);
        int half = (int) (82 * open);
        if (half < 4) return;
        int border = pardoned ? 0xFFB8E8FF : one.ban() ? 0xFF8A1018 : 0xFFE6B840;
        int fill = pardoned ? 0xEE1A3A5A : one.ban() ? 0xEE0C0606 : one.doom() ? 0xEE6A0A04 : 0xEE4A0A0C;
        int cx = W / 2, top = y, bottom = y + 26;
        for (int i = 0; i < 6; i++) {
            context.fill(cx - half - 8 + i, top + 3 + i, cx - half, bottom - 3 - i, border);
            context.fill(cx + half, top + 3 + i, cx + half + 8 - i, bottom - 3 - i, border);
        }
        context.fill(cx - half, top, cx + half, bottom, border);
        context.fill(cx - half + 1, top + 1, cx + half - 1, bottom - 1, fill);
        if (since < 5) return;
        String title = fit(text, pardoned ? "Помилован" : one.spin.title(), 150);
        String reason = pardoned ? "покаяние снимает кару" : one.spin.reason();
        // The letters light up one after another, then a shimmer runs through them.
        int width = text.getWidth(title), x = cx - width / 2;
        int base = pardoned ? 0xE8F8FF : one.doom() ? 0xFFD84A : one.ban() ? 0xFF6060 : 0xFFE8B0;
        double lit = (since - 5) * 2.2;
        int index = 0;
        for (int i = 0; i < title.length(); ) {
            int cp = title.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            double glow = fx > 0 ? Math.max(0, Math.sin(index * 0.5 - since * 0.45)) : 0;
            int color = index < lit ? mix(base, 0xFFFFFF, (float) (glow * 0.8)) : 0x6A5A40;
            context.drawText(text, ch, x, top + 4, 0xFF000000 | color, true);
            x += text.getWidth(ch);
            i += Character.charCount(cp);
            index++;
        }
        if (reason != null && !reason.isBlank() && lit > title.length()) {
            var matrices = context.getMatrices();
            matrices.push();
            matrices.translate(cx, top + 16, 0);
            matrices.scale(0.7f, 0.7f, 1);
            context.drawCenteredTextWithShadow(text, fit(text, reason, 220), 0, 0, 0xFFD8C8B0);
            matrices.pop();
        }
    }

    /** The big words slamming onto the screen at the final: «КАРА!», «СТРАШНЫЙ СУД», «ИЗГНАНИЕ», «ПОМИЛОВАН». */
    private static void bigWords(DrawContext context, TextRenderer text, Show one, double t, int sw, double below, float base) {
        if (fx <= 0) return;
        boolean pardoned = one.pardoned(t);
        double since = pardoned ? t - one.cancelledTick() : t - one.timing.fin();
        if (since < 1 || one.stopped(t) && !pardoned) return;
        since -= 1;
        double hold = pardoned ? PARDON_HOLD - 8 : one.timing.act() - one.timing.fin() + 4;
        if (since > hold) return;
        String words = pardoned ? "ПОМИЛОВАН" : one.doom() ? "СТРАШНЫЙ СУД" : one.ban() ? "ИЗГНАНИЕ" : "КАРА!";
        int color = pardoned ? 0xD8F4FF : one.doom() ? 0xFFD040 : one.ban() ? 0xE02020 : 0xFF7040;
        double slam = since < 6 ? 1 + 2.4 * Math.pow(1 - since / 6, 3) : 1 + 0.05 * Math.sin((since - 6) * 0.5);
        float alpha = (float) (Math.min(1, since / 3) * Math.min(1, (hold - since) / 6));
        float size = (float) (base * 1.9 * slam);
        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(sw / 2.0, below + 10 * base, 0);
        matrices.scale(size, size, 1);
        int w = text.getWidth(words);
        int a = (int) (alpha * 255) << 24;
        if (a == 0) { matrices.pop(); return; }
        // A dark outline, then the words; the colour flickers to white like a casino sign.
        int outline = a | (one.ban() ? 0x000000 : 0x2A0A00);
        for (int[] d : new int[][] { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 }, { 1, 1 } }) context.drawText(text, words, -w / 2 + d[0], -4 + d[1], outline, false);
        float flicker = (float) Math.max(0, Math.sin(since * 0.9));
        context.drawText(text, words, -w / 2, -4, a | mix(color, 0xFFFFFF, flicker * 0.5f), false);
        matrices.pop();
    }

    /** The golden flash of 777 over the whole screen. */
    private static void flash(DrawContext context, Show one, double t, int sw, int sh) {
        if (!one.doom() || !one.own || !jackpotFx || one.cancelledAt >= 0) return;
        double x = t - one.timing.fin();
        if (x < 0 || x > 18) return;
        int alpha = (int) (170 * (1 - x / 18) * (1 - x / 18));
        context.fill(0, 0, sw, sh, alpha << 24 | 0xFFC830);
    }

    /** A tail's spin: one reel in a small gilded box under the top edge, for two seconds. */
    private static void drawMini(DrawContext context, TextRenderer text, Show one, double t, int sw) {
        float scale = MathHelper.clamp(sw * 0.09f / 48f, 0.6f, 2.2f);
        double in = easeOutBack(Math.min(1, t / 8.0), 2.0);
        double leave = t > one.leave() ? Math.min(1, (t - one.leave()) / Roulette.Timing.EXIT) : 0;
        double y0 = 4 + (in - 1) * 70 * scale - leave * leave * 80 * scale, x0 = (sw - 48 * scale) / 2.0;
        lastX = x0; lastY = y0; lastScale = scale;
        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(x0, y0, 0);
        matrices.scale(scale, scale, 1);
        context.fill(0, 0, 48, 48, 0xFF60400E);
        context.fill(1, 1, 47, 47, 0xFFD6AA3C);
        context.fill(4, 4, 44, 44, 0xFF60400E);
        context.fillGradient(6, 6, 42, 42, 0xFFFBF3DC, 0xFFE6D6AE);
        // Chase lights round the little box.
        for (int i = 0; i < 12; i++) {
            boolean on = Math.floorMod(i - (int) (t * 0.8), 3) == 0 || t >= one.timing.fin() && ((int) t / 3) % 2 == 0;
            int bx = i < 6 ? 4 + i * 8 : 44 - (i - 6) * 8, by = i < 6 ? 2 : 46;
            context.fill(bx - 1, by - 1, bx + 1, by + 1, on ? 0xFFFFF0B0 : 0xFF5A3A10);
        }
        matrices.pop();
        context.enableScissor((int) Math.floor(x0 + 6 * scale), (int) Math.floor(y0 + 6 * scale), (int) Math.ceil(x0 + 42 * scale), (int) Math.ceil(y0 + 42 * scale));
        matrices.push();
        matrices.translate(x0, y0, 0);
        matrices.scale(scale, scale, 1);
        drawReelMini(context, one, t);
        matrices.pop();
        context.disableScissor();
        if (t >= one.timing.fin()) {
            matrices.push();
            matrices.translate(x0, y0, 0);
            matrices.scale(scale, scale, 1);
            String line = (one.own ? "" : one.spin.name() + ": ") + one.spin.title();
            context.drawCenteredTextWithShadow(text, line, 24, 51, 0xFFFFE0A0);
            matrices.pop();
        }
    }

    private static void drawReelMini(DrawContext context, Show one, double t) {
        double p = one.position(0, t), speed = one.stopped(t) ? 0 : one.speed(0, t);
        List<RouletteSymbol> strip = one.strips.get(0);
        var matrices = context.getMatrices();
        int base = (int) Math.floor(p);
        for (int k = base - 2; k <= base + 2; k++) {
            RouletteSymbol symbol = one.pardoned(t) ? RouletteSymbol.DOVE : strip.get(Math.floorMod(k, strip.size()));
            matrices.push();
            matrices.translate(24, 24 + (p - k) * CELL, 0);
            drawSymbol(context, symbol, 0, 0, speed > 0.25 ? (float) (1 + Math.min(1.4, (speed - 0.25) * 1.5)) : 1);
            matrices.pop();
        }
        double since = t - one.timing.stops()[0];
        if (since >= 0 && since < 7) context.fill(6, 6, 42, 42, (int) ((1 - since / 7) * 200) << 24 | 0xFFFFFF);
        context.fillGradient(6, 6, 42, 16, 0xD0180C06, 0x00180C06);
        context.fillGradient(6, 32, 42, 42, 0x00180C06, 0xD0180C06);
    }

    private static double easeOutBack(double u, double c1) {
        u = MathHelper.clamp(u, 0, 1);
        double c3 = c1 + 1;
        return 1 + c3 * Math.pow(u - 1, 3) + c1 * Math.pow(u - 1, 2);
    }
    private static double smooth(double u) { u = MathHelper.clamp(u, 0, 1); return u * u * (3 - 2 * u); }
    private static int mix(int a, int b, float k) {
        k = MathHelper.clamp(k, 0, 1);
        int r = (int) MathHelper.lerp(k, a >> 16 & 255, b >> 16 & 255), g = (int) MathHelper.lerp(k, a >> 8 & 255, b >> 8 & 255), bl = (int) MathHelper.lerp(k, a & 255, b & 255);
        return r << 16 | g << 8 | bl;
    }

    private static String fit(TextRenderer text, String line, int width) {
        if (text.getWidth(line) <= width) return line;
        return text.trimToWidth(line, width - text.getWidth("…")) + "…";
    }

    private static Identifier id(String name) { return Identifier.of("divineblock", "textures/gui/roulette/" + name + ".png"); }
    private static SoundEvent sound(String name) { return SoundEvent.of(Identifier.of("divineblock", "roulette." + name)); }

    /** For the self-test: whether a spin is on this screen now. */
    public static boolean showing() { return !SHOWS.isEmpty(); }
}
