package io.github.mobtalk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Track K: village small talk between villagers nobody introduced, and the villagers' chat announcements with a call to work. */
class VillageTalkTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private final UUID first = UUID.randomUUID(), second = UUID.randomUUID(), player = UUID.randomUUID();
    @TempDir Path directory;
    @BeforeEach void fixture() { TradeFixtures.install(); }
    @AfterEach void restoreBundle() { TradeFixtures.reset(); }

    private static VillageChatter.Speaker speaker(String name) { return new VillageChatter.Speaker(UUID.randomUUID(), name, Gender.MALE, "Фермер", "ворчливый", ""); }

    // A rumour one has and the other has not is the topic most of the time; without anything to tell they talk about work.
    @Test void topicFollowsWhatTheyKnow() {
        VillageChatter.Speaker a = speaker("Пахом"), b = speaker("Фома");
        VillageRumors.View rumor = new VillageRumors.View("r1", "Стив подарил Марфе цветок", "Марфа", 3, 1, 0, 3);
        VillageChatter.Subject told = VillageChatter.subject(a, b, new VillageChatter.Facts(rumor, "", "день", ""), () -> 0.1);
        assertEquals(VillageChatter.Topic.RUMOR, told.topic()); assertEquals("r1", told.rumorId());
        assertTrue(told.text().contains("Стив подарил Марфе цветок (пошло от Марфа)") && !told.text().contains("говорит, что Стив"), told.text());
        // Neighbours know each other by sight: nobody opens with «Я Пахом».
        String prompt = VillageChatter.instructions(new VillageChatter.Input("v", "Ольховка", a, b, told, "Стив", "день", false));
        assertTrue(prompt.contains("не представляются") && !prompt.contains("назвать себя"), prompt);
        VillageChatter.Subject idle = VillageChatter.subject(a, b, new VillageChatter.Facts(null, "", "день", ""), () -> 0.5);
        assertEquals(VillageChatter.Topic.WORK, idle.topic(), "a plain day with nothing to tell: work");
        VillageChatter.Subject rain = VillageChatter.subject(a, b, new VillageChatter.Facts(null, "", "дождь", ""), () -> 0.0);
        assertEquals(VillageChatter.Topic.WEATHER, rain.topic());
    }

    // The model's lines alternate whatever it labelled them, lose the speaker's name and quotes, and stop at three.
    @Test void linesAlternateAndAreCleaned() {
        VillageChatter.Input input = new VillageChatter.Input("v", "Ольховка", speaker("Пахом"), speaker("Фома"),
                new VillageChatter.Subject(VillageChatter.Topic.WORK, "работа", ""), "Стив", "день", false);
        String content = "{\"lines\":[{\"speaker\":\"a\",\"text\":\"Пахом: «Опять дождь, всё сгниёт»\"},{\"speaker\":\"a\",\"text\":\"Не каркай, Пахом.\"},"
                + "{\"speaker\":\"b\",\"text\":\"…\"},{\"speaker\":\"b\",\"text\":\"Ладно, пойду.\"},{\"speaker\":\"a\",\"text\":\"Четвёртая лишняя\"}]}";
        List<VillageChatter.Line> lines = VillageChatter.parse(content, input);
        assertEquals(3, lines.size());
        assertEquals(new VillageChatter.Line(0, "Опять дождь, всё сгниёт"), lines.get(0));
        assertEquals(1, lines.get(1).speaker()); assertEquals(0, lines.get(2).speaker()); assertEquals("Ладно, пойду.", lines.get(2).text());
    }

    // One talk per village per interval, one per villager per five minutes.
    @Test void budgetKeepsItRare() {
        VillageChatter.Budget budget = new VillageChatter.Budget();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        assertTrue(budget.allows("v1", a, b, 120, 0));
        budget.spent("v1", a, b, 0);
        assertFalse(budget.allows("v1", c, d, 120, 60_000), "the same village waits two minutes");
        assertTrue(budget.allows("v2", c, d, 120, 60_000), "another village does not");
        assertFalse(budget.allows("v1", a, c, 120, 130_000), "Пахом already talked five minutes ago");
        assertTrue(budget.allows("v1", c, d, 120, 130_000));
        assertTrue(budget.allows("v1", a, c, 120, VillageChatter.MOB_COOLDOWN_MILLIS + 1));
    }

    // Through the store and the engine: two strangers get names and voices, the talk is written by the cheap model, the rumour passes,
    // they know each other afterwards, and neither counts as acquainted with the player.
    @Test void smallTalkIntroducesStrangersAndPassesTheRumour() throws Exception {
        AtomicReference<JsonObject> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            request.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] body = completion("{\"lines\":[{\"speaker\":\"a\",\"text\":\"Слыхал, Стив ударил Аксинью?\"},{\"speaker\":\"b\",\"text\":\"Да ну! Вот негодник.\"}]}");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/speech", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        try {
            MobTalkConfig config = new MobTalkConfig(); config.backgroundModel = "test/cheap";
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ConversationStore store = ConversationStore.open(directory, config);
            ConversationStore.Village village = store.createVillage(OVERWORLD, 100, 100, "Ольховка", "Деревня у реки.");
            store.addRumors(village.id(), List.of(new VillageRumors.View("", "Стив ударил Аксинью", "", 5, -1, 0, 5)), 1000);
            long now = System.currentTimeMillis();
            // Nobody heard the rumour yet; the second villager picks it up as a newcomer would, through the chronicle teller rule.
            VillageChatter.Input input = store.chatterInput(new VillageChatter.Seat(first, "minecraft:villager", "Фермер"),
                    new VillageChatter.Seat(second, "minecraft:villager", "Библиотекарь"), village.id(), village.name(), "Стив", "день", () -> 0.1, now);
            assertFalse(input.a().name().isBlank()); assertFalse(input.b().name().isBlank());
            assertNotEquals("Фермер", input.a().name(), "a stranger villager gets a given name from the social layer");
            assertFalse(input.acquainted());
            assertEquals(village.id(), store.villageOf(first), "the village is known: they belong to it now");
            assertFalse(store.acquainted(first), "talking among themselves does not make them the player's acquaintances");

            // Give the first one the rumour, and ask again: now it is the topic.
            store.addRumors(village.id(), List.of(new VillageRumors.View("", "Стив подарил Фёкле цветок", input.a().name(), 3, 1, 0, 3)), now);
            input = store.chatterInput(new VillageChatter.Seat(second, "minecraft:villager", "Библиотекарь"),
                    new VillageChatter.Seat(first, "minecraft:villager", "Фермер"), village.id(), village.name(), "Стив", "день", () -> 0.1, now);
            assertEquals(first, input.a().id(), "the one who knows the rumour tells it");
            assertEquals(VillageChatter.Topic.RUMOR, input.subject().topic());

            ConversationEngine engine = new ConversationEngine(config, directory, new OpenRouterConversationClient("unit-test-key", config, URI.create(base + "/chat")),
                    new OpenRouterSpeechClient("unit-test-key", config, URI.create(base + "/speech")));
            List<ConversationEngine.ChatterLine> lines = engine.chatter(input);
            assertEquals(2, lines.size());
            assertEquals(first, lines.get(0).speaker()); assertEquals(second, lines.get(1).speaker());
            assertNull(lines.get(0).audio(), "speech is down: the line is still shown");
            assertEquals("test/cheap", request.get().get("model").getAsString());
            assertEquals("village_chatter", request.get().getAsJsonObject("response_format").getAsJsonObject("json_schema").get("name").getAsString());
            assertTrue(request.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString().contains("Стив подарил Фёкле цветок"));

            store.recordChatter(input, List.of(new VillageChatter.Line(0, lines.get(0).text()), new VillageChatter.Line(1, lines.get(1).text())), now);
            assertTrue(store.rumorsOf(second, now).stream().anyMatch(v -> v.text().equals("Стив подарил Фёкле цветок")), "the rumour passed");
            assertTrue(store.bonds(first, now).stream().anyMatch(v -> v.other().equals(second)), "they know each other now");
            assertTrue(store.events(second).getLast().contains(input.a().name()), store.events(second).toString());
            assertTrue(store.chatterInput(new VillageChatter.Seat(first, "minecraft:villager", "Фермер"), new VillageChatter.Seat(second, "minecraft:villager", "Библиотекарь"),
                    village.id(), village.name(), "Стив", "день", () -> 0.9, now).acquainted());
            engine.close();
            ConversationStore.closeWorld(directory);
        } finally { server.stop(0); }
    }

    // An announcement needs a villager the store knows; a call to work opens an invitation that becomes an ordinary favour, is not
    // offered twice, survives the disk, and lapses after half an hour.
    @Test void callToWorkBecomesAFavour() throws Exception {
        MobTalkConfig config = new MobTalkConfig();
        ConversationStore store = ConversationStore.open(directory, config);
        assertNull(store.noticeInput(first, player, "Стив", VillageNotices.Kind.NEWS, null, 0), "a stranger announces nothing");
        assertEquals(VillageNotices.Kind.WORK, VillageNotices.kind(false, 40, 0.1));
        assertEquals(VillageNotices.Kind.NEWS, VillageNotices.kind(false, 40, 0.5));
        assertEquals(VillageNotices.Kind.NEWS, VillageNotices.kind(true, 100, 0.0), "a villager already waiting for a favour does not call for another");
        store.chatterInput(new VillageChatter.Seat(first, "minecraft:villager", "Фермер"), new VillageChatter.Seat(second, "minecraft:villager", "Фермер"), "", "", "Стив", "день", () -> 0.5, 0);
        VillageNotices.Work work = new VillageNotices.Work("minecraft:wheat", "Пшеница", 12, "", "");
        long now = System.currentTimeMillis();
        VillageNotices.Input input = store.noticeInput(first, player, "Стив", VillageNotices.Kind.WORK, work, now);
        assertNotNull(input);
        assertTrue(VillageNotices.instructions(input).contains("12× Пшеница") && VillageNotices.instructions(input).contains("Стив"));
        assertEquals("Стив, загляни ко мне, есть дело", VillageNotices.parse("{\"text\":\"" + input.name() + ": Стив, загляни ко мне, есть дело\"}", input));
        store.recordNotice(input, "Стив, загляни ко мне, есть дело", now);
        assertTrue(store.invited(first, player, now)); assertEquals(now, store.lastNotice(first));
        assertTrue(store.events(first).getLast().contains("есть для него работа"));
        assertEquals(List.of("Стив, загляни ко мне, есть дело"), store.noticeInput(first, player, "Стив", VillageNotices.Kind.NEWS, null, now).earlier(), "it remembers what it wrote");
        ConversationStore.closeWorld(directory);
        store = ConversationStore.open(directory, config);
        List<VillageNotices.Invitation> open = store.invitations(player, now);
        assertEquals(1, open.size(), "the call survived the disk");
        QuestIntent quest = open.getFirst().quest();
        assertEquals(QuestIntent.Type.OFFER, quest.type()); assertEquals("minecraft:wheat", quest.item()); assertEquals(12, quest.count());
        store.startFavor(first, player, quest.favor(), now);
        assertNotNull(store.takeInvitation(first, player)); assertNull(store.takeInvitation(first, player), "answered once");
        assertEquals(12, store.favor(first, player).count());
        store.recordNotice(store.noticeInput(second, player, "Стив", VillageNotices.Kind.WORK, work, now), "Стив, помоги", now);
        assertTrue(store.invitations(player, now + VillageNotices.INVITATION_MILLIS).isEmpty(), "nobody came for half an hour: the call lapsed");
        ConversationStore.closeWorld(directory);
    }

    private static byte[] completion(String content) {
        JsonObject message = new JsonObject(); message.addProperty("role", "assistant"); message.addProperty("content", content);
        JsonObject choice = new JsonObject(); choice.add("message", message); JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject response = new JsonObject(); response.add("choices", choices);
        return response.toString().getBytes(StandardCharsets.UTF_8);
    }
}
