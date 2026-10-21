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
 * second chance: he never asks back, never delays, and an announced punishment cannot be talked away. */
public final class Prayers {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "divineblock-worker"); thread.setDaemon(true); return thread;
    });
    private static final java.util.Set<UUID> THINKING = new java.util.HashSet<>();

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
        if (!(world.getBlockState(altar).getBlock() instanceof AltarBlock) || !altar.isWithinDistance(player.getBlockPos(), 8)) return;
        MinecraftServer server = world.getServer();
        DivineConfig config = DivineBlockMod.config();
        GodLedger.Soul soul = DivineState.of(server).ledger.soul(player.getUuid());

        if (THINKING.contains(player.getUuid())) { hint(player, "Бог ещё размышляет над прежней молитвой…"); return; }
        long now = server.getOverworld().getTime();
        if (now - soul.lastPrayerTick < config.cooldownSeconds * 20L) { hint(player, "Алтарь ещё не остыл. Повремени, смертный."); return; }

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
                pray(player, altar, world, text, config, judgement);
            });
        });
    }

    /** Whether the words are a prayer and whether they insult the god: Jev decides, the rules answer when Jev is off or silent. */
    record Judgement(boolean prayer, boolean insult, String by) { }
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
        List<String> gifts = new ArrayList<>();
        for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class, new Box(altar.up()).expand(0.75, 0.5, 0.75), ItemEntity::isAlive)) {
            String id = Registries.ITEM.getId(item.getStack().getItem()).toString();
            int count = item.getStack().getCount();
            offeringValue += Offerings.value(id, count);
            insult |= Offerings.insults(id);
            if (gifts.size() < 4) gifts.add(item.getStack().getName().getString() + " ×" + count);
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
                righteousness.score, righteousness.label(), righteousness.notable(day, 3, 2), day, (int) Math.round(soul.mood(now)), recent,
                storm, world.getMoonPhase(), danger, judgement.insult(),
                righteousness.memories.stream().filter(m -> m.deed == Righteousness.Deed.BLASPHEMY && day - m.day <= 3).mapToInt(m -> m.count).sum());
        Verdict verdict = VerdictBuilder.build(context, config);

        soul.prayed(now);
        if (insult) soul.shiftMood(now, -15);
        else if (offeringValue > 0) soul.shiftMood(now, Math.min(25, 3 + offeringValue / 3.0));
        // Blasphemy of any kind: the god's mood drops to the bottom at once, and the sin is remembered.
        if (verdict.fury) { soul.shiftMood(now, -200); righteousness.record(Righteousness.Deed.BLASPHEMY, day, ""); }
        else if (verdict.forgiveness) soul.shiftMood(now, 60 - soul.mood(now)); // Anger let go: the mood returns to mild favour.
        else if (tone.polite()) soul.shiftMood(now, 3);
        if (recent >= 2) soul.shiftMood(now, -5);
        state.markDirty();

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
            server.execute(() -> { THINKING.remove(player.getUuid()); deliver(player, altar, world, verdict, config); });
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
        if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) return;
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
        speakThen(player, verdict.speech, () -> {
            if (player.isRemoved() || player.getServerWorld() != world) return;
            Miracles.speaking(player, altar, verdict.chosen().kind, estimate);
            if (ServerPlayNetworking.canSend(player, BeamsPayload.ID))
                ServerPlayNetworking.send(player, new BeamsPayload(altar, 20 * 90, color, music, sky));
        }, () -> {
            if (ServerPlayNetworking.canSend(player, BeamsPayload.ID)) ServerPlayNetworking.send(player, new BeamsPayload(altar, 0, color, music, sky));
            if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) return;
            Miracles.flourish(player, altar, verdict.chosen().kind, verdict.power);
            if (verdict.warned) {
                List<Verdict.Reason> main = verdict.mainReasons(1);
                title(player, Text.literal("Гнев небес").formatted(Formatting.DARK_RED),
                        Text.literal(main.isEmpty() ? "Мене, мене, текел, упарсин" : main.get(0).text()).formatted(Formatting.RED));
            }
            act(player, altar, verdict, config);
        });
    }

    static int beamColor(Miracle.Kind kind) {
        return switch (kind) { case HELP -> 0xFFFFD24A; case JEST -> 0xFFB050FF; case WRATH -> 0xFFFF2A1A; case OMEN -> 0xFFFFFFFF; };
    }

    /** A line on its way through the client's voice: started (the words are heard) and ended (the last word is said). */
    private static final class Speaking {
        final String line; final ServerPlayerEntity player; final Runnable onStart, onEnd;
        boolean started, ended;
        Speaking(ServerPlayerEntity player, String line, Runnable onStart, Runnable onEnd) { this.player = player; this.line = line; this.onStart = onStart; this.onEnd = onEnd; }
        void start() { if (started) return; started = true; chat(player, line); onStart.run(); }
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
        ServerPlayNetworking.send(player, new GodLinePayload(id, line.length() > 900 ? line.substring(0, 900) : line));
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
        speaking.end();
    }

    private static void act(ServerPlayerEntity player, BlockPos altar, Verdict verdict, DivineConfig config) {
        Miracle miracle = verdict.chosen();
        String done;
        try { done = Miracles.perform(miracle, verdict.power, player, altar, verdict.prayer, config, verdict.severity()); }
        catch (RuntimeException failure) { DivineBlockMod.LOG.warn("Божественный блок: чудо {} не удалось", miracle.id(), failure); done = null; }
        if (done == null) {
            // The act cannot happen here (the Nether has no sky, the altar's land is protected): another act of the same
            // group takes its place, so the verdict is still kept in full. Only when none of them can does an omen answer.
            MiracleGroup group = MiracleGroup.byId(verdict.group);
            List<Miracle> others = group == null ? List.of() : new java.util.ArrayList<>(group.allowed(verdict, config));
            java.util.Collections.shuffle(others);
            for (Miracle other : others) {
                if (other == miracle) continue;
                try { done = Miracles.perform(other, GodDecider.power(other, verdict.power, verdict.budget), player, altar, verdict.prayer, config, verdict.severity()); }
                catch (RuntimeException failure) { DivineBlockMod.LOG.warn("Божественный блок: чудо {} не удалось", other.id(), failure); done = null; }
                if (done != null) { DivineBlockMod.LOG.info("Божественный блок: {} здесь невозможно, вместо него {}", miracle.id(), other.id()); miracle = other; break; }
            }
        }
        if (done == null) {
            Miracle omen = miracle.kind == Miracle.Kind.WRATH ? Miracle.OMEN_THUNDER : Miracle.OMEN_LIGHT;
            done = Miracles.perform(omen, 0, player, altar, verdict.prayer, config) + " (" + miracle.id() + " не вышло)";
        }
        hint(player, "✦ " + done);
        performed.accept(miracle.id());
    }

    /** Every line the god says, every utterance he did not count as a prayer and every act, for the self-test's report. */
    public static volatile java.util.function.Consumer<String> spoken = line -> { };
    public static volatile java.util.function.Consumer<String> unheard = text -> { };
    public static volatile java.util.function.Consumer<String> performed = act -> { };

    /** A line with nothing to follow it (the answer to "why?", a refusal): voiced and shown together. */
    static void say(ServerPlayerEntity player, String line) { speakThen(player, line, () -> { }, () -> { }); }

    private static void chat(ServerPlayerEntity player, String line) {
        spoken.accept(line);
        player.sendMessage(Text.literal("[Бог] ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal(line == null || line.isBlank() ? "…" : line).formatted(Formatting.YELLOW, Formatting.ITALIC)), false);
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
    static void clear() { THINKING.clear(); WAITING.clear(); }
}
