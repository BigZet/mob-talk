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

/** The web admin's protocol ({@code mobtalk-admin}) over a fake game: describe (with the toggle lists), values, patch,
 * journal, the actions, souls, active and the prayers. */
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
                assertTrue(List.of("int", "decimal", "bool", "enum", "string", "flags").contains(type), f.toString());
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

        JsonObject clean = ask("{\"op\":\"patch\",\"values\":{\"curseMinutes\":5}}");
        assertTrue(clean.get("ok").getAsBoolean(), clean.toString());
        assertEquals(5, host.config.curseMinutes);
        assertFalse(ask("{\"op\":\"patch\",\"values\":{\"curseMinutes\":21}}").get("ok").getAsBoolean(), "never longer than twenty minutes");
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

    @Test void disabledActsAndBossesAreToggleListsWithTheSameValues() {
        JsonObject disabled = null, bosses = null;
        for (JsonElement group : ask("{\"op\":\"describe\"}").getAsJsonArray("groups"))
            for (JsonElement field : group.getAsJsonObject().getAsJsonArray("fields")) {
                JsonObject f = field.getAsJsonObject();
                if (f.get("key").getAsString().equals("disabled")) disabled = f;
                if (f.get("key").getAsString().equals("disabledBosses")) bosses = f;
            }
        assertNotNull(disabled); assertNotNull(bosses);
        assertEquals("flags", disabled.get("type").getAsString());
        assertEquals("flags", bosses.get("type").getAsString());
        // Every act of the catalogue, with a Russian title, a description and its kind's group.
        Set<String> ids = new HashSet<>(), groups = new HashSet<>();
        for (JsonElement option : disabled.getAsJsonArray("options")) {
            JsonObject o = option.getAsJsonObject();
            ids.add(o.get("id").getAsString()); groups.add(o.get("group").getAsString());
            for (String key : List.of("title", "help", "groupTitle")) assertFalse(o.get(key).getAsString().isBlank(), o.toString());
            assertFalse(o.get("title").getAsString().equals(o.get("id").getAsString()), "a Russian title: " + o);
        }
        for (Miracle miracle : Miracle.values()) assertTrue(ids.contains(miracle.id()), miracle.id());
        assertTrue(groups.containsAll(List.of("wrath", "miracle", "jest")), groups.toString());
        JsonObject first = disabled.getAsJsonArray("options").get(0).getAsJsonObject();
        assertEquals("Кары", first.get("groupTitle").getAsString());
        Set<String> bossIds = new HashSet<>();
        for (JsonElement option : bosses.getAsJsonArray("options")) bossIds.add(option.getAsJsonObject().get("id").getAsString());
        for (io.github.divineblock.boss.BossKind kind : io.github.divineblock.boss.BossKind.values()) assertTrue(bossIds.contains(kind.id), kind.id);
        assertTrue(bossIds.contains(DivineSettings.GOD_AVATAR_ID), "the god with the mace can be switched off too");
        // The value stays the comma-joined ids switched off, and a patch of it works as before.
        host.config.disabledBosses = new ArrayList<>(List.of("seraph", "god_avatar"));
        assertEquals("seraph,god_avatar", ask("{\"op\":\"values\"}").get("disabledBosses").getAsString());
        assertTrue(ask("{\"op\":\"patch\",\"values\":{\"disabled\":\"flood,sodom,heal\",\"disabledBosses\":\"\"}}").get("ok").getAsBoolean());
        assertEquals(List.of("flood", "sodom", "heal"), host.config.disabled);
        assertTrue(host.config.disabledBosses.isEmpty());
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

    @Test void prayersComeNewestFirstWithTheChoiceAndReasons() {
        PrayerLog log = new PrayerLog();
        AdminProtocol.Host withLog = new FakeHost() { @Override public PrayerLog prayers() { return log; } };
        AdminProtocol p = new AdminProtocol(withLog);
        Verdict mercy = new Verdict();
        mercy.prayer = "Господи, пожалуйста, дай хлеба"; mercy.inclination = Miracle.Kind.HELP; mercy.miracle = "feed"; mercy.power = 1;
        mercy.decidedBy = "jev"; mercy.decisionNote = "Б1"; mercy.speech = "Ешь, смертный.";
        mercy.reasons = new ArrayList<>(List.of(new Verdict.Reason("смиренная молитва", 1, 0), new Verdict.Reason("подношение: хлеб ×3", 1, 0)));
        log.add(PrayerLog.of(1000, "uuid-dima", "Дима", mercy, PrayerTone.of(mercy.prayer), false, "minecraft:bread", 6));
        Verdict wrath = new Verdict();
        wrath.prayer = "ты никто, слышишь"; wrath.inclination = Miracle.Kind.WRATH; wrath.miracle = "anvil_rain"; wrath.power = 3; wrath.speech = "Падут наковальни.";
        wrath.reasons = new ArrayList<>(List.of(new Verdict.Reason("хула и дерзость к богу — бог в бешенстве", -10, 3)));
        log.add(PrayerLog.of(2000, "uuid-petya", "Петя", wrath, PrayerTone.of(wrath.prayer), true, "", 0));
        Verdict silence = new Verdict(); silence.prayer = "эй"; silence.miracle = "ignore";
        log.add(PrayerLog.of(3000, "uuid-dima", "Дима", silence, PrayerTone.of("эй"), false, "", 0));

        JsonArray items = JsonParser.parseString(p.handle("{\"op\":\"prayers\",\"since\":0,\"limit\":50,\"player\":\"\"}")).getAsJsonObject().getAsJsonArray("items");
        assertEquals(3, items.size());
        assertEquals(3000, items.get(0).getAsJsonObject().get("t").getAsLong(), "newest first");
        assertEquals("none", items.get(0).getAsJsonObject().getAsJsonObject("choice").get("kind").getAsString(), "silence");
        JsonObject rude = items.get(1).getAsJsonObject();
        assertEquals("Петя", rude.getAsJsonObject("player").get("name").getAsString());
        assertEquals("rude", rude.get("tone").getAsString());
        assertEquals("грубо", rude.get("toneTitle").getAsString());
        JsonObject choice = rude.getAsJsonObject("choice");
        assertEquals("wrath", choice.get("kind").getAsString());
        assertEquals("anvil_rain", choice.get("deed").getAsString());
        assertEquals("Дождь наковален", choice.get("deedTitle").getAsString());
        assertEquals(3, choice.get("power").getAsInt());
        assertEquals("rules", choice.get("decidedBy").getAsString());
        assertEquals(-10, rude.getAsJsonArray("reasons").get(0).getAsJsonObject().get("mercy").getAsInt());
        assertEquals(3, rude.getAsJsonArray("reasons").get(0).getAsJsonObject().get("power").getAsInt());

        JsonArray dima = JsonParser.parseString(p.handle("{\"op\":\"prayers\",\"since\":0,\"limit\":50,\"player\":\"дима\"}")).getAsJsonObject().getAsJsonArray("items");
        assertEquals(2, dima.size(), "by name, any case");
        JsonObject bread = dima.get(1).getAsJsonObject();
        assertEquals("polite", bread.get("tone").getAsString());
        assertEquals("minecraft:bread", bread.get("offering").getAsString());
        assertEquals(6, bread.get("offeringValue").getAsInt());
        assertEquals("Ешь, смертный.", bread.get("answer").getAsString());
        assertEquals("miracle", bread.getAsJsonObject("choice").get("kind").getAsString());
        assertEquals("jev", bread.getAsJsonObject("choice").get("decidedBy").getAsString());
        assertEquals(2, bread.getAsJsonArray("reasons").size());
        assertEquals(1, JsonParser.parseString(p.handle("{\"op\":\"prayers\",\"player\":\"uuid-petya\"}")).getAsJsonObject().getAsJsonArray("items").size(), "by uuid");
        assertEquals(1, JsonParser.parseString(p.handle("{\"op\":\"prayers\",\"since\":2500}")).getAsJsonObject().getAsJsonArray("items").size(), "since");
        assertEquals(1, JsonParser.parseString(p.handle("{\"op\":\"prayers\",\"limit\":1}")).getAsJsonObject().getAsJsonArray("items").size(), "limit");
        for (int i = 0; i < PrayerLog.LIMIT + 10; i++) log.add(PrayerLog.of(5000 + i, "u", "n", silence, PrayerTone.of(""), false, "", 0));
        assertEquals(PrayerLog.LIMIT, log.query(0, 10_000, "").size(), "a ring of the last ones");
    }

    @Test void theOldClearStillRemovesTheBosses() {
        List<String> ran = new ArrayList<>();
        AdminProtocol.Host host = new FakeHost() {
            @Override public List<AdminProtocol.Action> actions() {
                Map<String, String> uuid = new LinkedHashMap<>(); uuid.put("uuid", "UUID босса");
                return List.of(new AdminProtocol.Action("boss_remove", "Убрать босса", uuid), new AdminProtocol.Action("boss_clear", "Убрать всех", Map.of()),
                        new AdminProtocol.Action("god_recall", "Отозвать Бога с булавой", Map.of()), new AdminProtocol.Action("court_end", "Закончить Небесный суд", Map.of()));
            }
            @Override public String run(String id, Map<String, String> args) { ran.add(id + args); return "ок"; }
        };
        AdminProtocol p = new AdminProtocol(host);
        assertTrue(JsonParser.parseString(p.handle("{\"op\":\"action\",\"id\":\"clear\"}")).getAsJsonObject().get("ok").getAsBoolean());
        assertTrue(JsonParser.parseString(p.handle("{\"op\":\"action\",\"id\":\"boss_remove\",\"args\":{\"uuid\":\"1-2-3\"}}")).getAsJsonObject().get("ok").getAsBoolean());
        assertEquals(List.of("boss_clear{}", "boss_remove{uuid=1-2-3}"), ran, "clear is the old name of boss_clear");
        JsonArray actions = JsonParser.parseString(p.handle("{\"op\":\"actions\"}")).getAsJsonObject().getAsJsonArray("actions");
        assertEquals("uuid", actions.get(0).getAsJsonObject().getAsJsonArray("args").get(0).getAsJsonObject().get("key").getAsString());
    }

    @Test void soulsAndActiveComeFromTheHostOrAnswerAnError() {
        JsonObject missing = JsonParser.parseString(protocol.handle("{\"op\":\"souls\"}")).getAsJsonObject();
        assertFalse(missing.get("ok").getAsBoolean(), "a host without souls says so instead of failing");
        AdminProtocol.Host souls = new FakeHost() {
            @Override public JsonObject souls() { return JsonParser.parseString("{\"souls\":[{\"sins\":2,\"mercy\":-12.5,\"label\":\"грешник\",\"deeds\":[]}]}").getAsJsonObject(); }
            @Override public JsonObject active() {
                return JsonParser.parseString("{\"bosses\":[],\"curses\":[],\"godAvatar\":{\"active\":false,\"player\":null},"
                        + "\"skyCourt\":{\"active\":false,\"player\":null},\"blessings\":[]}").getAsJsonObject();
            }
        };
        AdminProtocol withSouls = new AdminProtocol(souls);
        assertEquals(2, JsonParser.parseString(withSouls.handle("{\"op\":\"souls\"}")).getAsJsonObject().getAsJsonArray("souls").get(0).getAsJsonObject().get("sins").getAsInt());
        JsonObject soul = JsonParser.parseString(withSouls.handle("{\"op\":\"souls\"}")).getAsJsonObject().getAsJsonArray("souls").get(0).getAsJsonObject();
        assertEquals("грешник", soul.get("label").getAsString());
        JsonObject active = JsonParser.parseString(withSouls.handle("{\"op\":\"active\"}")).getAsJsonObject();
        for (String key : List.of("bosses", "curses", "godAvatar", "skyCourt", "blessings")) assertTrue(active.has(key), key);
        assertTrue(active.getAsJsonObject("skyCourt").get("player").isJsonNull());
    }
}
