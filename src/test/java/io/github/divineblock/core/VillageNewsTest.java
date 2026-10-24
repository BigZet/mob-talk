package io.github.divineblock.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The deeds told to Mob Talk's villages: how an act reads (kind, group, reach), the event's JSON v1 and the admin op {@code events}. */
class VillageNewsTest {
    private static final UUID DIMA = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static VillageNews.Event wrath(String id, long t) {
        return new VillageNews.Event(id, t, VillageNews.Kind.WRATH, "earthquake", "Землетрясение", 3, 0, true,
                new VillageNews.Soul(DIMA, "Дима", "хула"), null, true, true, new VillageNews.Place("minecraft:overworld", 10, 64, -20), 32, 600,
                new VillageNews.Place("minecraft:overworld", 12, 64, -18), List.of("хула", "без даров"), List.of());
    }

    @Test void actsReadAsTheVillagesSeeThem() {
        assertEquals(VillageNews.Kind.WRATH, VillageNews.kind(Miracle.EARTHQUAKE));
        assertEquals(VillageNews.Kind.BOSS, VillageNews.kind(Miracle.BOSS_SCHEMNIK));
        assertEquals(VillageNews.Kind.BOSS, VillageNews.kind(Miracle.GOD_MACE));
        assertEquals(VillageNews.Kind.BAN, VillageNews.kind(Miracle.ETERNAL_BAN));
        assertEquals(VillageNews.Kind.MIRACLE, VillageNews.kind(Miracle.HEAL));
        assertEquals(VillageNews.Kind.JEST, VillageNews.kind(Miracle.CHICKEN_RAIN));
        assertEquals(VillageNews.Kind.WARNING, VillageNews.kind(Miracle.OMEN_THUNDER));
        assertNull(VillageNews.kind(Miracle.IGNORE));
        assertNull(VillageNews.kind(Miracle.OMEN_LIGHT));
    }

    @Test void punishmentsKeepTheirGroup() {
        assertEquals(1, VillageNews.group(Miracle.LIGHTNING));
        assertEquals(2, VillageNews.group(Miracle.ACID_RAIN));
        assertEquals(3, VillageNews.group(Miracle.EARTHQUAKE));
        assertEquals(4, VillageNews.group(Miracle.CAIN));
        assertEquals(4, VillageNews.group(Miracle.BOSS_SCHEMNIK));
        assertEquals(5, VillageNews.group(Miracle.WITHER));
        assertEquals(6, VillageNews.group(Miracle.ETERNAL_BAN));
        assertEquals(0, VillageNews.group(Miracle.HEAL));
        assertEquals(0, VillageNews.group(Miracle.TRANSMUTE), "a jest is no punishment, whatever group it sits in");
        for (Miracle miracle : Miracle.values())
            if (VillageNews.kind(miracle) == VillageNews.Kind.WRATH) assertTrue(VillageNews.group(miracle) >= 1 && VillageNews.group(miracle) <= 5, miracle.id());
    }

    @Test void onlyWorldActsReachAround() {
        assertEquals(0, VillageNews.radius(Miracle.LIGHTNING));
        assertEquals(0, VillageNews.radius(Miracle.WEATHER_CLEAR));
        assertTrue(VillageNews.radius(Miracle.METEOR_SHOWER) > 0);
    }

    @Test void theEventIsProtocolOne() {
        JsonObject o = wrath("e1", 1000).json();
        assertEquals(1, o.get("v").getAsInt());
        assertEquals("wrath", o.get("kind").getAsString());
        assertEquals("world", o.get("scope").getAsString());
        assertEquals(3, o.get("group").getAsInt());
        assertEquals("Дима", o.getAsJsonObject("culprit").get("name").getAsString());
        assertEquals("хула", o.getAsJsonObject("culprit").get("reason").getAsString());
        assertEquals(DIMA.toString(), o.getAsJsonObject("culprit").get("uuid").getAsString());
        assertFalse(o.has("beneficiary"));
        assertTrue(o.get("named").getAsBoolean());
        assertEquals(12, o.getAsJsonObject("altar").get("x").getAsInt());
        assertEquals(600, o.get("seconds").getAsInt());
        assertEquals(2, o.getAsJsonArray("reasons").size());
        for (String key : List.of("id", "t", "deed", "deedTitle", "power", "prompted", "dim", "x", "y", "z", "radius", "of")) assertTrue(o.has(key), key);
    }

    @Test void stampsNeverRepeat() {
        VillageNews news = new VillageNews();
        long a = news.stamp(500), b = news.stamp(500), c = news.stamp(400);
        assertTrue(a < b && b < c);
    }

    @Test void theBufferKeepsTheLastAndAnswersSince() {
        VillageNews news = new VillageNews();
        for (int i = 1; i <= VillageNews.CAPACITY + 10; i++) news.add(wrath("e" + i, i));
        List<VillageNews.Event> all = news.since(0, 1000);
        assertEquals(VillageNews.LIMIT, all.size());
        assertEquals("e11", all.get(0).id(), "the oldest ten fell out of the ring");
        List<VillageNews.Event> after = news.since(VillageNews.CAPACITY + 7, 50);
        assertEquals(List.of("e208", "e209", "e210"), after.stream().map(VillageNews.Event::id).toList());
    }

    @Test void theAdminOpEventsAnswersItems() {
        VillageNews news = new VillageNews();
        news.add(wrath("a", 100)); news.add(wrath("b", 200));
        AdminProtocol protocol = new AdminProtocol(new AdminProtocol.Host() {
            final DivineConfig config = new DivineConfig();
            @Override public DivineConfig config() { return config; }
            @Override public void save(DivineConfig saved) { }
            @Override public GodJournal journal() { return new GodJournal(); }
            @Override public List<AdminProtocol.Action> actions() { return List.of(); }
            @Override public String run(String id, Map<String, String> args) { return ""; }
            @Override public VillageNews news() { return news; }
        });
        JsonArray items = JsonParser.parseString(protocol.handle("{\"op\":\"events\",\"since\":100,\"limit\":50}")).getAsJsonObject().getAsJsonArray("items");
        assertEquals(1, items.size());
        assertEquals("b", items.get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test void bothSwitchesAreServerSettingsAndOnByDefault() {
        DivineConfig config = new DivineConfig();
        assertTrue(config.tellVillages);
        assertTrue(config.nameSinner);
        assertTrue(DivineSettings.SERVER.stream().anyMatch(s -> s.key().equals("tellVillages")));
        assertTrue(DivineSettings.SERVER.stream().anyMatch(s -> s.key().equals("nameSinner")));
    }
}
