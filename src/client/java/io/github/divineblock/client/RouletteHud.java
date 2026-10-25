package io.github.divineblock.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.client.fx.FxHud;
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
import net.minecraft.util.math.Vec3d;

/** The punishment roulette on the screen ({@link Roulette}): a kiot in the manner of an iconostasis, made a slot machine of
 * the Last Judgement. Three onion domes with eight-pointed crosses crown it, the Eye of God shines on the central drum,
 * candles burn on its top edge and lampadas glow down its sides; it comes down on a spring, the hand of God pulls the lever
 * and the royal doors open on the reels. The reels turn as drums, their symbols in klejma on gold leaf; they blur, flash
 * and bounce as they stop, each with a stroke of a bell, a halo and sparks; the last one is awaited with its window
 * burning and a heartbeat. At the final, light streams from the Eye of God, the line runs with light, the bells ring out,
 * golden stars, petals and feathers fly, incense rises from the pedestal, the words of judgement come down in Church
 * Slavonic and a banner names the punishment; the psalm of repentance runs along the pedestal. 777 is the seven bowls of
 * wrath (Rev 16) with the choir; the ban burns black and crimson; a pardon jams the reels into doves to «Господи,
 * помилуй». The light is the mod's own effects atlas ({@link FxHud}); the world round the player answers the final through
 * the same engine. Timed from the server's start tick and drawn from its seed, so every screen shows the same spin. How
 * much show is the player's own «Интенсивность эффектов»; hidden with the HUD (F1) and by «Показывать у меня». */
public final class RouletteHud {
    private static final Identifier SYMBOLS = id("symbols"), LEVER = id("lever"), HAND = id("hand"), LAMPS = id("lamps"), DOORS = id("doors"),
            BANNER = id("banner");
    private static final Identifier[] FRAMES = { id("frame_silver"), id("frame_gold"), id("frame_hot"), id("frame_black") };
    /** The machine in its own units, one texel each (Minecraft pixel art): the body is 176x112 from y 0, the crest rises 16
     * above it and the pedestal ends at 126. It is drawn on a whole number of screen pixels per texel and moves by whole
     * texels; only the light and particles over it are smooth. */
    private static final int W = 176, H = 112, TOP = -16, BOTTOM = 126, WIN_L = 34, WIN_T = 38, WIN_B = 98, WIN_R = 142, REEL = 34, REEL_STEP = 37,
            LINE = 68, CELL = 36, SYMBOL_TEX = 32;
    /** The radius of the reels' drums: a cell turns by CELL / RADIUS radians. */
    private static final double RADIUS = 34;
    /** Where the light of the final comes from: the Eye of God on the central drum. */
    private static final float EYE_X = 88, EYE_Y = 19;
    /** How long a pardoned machine stays with its doves, in ticks. */
    private static final int PARDON_HOLD = 60;
    private static final SoundEvent S_LEVER = sound("lever"), S_TICK = sound("tick"), S_THUD = sound("thud"), S_BELL = sound("bell"),
            S_BIG_BELL = sound("big_bell"), S_CHIME = sound("chime"), S_CHOIR = sound("choir"), S_THUNDER = sound("thunder"),
            S_TREZVON = sound("trezvon"), S_BLAGOVEST = sound("blagovest"), S_CHOIR_DOOM = sound("choir_doom"), S_POMILUY = sound("pomiluy"),
            S_RISER = sound("riser"), S_HEART = sound("heartbeat"), S_WHOOSH = sound("whoosh");
    private static final float[] NOTES = { 0.75f, 1.0f, 1.26f };
    private static final float[] FX = { 0f, 0.35f, 0.7f, 1f };
    /** Petals: gold, crimson, ivory, rose, pale gold. */
    private static final int[] PETALS = { 0xE8B84A, 0xB0202C, 0xF4EEE0, 0xE8A0A8, 0xFFD978 };
    /** The psalm of repentance along the pedestal (Ps 6:2–3, Church Slavonic). */
    private static final String PSALM = "Господи, да не яростию Твоею обличиши мене, ниже гневом Твоим накажеши мене. Помилуй мя, Господи, яко немощен есмь.   ✠   ";
    /** The lights that run round the kiot: the six candles on its top edge, then the lampada jewels down its sides. */
    private static final float[][] LIGHTS = lights();
    private static final int CANDLES = 6;

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
        int stopsPlayed, lastBeat = -100;
        double[] last;

        Show(RouletteSpinPayload spin, boolean own) {
            this.spin = spin; this.own = own;
            Roulette.Mode mode = Roulette.Mode.values()[MathHelper.clamp(spin.mode(), 0, Roulette.Mode.values().length - 1)];
            timing = Roulette.Timing.of(mode, spin.severity());
            symbol = RouletteSymbol.values()[MathHelper.clamp(spin.symbol(), 0, RouletteSymbol.values().length - 1)];
            landing = new int[timing.reels()];
            last = new double[timing.reels()];
            for (int reel = 0; reel < timing.reels(); reel++) {
                boolean lastReel = reel == timing.reels() - 1;
                List<RouletteSymbol> strip = Roulette.strip(spin.seed(), reel, symbol, spin.tease() && lastReel, spin.mercy() && lastReel);
                strips.add(strip);
                landing[reel] = Math.max(0, strip.indexOf(Roulette.landing(symbol, reel, timing.reels(), spin.mercy())));
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
        boolean won(double t) { return t >= timing.fin() && !stopped(t) && !spin.mercy(); }
        /** Spared at the final by the third reel's dove. */
        boolean spared(double t) { return spin.mercy() && t >= timing.fin() && !stopped(t); }
        /** Mercy in either way, by repentance or by chance: the light, the banner and the words turn to grace. */
        boolean graced(double t) { return pardoned(t) || spared(t); }
        /** Ticks since grace came. */
        double sinceGrace(double t) { return pardoned(t) ? t - cancelledTick() : t - timing.fin(); }
        /** When it is gone from the screen. */
        double over() { return cancelledAt < 0 ? timing.end() : cancelledTick() + (pardon ? PARDON_HOLD + Roulette.Timing.EXIT : Roulette.Timing.EXIT); }
        /** The moment it starts to leave. */
        double leave() { return cancelledAt >= 0 ? cancelledTick() + (pardon ? PARDON_HOLD : 0) : timing.act(); }
        /** Between the second reel's stop and the last one's: everything waits for it. */
        boolean anticipating(double t) { return !mini() && !stopped(t) && t >= timing.stops()[1] && t < timing.stops()[2]; }
        double tension(double t) { return anticipating(t) ? smooth((t - timing.stops()[1]) / Math.max(1, timing.stops()[2] - timing.stops()[1])) : 0; }
        /** Where reel {@code reel} stands at {@code t}: as the server laid it, or jammed where the pardon caught it. */
        double position(int reel, double t) {
            if (jammed != null && t >= cancelledTick()) {
                double x = t - cancelledTick();
                return jammed[reel] + (x < 5 ? Math.sin(x * 2.7 + reel) * 0.08 * (5 - x) / 5 : 0);
            }
            // The teasing reel and the sparing one crawl their last cells: past the seven, or up to the match and onto the dove.
            return Roulette.position(timing, reel, (spin.tease() || spin.mercy()) && reel == timing.reels() - 1, landing[reel], t);
        }
        double speed(int reel, double t) { return Math.abs(position(reel, t + 0.5) - position(reel, Math.max(0, t - 0.5))); }
        /** The colours of this spin's light: the main one, the deep one and the bright one. */
        int[] palette(double t) {
            if (graced(t)) return new int[] { 0xBFE0FF, 0x7FA8E0, 0xFFFFFF };
            if (doom()) return new int[] { 0xFFC040, 0xD04018, 0xFFF0B0 };
            if (ban()) return new int[] { 0xC0101C, 0x500010, 0xFF5040 };
            return switch (Math.min(5, spin.severity())) {
                case 1, 2 -> new int[] { 0xC8D8F0, 0x8098C8, 0xFFF8E8 };
                case 3, 4 -> new int[] { 0xFFC048, 0xC06820, 0xFFEBB0 };
                default -> new int[] { 0xFF8030, 0xC02020, 0xFFD878 };
            };
        }
    }

    /** A spark, a petal, a feather, an ember, a star, a halo, a wisp of incense or a big seven, in screen pixels. */
    private static final class Particle {
        static final int SPARK = 0, PETAL = 1, FEATHER = 2, EMBER = 3, SEVEN = 4, STAR = 6, HALO = 7, SMOKE = 8;
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
                    play(one, S_POMILUY, 1f, 1f);
                    play(one, S_CHIME, 1f, 0.6f);
                    if (shown(one)) {
                        for (int i = 0; i < 60 * fx; i++) feather(lastWidth * RANDOM.nextDouble(), -10 - RANDOM.nextDouble() * 80);
                        halo(machine(W / 2.0, LINE), 0xD0F0FF, 3);
                        for (int i = 0; i < 30 * fx; i++) star(machine(RANDOM.nextDouble() * W, TOP + RANDOM.nextDouble() * (BOTTOM - TOP)), 0xE8F4FF);
                    }
                    world(one, 2);
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

    /** Whether a roulette is likely to follow the god's word: a punishment is being announced (the beams of wrath are up). */
    public static boolean expected() { return AltarBeams.wrath(); }

    /** Whether a roulette is on (the altar's chants sink under it). */
    public static boolean spinning() {
        MinecraftClient client = MinecraftClient.getInstance();
        for (Show one : SHOWS) if (one.at(client, 0) < one.leave() + 20) return true;
        return false;
    }

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
            if (one.stopped(t)) {
                // Incense goes on rising round a pardon.
                if (one.pardon && shown(one) && RANDOM.nextFloat() < 0.6f * fx) incense(one);
                continue;
            }
            boolean drawn = shown(one);
            int[] palette = one.palette(t);
            if (!one.leverPlayed) {
                one.leverPlayed = true;
                if (!one.mini()) play(one, S_LEVER, 1f, 0.8f);
                moment.accept("start");
            }
            if (!one.mini() && drawn && t >= 5 && t < 6) burst(machine(157, 30), (int) (14 * fx), 0xFFE070, 2.2, false);
            // The ratchet: a click for each cell that passed the line, so it slows down with the reels.
            for (int reel = 0; reel < one.timing.reels(); reel++) {
                if (t >= one.timing.stops()[reel]) continue;
                double p = one.position(reel, t);
                if (!Double.isNaN(one.last[reel]) && Math.floor(p + 0.5) != Math.floor(one.last[reel] + 0.5) && !ticked) {
                    ticked = true;
                    play(one, S_TICK, (one.anticipating(t) ? 1.15f : 0.9f) + RANDOM.nextFloat() * 0.2f, 0.3f);
                }
                one.last[reel] = p;
            }
            while (one.stopsPlayed < one.timing.reels() && t >= one.timing.stops()[one.stopsPlayed]) {
                int reel = one.stopsPlayed++;
                // Each reel stops with a stroke of a bell, rising: low, middle, high.
                play(one, S_THUD, 1f, 0.7f);
                play(one, S_BELL, one.mini() ? 1f : NOTES[Math.min(reel, NOTES.length - 1)], 0.6f);
                if (drawn) {
                    double rx = one.mini() ? 24 : WIN_L + reel * REEL_STEP + REEL / 2.0;
                    int sparks = (int) (18 * fx);
                    burst(machine(rx, one.mini() ? 6 : WIN_T), sparks, palette[0], 2.6, false);
                    burst(machine(rx, one.mini() ? 42 : WIN_B), sparks, palette[2], 2.6, false);
                    halo(machine(rx, one.mini() ? 24 : LINE), palette[0], 1);
                }
                moment.accept("stop" + (reel + 1));
            }
            // Waiting for the last reel: a rising tone and a heartbeat; stars gather round the third reel.
            if (one.anticipating(t)) {
                if (!one.riserPlayed) { one.riserPlayed = true; play(one, S_RISER, 1f, 0.35f); }
                int beat = (int) ((t - one.timing.stops()[1]) / 11);
                if (beat != one.lastBeat) { one.lastBeat = beat; play(one, S_HEART, 1f, 0.9f); }
                if (drawn && RANDOM.nextFloat() < 0.7f * fx) star(machine(WIN_L + 2 * REEL_STEP + RANDOM.nextDouble() * REEL, WIN_T + RANDOM.nextDouble() * (WIN_B - WIN_T)), palette[2]);
            }
            if (!one.finPlayed && t >= one.timing.fin()) {
                one.finPlayed = true;
                finale(one, drawn, palette);
            }
            if (drawn && one.finPlayed && t < one.leave()) {
                double since = t - one.timing.fin();
                // Golden stars keep falling on 777; the ban's frame smoulders; petals drift down; incense rises.
                if (one.doom() && since < 40) for (int i = 0; i < 4 * fx; i++) fallingStar(RANDOM.nextDouble() * lastWidth, -12);
                if (one.ban() && RANDOM.nextFloat() < fx) ember(machine(RANDOM.nextDouble() * W, H));
                if (!one.ban() && !one.mini() && since < 26) for (int i = 0; i < 2 * fx; i++) petal(RANDOM.nextDouble() * lastWidth, -8);
                if (!one.mini() && RANDOM.nextFloat() < 0.7f * fx) incense(one);
                if (RANDOM.nextFloat() < 0.5f * fx) star(machine(RANDOM.nextDouble() * W, TOP + RANDOM.nextDouble() * (BOTTOM - TOP)), palette[2]);
            }
            if (drawn && !one.mini() && one.spin.severity() >= 5 && RANDOM.nextFloat() < 0.4f * fx) ember(machine(RANDOM.nextDouble() * W, 26 + RANDOM.nextDouble() * 80));
            // Glitter: stars twinkling on and round the gilding all the while it spins.
            if (drawn && !one.mini()) for (int i = 0; i < 2; i++) if (RANDOM.nextFloat() < 0.6f * fx) star(machine(-6 + RANDOM.nextDouble() * (W + 12), TOP + RANDOM.nextDouble() * (BOTTOM - TOP)), RANDOM.nextBoolean() ? palette[2] : 0xFFFFFF);
        }
    }

