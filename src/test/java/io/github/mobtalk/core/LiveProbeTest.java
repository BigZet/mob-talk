package io.github.mobtalk.core;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Live probes against OpenRouter on the user's own key: one village chronicle, one creature speaking first, one Jev decision.
 * Off by default; {@code MOBTALK_LIVE=1} runs them and {@code MOBTALK_LIVE_OUT} names the Markdown file for the result. The key
 * comes from the usual places (environment, token file in {@code MOBTALK_CONFIG_DIR}, DPAPI); nothing of it is written anywhere.
 * Speech goes to a dead local port: no TTS is paid for. */
class LiveProbeTest {
    @TempDir Path directory;

    @Test void chronicleInitiativeAndJev() throws Exception {
        assumeTrue("1".equals(System.getenv("MOBTALK_LIVE")), "live probes run only with MOBTALK_LIVE=1");
        String key = OpenRouterToken.load(Path.of(System.getenv().getOrDefault("MOBTALK_CONFIG_DIR", "config-not-here")));
        MobTalkConfig config = new MobTalkConfig();
        StringBuilder out = new StringBuilder("# Живая проба трека B (часть 2)\n\nМодель: `").append(config.chatModel()).append("`, решения: `").append(JevDecider.MODEL).append("`.\n");
        try (OpenRouterConversationClient llm = new OpenRouterConversationClient(key, config)) {
            // 1. The chronicle of a village from a day's facts.
            UUID player = UUID.randomUUID();
            VillageRumors.Input input = new VillageRumors.Input("village-probe", "Ольховка", "Деревня у реки, славится мёдом; когда-то её сожгли разбойники.",
                    List.of("Аксинья", "Ладко", "Кузьма"), List.of(
                    new VillageRumors.Fact("Аксинья", "Игрок Стив ударил моба; отношение -80", 5, 1),
                    new VillageRumors.Fact("Ладко", "Игрок Стив выполнил услугу: Доски для забора; отношение 45", 4, 2),
                    new VillageRumors.Fact("Ладко", "Видел, как игрок Стив убил Марфу (корова); отношение 20", 5, 3),
                    new VillageRumors.Fact("Кузьма", "Игрок Стив купил 3× Хлеб за 2× Изумруд; отношение 10", 4, 4)), "Стив", 7);
            long started = System.nanoTime();
            List<VillageRumors.View> rumors = VillageRumors.request(llm, input);
            double seconds = (System.nanoTime() - started) / 1e9;
            out.append("\n## Хроника деревни\n\nЗапрос из 4 фактов, ").append(String.format(java.util.Locale.ROOT, "%.1f", seconds)).append(" с, слухов: ").append(rumors.size()).append("\n\n");
            for (VillageRumors.View rumor : rumors) out.append("- ").append(rumor.line()).append(" — важность ").append(rumor.importance()).append(", знак ").append(rumor.sign()).append('\n');
            assertFalse(rumors.isEmpty(), "the chronicle came back empty");
            assertTrue(rumors.stream().allMatch(r -> input.villagers().contains(r.source())), rumors.toString());

            // 2. A creature speaking first: the player came back after a long absence.
            ConversationEngine engine = new ConversationEngine(config, directory, llm, new OpenRouterSpeechClient(key, config, URI.create("http://127.0.0.1:9/speech")));
            UUID mob = UUID.randomUUID();
            MobSnapshot snapshot = new MobSnapshot(mob, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 3,
                    "minecraft:stick", List.of(), List.of(), player, Map.of("name", "Стив", "health", 20, "maxHealth", 20), Map.of("position", Map.of("x", 100, "y", 64, "z", 100)),
                    Map.of(), List.of(), List.of(ActionType.NONE), 0);
            engine.memory().observe(snapshot);
            engine.memory().setBackstory(mob, "Ладко", "Ты родился в Ольховке и всю жизнь чинишь заборы. Однажды рейд сжёг твой дом. Ты ищешь того, кто подарил бы деревне колокол.", "", "", "");
            engine.memory().recordHelp(mob, "minecraft:villager", "Житель", player, "Стив", System.currentTimeMillis() - 25 * 60_000L);
            Initiative.Cue cue = new Initiative.Cue(mob, "Ладко", Initiative.Trigger.RETURN, "игрок вернулся к тебе после долгого отсутствия (его не было около 25 минут)", List.of(), 0);
            Initiative.Verdict verdict = new Initiative.Verdict(true, Initiative.Reaction.GREET, 1);
            MobSnapshot current = new MobSnapshot(mob, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 3,
                    "minecraft:stick", List.of(), engine.memory().events(mob), player, Map.of("name", "Стив", "health", 20, "maxHealth", 20),
                    Map.of("position", Map.of("x", 100, "y", 64, "z", 100)), Map.of(), List.of(), List.of(ActionType.NONE), engine.memory().revision(mob));
            started = System.nanoTime();
            ConversationEngine.PreparedReply prepared = engine.prepare(current, "", ConversationEngine.Overheard.initiative(Initiative.cueText(cue, verdict)));
            seconds = (System.nanoTime() - started) / 1e9;
            String line = prepared.reply().responseText();
            out.append("\n## Инициатива\n\nПовод: возвращение игрока после 25 минут; настрой: поздороваться. Ответ за ").append(String.format(java.util.Locale.ROOT, "%.1f", seconds))
                    .append(" с:\n\n> ").append(prepared.reply().speakerName()).append(": ").append(line.isBlank() ? "(промолчал)" : line).append('\n');
            assertTrue(engine.commitDelivered(prepared, engine.memory().revision(mob)));
            engine.close();

            // 3. One Jev decision on the same occasion.
            JevDecider jev = new JevDecider(key, new RuleDecider());
            DecisionState state = DecisionState.forCue(mob, "Ладко", "Житель", new MobState.View(62, 55, 70, 60, "надо бы спросить про колокол"), 65, 55, 45,
                    List.of(), cue, engine.memory().events(mob));
            started = System.nanoTime();
            Initiative.Verdict decided = jev.speaksUp(state);
            seconds = (System.nanoTime() - started) / 1e9;
            out.append("\n## Решатель Jev\n\nВопросы speaks_up/reaction/level на том же поводе: ").append(String.format(java.util.Locale.ROOT, "%.2f", seconds)).append(" с, вызовов ")
                    .append(jev.calls).append(", ответила модель: ").append(jev.answered).append(", откатов на правила: ").append(jev.fellBack)
                    .append(", стоимость ").append(String.format(java.util.Locale.ROOT, "%.6f", jev.lastCost)).append(" $\n\nВердикт: ").append(decided).append('\n');
            assertEquals(1, jev.calls);
        } finally {
            String path = System.getenv("MOBTALK_LIVE_OUT");
            if (path != null && !path.isBlank()) Files.writeString(Path.of(path), out.toString(), StandardCharsets.UTF_8);
            System.out.println(out);
        }
    }
}
