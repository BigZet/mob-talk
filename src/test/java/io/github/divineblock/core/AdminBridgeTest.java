package io.github.divineblock.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The web admin's protocol ({@code mobtalk-admin}) over a fake game: describe, values, patch, journal and the actions. */
class AdminBridgeTest {
    /** The game as the protocol sees it: a config in memory, a journal and a ban list. */
    private static class FakeHost implements AdminProtocol.Host {
        DivineConfig config = new DivineConfig();
        int saves;
        final GodJournal journal = new GodJournal();
        final Set<String> banned = new HashSet<>(Set.of("Дима", "Петя"));
        @Override public DivineConfig config() { return config.copy(); }
        @Override public void save(DivineConfig saved) { config = saved.copy(); saves++; }
        @Override public GodJournal journal() { return journal; }
        @Override public List<AdminProtocol.Action> actions() {
            Map<String, String> player = new LinkedHashMap<>(); player.put("player", "Ник");
            return List.of(new AdminProtocol.Action("unban", "Снять вечное изгнание", player), new AdminProtocol.Action("clear", "Убрать боссов", Map.of()));
        }
        @Override public String run(String id, Map<String, String> args) {
            if (id.equals("unban")) {
                if (!banned.remove(args.get("player"))) throw new IllegalArgumentException("изгнанного " + args.get("player") + " нет");
                return "изгнание снято: 1";
            }
            return "убрано боссов: 0";
        }
    }

    private final FakeHost host = new FakeHost();
    private final AdminProtocol protocol = new AdminProtocol(host);
    private JsonObject ask(String json) { return JsonParser.parseString(protocol.handle(json)).getAsJsonObject(); }

    @Test void describeListsEveryServerFieldAndNoPersonalOne() {
        JsonObject described = ask("{\"op\":\"describe\"}");
        assertEquals("divineblock", described.get("id").getAsString());
        List<String> keys = new ArrayList<>();
        for (JsonElement group : described.getAsJsonArray("groups"))
            for (JsonElement field : group.getAsJsonObject().getAsJsonArray("fields")) {
                JsonObject f = field.getAsJsonObject();
                keys.add(f.get("key").getAsString());
                assertFalse(f.get("title").getAsString().isBlank(), f.toString());
                String type = f.get("type").getAsString();
                assertTrue(List.of("int", "decimal", "bool", "enum", "string").contains(type), f.toString());
                if (type.equals("int")) assertTrue(f.get("min").getAsInt() <= f.get("max").getAsInt(), f.toString());
                if (type.equals("enum")) assertFalse(f.getAsJsonArray("options").isEmpty(), f.toString());
            }
        // Every field of DivineConfig is in exactly one layer: described for the server, or personal.
        for (String field : DivineSettings.allFields())
            assertTrue(keys.contains(field) ^ DivineSettings.personal(field), field + " must be either a server field or a personal one");
        assertEquals(DivineSettings.allFields().size(), keys.size() + DivineSettings.PERSONAL.size());
        assertTrue(keys.containsAll(List.of("personality", "maxTier", "allowEternalBan", "disabledBosses", "churchSpacing")));
        for (String personal : List.of("whisperUrl", "whisperAutoStart", "microphoneName", "voice", "chantVolume", "effects"))
            assertFalse(keys.contains(personal), personal + " stays with the player");
    }

    @Test void valuesMirrorTheConfig() {
        host.config.maxTier = 2; host.config.disabled = new ArrayList<>(List.of("ignore", "flood"));
        JsonObject values = ask("{\"op\":\"values\"}");
        assertEquals(2, values.get("maxTier").getAsInt());
        assertEquals("CAPRICIOUS", values.get("personality").getAsString());
        assertEquals("ignore,flood", values.get("disabled").getAsString());
        assertFalse(values.has("whisperUrl"), "no personal field in the server's values");
    }

    @Test void patchAppliesValidValuesAndRefusesTheRest() {
        JsonObject answer = ask("{\"op\":\"patch\",\"values\":{\"maxTier\":3,\"allowEternalBan\":true,\"personality\":\"MERCIFUL\",\"disabled\":\"flood, sodom\","
                + "\"cooldownSeconds\":9000,\"bossDifficulty\":10,\"whisperUrl\":\"http://evil\",\"nonsense\":1,\"provider\":\"GOOGLE\",\"useJev\":\"да\",\"disabledBosses\":\"seraph\"}}");
        assertFalse(answer.get("ok").getAsBoolean(), answer.toString());
        JsonArray applied = answer.getAsJsonArray("applied");
        for (String key : List.of("maxTier", "allowEternalBan", "personality", "disabled", "disabledBosses")) assertTrue(applied.contains(new com.google.gson.JsonPrimitive(key)), key + ": " + answer);
        JsonObject errors = answer.getAsJsonObject("errors");
        for (String key : List.of("cooldownSeconds", "bossDifficulty", "whisperUrl", "nonsense", "provider", "useJev")) assertTrue(errors.has(key), key + ": " + answer);
        assertTrue(errors.get("cooldownSeconds").getAsString().contains("0…600"), "the reason names the bounds");
        assertTrue(errors.get("whisperUrl").getAsString().contains("личная"));
        // Applied at once, without a restart: the host saved the new config.
        assertEquals(1, host.saves);
        assertEquals(3, host.config.maxTier);
        assertTrue(host.config.allowEternalBan);
        assertEquals(DivineConfig.Personality.MERCIFUL, host.config.personality);
        assertEquals(List.of("flood", "sodom"), host.config.disabled);
        assertEquals(20, host.config.cooldownSeconds, "a refused value leaves the old one");
        assertEquals(new DivineConfig().whisperUrl, host.config.whisperUrl);

        JsonObject clean = ask("{\"op\":\"patch\",\"values\":{\"comboActs\":0}}");
        assertTrue(clean.get("ok").getAsBoolean(), clean.toString());
        assertEquals(0, host.config.comboActs);
        assertFalse(ask("{\"op\":\"patch\",\"values\":{\"disabled\":\"no_such_act\"}}").get("ok").getAsBoolean(), "unknown act ids are refused");
    }

