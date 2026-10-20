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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/** A prayer from words to deed. The game thread gathers the facts and the gifts; a worker asks Jev and the chat model; the
 * game thread performs the act if the player is still there, alive, in the same world. Serious wrath waits behind an omen. */
public final class Prayers {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "divineblock-worker"); thread.setDaemon(true); return thread;
    });
    /** A punishment announced and not yet fallen, per player: repenting in time can stop it. */
    private record Pending(Verdict verdict, BlockPos altar, net.minecraft.registry.RegistryKey<net.minecraft.world.World> world, boolean[] cancelled) { }
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final java.util.Set<UUID> THINKING = new java.util.HashSet<>();

    private Prayers() { }

    public static void handle(ServerPlayerEntity player, BlockPos altar, String rawText) {
        ServerWorld world = player.getServerWorld();
        String text = rawText == null ? "" : rawText.replaceAll("\\p{Cntrl}", " ").strip();
        if (text.isEmpty() || text.length() > PrayPayload.MAX_TEXT) return;
        if (!(world.getBlockState(altar).getBlock() instanceof AltarBlock) || !altar.isWithinDistance(player.getBlockPos(), 8)) return;
        MinecraftServer server = world.getServer();
        DivineConfig config = DivineBlockMod.config();
        DivineState state = DivineState.of(server);
        GodLedger.Soul soul = state.ledger.soul(player.getUuid());
        long now = server.getOverworld().getTime(), day = DeedWatcher.day(server);
        PrayerTone tone = PrayerTone.of(text);

        Pending pending = PENDING.get(player.getUuid());
        if (pending != null && tone.repents()) { repent(player, pending, soul, now); state.markDirty(); return; }

        if (PrayerTone.isWhyQuestion(text)) {
            Verdict last = soul.lastVerdict();
            if (last == null) { say(player, "Я ещё ничего не решал о тебе, смертный."); return; }
            Verdict copy = last;
            WORKER.execute(() -> {
                String line = new GodSpeech(config, DivineBlockMod.configDirectory()).explain(copy);
                server.execute(() -> { if (!player.isRemoved()) say(player, line); });
            });
            return;
        }

        if (THINKING.contains(player.getUuid())) { hint(player, "Бог ещё обдумывает прошлую молитву…"); return; }
        if (now - soul.lastPrayerTick < config.cooldownSeconds * 20L) { hint(player, "Алтарь ещё не остыл. Подожди немного."); return; }

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

        hint(player, "Бог внимает…");
        world.spawnParticles(ParticleTypes.ENCHANT, altar.getX() + 0.5, altar.getY() + 1.5, altar.getZ() + 0.5, 80, 0.5, 0.8, 0.5, 0.5);
        THINKING.add(player.getUuid());
        WORKER.execute(() -> {
            try {
                GodDecider.Choice choice = decider(config).choose(verdict, config);
                verdict.miracle = choice.miracle().id(); verdict.power = choice.power(); verdict.warned = choice.warn();
                verdict.decidedBy = choice.decidedBy(); verdict.decisionNote = choice.note();
                verdict.speech = new GodSpeech(config, DivineBlockMod.configDirectory()).announce(verdict);
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

    /** Back on the game thread: the player must still be here for the god to act. */
    private static void deliver(ServerPlayerEntity player, BlockPos altar, ServerWorld world, Verdict verdict, DivineConfig config) {
        if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) return;
        MinecraftServer server = world.getServer();
        GodLedger.Soul soul = DivineState.of(server).ledger.soul(player.getUuid());
        soul.remember(verdict);
        DivineState.of(server).markDirty();
        DivineBlockMod.LOG.info("Божественный блок: вердикт для {}\n{}", player.getName().getString(), verdict.breakdown());
        say(player, verdict.speech);
        if (config.debug) player.sendMessage(Text.literal(verdict.breakdown()).formatted(Formatting.DARK_GRAY), false);

        if (verdict.warned) {
            Miracles.warn(player, altar);
            List<Verdict.Reason> main = verdict.mainReasons(1);
            title(player, Text.literal("Гнев небес").formatted(Formatting.DARK_RED),
                    Text.literal((main.isEmpty() ? "" : main.get(0).text() + ". ") + "Покайся у алтаря!").formatted(Formatting.RED));
            boolean[] cancelled = { false };
            Pending pending = new Pending(verdict, altar, world.getRegistryKey(), cancelled);
            PENDING.put(player.getUuid(), pending);
            Scheduler.later(config.warnSeconds * 20, () -> {
                if (PENDING.get(player.getUuid()) == pending) PENDING.remove(player.getUuid());
                if (cancelled[0] || player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) return;
                act(player, altar, verdict, config);
            });
            return;
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

    private static void repent(ServerPlayerEntity player, Pending pending, GodLedger.Soul soul, long now) {
        Verdict verdict = pending.verdict();
        boolean forgivable = verdict.power <= 3 && soul.righteousness.score > -60 && !PrayerTone.of(verdict.prayer).rude();
        if (!forgivable) { say(player, "Поздно каяться. Ты знаешь, что сделал."); return; }
        pending.cancelled()[0] = true;
        PENDING.remove(player.getUuid());
        verdict.forgiven = true;
        verdict.reasons.add(new Verdict.Reason("покаялся вовремя", 2, 0));
        soul.shiftMood(now, 5);
        Miracles.forgiven(player, pending.altar());
        title(player, Text.literal("Гнев утих").formatted(Formatting.GOLD), Text.literal("На этот раз пощажу.").formatted(Formatting.YELLOW));
        say(player, "Ладно, смертный. Раскаяние принято, но я запомню.");
    }

    static void say(ServerPlayerEntity player, String line) {
        player.sendMessage(Text.literal("[Бог] ").formatted(Formatting.GOLD, Formatting.BOLD)
                .append(Text.literal(line == null || line.isBlank() ? "…" : line).formatted(Formatting.YELLOW, Formatting.ITALIC)), false);
    }
    static void hint(ServerPlayerEntity player, String line) { player.sendMessage(Text.literal(line).formatted(Formatting.GOLD), true); }
    private static void title(ServerPlayerEntity player, Text title, Text subtitle) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 60, 20));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
    }

    /** Forgets pending punishments and in-flight prayers when the server stops. */
    static void clear() { PENDING.clear(); THINKING.clear(); }
}
