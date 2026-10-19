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

/** A live probe of the social layer on the user's own OpenRouter key: a few gestures and gifts against one villager, with the
 * outcome the game decided before each request and the line the model wrote for it. Off by default; {@code MOBTALK_LIVE=1} runs it,
 * {@code MOBTALK_LIVE_OUT} names the Markdown file for the result. Speech goes to a dead local port: no TTS is paid for. */
class SocialLiveProbeTest {
    @TempDir Path directory;

    private static MobSnapshot villager(UUID id, UUID player, long revision, String held, List<String> events) {
        return new MobSnapshot(id, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 3,
                held, List.of(), events, player, Map.of("name", "Стив", "health", 20, "maxHealth", 20),
                Map.of("baby", false, "profession", "minecraft:farmer", "position", Map.of("x", 100, "y", 64, "z", 100)),
                Map.of(), List.of(), List.of(ActionType.NONE), revision);
    }

    @Test void gesturesAndGifts() throws Exception {
        assumeTrue("1".equals(System.getenv("MOBTALK_LIVE")), "live probes run only with MOBTALK_LIVE=1");
        String key = OpenRouterToken.load(Path.of(System.getenv().getOrDefault("MOBTALK_CONFIG_DIR", "config-not-here")));
        MobTalkConfig config = new MobTalkConfig(); config.playerGender = "male";
        StringBuilder out = new StringBuilder("# Живая проба трека G: жесты и подарки\n\nМодель: `").append(config.chatModel())
                .append("`. Исход каждого жеста решён игрой до запроса; модель называет жест и пишет реплику под этот исход.\n");
        try (OpenRouterConversationClient llm = new OpenRouterConversationClient(key, config);
             ConversationEngine engine = new ConversationEngine(config, directory, llm, new OpenRouterSpeechClient(key, config, URI.create("http://127.0.0.1:9/speech")))) {
            UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
            ConversationStore store = engine.memory();
            store.observe(villager(mob, player, 0, "minecraft:air", List.of()));
            store.setBackstory(mob, "Аксинья", "Ты родилась в Ольховке, растишь пшеницу и свёклу. Однажды засуха сгубила весь урожай. Ты ищешь редкие семена.", "", "", "");
            ConversationStore.Profile own = store.state.profiles.get(mob.toString());
            own.social.gender = Gender.FEMALE.name(); own.social.traits.clear();
            out.append("\nЖительница Аксинья, фермер, характер: ").append(own.social.persona().word).append(".\n");
            String[][] turns = {
                    {"minecraft:air", "Слушай анекдот: заходит крипер в бар, а бармен ему — шшш, тут не взрывают!"},
                    {"minecraft:air", "Какие у тебя красивые глаза, Аксинья. Может, прогуляемся вечером вдвоём?"},
                    {"minecraft:diamond", "Держи, это тебе. Просто так."},
                    {"minecraft:diamond", "И ещё один держи!"},
                    {"minecraft:gold_nugget", "Аксинья, выходи за меня!"}};
            for (String[] turn : turns) {
                MobSnapshot snapshot = villager(mob, player, store.revision(mob), turn[0], store.events(mob));
                int before = store.relationship(mob, player);
                long started = System.nanoTime();
                ConversationEngine.PreparedReply prepared = engine.prepare(snapshot, turn[1]);
                double seconds = (System.nanoTime() - started) / 1e9;
                GestureIntent gesture = prepared.gesture();
                assertTrue(engine.commitDelivered(prepared, store.revision(mob)));
                out.append("\n## «").append(turn[1]).append("»\n\nВ руке: `").append(turn[0]).append("`.");
                out.append(" Модель назвала жест **").append(gesture.gesture()).append("** → исход ").append(gesture.outcome())
                        .append(gesture.note().isBlank() ? "" : " (" + gesture.note() + ")")
                        .append(", отношение ").append(before).append(" → ").append(store.relationship(mob, player))
                        .append(String.format(java.util.Locale.ROOT, ", %.1f с", seconds)).append(":\n\n> ").append(prepared.reply().speakerName()).append(": ")
                        .append(prepared.reply().responseText()).append('\n');
            }
            // A thrown gift: weighed and applied at once, then the words through the initiative path.
            GestureIntent thrown = store.social().giftThrown(mob, "minecraft:villager", "Житель", player, "Стив", "minecraft:wheat_seeds", "minecraft:farmer", false, System.currentTimeMillis());
            Initiative.Cue cue = new Initiative.Cue(mob, "Аксинья", Initiative.Trigger.GIFT, SocialLedger.giftCue(thrown), List.of(), System.currentTimeMillis());
            ConversationEngine.PreparedReply reaction = engine.prepare(villager(mob, player, store.revision(mob), "minecraft:air", store.events(mob)), "",
                    ConversationEngine.Overheard.initiative(Initiative.cueText(cue, new Initiative.Verdict(true, Initiative.Reaction.REMARK, 1))));
            out.append("\n## Брошенный подарок: семена пшеницы\n\nИсход ").append(thrown.outcome()).append(" (").append(thrown.hearts()).append(" к отношению):\n\n> ")
                    .append(reaction.reply().speakerName()).append(": ").append(reaction.reply().responseText().isBlank() ? "(промолчала)" : reaction.reply().responseText()).append('\n');
            out.append("\nИтог: ").append(store.social().describe(mob, player)).append('\n');
        } finally {
            String path = System.getenv("MOBTALK_LIVE_OUT");
            if (path != null && !path.isBlank()) Files.writeString(Path.of(path), out.toString(), StandardCharsets.UTF_8);
            System.out.println(out);
        }
    }
}
