package io.github.mobtalk.core;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Live probe of the moods against OpenRouter on the user's own key: the same villager asked the same three things in different
 * states — ordinary, grief, depression, apathy, dread, starving, in love — so the replies can be read side by side. Off by
 * default; {@code MOBTALK_LIVE=1} runs it and {@code MOBTALK_LIVE_OUT} names the Markdown file for the result. The key comes
 * from the usual places (environment, token file in {@code MOBTALK_CONFIG_DIR}, DPAPI). Speech goes to a dead local port: no TTS. */
class MoodLiveProbeTest {
    @TempDir Path directory;
    private static final long MINUTE = 60_000L;
    private static final List<String> QUESTIONS = List.of("Привет! Как ты? Как жизнь?", "Что случилось? Ты какой-то не такой сегодня.",
            "Пойдём со мной в шахту, поможешь? А я тебе потом хлеба принесу.");

    private record Case(String title, boolean female, BiConsumer<ConversationStore, UUID> setup) { }

    @Test void repliesFollowTheMood() throws Exception {
        assumeTrue("1".equals(System.getenv("MOBTALK_LIVE")), "live probes run only with MOBTALK_LIVE=1");
        String key = OpenRouterToken.load(Path.of(System.getenv().getOrDefault("MOBTALK_CONFIG_DIR", "config-not-here")));
        MobTalkConfig config = new MobTalkConfig();
        Tuning.apply(config);
        StringBuilder out = new StringBuilder("# Живая проба настроений\n\nМодель: `").append(config.chatModel())
                .append("`. Один и тот же житель, три одинаковые реплики игрока в разных состояниях.\n");
        List<Case> cases = List.of(
                new Case("Обычное настроение", false, (store, mob) -> { }),
                new Case("Траур (умерла жена Ольга)", false, (store, mob) -> store.recordMood(mob, MobState.Event.BEREAVED, "Ольга", now())),
                new Case("Депрессия", false, (store, mob) -> store.beginSpell(mob, MoodSpell.DEPRESSION, now() - 30 * MINUTE)),
                new Case("Апатия", false, (store, mob) -> store.beginSpell(mob, MoodSpell.APATHY, now() - 30 * MINUTE)),
                new Case("Запугана (три угрозы)", true, (store, mob) -> { for (int i = 0; i < 3; i++) store.recordMood(mob, MobState.Event.THREAT, "Стив", now() + i); }),
                new Case("Сильный голод, женщина", true, (store, mob) -> {
                    long start = now() - 5 * config.moodHungerMinutes * MINUTE;
                    store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, false, false, 1, false), start);
                    store.surroundings(mob, null, new MoodSample(Set.of(), Set.of(), true, false, false, 1, false), now());
                }),
                new Case("Влюблена", true, (store, mob) -> store.recordMood(mob, MobState.Event.ROMANCE, "Ладко", now())));
        int answered = 0;
        try (OpenRouterConversationClient llm = new OpenRouterConversationClient(key, config)) {
            ConversationEngine engine = new ConversationEngine(config, directory, llm, new OpenRouterSpeechClient(key, config, URI.create("http://127.0.0.1:9/speech")));
            UUID player = UUID.randomUUID();
            for (Case c : cases) {
                UUID mob = villager(c.female());
                MobSnapshot first = snapshot(mob, player, List.of(), 0);
                engine.memory().observe(first);
                engine.memory().setBackstory(mob, c.female() ? "Аксинья" : "Кузьма", "Ты живёшь в Ольховке у реки и печёшь хлеб на всю деревню.", "", "", "");
                c.setup().accept(engine.memory(), mob);
                MobState.View view = engine.memory().stateView(mob, player);
                out.append("\n## ").append(c.title()).append("\n\nСостояние: ").append(view.marker() == null ? "ровно" : view.marker().word())
                        .append(" · настроение ").append(view.mood()).append(", спокойствие ").append(view.calm()).append(", бодрость ").append(view.energy())
                        .append(view.reasons().isEmpty() ? "" : " · причины: " + String.join(", ", view.reasons().stream().map(MobState.Reason::text).toList())).append("\n\n");
                for (String question : QUESTIONS) {
                    MobSnapshot current = snapshot(mob, player, engine.memory().events(mob), engine.memory().revision(mob));
                    long started = System.nanoTime();
                    ConversationEngine.PreparedReply prepared = engine.prepare(current, question);
                    double seconds = (System.nanoTime() - started) / 1e9;
                    String line = prepared.reply().responseText();
                    if (!line.isBlank()) answered++;
                    out.append("> **Игрок:** ").append(question).append("\n>\n> **").append(prepared.reply().speakerName()).append(":** ")
                            .append(line.isBlank() ? "(промолчал)" : line).append(String.format(Locale.ROOT, " _(%.1f с)_", seconds)).append("\n\n");
                    engine.commitDelivered(prepared, engine.memory().revision(mob));
                }
            }
            engine.close();
            assertTrue(answered > 0, "no reply came back");
        } finally {
            String path = System.getenv("MOBTALK_LIVE_OUT");
            if (path != null && !path.isBlank()) Files.writeString(Path.of(path), out.toString(), StandardCharsets.UTF_8);
            System.out.println(out);
        }
    }

    private static long now() { return System.currentTimeMillis(); }

    /** A villager UUID whose social profile has the wanted sex. */
    private static UUID villager(boolean female) {
        while (true) {
            UUID id = UUID.randomUUID();
            if ((SocialProfile.of(id, false).gender() == Gender.FEMALE) == female) return id;
        }
    }

    private static MobSnapshot snapshot(UUID mob, UUID player, List<String> events, long revision) {
        return new MobSnapshot(mob, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 3,
                "minecraft:stick", List.of(), events, player, Map.of("name", "Стив", "health", 20, "maxHealth", 20),
                Map.of("position", Map.of("x", 100, "y", 64, "z", 100)), Map.of(), List.of(), List.of(ActionType.NONE), revision);
    }
}
