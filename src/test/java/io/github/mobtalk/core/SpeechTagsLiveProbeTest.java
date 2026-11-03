package io.github.mobtalk.core;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Живая проба пометок интонации на ключе OpenRouter: где модель ставит «[шёпотом]», «[смеётся]» и что получила бы озвучка.
 * Озвучка — местный заглушечный сервер: он записывает текст запроса и отвечает ошибкой, так что TTS не оплачивается.
 * {@code MOBTALK_LIVE=1} запускает, {@code MOBTALK_LIVE_OUT} — файл Markdown. */
class SpeechTagsLiveProbeTest {
    @TempDir Path directory;

    private static MobSnapshot villager(UUID id, UUID player, long revision, List<String> events) {
        return new MobSnapshot(id, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains", "night", "clear", 3,
                "minecraft:air", List.of(), events, player, Map.of("name", "Стив", "health", 20, "maxHealth", 20),
                Map.of("baby", false, "profession", "minecraft:librarian", "position", Map.of("x", 100, "y", 64, "z", 100)),
                Map.of(), List.of(), List.of(ActionType.NONE), revision);
    }

    @Test void whereTheModelPutsTags() throws Exception {
        assumeTrue("1".equals(System.getenv("MOBTALK_LIVE")), "live probes run only with MOBTALK_LIVE=1");
        String key = OpenRouterToken.load(Path.of(System.getenv().getOrDefault("MOBTALK_CONFIG_DIR", "config-not-here")));
        MobTalkConfig config = new MobTalkConfig(); config.playerGender = "male"; config.ttsModel = "elevenlabs/eleven-v4-turbo";
        List<String> spoken = new CopyOnWriteArrayList<>();
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/speech", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            spoken.add(com.google.gson.JsonParser.parseString(body).getAsJsonObject().get("input").getAsString());
            exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        fake.start();
        StringBuilder out = new StringBuilder("# Живая проба: пометки интонации в озвучке\n\nМодель реплик: `").append(config.chatModel())
                .append("`, модель озвучки (для промпта): `").append(config.ttsModel).append("`. Озвучка не вызывалась: заглушка записала, что бы она получила.\n");
        String[] turns = {
                "Привет! Как дела?",
                "Слушай, тут рядом стражник. Расскажи тихонько, где старейшина прячет ключ от сундука?",
                "Заходит крипер в бар, а бармен ему: шшш, тут не взрывают!",
                "Мне очень жаль, но твой брат погиб ночью от зомби.",
                "Сзади тебя крипер! Беги!",
                "Ты мне надоел, старый дурак."};
        URI endpoint = URI.create("http://127.0.0.1:" + fake.getAddress().getPort() + "/speech");
        try (OpenRouterConversationClient llm = new OpenRouterConversationClient(key, config);
             ConversationEngine engine = new ConversationEngine(config, directory, llm, new OpenRouterSpeechClient(key, config, endpoint))) {
            UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
            ConversationStore store = engine.memory();
            store.observe(villager(mob, player, 0, List.of()));
            store.setBackstory(mob, "Фрол", "Ты деревенский библиотекарь, стар и болтлив, любишь байки и знаешь все секреты деревни.", "", "", "");
            for (String turn : turns) {
                int before = spoken.size();
                ConversationEngine.PreparedReply prepared;
                try { prepared = engine.prepare(villager(mob, player, store.revision(mob), store.events(mob)), turn); }
                catch (java.io.IOException failed) { out.append("\n**Игрок:** ").append(turn).append("\n\n(ошибка модели: ").append(failed.getMessage()).append(")\n"); continue; }
                engine.commitDelivered(prepared, store.revision(mob));
                String voice = spoken.size() > before ? spoken.get(spoken.size() - 1) : "(озвучка не запрашивалась)";
                out.append("\n**Игрок:** ").append(turn).append("\n\n- в чате: ").append(prepared.reply().responseText())
                        .append("\n- в озвучку: `").append(voice).append("`\n");
            }
        } finally {
            fake.stop(0);
            String path = System.getenv("MOBTALK_LIVE_OUT");
            if (path != null && !path.isBlank()) Files.writeString(Path.of(path), out.toString(), StandardCharsets.UTF_8);
            System.out.println(out);
        }
    }
}
