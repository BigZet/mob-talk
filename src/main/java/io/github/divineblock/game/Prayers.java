package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.ApiKeys;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.GodDecider;
import io.github.divineblock.core.GodLedger;
import io.github.divineblock.core.GodSpeech;
import io.github.divineblock.core.JevGodDecider;
import io.github.divineblock.core.Miracle;
import io.github.divineblock.core.MiracleGroup;
import io.github.divineblock.core.Offerings;
import io.github.divineblock.core.Penance;
import io.github.divineblock.core.PrayerLog;
import io.github.divineblock.core.PrayerTone;
import io.github.divineblock.core.Righteousness;
import io.github.divineblock.core.RuleGodDecider;
import io.github.divineblock.core.Verdict;
import io.github.divineblock.core.VerdictBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.ItemEntity;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** A prayer from words to deed. A worker first asks Jev whether the words are a prayer at all (the god does not hear idle
 * talk); then the game thread gathers the facts and the gifts, a worker asks Jev for the act and the chat model for the line,
 * and the game thread performs the act at once if the player is still there, alive, in the same world. The god gives no
 * second chance: he never asks back, never delays, and an announced punishment cannot be talked away. One sin is one
 * punishment ({@link Penance}): a new sin while it runs makes it heavier and longer instead of laying another. */