    /** The final: the great bell and the bells ringing out, thunder; on 777 the choir and a long thunder, gold and a shaking
     * camera; the ban: the great bell low, thunder and a heartbeat. */
    private static void finale(Show one, boolean drawn, int[] palette) {
        if (one.mini()) {
            play(one, S_BIG_BELL, 1.3f, 0.35f);
            play(one, S_TREZVON, 1.1f, 0.3f);
            if (drawn) { burst(machine(24, 48), (int) (20 * fx), palette[0], 3, true); halo(machine(24, 24), palette[0], 1); }
            moment.accept("final");
            return;
        }
        play(one, S_WHOOSH, 0.8f, 0.3f);
        if (one.spin.mercy()) {
            // Spared by chance: «Господи, помилуй», a bright chime, feathers and pale light instead of the punishment.
            play(one, S_POMILUY, 1f, 1f);
            play(one, S_CHIME, 1f, 0.7f);
            if (drawn) {
                for (int i = 0; i < 60 * fx; i++) feather(lastWidth * RANDOM.nextDouble(), -10 - RANDOM.nextDouble() * 80);
                halo(machine(WIN_L + 2 * REEL_STEP + REEL / 2.0, LINE), 0xD0F0FF, 3);
                halo(machine(EYE_X, EYE_Y), 0xFFFFFF, 2);
                burst(machine(WIN_L + 2 * REEL_STEP + REEL / 2.0, LINE), (int) (40 * fx), 0xD8F0FF, 3.5, false);
                for (int i = 0; i < 30 * fx; i++) star(machine(RANDOM.nextDouble() * W, TOP + RANDOM.nextDouble() * (BOTTOM - TOP)), 0xE8F4FF);
            }
            world(one, 2);
            moment.accept("mercy");
            moment.accept("final");
            return;
        }
        if (one.doom()) {
            for (float note : new float[] { 1.0f, 1.26f, 1.5f }) play(one, S_BELL, note, 0.45f);
            play(one, S_BLAGOVEST, 1f, 1f);
            play(one, S_CHOIR_DOOM, 1f, 0.9f);
            play(one, S_TREZVON, 1f, 0.7f);
            play(one, S_THUNDER, 0.55f, 0.8f);
            if (one.own && jackpotFx) io.github.divineblock.client.fx.FxCamera.shake(0.85f, 50);
            moment.accept("777");
        } else if (one.ban()) {
            play(one, S_BLAGOVEST, 0.75f, 1f);
            play(one, S_THUNDER, 0.5f, 0.9f);
            play(one, S_HEART, 0.8f, 1f);
            moment.accept("gates");
        } else {
            play(one, S_BLAGOVEST, 1f, 0.9f);
            play(one, S_TREZVON, 1f, 0.55f);
            play(one, S_THUNDER, 0.8f, 0.5f);
        }
        if (drawn) {
            // Golden stars, sparks and petals out of the plaque under the reels; halos from the line; light from the Eye.
            double[] from = machine(W / 2.0, WIN_B + 6);
            int stars = (int) ((one.doom() ? 50 : one.ban() ? 0 : 24) * fx);
            for (int i = 0; i < stars; i++) fountainStar(from[0], from[1]);
            burst(from, (int) (40 * fx), palette[0], 4.5, true);
            burst(machine(EYE_X, EYE_Y), (int) (30 * fx), palette[2], 3.5, false);
            if (!one.ban()) for (int i = 0; i < 40 * fx; i++) petal(from[0] + (RANDOM.nextDouble() - 0.5) * 40, from[1], (RANDOM.nextDouble() - 0.5) * 7, -3 - RANDOM.nextDouble() * 5);
            halo(machine(W / 2.0, LINE), palette[0], one.doom() ? 4 : 2);
            halo(machine(EYE_X, EYE_Y), palette[2], 2);
            if (one.doom() && fx > 0) for (int reel = 0; reel < 3; reel++) seven(machine(WIN_L + reel * REEL_STEP + REEL / 2.0, LINE), reel * 3);
            if (one.ban()) for (int i = 0; i < 40 * fx; i++) ember(machine(RANDOM.nextDouble() * W, 30 + RANDOM.nextDouble() * 80));
        }
        world(one, one.doom() ? 3 : one.ban() ? 1 : 1.5f);
        moment.accept("final");
    }

