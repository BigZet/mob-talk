package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.ApiKeys;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.GodDecider;
import io.github.divineblock.core.GodLedger;
import io.github.divineblock.core.GodSpeech;
import io.github.divineblock.core.JevGodDecider;
import io.github.divineblock.core.Miracle;
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

    public static void handle(ServerPlayerEntity player, BlockPos altar, String rawText) {
        ServerWorld world = player.getServerWorld();
        String text = rawText == null ? "" : rawText.replaceAll("\\p{Cntrl}", " ").strip();
        if (text.isEmpty() || text.length() > PrayPayload.MAX_TEXT) return;
        if (!(world.getBlockState(altar).getBlock() instanceof AltarBlock) || !altar.isWithinDistance(player.getBlockPos(), 8)) return;
        MinecraftServer server = world.getServer();
        DivineConfig config = DivineBlockMod.config();
        GodLedger.Soul soul = DivineState.of(server).ledger.soul(player.getUuid());

        if (PrayerTone.isWhyQuestion(text)) {
            Verdict last = soul.lastVerdict();
            if (last == null) { say(player, "Я ещё ничего о тебе не решал, смертный."); return; }
            WORKER.execute(() -> {
                String line = new GodSpeech(config, DivineBlockMod.configDirectory()).explain(last);
                server.execute(() -> { if (!player.isRemoved()) say(player, line); });
            });
            return;
        }

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
                pray(player, altar, world, text, config, judgement.by());
            });
        });
    }

    /** Whether the words are a prayer: Jev decides, the rules answer when Jev is off or silent. */
    record Judgement(boolean prayer, String by) { }
    static Judgement judge(DivineConfig config, String text) {
        if (config.useJev) {
            try {
                Boolean jev = new JevGodDecider(ApiKeys.load(DivineBlockMod.configDirectory(), ApiKeys.Provider.OPENROUTER), new RuleGodDecider()).isPrayer(text);
                if (jev != null) return new Judgement(jev, "jev");
            } catch (Exception missing) { DivineBlockMod.LOG.info("Божественный блок: Jev без ключа, молитву распознают правила"); }
        }
        return new Judgement(PrayerTone.addressesGod(text), "rules");
    }

    /** The heard prayer: the gifts burn, the verdict is weighed, and the decision is made off the game thread. */
    private static void pray(ServerPlayerEntity player, BlockPos altar, ServerWorld world, String text, DivineConfig config, String judgedBy) {
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
                storm, world.getMoonPhase(), danger);
        Verdict verdict = VerdictBuilder.build(context, config);

        soul.prayed(now);
        if (insult) soul.shiftMood(now, -15);
        else if (offeringValue > 0) soul.shiftMood(now, Math.min(25, 3 + offeringValue / 3.0));
        if (tone.rude()) { soul.shiftMood(now, -15); righteousness.record(Righteousness.Deed.RUDE_PRAYER, day, ""); }
        else if (tone.polite()) soul.shiftMood(now, 3);
        if (recent >= 2) soul.shiftMood(now, -5);
        state.markDirty();

        WORKER.execute(() -> {
            try {
                GodDecider.Choice choice = decider(config).choose(verdict, config);
                verdict.miracle = choice.miracle().id(); verdict.power = choice.power(); verdict.warned = choice.warn();
                verdict.decidedBy = choice.decidedBy(); verdict.decisionNote = choice.note() + "; молитву признал: " + judgedBy;
                verdict.speech = verdict.chosen() == Miracle.IGNORE ? "" : new GodSpeech(config, DivineBlockMod.configDirectory()).announce(verdict);
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

    /** Back on the game thread: the player must still be here, and then the god acts at once. */
    private static void deliver(ServerPlayerEntity player, BlockPos altar, ServerWorld world, Verdict verdict, DivineConfig config) {
        if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) return;
        MinecraftServer server = world.getServer();
        DivineState.of(server).ledger.soul(player.getUuid()).remember(verdict);
        DivineState.of(server).markDirty();
        DivineBlockMod.LOG.info("Божественный блок: вердикт для {}\n{}", player.getName().getString(), verdict.breakdown());
        if (config.debug) player.sendMessage(Text.literal(verdict.breakdown()).formatted(Formatting.DARK_GRAY), false);
        if (verdict.chosen() == Miracle.IGNORE) { act(player, altar, verdict, config); return; } // Silence: no word at all.
        say(player, verdict.speech);
        if (verdict.warned) {
            // The wrath is announced and falls in the same breath: thunder, the title, the act.
            Miracles.warn(player, altar);
            List<Verdict.Reason> main = verdict.mainReasons(1);
            title(player, Text.literal("Гнев небес").formatted(Formatting.DARK_RED),
                    Text.literal(main.isEmpty() ? "Мене, мене, текел, упарсин" : main.get(0).text()).formatted(Formatting.RED));
        }
        act(player, altar, verdict, config);
    }

    private static void act(ServerPlayerEntity player, BlockPos altar, Verdict verdict, DivineConfig config) {
        Miracle miracle = verdict.chosen();
        String done;
        try { done = Miracles.perform(miracle, verdict.power, player, altar, verdict.prayer, config); }
        catch (RuntimeException failure) { DivineBlockMod.LOG.warn("Божественный блок: чудо {} не удалось", miracle.id(), failure); done = null; }
        if (done == null) {
            // Nothing to act on here (empty hand, the Nether's roof): the god answers with a sign instead of half a miracle.
            Miracle omen = miracle.kind == Miracle.Kind.WRATH ? Miracle.OMEN_THUNDER : Miracle.OMEN_LIGHT;
            done = Miracles.perform(omen, 0, player, altar, verdict.prayer, config) + " (" + miracle.id() + " не вышло)";
        }
        hint(player, "✦ " + done);
    }

    /** Every line the god says and every utterance he did not count as a prayer, for the self-test's report. */
    public static volatile java.util.function.Consumer<String> spoken = line -> { };
    public static volatile java.util.function.Consumer<String> unheard = text -> { };

    static void say(ServerPlayerEntity player, String line) {
        spoken.accept(line);
        if (line != null && !line.isBlank() && !"…".equals(line) && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, GodLinePayload.ID))
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, new GodLinePayload(line.length() > 600 ? line.substring(0, 600) : line));
        player.sendMessage(Text.literal("[Бог] ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal(line == null || line.isBlank() ? "…" : line).formatted(Formatting.YELLOW, Formatting.ITALIC)), false);
    }
    static void hint(ServerPlayerEntity player, String line) { player.sendMessage(Text.literal(line).formatted(Formatting.GOLD), true); }
    private static void title(ServerPlayerEntity player, Text title, Text subtitle) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 60, 20));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
    }

    /** Forgets in-flight prayers when the server stops. */
    static void clear() { THINKING.clear(); }
}