public final class Prayers {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "divineblock-worker"); thread.setDaemon(true); return thread;
    });
    private static final java.util.Set<UUID> THINKING = new java.util.HashSet<>();
    /** The punishment running on each mortal (server thread). */
    static final Penance PENANCE = new Penance();

    private Prayers() { }

    /** The "Почему?" button: the god retells his last verdict and its reasons. Words alone, even "почему", are a prayer. */
    public static void explain(ServerPlayerEntity player, BlockPos altar) {
        ServerWorld world = player.getServerWorld();
        if (!(world.getBlockState(altar).getBlock() instanceof AltarBlock) || !altar.isWithinDistance(player.getBlockPos(), 8)) return;
        MinecraftServer server = world.getServer();
        DivineConfig config = DivineBlockMod.config();
        Verdict last = DivineState.of(server).ledger.soul(player.getUuid()).lastVerdict();
        if (last == null) { say(player, "Я ещё ничего о тебе не решал, смертный."); return; }
        WORKER.execute(() -> {
            String line = new GodSpeech(config, DivineBlockMod.configDirectory()).explain(last);
            server.execute(() -> { if (!player.isRemoved()) say(player, line); });
        });
    }

    public static void handle(ServerPlayerEntity player, BlockPos altar, String rawText) {
        ServerWorld world = player.getServerWorld();
        String text = rawText == null ? "" : rawText.replaceAll("\\p{Cntrl}", " ").strip();
        if (text.isEmpty() || text.length() > PrayPayload.MAX_TEXT) return;
        if (io.github.divineblock.boss.BossEffects.silenced(player)) {
            player.sendMessage(Text.literal("Обет молчания: уста запечатаны, бог не слышит").formatted(Formatting.DARK_GRAY), true);
            return;
        }
        if (!(world.getBlockState(altar).getBlock() instanceof AltarBlock) || !altar.isWithinDistance(player.getBlockPos(), 8)) return;
        MinecraftServer server = world.getServer();
        DivineConfig config = DivineBlockMod.config();
        GodLedger.Soul soul = DivineState.of(server).ledger.soul(player.getUuid());

        if (THINKING.contains(player.getUuid())) { hint(player, "Бог ещё размышляет над прежней молитвой…"); return; }
        long now = server.getOverworld().getTime();
        // Repentance while the roulette of his punishment still spins is heard at once: the altar has no time to cool.
        boolean repentsUnderRoulette = RouletteService.spinning(player) && PrayerTone.of(text).repents();
        if (now - soul.lastPrayerTick < config.cooldownSeconds * 20L && !repentsUnderRoulette) { hint(player, "Алтарь ещё не остыл. Повремени, смертный."); return; }

        THINKING.add(player.getUuid());
        hint(player, "Бог внимает…");
        world.spawnParticles(ParticleTypes.ENCHANT, altar.getX() + 0.5, altar.getY() + 1.5, altar.getZ() + 0.5, 80, 0.5, 0.8, 0.5, 0.5);
        WORKER.execute(() -> {
            Judgement judgement = judge(config, text);
            server.execute(() -> {
                if (!judgement.prayer()) {
                    THINKING.remove(player.getUuid());
                    DivineBlockMod.LOG.info("Божественный блок: не молитва ({}): {}", judgement.by(), text);
                    unheard.accept(text);
                    if (!player.isRemoved()) hint(player, "Алтарь безмолвен: это не молитва.");
                    return;
                }
                if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) { THINKING.remove(player.getUuid()); return; }
                echoPrayer(player, text);
                pray(player, altar, world, text, config, judgement);
            });
        });
    }

    /** Whether the words are a prayer and whether they insult the god: Jev decides, the rules answer when Jev is off or silent. */
    record Judgement(boolean prayer, boolean insult, String by) { }
    /** The notable deeds and, after them, what confession left on the conscience (sins kept back, relapses): the god names those too. */
    static List<Righteousness.Memory> withConscience(List<Righteousness.Memory> notable, Righteousness righteousness) {
        List<Righteousness.Memory> out = new ArrayList<>(notable);
        for (Righteousness.Memory memory : righteousness.conscience(2)) if (!out.contains(memory)) out.add(memory);
        return out;
    }

    static Judgement judge(DivineConfig config, String text) {
        if (config.useJev) {
            try {
                JevGodDecider.Reading jev = new JevGodDecider(ApiKeys.load(DivineBlockMod.configDirectory(), ApiKeys.Provider.OPENROUTER), new RuleGodDecider()).read(text);
                if (jev != null) return new Judgement(jev.prayer(), jev.insult(), "jev");
            } catch (Exception missing) { DivineBlockMod.LOG.info("Божественный блок: Jev без ключа, молитву распознают правила"); }
        }
        return new Judgement(PrayerTone.addressesGod(text), PrayerTone.of(text).rude(), "rules");
    }

    /** The heard prayer: the gifts burn, the verdict is weighed, and the decision is made off the game thread. */
    private static void pray(ServerPlayerEntity player, BlockPos altar, ServerWorld world, String text, DivineConfig config, Judgement judgement) {
        String judgedBy = judgement.by();
        MinecraftServer server = world.getServer();
        DivineState state = DivineState.of(server);
        GodLedger.Soul soul = state.ledger.soul(player.getUuid());
        long now = server.getOverworld().getTime(), day = DeedWatcher.day(server);
        PrayerTone tone = PrayerTone.of(text);

        // Gifts thrown on the altar are taken whatever the answer.
        int offeringValue = 0; boolean insult = false;
        List<String> gifts = new ArrayList<>(), giftIds = new ArrayList<>();
        for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class, new Box(altar.up()).expand(0.75, 0.5, 0.75), ItemEntity::isAlive)) {
            String id = Registries.ITEM.getId(item.getStack().getItem()).toString();
            int count = item.getStack().getCount();
            offeringValue += Offerings.value(id, count);
            insult |= Offerings.insults(id);
            if (gifts.size() < 4) gifts.add(item.getStack().getName().getString() + " ×" + count);
            if (!giftIds.contains(id)) giftIds.add(id);
            item.discard();
        }
        if (!gifts.isEmpty()) {
            world.spawnParticles(ParticleTypes.FLAME, altar.getX() + 0.5, altar.getY() + 1.2, altar.getZ() + 0.5, 30, 0.3, 0.2, 0.3, 0.02);
            Miracles.sound(world, altar, SoundEvents.ITEM_FIRECHARGE_USE, 1f, 1.2f);
        }

        Righteousness righteousness = soul.righteousness;
        righteousness.drift(day);
        int recent = soul.recentPrayers(now);
        boolean storm = world.isThundering(), danger = player.getHealth() < player.getMaxHealth() * 0.3f;
        VerdictBuilder.Context context = new VerdictBuilder.Context(text, tone, offeringValue, String.join(", ", gifts), insult,
                righteousness.score, righteousness.label(), withConscience(righteousness.notable(day, 3, 2), righteousness), day, (int) Math.round(soul.mood(now)), recent,
                storm, world.getMoonPhase(), danger, judgement.insult(),
                righteousness.memories.stream().filter(m -> m.deed == Righteousness.Deed.BLASPHEMY && day - m.day <= 3).mapToInt(m -> m.count).sum());
        Verdict verdict = VerdictBuilder.build(context, config);
        Multiplayer.stamp(server, player, verdict);
        Multiplayer.journal(io.github.divineblock.core.GodJournal.Kind.PRAYER, player.getName().getString(), text);
        // Punishments in a row climb the ladder; sincere repentance under them (or under a lingering curse) is forgiven.
        int streak = soul.wrathStreak(day);
        boolean spinning = RouletteService.spinning(player);
        VerdictBuilder.forgiveBurden(verdict, tone.repents(), streak > 0 || !Curses.of(server, player.getUuid()).isEmpty() || spinning);
        VerdictBuilder.escalate(verdict, streak, config);
        if (verdict.forgiveness) {
            soul.forgiven();
            List<String> ended = new ArrayList<>();
            int lifted = Curses.lift(server, player.getUuid(), ended, "покаялся");
            VillageEvents.forgiven(player, altar, ended);
            PENANCE.end(player.getUuid()); // The punishment under way is forgiven too: the next sin starts a new one.
            // Its reels still spin: they jam into doves and it does not fall.
            if (RouletteService.pardon(player)) hint(player, "Рулетка заклинила: помилован");
            if (lifted > 0) hint(player, "Проклятия сняты: " + lifted);
        }

        soul.prayed(now);
        if (insult) soul.shiftMood(now, -15);
        else if (offeringValue > 0) soul.shiftMood(now, Math.min(25, 3 + offeringValue / 3.0));
        // Blasphemy of any kind: the god's mood drops to the bottom at once, and the sin is remembered.
        if (verdict.fury) { soul.shiftMood(now, -200); righteousness.record(Righteousness.Deed.BLASPHEMY, day, "", config.confessRelapseMultiplier); }
        else if (verdict.forgiveness) soul.shiftMood(now, 60 - soul.mood(now)); // Anger let go: the mood returns to mild favour.
        else if (tone.polite()) soul.shiftMood(now, 3);
        if (recent >= 2) soul.shiftMood(now, -5);
        state.markDirty();

        // One sin, one punishment: wrath while a punishment of his still runs makes that one heavier instead of laying another.
        String offering = String.join(",", giftIds);
        int offered = offeringValue;
        boolean jevInsult = judgement.insult();
        Penance.Outcome outcome = Penance.decide(verdict, PENANCE.running(player.getUuid(), now, lasting(player)));
        if (outcome == Penance.Outcome.STRENGTHEN) {
            THINKING.remove(player.getUuid());
            strengthen(player, altar, world, verdict, config, tone, jevInsult, offering, offered);
            return;
        }
        if (outcome == Penance.Outcome.PUNISH) PENANCE.begin(player.getUuid(), "", now);

        verdict.recentActs = soul.chronicle.stream().map(v -> v.miracle).skip(Math.max(0, soul.chronicle.size() - 3)).toList();
        List<String> recentLines = soul.chronicle.stream().map(v -> v.speech).filter(line -> line != null && !line.isBlank())
                .skip(Math.max(0, soul.chronicle.size() - 4)).toList();
        WORKER.execute(() -> {
            try {
                GodDecider.Choice choice = decider(config).choose(verdict, config);
                verdict.miracle = choice.miracle().id(); verdict.power = choice.power(); verdict.warned = choice.warn();
                verdict.decidedBy = choice.decidedBy(); verdict.decisionNote = choice.note() + "; молитву признал: " + judgedBy; verdict.group = choice.group();
                verdict.speech = verdict.chosen() == Miracle.IGNORE ? "" : new GodSpeech(config, DivineBlockMod.configDirectory()).announce(verdict, recentLines);
            } catch (RuntimeException failure) {
                DivineBlockMod.LOG.warn("Божественный блок: решение не принято", failure);
                verdict.miracle = Miracle.OMEN_LAUGH.id(); verdict.power = 0; verdict.warned = false; verdict.speech = "…";
            }
            server.execute(() -> {
                THINKING.remove(player.getUuid());
                record(player, verdict, tone, jevInsult, offering, offered);
                deliver(player, altar, world, verdict, config);
            });
        });
    }

    private static GodDecider decider(DivineConfig config) {
        RuleGodDecider rules = new RuleGodDecider();
        if (!config.useJev) return rules;
        try { return new JevGodDecider(ApiKeys.load(DivineBlockMod.configDirectory(), ApiKeys.Provider.OPENROUTER), rules); }
        catch (Exception missing) { DivineBlockMod.LOG.info("Божественный блок: Jev без ключа OpenRouter, решают правила"); return rules; }
    }

    /** Back on the game thread: the player must still be here. The god speaks first; the act falls when his voice ends. */
    private static void deliver(ServerPlayerEntity player, BlockPos altar, ServerWorld world, Verdict verdict, DivineConfig config) {
        if (Multiplayer.left(player)) { Multiplayer.heldBack(player, world, altar, verdict); return; }
        if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) { cancelled(player, verdict); return; }
        List<Verdict.Reason> why = verdict.mainReasons(1);
        Multiplayer.journal(io.github.divineblock.core.GodJournal.Kind.VERDICT, player.getName().getString(), verdict.chosen().kind.label + " «"
                + verdict.chosen().id() + "», сила " + verdict.power + (why.isEmpty() ? "" : ": " + why.get(0).text()));
        MinecraftServer server = world.getServer();
        DivineState.of(server).ledger.soul(player.getUuid()).remember(verdict);
        DivineState.of(server).markDirty();
        DivineBlockMod.LOG.info("Божественный блок: вердикт для {}\n{}", player.getName().getString(), verdict.breakdown());
        if (config.debug) player.sendMessage(Text.literal(verdict.breakdown()).formatted(Formatting.DARK_GRAY), false);
        if (verdict.chosen() == Miracle.IGNORE) { act(player, altar, verdict, config); return; } // Silence: no word at all.
        int color = beamColor(verdict.chosen().kind);
        // The music follows the verdict, not the act: wrath that fell back to a thunder omen is still a punishment.
        int music = verdict.inclination == Miracle.Kind.HELP ? BeamsPayload.BLESSING : verdict.inclination == Miracle.Kind.WRATH ? BeamsPayload.PUNISHMENT : BeamsPayload.NO_MUSIC;
        // From the second wrath group on (whatever the act's own kind: a transmuted treasure is still a punishment), the sky reddens while he speaks and while the punishment falls.
        MiracleGroup group = MiracleGroup.byId(verdict.group);
        int sky = group != null && group.kind == Miracle.Kind.WRATH ? Math.max(0, group.ordinal() - MiracleGroup.K1.ordinal()) : 0;
        double estimate = Math.max(3, verdict.speech.split("\s+").length * 0.45); // Seconds the words may take, for the glow.
        if (RouletteService.applies(verdict.inclination, verdict.chosen(), config)) { spinThen(player, altar, world, verdict, config, color, music, sky, estimate); return; }
        speakThen(player, verdict.speech, () -> {
            if (player.isRemoved() || player.getServerWorld() != world) return;
            Miracles.speaking(player, altar, verdict.chosen().kind, estimate);
            Portents.before(player, altar, verdict.chosen(), estimate);
            if (ServerPlayNetworking.canSend(player, BeamsPayload.ID))
                ServerPlayNetworking.send(player, new BeamsPayload(altar, 20 * 90, color, music, sky));
        }, () -> {
            if (ServerPlayNetworking.canSend(player, BeamsPayload.ID)) ServerPlayNetworking.send(player, new BeamsPayload(altar, 0, color, music, sky));
            // Logged out while the god spoke: the punishment waits for him; death or another world still cancels it.
            if (Multiplayer.left(player)) { Multiplayer.heldBack(player, world, altar, verdict); return; }
            if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) { cancelled(player, verdict); return; }
            Miracles.flourish(player, altar, verdict.chosen().kind, verdict.power);
            if (verdict.warned) {
                List<Verdict.Reason> main = verdict.mainReasons(1);
                title(player, Text.literal("Гнев небес").formatted(Formatting.DARK_RED),
                        Text.literal(main.isEmpty() ? "Мене, мене, текел, упарсин" : main.get(0).text()).formatted(Formatting.RED));
            }
            act(player, altar, verdict, config);
        });
    }

    /** A punishment through the roulette: first the god says his word (the portents, the red sky and the beams begin with
     * it); his client reports when the voice has ended, and only then — after the echo's pause, or at the deadline if the
     * client never answers — the reels start; the act falls exactly when the machine leaves, on the server's ticks. The
     * portents last for the words and the whole spin. A pardon while he speaks or while the reels turn takes it all back; a
     * sinner who leaves is held back as before; death or another world cancel it. */
    private static void spinThen(ServerPlayerEntity player, BlockPos altar, ServerWorld world, Verdict verdict, DivineConfig config,
                                 int color, int music, int sky, double estimate) {
        Miracle chosen = verdict.chosen();
        MinecraftServer server = world.getServer();
        int spinTicks = io.github.divineblock.core.Roulette.Timing.of(config.rouletteMode, io.github.divineblock.core.Roulette.severity(chosen, MiracleGroup.byId(verdict.group))).act();
        boolean[] begun = new boolean[1];
        Runnable begin = () -> {
            // The word and the spin together are the window of portents: it never gets shorter than it used to be.
            if (begun[0] || player.isRemoved()) return;
            begun[0] = true;
            Miracles.speaking(player, altar, chosen.kind, estimate);
            Portents.before(player, altar, chosen, estimate + spinTicks / 20.0);
            if (ServerPlayNetworking.canSend(player, BeamsPayload.ID)) ServerPlayNetworking.send(player, new BeamsPayload(altar, 20 * 90, color, music, sky));
        };
        Runnable beamsOff = () -> {
            if (!player.isRemoved() && ServerPlayNetworking.canSend(player, BeamsPayload.ID)) ServerPlayNetworking.send(player, new BeamsPayload(altar, 0, color, music, sky));
        };
        // Up to fifteen seconds for the voice (its synthesis may take a moment to begin): then the reels go anyway.
        int wait = Math.min(io.github.divineblock.core.SpeechGate.MAX_WAIT_TICKS, io.github.divineblock.core.SpeechGate.timeoutTicks(io.github.divineblock.core.SpeechGate.words(verdict.speech)) + 60);
        RouletteService.GATE.open(player.getUuid(), RouletteService.now(server), wait, () -> {
            if (Multiplayer.left(player)) { beamsOff.run(); Multiplayer.heldBack(player, world, altar, verdict); return; }
            if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) { beamsOff.run(); cancelled(player, verdict); return; }
            begin.run();
            spin(player, altar, world, verdict, config, beamsOff);
        }, beamsOff);
        speakThen(player, verdict.speech, begin, () -> RouletteService.spoken(player));
    }

    /** The reels of a punishment the god has announced. */
    private static void spin(ServerPlayerEntity player, BlockPos altar, ServerWorld world, Verdict verdict, DivineConfig config, Runnable beamsOff) {
        Miracle chosen = verdict.chosen();
        List<Verdict.Reason> main = verdict.mainReasons(1);
        RouletteService.spin(player, chosen, MiracleGroup.byId(verdict.group), main.isEmpty() ? "" : main.get(0).text(), config.rouletteMode, null,
                new RouletteService.Steps() {
                    @Override public void act() {
                        beamsOff();
                        if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) { Prayers.cancelled(player, verdict); return; }
                        Miracles.flourish(player, altar, chosen.kind, verdict.power);
                        Prayers.act(player, altar, verdict, config);
                    }
                    @Override public void spared() {
                        // Spared by chance: the punishment does not fall, the ladder of punishments stays (no forgiveness).
                        beamsOff();
                        verdict.decisionNote = verdict.decisionNote + "; рулетка: пощажён случаем";
                        Prayers.cancelled(player, verdict);
                        performed.accept("spared");
                    }
                    @Override public void cancelled(io.github.divineblock.core.RouletteQueue.Cancel why) {
                        beamsOff();
                        if (why == io.github.divineblock.core.RouletteQueue.Cancel.LEFT) Multiplayer.heldBack(player, world, altar, verdict);
                        else if (why != io.github.divineblock.core.RouletteQueue.Cancel.PARDON) Prayers.cancelled(player, verdict);
                    }
                    private void beamsOff() { beamsOff.run(); }
                });
    }

    /** A sinner who logged out before his punishment fell is back: the god names it and it falls at once, without a second
     * speech (the first was already said). */
    static void strikeReturned(ServerPlayerEntity player, BlockPos altar, Verdict verdict) {
        DivineConfig config = DivineBlockMod.config();
        chat(player, "Ты бежал от суда, но суд дождался тебя.");
        List<Verdict.Reason> main = verdict.mainReasons(1);
        title(player, Text.literal("Гнев небес").formatted(Formatting.DARK_RED),
                Text.literal(main.isEmpty() ? "Мене, мене, текел, упарсин" : main.get(0).text()).formatted(Formatting.RED));
        Miracles.flourish(player, altar, verdict.chosen().kind, verdict.power);
        act(player, altar, verdict, config);
    }

    static int beamColor(Miracle.Kind kind) {
        return switch (kind) { case HELP -> 0xFFFFD24A; case JEST -> 0xFFB050FF; case WRATH -> 0xFFFF2A1A; case OMEN -> 0xFFFFFFFF; };
    }

    /** A line on its way through the client's voice: started (the words are heard) and ended (the last word is said). */
    private static final class Speaking {
        final String line; final ServerPlayerEntity player; final Runnable onStart, onEnd;
        boolean started, ended;
        /** The line as the overlay keeps it, for the length of its voice. */
        io.github.divineblock.core.StreamOverlay.Line said;
        Speaking(ServerPlayerEntity player, String line, Runnable onStart, Runnable onEnd) { this.player = player; this.line = line; this.onStart = onStart; this.onEnd = onEnd; }
        void start() { if (started) return; started = true; said = chat(player, line); onStart.run(); }
        void end() { if (ended) return; start(); ended = true; onEnd.run(); }
    }
    private static final java.util.Map<String, Speaking> WAITING = new java.util.HashMap<>();
    private static int nextLine = 1;
    /** How long to wait for the voice to begin, and then to end, before acting anyway (no mod on the client, a stalled service). */
    static final int VOICE_START_TIMEOUT_TICKS = 20 * 20, VOICE_END_TIMEOUT_TICKS = 20 * 90;

    /** Sends the line to be voiced. The chat text and {@code onStart} come when the voice starts; {@code onEnd} when it ends. */
    private static void speakThen(ServerPlayerEntity player, String line, Runnable onStart, Runnable onEnd) {
        Speaking speaking = new Speaking(player, line, onStart, onEnd);
        boolean voiced = line != null && !line.isBlank() && !"…".equals(line) && ServerPlayNetworking.canSend(player, GodLinePayload.ID);
        if (!voiced) { speaking.end(); return; }
        int id = nextLine++;
        String key = player.getUuid() + "/" + id;
        WAITING.put(key, speaking);
        String said = line.length() > 900 ? line.substring(0, 900) : line;
        // A dedicated server with a key voices the god itself, for everyone near: a friend without a key hears him too.
        if (ServerGodVoice.available(player.getServer(), DivineBlockMod.config()) && ServerPlayNetworking.canSend(player, GodAudioPayload.ID))
            ServerGodVoice.speak(player, id, said, () -> { if (!player.isRemoved()) ServerPlayNetworking.send(player, new GodLinePayload(id, said)); });
        else ServerPlayNetworking.send(player, new GodLinePayload(id, said));
        Scheduler.later(VOICE_START_TIMEOUT_TICKS, () -> {
            if (!speaking.started && WAITING.remove(key) != null) speaking.end();
        });
    }

    /** The client's voice news: {@code seconds < 0} — it started (length unknown yet); otherwise it ended or never came. */
    public static void voiceStarted(ServerPlayerEntity player, int id, float seconds) {
        String key = player.getUuid() + "/" + id;
        Speaking speaking = WAITING.get(key);
        if (speaking == null) return;
        if (seconds < 0) {
            speaking.start();
            Scheduler.later(VOICE_END_TIMEOUT_TICKS, () -> { if (WAITING.remove(key) != null) speaking.end(); });
            return;
        }
        WAITING.remove(key);
        io.github.divineblock.core.StreamOverlay.voiced(speaking.said, Math.round(seconds * 1000.0));
        speaking.end();
    }

    private static void act(ServerPlayerEntity player, BlockPos altar, Verdict verdict, DivineConfig config) {
        // What Mob Talk's villages hear of it: the god answered a prayer, for these reasons.
        List<String> why = verdict.mainReasons(3).stream().map(Verdict.Reason::text).toList();
        VillageEvents.within(new VillageEvents.Context(true, why.isEmpty() ? "" : why.get(0), why), () -> { actHere(player, altar, verdict, config); return null; });
    }

    private static void actHere(ServerPlayerEntity player, BlockPos altar, Verdict verdict, DivineConfig config) {
        Miracle miracle = verdict.chosen();
        MinecraftServer server = player.getServer();
        long now = server.getOverworld().getTime();
        // Sins said while the god was still speaking made this punishment heavier: a rung of power for each.
        Penance.Running running = miracle.kind == Miracle.Kind.WRATH ? PENANCE.of(player.getUuid(), now) : null;
        int power = Math.min(miracle.maxTier, verdict.power + (running == null ? 0 : running.strengthened));
        String done;
        try { done = Zones.perform(miracle, power, player, altar, verdict.prayer, config, verdict.severity()); }
        catch (RuntimeException failure) { DivineBlockMod.LOG.warn("Божественный блок: чудо {} не удалось", miracle.id(), failure); done = null; }
        if (done == null) {
            // The act cannot happen here (the Nether has no sky, the altar's land is protected): another act of the same
            // group takes its place, so the verdict is still kept in full. Only when none of them can does an omen answer.
            MiracleGroup group = MiracleGroup.byId(verdict.group);
            List<Miracle> others = group == null ? List.of() : new java.util.ArrayList<>(group.allowed(verdict, config));
            java.util.Collections.shuffle(others);
            for (Miracle other : others) {
                if (other == miracle) continue;
                try { done = Zones.perform(other, GodDecider.power(other, verdict.power, verdict.budget), player, altar, verdict.prayer, config, verdict.severity()); }
                catch (RuntimeException failure) { DivineBlockMod.LOG.warn("Божественный блок: чудо {} не удалось", other.id(), failure); done = null; }
                if (done != null) { DivineBlockMod.LOG.info("Божественный блок: {} здесь невозможно, вместо него {}", miracle.id(), other.id()); miracle = other; break; }
            }
        }
        boolean fell = done != null;
        if (done == null) {
            Miracle omen = miracle.kind == Miracle.Kind.WRATH ? Miracle.OMEN_THUNDER : Miracle.OMEN_LIGHT;
            done = Miracles.perform(omen, 0, player, altar, verdict.prayer, config) + " (" + miracle.id() + " не вышло)";
        }
        hint(player, "✦ " + done);
        performed.accept(miracle.id());
        // The overlay learns of the act as it falls, never earlier (no spoiler while the god still speaks).
        AdminBridge.shown(player, verdict, fell ? miracle : miracle.kind == Miracle.Kind.WRATH ? Miracle.OMEN_THUNDER : Miracle.OMEN_LIGHT);
        Multiplayer.journal(io.github.divineblock.core.GodJournal.Kind.MIRACLE, player.getName().getString(), miracle.id() + ": " + done);
        if (fell && miracle.kind == Miracle.Kind.WRATH) PENANCE.fell(player.getUuid(), miracle.id(), now);
        else if (verdict.inclination == Miracle.Kind.WRATH) PENANCE.end(player.getUuid()); // only an omen came: nothing runs
        if (fell && miracle.kind == Miracle.Kind.HELP) AdminBridge.blessed(player, miracle, power);
        if (fell && miracle.kind == Miracle.Kind.WRATH && miracle != Miracle.ETERNAL_BAN) {
            DivineState.of(server).ledger.soul(player.getUuid()).punished(DeedWatcher.day(server));
            DivineState.of(server).markDirty();
        }
    }

    /** The punishment was announced but cannot fall (the sinner died or went to another world): nothing runs any more. */
    private static void cancelled(ServerPlayerEntity player, Verdict verdict) {
        if (verdict.inclination == Miracle.Kind.WRATH) PENANCE.end(player.getUuid());
    }

    /** What his punishment is still doing to him right now: the god with the mace over him, the Sky Court. A curse or a boss
     * left behind does not hold the next punishment back (Dmitriy, 23 Oct: «кара не окончена» for minutes was wrong). */
    private static boolean lasting(ServerPlayerEntity player) {
        return SkyCourt.inCourt(player) || io.github.divineblock.boss.DivineBosses.godAvatarActive(player);
    }

    /** The sacred bosses near him that are after him. */
    static List<io.github.divineblock.boss.SacredBoss> hunters(ServerPlayerEntity player) {
        return io.github.divineblock.boss.DivineBosses.near(player.getServerWorld(), player.getBlockPos(), 128).stream()
                .filter(boss -> boss.getTarget() != null && boss.getTarget().getUuid().equals(player.getUuid())).toList();
    }

    /** A sin under a running punishment: no new act. Its curses last longer (within twenty minutes), the bosses after him
     * get a quarter of their health back, an act still on its way falls a rung harder, the god says so and thunder rolls. */
    private static void strengthen(ServerPlayerEntity player, BlockPos altar, ServerWorld world, Verdict verdict, DivineConfig config,
                                   PrayerTone tone, boolean insult, String offering, int offeringValue) {
        MinecraftServer server = world.getServer();
        long now = server.getOverworld().getTime();
        Penance.Running running = PENANCE.strengthen(player.getUuid(), "", now);
        int prolonged = Curses.prolong(server, player.getUuid(), Penance.PROLONG_TICKS);
        List<io.github.divineblock.boss.SacredBoss> bosses = hunters(player);
        for (io.github.divineblock.boss.SacredBoss boss : bosses) boss.heal(boss.getMaxHealth() * 0.25f);
        String what = "кара" + (running.miracle.isBlank() ? "" : " «" + running.miracle + "»") + " усилена (" + running.strengthened + "-й раз)"
                + (prolonged > 0 ? ", проклятия продлены: " + prolonged : "") + (bosses.isEmpty() ? "" : ", боссы окрепли: " + bosses.size());
        verdict.miracle = Miracle.OMEN_THUNDER.id(); verdict.power = 0; verdict.warned = false; verdict.group = "";
        verdict.decidedBy = "rules"; verdict.decisionNote = "кара уже идёт — новой нет: " + what;
        verdict.speech = Penance.line(running.strengthened);
        DivineState state = DivineState.of(server);
        state.ledger.soul(player.getUuid()).remember(verdict);
        // A sin under a running punishment is still a sin: the ladder of punishments goes a rung up, the next one falls harder.
        state.ledger.soul(player.getUuid()).punished(DeedWatcher.day(server));
        state.markDirty();
        record(player, verdict, tone, insult, offering, offeringValue);
        Multiplayer.journal(io.github.divineblock.core.GodJournal.Kind.VERDICT, player.getName().getString(), what);
        DivineBlockMod.LOG.info("Божественный блок: {} грешит под карой — {}", player.getName().getString(), what);
        hint(player, "✦ " + what);
        speakThen(player, verdict.speech, () -> { }, () -> {
            if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) return;
            Miracles.perform(Miracle.OMEN_THUNDER, 0, player, altar, verdict.prayer, config);
        });
    }

    /** The heard prayer and the god's answer, for the web admin. */
    private static void record(ServerPlayerEntity player, Verdict verdict, PrayerTone tone, boolean insult, String offering, int offeringValue) {
        PrayerLog.shared().add(PrayerLog.of(System.currentTimeMillis(), player.getUuidAsString(), player.getName().getString(), verdict, tone, insult,
                offering, offeringValue));
    }

    /** Every line the god says, every utterance he did not count as a prayer and every act, for the self-test's report. */
    public static volatile java.util.function.Consumer<String> spoken = line -> { };
    public static volatile java.util.function.Consumer<String> unheard = text -> { };
    public static volatile java.util.function.Consumer<String> performed = act -> { };

    /** A line with nothing to follow it (the answer to "why?", a refusal): voiced and shown together. */
    static void say(ServerPlayerEntity player, String line) { speakThen(player, line, () -> { }, () -> { }); }
    /** A line of the god outside a verdict (the god with the mace): voiced when the voice works, always shown in chat. */
    public static void proclaim(ServerPlayerEntity player, String line) { say(player, line); }

    /** A line of the god in chat only, without the voice: what the zones of punishment explain (where, why). */
    public static void godChat(ServerPlayerEntity player, String line) { chat(player, line); }

    private static io.github.divineblock.core.StreamOverlay.Line chat(ServerPlayerEntity player, String line) {
        spoken.accept(line);
        String text = line == null || line.isBlank() ? "…" : line;
        List<ServerPlayerEntity> hearers = ServerGodVoice.listeners(player);
        for (ServerPlayerEntity hearer : hearers) {
            Text head = hearer == player ? Text.literal("[Бог] ").formatted(Formatting.GOLD, Formatting.BOLD)
                    : Text.literal("[Бог → " + player.getName().getString() + "] ").formatted(Formatting.GOLD, Formatting.BOLD);
            hearer.sendMessage(head.copy().append(Text.literal(text).formatted(Formatting.YELLOW, Formatting.ITALIC)), false);
        }
        if (line == null || line.isBlank() || "…".equals(line)) return null;
        return io.github.divineblock.core.StreamOverlay.shared().line(System.currentTimeMillis(), line, hearers.stream().map(ServerPlayerEntity::getUuidAsString).toList());
    }

    /** The heard prayer, for the others near the altar: who prays and what he says (he himself knows). */
    private static void echoPrayer(ServerPlayerEntity player, String text) {
        for (ServerPlayerEntity hearer : ServerGodVoice.listeners(player))
            if (hearer != player) hearer.sendMessage(Text.literal(player.getName().getString() + " молится: «" + text + "»").formatted(Formatting.GRAY, Formatting.ITALIC), false);
    }
    /** A service line (heard, ignored, what happened): only in debug mode, so the game itself shows nothing but the god. */
    static void hint(ServerPlayerEntity player, String line) {
        if (DivineBlockMod.config().debug) player.sendMessage(Text.literal(line).formatted(Formatting.GOLD), true);
    }
    private static void title(ServerPlayerEntity player, Text title, Text subtitle) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 60, 20));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
    }

    /** Forgets in-flight prayers and unvoiced lines when the server stops. */
    static void clear() { THINKING.clear(); WAITING.clear(); PENANCE.clear(); }
}