    /** The world answers the final round the sinner (his own screen only): sparks, feathers on 777 and on a pardon. */
    private static void world(Show one, float amount) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!one.own || client.player == null || fx <= 0 || FxHud.quality() <= 0) return;
        Vec3d at = client.player.getPos().add(0, 1.2, 0);
        if (one.pardon || one.spin.mercy()) { FxHud.feathers(at.add(0, 2, 0), amount); return; }
        FxHud.sparks(at, one.palette(0)[0], amount);
        if (one.doom()) FxHud.feathers(at.add(0, 2.5, 0), amount);
    }

    private static void play(Show one, SoundEvent sound, float pitch, float loudness) {
        float gain = loudness * volume * (one.own ? 0.5f : 0.3f);
        if (gain <= 0.001f) return;
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(sound, MathHelper.clamp(pitch, 0.5f, 2f), Math.min(1f, gain)));
    }

    // ---- Particles. ----

    /** A point of the machine (its own units) on the screen, where it was last drawn. */
    private static double[] machine(double x, double y) { return new double[] { lastX + x * lastScale, lastY + y * lastScale }; }

    private static Particle add(int kind, double x, double y, double vx, double vy, int life) {
        if (PARTICLES.size() > 1400 || fx <= 0) return null;
        Particle p = new Particle();
        p.kind = kind; p.x = p.px = x; p.y = p.py = y; p.vx = vx; p.vy = vy; p.life = life;
        PARTICLES.add(p);
        return p;
    }

    /** Sparks with trails, a third of them white-hot, the rest in the colour and its neighbours. */
    private static void burst(double[] at, int count, int color, double force, boolean up) {
        for (int i = 0; i < count; i++) {
            double angle = up ? -Math.PI / 2 + (RANDOM.nextDouble() - 0.5) * 2.4 : RANDOM.nextDouble() * Math.PI * 2;
            double speed = force * (0.4 + RANDOM.nextDouble()) * lastScale * 0.6;
            Particle p = add(Particle.SPARK, at[0], at[1], Math.cos(angle) * speed, Math.sin(angle) * speed, 16 + RANDOM.nextInt(18));
            if (p == null) return;
            p.gravity = 0.12 * lastScale * 0.6; p.drag = 0.95;
            p.color = RANDOM.nextFloat() < 0.3f ? 0xFFFFFF : shift(color, (RANDOM.nextFloat() - 0.5f) * 0.06f);
            p.size = (0.7 + RANDOM.nextDouble() * 0.8) * lastScale;
        }
    }

    /** A golden star thrown up out of the plaque, falling back with a twinkle. */
    private static void fountainStar(double x, double y) {
        Particle p = add(Particle.STAR, x, y, (RANDOM.nextDouble() - 0.5) * 6, -4 - RANDOM.nextDouble() * 6, 50 + RANDOM.nextInt(40));
        if (p == null) return;
        p.gravity = 0.22; p.drag = 0.99; p.size = (10 + RANDOM.nextDouble() * 10) * Math.max(1, lastScale * 0.5); p.color = RANDOM.nextBoolean() ? 0xFFD24A : 0xFFF0B0;
        p.rot = RANDOM.nextDouble(); p.spin = 1;
    }

    private static void fallingStar(double x, double y) {
        Particle p = add(Particle.STAR, x, y, (RANDOM.nextDouble() - 0.5) * 1.2, 1.5 + RANDOM.nextDouble() * 2.5, 80 + RANDOM.nextInt(40));
        if (p == null) return;
        p.gravity = 0.04; p.drag = 1; p.size = (10 + RANDOM.nextDouble() * 12) * Math.max(1, lastScale * 0.5); p.color = 0xFFD860; p.spin = 1;
    }

    private static void feather(double x, double y) {
        Particle p = add(Particle.FEATHER, x, y, (RANDOM.nextDouble() - 0.5) * 0.6, 0.6 + RANDOM.nextDouble() * 0.8, 130 + RANDOM.nextInt(60));
        if (p == null) return;
        p.drag = 1; p.size = (10 + RANDOM.nextDouble() * 8) * Math.max(1, lastScale * 0.5); p.spin = (RANDOM.nextDouble() - 0.5) * 0.12; p.rot = RANDOM.nextDouble() * 6;
    }

    private static void petal(double x, double y) { petal(x, y, (RANDOM.nextDouble() - 0.5) * 1.5, 0.8 + RANDOM.nextDouble() * 1.2); }
    private static void petal(double x, double y, double vx, double vy) {
        Particle p = add(Particle.PETAL, x, y, vx, vy, 90 + RANDOM.nextInt(60));
        if (p == null) return;
        p.gravity = 0.07; p.drag = 0.97; p.size = (5 + RANDOM.nextDouble() * 4) * Math.max(1, lastScale * 0.5); p.spin = (RANDOM.nextDouble() - 0.5) * 0.4;
        p.rot = RANDOM.nextDouble() * 6; p.color = PETALS[RANDOM.nextInt(PETALS.length)];
    }

    private static void ember(double[] at) {
        Particle p = add(Particle.EMBER, at[0], at[1], (RANDOM.nextDouble() - 0.5) * 0.6, -0.4 - RANDOM.nextDouble() * 1.2, 30 + RANDOM.nextInt(30));
        if (p == null) return;
        p.gravity = -0.01; p.color = RANDOM.nextBoolean() ? 0xFF5020 : 0xFFA030; p.size = (5 + RANDOM.nextDouble() * 5) * Math.max(1, lastScale * 0.5);
    }

    private static void star(double[] at, int color) {
        Particle p = add(Particle.STAR, at[0], at[1], 0, -0.1, 12 + RANDOM.nextInt(12));
        if (p == null) return;
        p.color = color; p.drag = 1; p.size = (8 + RANDOM.nextDouble() * 10) * Math.max(1, lastScale * 0.5); p.rot = RANDOM.nextDouble();
    }

    /** Incense from the censers at the ends of the pedestal: a wisp rising and spreading. */
    private static void incense(Show one) {
        double[] at = machine(RANDOM.nextBoolean() ? 26 : 150, 112);
        Particle p = add(Particle.SMOKE, at[0] + (RANDOM.nextDouble() - 0.5) * 4 * lastScale, at[1], (RANDOM.nextDouble() - 0.5) * 0.4, -0.5 - RANDOM.nextDouble() * 0.5, 50 + RANDOM.nextInt(30));
        if (p == null) return;
        p.gravity = -0.004; p.drag = 0.99; p.size = 10 * Math.max(1, lastScale * 0.6); p.rot = RANDOM.nextDouble() * 6; p.spin = (RANDOM.nextDouble() - 0.5) * 0.03;
        p.color = one.ban() ? 0x6A5A5A : 0xE8E4DC;
    }

    /** {@code rings} halos spreading from a point, one after another. */
    private static void halo(double[] at, int color, int rings) {
        for (int i = 0; i < rings; i++) {
            Particle p = add(Particle.HALO, at[0], at[1], 0, 0, 18);
            if (p == null) return;
            p.age = -i * 4; p.color = i % 2 == 0 ? color : 0xFFFFFF; p.drag = 1; p.size = 60 * lastScale;
        }
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
            if (p.kind == Particle.FEATHER) { p.vx = Math.sin((p.age + p.rot * 30) * 0.07) * 1.1; p.rot += p.spin; }
            if (p.kind == Particle.PETAL) { p.rot += p.spin; p.vx += Math.sin((p.age + p.rot) * 0.2) * 0.05; }
            if (p.kind == Particle.SMOKE) { p.rot += p.spin; p.size *= 1.012; p.vx += Math.sin(p.age * 0.08 + p.rot) * 0.02; }
            p.vy += p.gravity; p.vx *= p.drag; p.vy *= p.drag;
            p.x += p.vx; p.y += p.vy;
            if (p.age >= p.life || p.y > lastHeight + 40) it.remove();
        }
    }

    /** Incense, feathers and petals see-through; sevens as the klejmo; sparks, embers, stars and halos as light. */
    private static void drawParticles(DrawContext context, float delta) {
        FxHud.Batch soft = FxHud.begin(context, false);
        for (Particle p : PARTICLES) {
            if (p.age < 0 || p.kind != Particle.FEATHER && p.kind != Particle.PETAL && p.kind != Particle.SMOKE) continue;
            float x = (float) MathHelper.lerp(delta, p.px, p.x), y = (float) MathHelper.lerp(delta, p.py, p.y);
            float life = (float) (p.age + delta) / p.life, fade = MathHelper.clamp((1 - life) * 3, 0, 1);
            float rot = (float) MathHelper.lerp(delta, p.prot, p.rot);
            switch (p.kind) {
                case Particle.FEATHER -> soft.sprite(FxHud.Sprite.FEATHER, x, y, (float) p.size, rot * 0.6f, 1, 0xFFFFFF, fade);
                case Particle.PETAL -> soft.sprite(FxHud.Sprite.CONFETTI, x, y, (float) p.size, rot, Math.abs(MathHelper.cos(rot * 2.3f)) * 0.6f + 0.25f, p.color, fade * 0.95f);
                default -> soft.sprite(FxHud.Sprite.SMOKE, x, y, (float) p.size * (1 + life * 2), rot, 1, p.color, 0.35f * MathHelper.sin(life * MathHelper.PI) * fx);
            }
        }
        soft.draw();
        var matrices = context.getMatrices();
        for (Particle p : PARTICLES) {
            if (p.age < 0 || p.kind != Particle.SEVEN) continue;
            double x = MathHelper.lerp(delta, p.px, p.x), y = MathHelper.lerp(delta, p.py, p.y);
            float life = (float) (p.age + delta) / p.life;
            matrices.push();
            matrices.translate(x, y, 0);
            float scale = (1.2f + 7f * life * life) * (float) lastScale;
            matrices.scale(scale, scale, 1);
            context.setShaderColor(1f, 1f, 1f, MathHelper.clamp(1.2f - life * 1.2f, 0, 1));
            symbolQuad(context, RouletteSymbol.SEVEN, -16, -16, 32, 32);
            context.setShaderColor(1f, 1f, 1f, 1f);
            matrices.pop();
        }
        FxHud.Batch light = FxHud.begin(context, true);
        for (Particle p : PARTICLES) {
            if (p.age < 0) continue;
            float x = (float) MathHelper.lerp(delta, p.px, p.x), y = (float) MathHelper.lerp(delta, p.py, p.y);
            float life = (float) (p.age + delta) / p.life, fade = MathHelper.clamp(1 - life * life, 0, 1);
            switch (p.kind) {
                case Particle.SPARK -> {
                    double speed = Math.hypot(p.vx, p.vy);
                    float angle = (float) Math.atan2(p.vy, p.vx) + MathHelper.HALF_PI;
                    float length = (float) Math.max(p.size * 1.5, speed * 3.5);
                    light.streak(FxHud.Sprite.BEAM, x - (float) (p.vx * 1.5), y - (float) (p.vy * 1.5), length, (float) p.size * 1.2f, angle, p.color, fade * 0.8f);
                    light.sprite(FxHud.Sprite.SPARK, x, y, (float) p.size * 4f, 0, 1, p.color, fade);
                }
                case Particle.EMBER -> light.sprite(FxHud.Sprite.EMBER, x, y, (float) p.size, 0, 1, p.color, fade);
                case Particle.STAR -> {
                    // A golden star of the fountain twinkles all its life; a passing star blinks once.
                    float twinkle = p.spin > 0 ? 0.65f + 0.35f * MathHelper.sin((p.age + delta) * 0.6f + (float) p.rot * 9) : MathHelper.sin(life * MathHelper.PI);
                    float alpha = p.spin > 0 ? fade : twinkle;
                    light.sprite(FxHud.Sprite.GLOW, x, y, (float) p.size * 1.1f, 0, 1, p.color, alpha * 0.45f);
                    light.sprite(FxHud.Sprite.STAR, x, y, (float) p.size * twinkle, (float) p.rot, 1, p.color, alpha);
                }
                case Particle.HALO -> light.sprite(FxHud.Sprite.HALO, x, y, (float) p.size * (0.2f + 1.6f * (1 - (1 - life) * (1 - life))), 0, 1, p.color, (1 - life) * 0.9f);
                default -> { }
            }
        }
        light.draw();
    }

    // ---- The picture. ----

    private static void render(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden || client.world == null) return;
        float delta = counter.getTickDelta(false);
        int sw = context.getScaledWindowWidth(), sh = context.getScaledWindowHeight();
        lastWidth = sw; lastHeight = sh;
        Show one = current();
        if (one != null && show) {
            double t = one.at(client, delta);
            if (t < one.over()) {
                if (one.mini()) drawMini(context, client.textRenderer, one, t, sw);
                else drawMachine(context, client.textRenderer, one, t, sw, sh);
            }
        }
        if (!PARTICLES.isEmpty()) drawParticles(context, delta);
        context.draw();
        context.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.defaultBlendFunc();
    }

    private static void drawMachine(DrawContext context, TextRenderer text, Show one, double t, int sw, int sh) {
        int severity = MathHelper.clamp(one.spin.severity(), 1, 6);
        // The kiot with its crest, pedestal and banner takes about 44% of the width and 45% of the height.
        float base = (float) MathHelper.clamp(Math.min(sw * 0.44 / W, sh * 0.45 / (BOTTOM - TOP + 32)), 0.45, 3.2);
        // Coming down on a spring, leaving upwards; a zoom while the last reel is awaited and a kick at the final.
        double in = easeOutBack(Math.min(1, t / 12.0), 1.7);
        double leave = t > one.leave() ? Math.min(1, (t - one.leave()) / Roulette.Timing.EXIT) : 0;
        double zoom = (0.85 + 0.15 * in) * (1 + 0.06 * one.tension(t));
        if (one.won(t)) zoom *= 1 + 0.06 * Math.max(0, 1 - (t - one.timing.fin()) / 10);
        // A whole number of screen pixels per texel (zooms go in steps), and the kiot moves by whole texels.
        double pixels = MinecraftClient.getInstance().getWindow().getScaleFactor();
        float scale = (float) (Math.max(1, Math.round(base * zoom * pixels)) / pixels);
        double[] shake = shake(one, t);
        double cx = sw / 2.0 + shake[0] * base;
        double top = 3 - TOP * base + (in - 1) * (BOTTOM - TOP + 40) * base - leave * leave * (BOTTOM - TOP + 80) * base + shake[1] * base;
        double x0 = cx - W * scale / 2.0, y0 = top + (H * base - H * scale) / 2.0;
        x0 = Math.round(x0 * pixels / Math.round(scale * pixels)) * Math.round(scale * pixels) / pixels;
        y0 = Math.round(y0 * pixels / Math.round(scale * pixels)) * Math.round(scale * pixels) / pixels;
        lastX = x0; lastY = y0; lastScale = scale;
        float presence = (float) (Math.min(1, t / 8) * (1 - leave));

        aura(context, one, t, x0, y0, scale, presence);
        flash(context, one, t, sw, sh);

        var matrices = context.getMatrices();
        // The reels, clipped to the window.
        matrices.push();
        matrices.translate(x0, y0, 0);
        matrices.scale(scale, scale, 1);
        boolean doves = one.pardoned(t);
        for (int reel = 0; reel < 3; reel++) {
            int x = WIN_L + reel * REEL_STEP;
            context.fillGradient(x, WIN_T, x + REEL, WIN_B, doves ? 0xFFE6EEF8 : 0xFF6A4A26, doves ? 0xFFC8D8EC : 0xFF3A2410);
        }
        matrices.pop();
        context.enableScissor((int) Math.floor(x0 + WIN_L * scale), (int) Math.floor(y0 + WIN_T * scale), (int) Math.ceil(x0 + WIN_R * scale), (int) Math.ceil(y0 + WIN_B * scale));
        matrices.push();
        matrices.translate(x0, y0, 0);
        matrices.scale(scale, scale, 1);
        for (int reel = 0; reel < 3; reel++) drawReel(context, one, reel, t, WIN_L + reel * REEL_STEP, LINE);
        glass(context, one, t);
        doors(context, t);
        matrices.pop();
        context.disableScissor();

        matrices.push();
        matrices.translate(x0, y0, 0);
        matrices.scale(scale, scale, 1);
        Identifier frame = FRAMES[severity <= 2 ? 0 : severity <= 4 ? 1 : severity == 5 ? 2 : 3];
        tex(context, frame, 0, TOP, W, BOTTOM - TOP, 0, 0, W, BOTTOM - TOP, W, BOTTOM - TOP);
        lamps(context, one, t, severity);
        leverAndHand(context, one, t);
        frameLight(context, one, t);
        inscriptions(context, text, one, t, x0, y0, scale);
        banner(context, text, one, t);
        matrices.pop();
        bigWords(context, text, one, t, sw, y0 + (BOTTOM + 40) * scale, base);
    }

    /** Behind the kiot: a warm glow drifting round it, and god rays from the Eye of God on its crest, turning slowly — faint
     * while it spins, brighter while the last reel is awaited, streaming at the final. Added light: nothing darkens. */
    private static void aura(DrawContext context, Show one, double t, double x0, double y0, float scale, float presence) {
        if (fx <= 0 || presence <= 0.01f || one.stopped(t) && !one.pardon) return;
        int[] palette = one.palette(t);
        float cx = (float) (x0 + W / 2.0 * scale), cy = (float) (y0 + LINE * scale);
        float ex = (float) (x0 + EYE_X * scale), ey = (float) (y0 + EYE_Y * scale);
        double since = one.won(t) || one.graced(t) ? one.sinceGrace(t) : -1;
        float power = (float) (0.3 + 0.3 * one.tension(t) + (since >= 0 ? 0.7 * Math.max(0.5, 1 - since / 30) + 0.4 * Math.max(0, 1 - since / 6) : 0)) * presence * fx;
        FxHud.Batch light = FxHud.begin(context, true);
        // The glow round the kiot: big soft lights in the spin's colours, circling slowly and shading into each other.
        for (int i = 0; i < 6; i++) {
            double a = t * 0.02 * (i % 2 == 0 ? 1 : -1.3) + i * Math.PI / 3;
            float ox = (float) Math.cos(a) * W * 0.34f * scale, oy = (float) Math.sin(a * 1.3) * H * 0.26f * scale;
            int color = mix(palette[i % 3], palette[(i + 1) % 3], 0.5f + 0.5f * MathHelper.sin((float) (t * 0.05 + i)));
            light.sprite(FxHud.Sprite.GLOW, cx + ox, cy + oy, W * 0.95f * scale, 0, 1, color, 0.32f * power);
        }
        // God rays from the Eye: two wheels turning against each other, long and short beams in shades of the light.
        int rays = 20;
        float reach = W * (0.85f + (since >= 0 ? 0.45f * (float) Math.min(1, since / 6) : 0.15f * (float) one.tension(t))) * scale;
        for (int wheel = 0; wheel < 2; wheel++) {
            float turn = (float) (t * (since >= 0 ? 0.012 : 0.005) * (wheel == 0 ? 1 : -0.7)) + wheel * 0.16f;
            for (int i = 0; i < rays; i++) {
                float angle = turn + i * MathHelper.TAU / rays;
                float pulse = 0.55f + 0.45f * MathHelper.sin((float) (t * 0.1 + i * 1.7 + wheel));
                float length = reach * (i % 2 == 0 ? 1f : 0.62f) * (wheel == 0 ? 1 : 0.8f);
                int color = mix(palette[0], palette[2], 0.5f + 0.5f * MathHelper.sin((float) (t * 0.04 + i * 0.9)));
                float dx = MathHelper.cos(angle), dy = MathHelper.sin(angle);
                light.streak(FxHud.Sprite.BEAM, ex + dx * length / 2, ey + dy * length / 2, length / 2, 8 * scale * (i % 2 == 0 ? 1 : 0.6f),
                        angle + MathHelper.HALF_PI, color, 0.3f * power * pulse);
            }
        }
        light.sprite(FxHud.Sprite.GLOW, ex, ey, 46 * scale, 0, 1, palette[2], 0.6f * power);
        // The Eye's halo breathes, and a star turns in it.
        float breath = 0.6f + 0.4f * MathHelper.sin((float) (t * 0.25));
        light.sprite(FxHud.Sprite.HALO, ex, ey, (26 + 8 * breath) * scale, 0, 1, palette[2], 0.5f * breath * power);
        light.sprite(FxHud.Sprite.STAR, ex, ey, 30 * breath * scale, (float) (t * 0.03), 1, 0xFFFFFF, 0.6f * breath * power);
        if (since >= 0) {
            float burst = (float) Math.max(0, 1 - since / 14);
            light.sprite(FxHud.Sprite.FLARE, ex, ey, W * (0.4f + 0.6f * burst) * scale, (float) (t * 0.02), 1, palette[2], (0.45f + 0.55f * burst) * presence * fx);
            light.sprite(FxHud.Sprite.FLARE, cx, cy, W * (0.3f + 0.7f * burst) * scale, (float) (-t * 0.02), 1, palette[0], 0.6f * burst * presence * fx);
            light.streak(FxHud.Sprite.BEAM, cx, cy, W * 1.1f * scale, 4 * scale, MathHelper.HALF_PI, palette[2], 0.5f * burst * presence * fx);
        }
        light.draw();
    }

    /** The kiot jolts: when the lever is pulled, at each stop, at the final; it trembles while the last reel is awaited. */
    private static double[] shake(Show one, double t) {
        double x = 0, y = 0;
        double[][] kicks = one.mini() ? new double[0][] : new double[][] { { 5, 1.0 }, { one.timing.stops()[0], 1.5 }, { one.timing.stops()[1], 1.7 },
                { one.timing.stops()[2], 2.6 }, { one.timing.fin(), one.doom() ? 4.5 : one.ban() ? 3.5 : 2.2 } };
        for (double[] kick : kicks) {
            double since = t - kick[0];
            if (since < 0 || since > 14) continue;
            double amp = kick[1] * Math.exp(-since / 3.5) * (0.4 + 0.6 * fx);
            x += amp * Math.sin(since * 2.9 + kick[0]);
            y += amp * 0.7 * Math.cos(since * 3.7 + kick[0]);
        }
        if (one.anticipating(t)) { x += Math.sin(t * 7.1) * 0.35 * fx * one.tension(t); y += Math.cos(t * 6.3) * 0.25 * fx * one.tension(t); }
        return new double[] { x, y };
    }

    /** One reel as a turning drum: its cells round the line, squeezed and darkened as they turn away. */
    private static void drawReel(DrawContext context, Show one, int reel, double t, int x, int line) {
        double p = one.position(reel, t);
        int last = one.timing.reels() - 1;
        double stop = one.timing.stops()[reel];
        // The last reel twitches just before it stops.
        if (reel == last && !one.mini() && t > stop - 22 && t < stop && !one.stopped(t)) p += Math.sin(t * 3.1) * 0.05 * fx;
        boolean doves = one.pardoned(t);
        double speed = one.stopped(t) ? 0 : one.speed(reel, t);
        List<RouletteSymbol> strip = one.strips.get(reel);
        boolean won = one.won(t);
        int[] palette = one.palette(t);
        int base = (int) Math.floor(p);
        var matrices = context.getMatrices();
        // Radiance behind the symbol under the line once the reels have won (or turned to doves): a glow, a halo breathing
        // round it and a slowly turning star.
        boolean mercyDove = one.spared(t) && reel == last;
        if ((won || doves || mercyDove) && fx > 0) {
            double since = one.sinceGrace(t);
            float pulse = 0.6f + 0.4f * Math.abs(MathHelper.sin((float) (since * 0.32)));
            FxHud.begin(context, true)
                    .sprite(FxHud.Sprite.GLOW, x + 17, line, 64, 0, 1, palette[0], pulse * fx)
                    .sprite(FxHud.Sprite.HALO, x + 17, line, 36 + 8 * pulse, 0, 1, palette[2], 0.55f * pulse * fx)
                    .sprite(FxHud.Sprite.STAR, x + 17, line, 46 * pulse, (float) (since * 0.05), 1, palette[2], 0.5f * pulse * fx)
                    .draw();
        }
        double step = CELL / RADIUS;
        for (int k = base - 2; k <= base + 3; k++) {
            double angle = (p - k) * step;
            if (Math.abs(angle) >= Math.PI / 2 - 0.05) continue;
            RouletteSymbol symbol = doves ? RouletteSymbol.DOVE : strip.get(Math.floorMod(k, strip.size()));
            // By whole texels: the cell's place and its squeezed height are rounded to the grid.
            double y = Math.round(line + RADIUS * Math.sin(angle));
            float squeeze = Math.max(1, Math.round(32 * Math.cos(angle))) / 32f, light = 0.35f + 0.65f * (float) Math.cos(angle);
            boolean centre = Math.abs(angle) < step / 2;
            matrices.push();
            matrices.translate(x + 17, y, 0);
            if (won && centre) {
                double since = t - one.timing.fin();
                float pulse = (float) (1 + 0.12 * Math.abs(Math.sin(since * 0.32)) + 0.2 * Math.max(0, 1 - since / 5));
                matrices.scale(pulse, pulse, 1);
            }
            matrices.scale(1, squeeze, 1);
            if (speed > 0.25 && !doves) {
                // Motion blur: the symbol stretched along the reel, with faint ghosts behind it.
                float stretch = Math.round(32 * (1 + Math.min(1.4, (speed - 0.25) * 1.4))) / 32f;
                // Pixel streaks: copies a whole number of texels behind, fainter and fainter.
                context.setShaderColor(light, light, light, 0.22f);
                drawSymbol(context, symbol, 0, -Math.round(speed * 10), stretch);
                drawSymbol(context, symbol, 0, -Math.round(speed * 20), stretch);
                dark(context, one, symbol, doves, light, 0.85f);
                drawSymbol(context, symbol, 0, 0, stretch);
            } else {
                dark(context, one, symbol, doves, light, 1f);
                drawSymbol(context, symbol, 0, 0, 1);
            }
            context.setShaderColor(1f, 1f, 1f, 1f);
            matrices.pop();
        }
        // Streaks of light down the reel at full speed, a flash as it stops.
        FxHud.Batch flare = FxHud.begin(context, true);
        if (speed > 0.6 && fx > 0) {
            float strength = (float) Math.min(1, (speed - 0.6) * 2) * fx;
            for (int i = 0; i < 3; i++) {
                float sx = x + 7 + i * 10 + (float) Math.sin(t * 1.7 + i * 2 + reel) * 2;
                flare.streak(FxHud.Sprite.BEAM, sx, (WIN_T + WIN_B) / 2f, (WIN_B - WIN_T) / 2f, 3, 0, palette[2], 0.3f * strength);
            }
        }
        double since = t - stop;
        if (since >= 0 && since < 8 && !one.stopped(t)) {
            float flash = (float) (1 - since / 8);
            flare.sprite(FxHud.Sprite.GLOW, x + 17, line, 70, 0, 1, 0xFFFFFF, flash * 0.9f);
            flare.streak(FxHud.Sprite.BEAM, x + 17, line, 30, 22 * flash, MathHelper.HALF_PI, palette[2], flash * 0.7f);
        }
        flare.draw();
        // The drum's curve: darker towards the top and bottom of the window.
        context.fillGradient(x, WIN_T, x + REEL, WIN_T + 18, 0xE0100804, 0x00100804);
        context.fillGradient(x, WIN_B - 18, x + REEL, WIN_B, 0x00100804, 0xE0100804);
    }

    /** The ban blackens every symbol but the gates; {@code light} is the drum's shade at this cell. */
    private static void dark(DrawContext context, Show one, RouletteSymbol symbol, boolean doves, float light, float alpha) {
        if (one.ban() && symbol != RouletteSymbol.GATE && !doves) context.setShaderColor(0.32f * light, 0.26f * light, 0.27f * light, alpha);
        else context.setShaderColor(light, light, light, alpha);
    }

    /** A symbol centred on the current point, stretched vertically by {@code stretch}. */
    private static void drawSymbol(DrawContext context, RouletteSymbol symbol, double dx, double dy, float stretch) {
        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(dx, dy, 0);
        matrices.scale(1, stretch, 1);
        symbolQuad(context, symbol, -16, -16, 32, 32);
        matrices.pop();
    }

    private static void symbolQuad(DrawContext context, RouletteSymbol symbol, int x, int y, int width, int height) {
        tex(context, SYMBOLS, x, y, width, height, symbol.ordinal() * SYMBOL_TEX, 0, SYMBOL_TEX, SYMBOL_TEX, SYMBOL_TEX * RouletteSymbol.values().length, SYMBOL_TEX);
    }

    /** The glass over the reels: a highlight at the top and a glint sweeping across now and then. */
    private static void glass(DrawContext context, Show one, double t) {
        context.fillGradient(WIN_L, WIN_T, WIN_R, WIN_T + 8, 0x30FFFFFF, 0x00FFFFFF);
        if (fx <= 0) return;
        double period = 34, phase = (t % period) / period;
        float gx = (float) (WIN_L - 30 + phase * (WIN_R - WIN_L + 60));
        FxHud.begin(context, true).streak(FxHud.Sprite.BEAM, gx, LINE, 50, 8, 0.45f, 0xFFFFFF, 0.35f * fx).draw();
    }

    /** The royal doors over the reels, opening outwards as the spin begins. */
    private static void doors(DrawContext context, double t) {
        double open = smooth(Math.max(0, (t - 2) / 9.0));
        if (open >= 1 || Math.round(54 * (1 - open)) <= 0) return;
        float leaf = Math.round(54 * (1 - open)); // whole texels
        var matrices = context.getMatrices();
        for (int side = 0; side < 2; side++) {
            matrices.push();
            // Hinged at the outer edges: each leaf narrows towards its hinge, as if it turned away.
            matrices.translate(side == 0 ? WIN_L : WIN_R, WIN_T, 0);
            matrices.scale((side == 0 ? 1 : -1) * leaf / 54f, 1, 1);
            float shade = (float) (1 - 0.4 * open);
            context.setShaderColor(shade, shade, shade, 1f);
            if (side == 0) tex(context, DOORS, 0, 0, 54, 60, 0, 0, 54, 60, 108, 60);
            else {
                // The right leaf, mirrored back so its picture faces the right way.
                matrices.translate(54, 0, 0);
                matrices.scale(-1, 1, 1);
                tex(context, DOORS, 0, 0, 54, 60, 54, 0, 54, 60, 108, 60);
            }
            context.setShaderColor(1f, 1f, 1f, 1f);
            matrices.pop();
        }
    }

    /** The light over the kiot: the third reel's window burning while it is awaited, the win line running with light, the
     * candles and lampadas flickering in a running wave, the lamps' glow, the hand's nimbus and a glint over the gilding. */
    private static void frameLight(DrawContext context, Show one, double t) {
        int[] palette = one.palette(t);
        FxHud.Batch light = FxHud.begin(context, true);
        if (one.anticipating(t)) {
            double x = t - one.timing.stops()[1];
            float pulse = (float) (0.5 + 0.5 * Math.sin(x * 0.9)), tension = (float) one.tension(t);
            int l = WIN_L + 2 * REEL_STEP - 1, r = l + REEL + 2;
            int color = one.ban() ? 0xE01020 : palette[0];
            float a = (0.5f + 0.5f * pulse) * (0.5f + 0.5f * tension) * Math.max(0.5f, fx);
            light.streak(FxHud.Sprite.BEAM, l, LINE, 32, 4, 0, color, a);
            light.streak(FxHud.Sprite.BEAM, r, LINE, 32, 4, 0, color, a);
            light.streak(FxHud.Sprite.BEAM, (l + r) / 2f, WIN_T - 1, 20, 4, MathHelper.HALF_PI, color, a);
            light.streak(FxHud.Sprite.BEAM, (l + r) / 2f, WIN_B + 1, 20, 4, MathHelper.HALF_PI, color, a);
            light.sprite(FxHud.Sprite.GLOW, (l + r) / 2f, LINE, 60 + 20 * tension, 0, 1, color, 0.35f * a);
        }
        boolean pardoned = one.graced(t);
        if (one.won(t) || pardoned) {
            double x = one.sinceGrace(t);
            int color = palette[0];
            float top = LINE - 19, bottom = LINE + 19, left = WIN_L - 2, right = WIN_R + 2;
            float glow = (float) (x < 14 ? 0.6 + 0.4 * Math.abs(Math.sin(x * 0.8)) : 0.7);
            light.streak(FxHud.Sprite.BEAM, (left + right) / 2, top, (right - left) / 2, 3, MathHelper.HALF_PI, color, glow);
            light.streak(FxHud.Sprite.BEAM, (left + right) / 2, bottom, (right - left) / 2, 3, MathHelper.HALF_PI, color, glow);
            double perimeter = 2 * (right - left) + 2 * (bottom - top);
            for (int bead = 0; bead < 2 && fx > 0; bead++) {
                double along = (x * 7 + bead * perimeter / 2.0) % perimeter;
                double bx, by;
                if (along < right - left) { bx = left + along; by = top; }
                else if (along < right - left + bottom - top) { bx = right; by = top + along - (right - left); }
                else if (along < 2 * (right - left) + bottom - top) { bx = right - (along - (right - left) - (bottom - top)); by = bottom; }
                else { bx = left; by = bottom - (along - 2 * (right - left) - (bottom - top)); }
                light.sprite(FxHud.Sprite.SPARK, (float) bx, (float) by, 18, 0, 1, palette[2], fx);
                light.sprite(FxHud.Sprite.GLOW, (float) bx, (float) by, 16, 0, 1, color, 0.8f * fx);
            }
        }
        // Candles and lampadas: never out, they flare in a wave running round the kiot as fast as the reels, all at once at
        // the final, softly for a pardon.
        double travel = t * 0.3;
        for (int reel = 0; reel < one.timing.reels(); reel++) travel += Math.max(0, one.position(reel, Math.min(t, one.timing.stops()[reel])) - one.position(reel, 0)) * 0.25;
        boolean won = one.won(t);
        for (int i = 0; i < LIGHTS.length; i++) {
            float wave = (float) (0.5 + 0.5 * Math.cos((i - travel) * MathHelper.TAU / 6));
            float level = pardoned ? 0.75f : won ? 0.65f + 0.35f * (((int) ((t - one.timing.fin()) / 3) + i) % 2) : 0.3f + 0.7f * wave * wave;
            float flicker = 0.85f + 0.15f * MathHelper.sin((float) (t * 1.9 + i * 2.7)) * MathHelper.sin((float) (t * 3.1 + i));
            float lx = LIGHTS[i][0], ly = LIGHTS[i][1];
            if (i < CANDLES) {
                // A candle's flame: a warm glow, a teardrop of light, a white heart; it leans a little as it flickers.
                float lean = 0.15f * MathHelper.sin((float) (t * 0.7 + i));
                light.sprite(FxHud.Sprite.GLOW, lx, ly - 1, 14 * level, 0, 1, 0xFFB050, 0.7f * level * flicker * Math.max(0.5f, fx));
                light.sprite(FxHud.Sprite.EMBER, lx, ly - 1.2f, 4.4f * (0.8f + 0.2f * flicker), lean, 0.55f, 0xFFC060, flicker);
                light.sprite(FxHud.Sprite.SPARK, lx, ly - 0.6f, 2.2f, 0, 1, 0xFFFFF0, flicker);
            } else {
                int color = pardoned ? 0xC8E8FF : one.ban() ? 0xFF2030 : palette[0];
                light.sprite(FxHud.Sprite.GLOW, lx, ly, 10 * level, 0, 1, color, 0.8f * level * flicker * Math.max(0.4f, fx));
                light.sprite(FxHud.Sprite.SPARK, lx, ly, 3, 0, 1, 0xFFFFFF, 0.6f * level);
            }
        }
        // The lamps of the fury scale: lit ones glow, their flames flicker.
        int level = Math.min(5, one.spin.severity()), lit = litLamps(one, t, level);
        for (int j = 0; j < lit; j++) {
            float flicker = 0.75f + 0.25f * (float) Math.sin(t * 1.3 + j * 2.1);
            float lx = 19, ly = 86 - j * 12 + 4;
            if (fx > 0) light.sprite(FxHud.Sprite.GLOW, lx, ly, 26, 0, 1, level >= 5 ? 0xFF5020 : 0xFFB040, 0.7f * flicker * fx);
            light.sprite(FxHud.Sprite.EMBER, lx, ly - 2, 5 * flicker, 0.1f * MathHelper.sin((float) t + j), 0.6f, 0xFFD080, flicker);
        }
        double[] hand = hand(t);
        if (hand != null && fx > 0) light.sprite(FxHud.Sprite.GLOW, (float) hand[0], (float) hand[1] - 14, 42, 0, 1, 0xFFE8A0, (float) (0.7 * hand[2] * fx));
        if (fx > 0) {
            double period = one.finPlayed ? 22 : 50, phase = ((t + 20) % period) / period;
            float gx = (float) (-30 + phase * (W + 60));
            light.streak(FxHud.Sprite.BEAM, gx, 28, 8, 5, 0.5f, 0xFFF4D0, 0.5f * fx);
            light.streak(FxHud.Sprite.BEAM, gx - 10, H - 4, 8, 5, 0.5f, 0xFFF4D0, 0.5f * fx);
        }
        light.draw();
    }

    private static float[][] lights() {
        List<float[]> out = new ArrayList<>();
        for (int y = 100; y >= 40; y -= 12) out.add(new float[] { 4, y });
        float[] candles = { 48, 56, 64, 112, 120, 128 };
        List<float[]> top = new ArrayList<>();
        for (float x : candles) top.add(new float[] { x, 12.2f });
        for (int y = 40; y <= 100; y += 12) out.add(new float[] { 172, y });
        // Candles first (the flames are drawn differently), then the jewels in running order: left up, right down.
        List<float[]> ordered = new ArrayList<>(top);
        ordered.addAll(out);
        return ordered.toArray(new float[0][]);
    }

    private static int litLamps(Show one, double t, int level) {
        double from = one.timing.pull() + one.timing.spinUp(), to = one.timing.stops()[one.timing.reels() - 1];
        return t <= from ? 0 : (int) Math.min(level, Math.floor((t - from) / Math.max(1, to - from) * level) + 1);
    }

    /** The fury scale: lamps light from the bottom while the reels spin, up to the punishment's weight. */
    private static void lamps(DrawContext context, Show one, double t, int severity) {
        int lit = litLamps(one, t, Math.min(5, severity));
        for (int j = 0; j < 5; j++) tex(context, LAMPS, 12, 86 - j * 12, 14, 12, j < lit ? 14 : 0, 0, 14, 12, 28, 12);
    }

    /** Where the hand is (its fingertips) and how visible, or null once it is gone. */
    private static double[] hand(double t) {
        if (t >= 16) return null;
        if (t < 3) { double u = easeOutBack(t / 3, 1.4); return new double[] { 155, 29 - (1 - u) * 34, 1 }; }
        if (t < 8) { double u = Math.min(1, (t - 3) / 2.5); return new double[] { 155 + u * 11, 29 + u * 20, 1 }; }
        double u = (t - 8) / 8;
        return new double[] { 166, 49 - u * 40, 1 - u };
    }

    /** The lever on the right, pulled by the hand of God at the start; it springs back with a bounce. */
    private static void leverAndHand(DrawContext context, Show one, double t) {
        boolean down = t >= 4 && t < 11;
        double bounce = t >= 11 && t < 17 ? Math.sin((t - 11) * 1.6) * 2.5 * (1 - (t - 11) / 6) : 0;
        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(0, Math.round(bounce), 0);
        tex(context, LEVER, 145, 15, 24, 80, down ? 24 : 0, 0, 24, 80, 48, 80);
        matrices.pop();
        double[] hand = hand(t);
        if (hand == null) return;
        context.setShaderColor(1f, 1f, 1f, (float) MathHelper.clamp(hand[2], 0, 1));
        matrices.push();
        matrices.translate(Math.round(hand[0]) - 16, Math.round(hand[1]) - 30, 0);
        tex(context, HAND, 0, 0, 32, 32, 0, 0, 32, 32, 32, 32);
        matrices.pop();
        context.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** «СУДЪ БОЖІЙ» in the cartouche, and the psalm of repentance running along the pedestal. */
    private static void inscriptions(DrawContext context, TextRenderer text, Show one, double t, double x0, double y0, float scale) {
        var matrices = context.getMatrices();
        String title = "СУДЪ БОЖІЙ";
        matrices.push();
        matrices.translate(88, 26.5, 0);
        matrices.scale(0.5f, 0.5f, 1);
        embossed(context, text, title, -text.getWidth(title) / 2f, 0, one.ban() ? 0xE04040 : 0xF0C860, one.ban() ? 0x200000 : 0x3A1A00);
        matrices.pop();
        // The psalm runs inside the dark plaque of the pedestal (clipped to it on the screen).
        int left = (int) Math.floor(x0 + 41 * scale), right = (int) Math.ceil(x0 + 135 * scale);
        int top = (int) Math.floor(y0 + 114 * scale), bottom = (int) Math.ceil(y0 + 122 * scale);
        context.draw();
        context.enableScissor(left, top, right, bottom);
        float size = 0.5f, width = text.getWidth(PSALM) * size;
        float shift = (float) ((t * 0.9) % width);
        for (int copy = 0; copy < 3; copy++) {
            matrices.push();
            matrices.translate(136 - shift + copy * width - 94, 115.6, 0);
            matrices.scale(size, size, 1);
            context.drawText(text, PSALM, 0, 0, one.ban() ? 0xFFB04040 : 0xFFD8B060, false);
            matrices.pop();
        }
        context.draw();
        context.disableScissor();
    }

    /** Words in gold with a dark rim and a light upper edge, as if pressed into the metal. */
    private static void embossed(DrawContext context, TextRenderer text, String words, float x, float y, int color, int rim) {
        int ix = Math.round(x), iy = Math.round(y);
        for (int[] d : new int[][] { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 }, { 1, 1 } }) context.drawText(text, words, ix + d[0], iy + d[1], 0xFF000000 | rim, false);
        context.drawText(text, words, ix, iy, 0xFF000000 | color, false);
    }

    /** Under the pedestal: whose punishment it is (for the others) and, after the final, the banner with the punishment's name
     * lighting up letter by letter, waving, and the reason under it. */
    private static void banner(DrawContext context, TextRenderer text, Show one, double t) {
        float y = BOTTOM + 3;
        if (!one.own) {
            context.drawCenteredTextWithShadow(text, "Кара для " + one.spin.name(), W / 2, (int) y, 0xFFE0D0B0);
            y += 10;
        }
        boolean pardoned = one.graced(t);
        double since = one.sinceGrace(t);
        if (since < 0 || one.stopped(t) && !pardoned) return;
        double open = easeOutBack(Math.min(1, since / 7.0), 1.6);
        float half = Math.round(90 * Math.min(1.08, open));
        if (half < 4) return;
        int variant = pardoned ? 1 : one.ban() ? 2 : 0;
        // The cloth in slices, each lifted by a slow wave so the banner ripples.
        var matrices = context.getMatrices();
        int slices = 20;
        for (int i = 0; i < slices; i++) {
            float u0 = i / (float) slices, u1 = (i + 1) / (float) slices;
            float sx = W / 2f - half + u0 * 2 * half, sw = (u1 - u0) * 2 * half;
            float wave = Math.round(Math.sin(u0 * 7 - since * 0.22) * 1.2 * Math.min(1, since / 8)); // by whole texels
            float shade = (float) (0.88 + 0.12 * Math.cos(u0 * 7 - since * 0.22));
            matrices.push();
            matrices.translate(sx, y + wave, 0);
            matrices.scale(sw / (180f / slices), 1, 1);
            context.setShaderColor(shade, shade, shade, 1f);
            tex(context, BANNER, 0, 0, 180 / slices, 28, i * 180f / slices, variant * 28, 180 / slices, 28, 180, 84);
            context.setShaderColor(1f, 1f, 1f, 1f);
            matrices.pop();
        }
        if (since < 5) return;
        String title = fit(text, pardoned ? "Помилованъ" : one.spin.title(), 150);
        String reason = one.spared(t) ? "пощажён случаем" : pardoned ? "покаяние снимает кару" : one.spin.reason();
        int width = text.getWidth(title);
        float x = W / 2f - width / 2f;
        int baseColor = pardoned ? 0xF0F8FF : one.ban() ? 0xFF8080 : 0xFFE8B0;
        double lit = (since - 5) * 2.2;
        int index = 0;
        List<float[]> sparkles = new ArrayList<>();
        for (int i = 0; i < title.length(); ) {
            int cp = title.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            double shimmer = fx > 0 ? Math.max(0, Math.sin(index * 0.5 - since * 0.45)) : 0;
            float wave = Math.round(Math.sin((x - (W / 2f - half)) / (2 * half) * 7 - since * 0.22) * 1.2 * Math.min(1, since / 8));
            int color = index < lit ? mix(baseColor, 0xFFFFFF, (float) (shimmer * 0.7)) : 0x705838;
            embossed(context, text, ch, x, y + 5 + wave, color, variant == 1 ? 0x10284A : 0x2A0800);
            if (index < lit && index > lit - 1.5) sparkles.add(new float[] { x + text.getWidth(ch) / 2f, y + 9 + wave });
            x += text.getWidth(ch);
            i += Character.charCount(cp);
            index++;
        }
        if (!sparkles.isEmpty() && fx > 0) {
            FxHud.Batch light = FxHud.begin(context, true);
            for (float[] s : sparkles) light.sprite(FxHud.Sprite.STAR, s[0], s[1], 16, (float) since * 0.2f, 1, 0xFFF4C0, fx);
            light.draw();
        }
        if (reason != null && !reason.isBlank() && lit > title.length()) {
            matrices.push();
            matrices.translate(W / 2f, y + 16.5, 0);
            matrices.scale(0.5f, 0.5f, 1);
            context.drawCenteredTextWithShadow(text, fit(text, reason, 250), 0, 0, 0xFFE8D8C0);
            matrices.pop();
        }
    }

    /** The words of judgement coming down at the final, in Church Slavonic: «ГНѢВЪ БОЖІЙ»; on 777 the seven bowls of wrath with
     * the verse of the Revelation; «ИЗГНАНІЕ» for the ban; «ПОМИЛОВАНЪ» with «Господи, помилуй» for a pardon. */
    private static void bigWords(DrawContext context, TextRenderer text, Show one, double t, int sw, double below, float base) {
        if (fx <= 0) return;
        boolean pardoned = one.graced(t);
        double since = one.sinceGrace(t);
        if (since < 1 || one.stopped(t) && !pardoned) return;
        since -= 1;
        double hold = one.pardoned(t) ? PARDON_HOLD - 8 : one.timing.act() - one.timing.fin() + 4;
        if (since > hold) return;
        String words = pardoned ? "ПОМИЛОВАНЪ" : one.doom() ? "СЕДМЬ ЧАШЪ ГНѢВА" : one.ban() ? "ИЗГНАНІЕ" : "ГНѢВЪ БОЖІЙ";
        String verse = pardoned ? "Господи, помилуй" : one.doom() ? "«Идите и вылейте семь чаш гнева Божия на землю» — Откр. 16:1" : null;
        int[] palette = one.palette(t);
        int color = pardoned ? 0xE0F2FF : one.doom() ? 0xFFD040 : one.ban() ? 0xE03030 : 0xF0B850;
        // They come down from above and settle with a little swing, like the heavy letters of a bell's inscription.
        double fall = since < 7 ? easeOutBack(since / 7, 1.4) : 1;
        float alpha = (float) (Math.min(1, since / 3) * Math.min(1, (hold - since) / 6));
        float size = (float) (base * 1.55);
        int w = text.getWidth(words);
        float cy = (float) (below - (1 - fall) * 30 * base);
        FxHud.begin(context, true)
                .streak(FxHud.Sprite.GLOW, sw / 2f, cy, w * size * 0.65f, 12 * size, MathHelper.HALF_PI, palette[0], 0.55f * alpha)
                .sprite(FxHud.Sprite.FLARE, sw / 2f, cy, 26 * size, (float) since * 0.03f, 1, palette[2], 0.4f * alpha * (float) Math.max(0.3, 1 - since / 10))
                .draw();
        var matrices = context.getMatrices();
        if (alpha > 0.02f) {
            matrices.push();
            matrices.translate(sw / 2.0, cy, 0);
            matrices.scale(size, size, 1);
            int a = (int) (alpha * 255) << 24;
            int rim = a | (one.ban() ? 0x000000 : pardoned ? 0x0A1A30 : 0x2A0A00);
            for (int[] d : new int[][] { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 }, { 1, 1 } }) context.drawText(text, words, -w / 2 + d[0], -4 + d[1], rim, false);
            float shimmer = (float) Math.max(0, Math.sin(since * 0.35));
            context.drawText(text, words, -w / 2, -4, a | mix(color, 0xFFFFFF, shimmer * 0.3f), false);
            matrices.pop();
            if (verse != null) {
                matrices.push();
                matrices.translate(sw / 2.0, cy + 9 * size, 0);
                matrices.scale(base * 0.75f, base * 0.75f, 1);
                int vw = text.getWidth(verse);
                context.drawText(text, verse, -vw / 2 + 1, 1, a | 0x200800, false);
                context.drawText(text, verse, -vw / 2, 0, a | (pardoned ? 0xD8ECFF : 0xF0E0C0), false);
                matrices.pop();
            }
        }
    }

    /** The golden flash of 777 over the whole screen. */
    private static void flash(DrawContext context, Show one, double t, int sw, int sh) {
        if (!one.doom() || !one.own || !jackpotFx || one.cancelledAt >= 0) return;
        double x = t - one.timing.fin();
        if (x < 0 || x > 18) return;
        float strength = (float) ((1 - x / 18) * (1 - x / 18));
        FxHud.begin(context, true).sprite(FxHud.Sprite.GLOW, sw / 2f, sh / 3f, Math.max(sw, sh) * 2.2f, 0, 1, 0xFFC830, strength).draw();
        context.fill(0, 0, sw, sh, (int) (80 * strength) << 24 | 0xFFD040);
    }

    /** A tail's spin: one reel in a small gilded case under the top edge, for two seconds. */
    private static void drawMini(DrawContext context, TextRenderer text, Show one, double t, int sw) {
        double pixels = MinecraftClient.getInstance().getWindow().getScaleFactor();
        float scale = (float) (Math.max(1, Math.round(MathHelper.clamp(sw * 0.09f / 48f, 0.6f, 2.2f) * pixels)) / pixels);
        double in = easeOutBack(Math.min(1, t / 8.0), 2.0);
        double leave = t > one.leave() ? Math.min(1, (t - one.leave()) / Roulette.Timing.EXIT) : 0;
        double y0 = 4 + Math.round(((in - 1) * 70 - leave * leave * 80)) * scale, x0 = Math.round((sw - 48 * scale) / 2.0 / scale) * scale;
        lastX = x0; lastY = y0; lastScale = scale;
        int[] palette = one.palette(t);
        if (fx > 0) {
            float presence = (float) (Math.min(1, t / 6) * (1 - leave)) * fx;
            FxHud.Batch light = FxHud.begin(context, true);
            light.sprite(FxHud.Sprite.GLOW, (float) (x0 + 24 * scale), (float) (y0 + 24 * scale), 110 * scale, 0, 1, palette[0], 0.45f * presence);
            for (int i = 0; i < 10; i++) {
                float angle = (float) (t * 0.02) + i * MathHelper.TAU / 10;
                float length = 50 * scale * (i % 2 == 0 ? 1 : 0.6f);
                light.streak(FxHud.Sprite.BEAM, (float) (x0 + 24 * scale) + MathHelper.cos(angle) * length / 2, (float) (y0 + 24 * scale) + MathHelper.sin(angle) * length / 2,
                        length / 2, 4 * scale, angle + MathHelper.HALF_PI, palette[i % 3], 0.3f * presence);
            }
            light.draw();
        }
        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(x0, y0, 0);
        matrices.scale(scale, scale, 1);
        context.fill(0, 0, 48, 48, 0xFF60400E);
        context.fillGradient(1, 1, 47, 47, 0xFFF0C868, 0xFFB88A30);
        context.fill(4, 4, 44, 44, 0xFF60400E);
        context.fillGradient(6, 6, 42, 42, 0xFF6A4A26, 0xFF3A2410);
        matrices.pop();
        context.enableScissor((int) Math.floor(x0 + 6 * scale), (int) Math.floor(y0 + 6 * scale), (int) Math.ceil(x0 + 42 * scale), (int) Math.ceil(y0 + 42 * scale));
        matrices.push();
        matrices.translate(x0, y0, 0);
        matrices.scale(scale, scale, 1);
        drawReelMini(context, one, t);
        matrices.pop();
        context.disableScissor();
        matrices.push();
        matrices.translate(x0, y0, 0);
        matrices.scale(scale, scale, 1);
        // Little lampadas round the case.
        FxHud.Batch lamps = FxHud.begin(context, true);
        for (int i = 0; i < 12; i++) {
            float level = (float) (0.4 + 0.6 * Math.max(0, Math.cos((i - t * 0.6) * MathHelper.TAU / 4)));
            if (t >= one.timing.fin()) level = 1;
            int bx = i < 6 ? 4 + i * 8 : 44 - (i - 6) * 8, by = i < 6 ? 2 : 46;
            lamps.sprite(FxHud.Sprite.GLOW, bx, by, 9 * level, 0, 1, palette[i % 3], 0.9f * level).sprite(FxHud.Sprite.SPARK, bx, by, 4, 0, 1, 0xFFFFFF, 0.8f * level);
        }
        lamps.draw();
        if (t >= one.timing.fin()) {
            String line = (one.own ? "" : one.spin.name() + ": ") + one.spin.title();
            embossed(context, text, line, 24 - text.getWidth(line) / 2f, 51, 0xF0C860, 0x2A0800);
        }
        matrices.pop();
    }

    private static void drawReelMini(DrawContext context, Show one, double t) {
        double p = one.position(0, t), speed = one.stopped(t) ? 0 : one.speed(0, t);
        List<RouletteSymbol> strip = one.strips.get(0);
        var matrices = context.getMatrices();
        int base = (int) Math.floor(p);
        double step = CELL / RADIUS;
        for (int k = base - 2; k <= base + 3; k++) {
            double angle = (p - k) * step;
            if (Math.abs(angle) >= Math.PI / 2 - 0.05) continue;
            RouletteSymbol symbol = one.pardoned(t) ? RouletteSymbol.DOVE : strip.get(Math.floorMod(k, strip.size()));
            float squeeze = (float) Math.cos(angle), light = 0.35f + 0.65f * squeeze;
            matrices.push();
            matrices.translate(24, Math.round(24 + RADIUS * 0.6 * Math.sin(angle)), 0);
            matrices.scale(1, Math.max(1, Math.round(32 * squeeze)) / 32f, 1);
            context.setShaderColor(light, light, light, 1f);
            drawSymbol(context, symbol, 0, 0, speed > 0.25 ? (float) (1 + Math.min(1.4, (speed - 0.25) * 1.5)) : 1);
            context.setShaderColor(1f, 1f, 1f, 1f);
            matrices.pop();
        }
        double since = t - one.timing.stops()[0];
        if (since >= 0 && since < 8) FxHud.begin(context, true).sprite(FxHud.Sprite.GLOW, 24, 24, 60, 0, 1, 0xFFFFFF, (float) (1 - since / 8)).draw();
        context.fillGradient(6, 6, 42, 16, 0xE0100804, 0x00100804);
        context.fillGradient(6, 32, 42, 42, 0x00100804, 0xE0100804);
    }

    // ---- Helpers. ----

    /** A texture of the mod drawn with its alpha (the context's fills and text turn blending off): what it buffered goes first. */
    private static void tex(DrawContext context, Identifier texture, int x, int y, int width, int height, float u, float v, int regionWidth, int regionHeight,
                            int textureWidth, int textureHeight) {
        context.draw();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        context.drawTexture(texture, x, y, width, height, u, v, regionWidth, regionHeight, textureWidth, textureHeight);
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
    /** The colour turned round the hue circle by {@code turn} (a whole turn is 1). */
    private static int shift(int rgb, float turn) {
        float[] hsb = java.awt.Color.RGBtoHSB(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, null);
        return java.awt.Color.HSBtoRGB(hsb[0] + turn, hsb[1], hsb[2]) & 0xFFFFFF;
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