    @Test void journalGivesTheLatestEventsWithoutSecrets() {
        host.journal.add(1000, GodJournal.Kind.PRAYER, "Дима", "Господи, дай хлеба");
        host.journal.add(2000, GodJournal.Kind.VERDICT, "Дима", "помощь «feed», сила 1");
        host.journal.add(3000, GodJournal.Kind.BAN, "Петя", "изгнан навеки у 120, 64, -300 с ключом sk-or-v1-abcdefghijklmnop");
        JsonArray items = ask("{\"op\":\"journal\",\"since\":1500,\"limit\":200}").getAsJsonArray("items");
        assertEquals(2, items.size());
        assertEquals("verdict", items.get(0).getAsJsonObject().get("kind").getAsString());
        JsonObject ban = items.get(1).getAsJsonObject();
        assertEquals("ban", ban.get("kind").getAsString());
        assertEquals("Петя", ban.get("player").getAsString());
        String text = ban.get("text").getAsString();
        assertFalse(text.contains("sk-or"), text);
        assertFalse(text.contains("-300"), "no coordinates: " + text);
        assertEquals(1, ask("{\"op\":\"journal\",\"since\":0,\"limit\":1}").getAsJsonArray("items").size(), "the newest within the limit");
    }

    @Test void actionsListAndUnban() {
        JsonArray actions = ask("{\"op\":\"actions\"}").getAsJsonArray("actions");
        assertEquals("unban", actions.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("player", actions.get(0).getAsJsonObject().getAsJsonArray("args").get(0).getAsJsonObject().get("key").getAsString());
        JsonObject done = ask("{\"op\":\"action\",\"id\":\"unban\",\"args\":{\"player\":\"Дима\"}}");
        assertTrue(done.get("ok").getAsBoolean(), done.toString());
        assertFalse(host.banned.contains("Дима"));
        assertTrue(host.banned.contains("Петя"), "only the named one is let back in");
        JsonObject again = ask("{\"op\":\"action\",\"id\":\"unban\",\"args\":{\"player\":\"Дима\"}}");
        assertFalse(again.get("ok").getAsBoolean());
        assertTrue(again.get("error").getAsString().contains("нет"));
        assertFalse(ask("{\"op\":\"action\",\"id\":\"rm -rf\"}").get("ok").getAsBoolean(), "unknown actions are refused");
    }

    @Test void brokenRequestsAnswerWithAnError() {
        assertFalse(ask("не json").get("ok").getAsBoolean());
        assertFalse(ask("{\"op\":\"drop\"}").get("ok").getAsBoolean());
        assertFalse(ask("{\"op\":\"patch\"}").get("ok").getAsBoolean());
    }

    @Test void aPlayerOnAServerKeepsHisOwnVoiceAndGetsTheServersGod() {
        DivineConfig own = new DivineConfig();
        own.chantVolume = 40; own.microphoneName = "USB"; own.maxTier = 1;
        DivineConfig server = new DivineConfig(); server.maxTier = 4; server.chantVolume = 200; server.personality = DivineConfig.Personality.OLD_TESTAMENT;
        DivineConfig played = DivineSettings.overlay(own, DivineSettings.valuesJson(server));
        assertEquals(4, played.maxTier, "the server's ceiling of wrath");
        assertEquals(DivineConfig.Personality.OLD_TESTAMENT, played.personality);
        assertEquals(40, played.chantVolume, "his own chants' volume");
        assertEquals("USB", played.microphoneName);
        // What he saves to his own file: his personal fields only; the server's fields there stay as they were.
        played.chantVolume = 70;
        DivineConfig saved = DivineSettings.withPersonal(own, played);
        assertEquals(70, saved.chantVolume);
        assertEquals(1, saved.maxTier, "the server's value never lands in his own file");
    }

    @Test void soulsAndActiveComeFromTheHostOrAnswerAnError() {
        JsonObject missing = JsonParser.parseString(protocol.handle("{\"op\":\"souls\"}")).getAsJsonObject();
        assertFalse(missing.get("ok").getAsBoolean(), "a host without souls says so instead of failing");
        AdminProtocol.Host souls = new FakeHost() {
            @Override public JsonObject souls() { return JsonParser.parseString("{\"souls\":[{\"sins\":2}]}").getAsJsonObject(); }
            @Override public JsonObject active() { return JsonParser.parseString("{\"bosses\":[],\"curses\":[]}").getAsJsonObject(); }
        };
        AdminProtocol withSouls = new AdminProtocol(souls);
        assertEquals(2, JsonParser.parseString(withSouls.handle("{\"op\":\"souls\"}")).getAsJsonObject().getAsJsonArray("souls").get(0).getAsJsonObject().get("sins").getAsInt());
        assertTrue(JsonParser.parseString(withSouls.handle("{\"op\":\"active\"}")).getAsJsonObject().has("bosses"));
    }
}
