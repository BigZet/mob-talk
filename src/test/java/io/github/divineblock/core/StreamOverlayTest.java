package io.github.divineblock.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code overlay} of the web admin's protocol over a fake game: the god's mood, the last act, the souls online, what goes on,
 * the god's lines after {@code since}, and the settings of «Бог на стриме» that hide parts of it. */
class StreamOverlayTest {
    private static final StreamOverlay.Player STREAMER = new StreamOverlay.Player("11111111-1111-1111-1111-111111111111", "Дима");
    private static final StreamOverlay.Player FRIEND = new StreamOverlay.Player("22222222-2222-2222-2222-222222222222", "Петя");

    private static class FakeHost implements AdminProtocol.Host {
        DivineConfig config = new DivineConfig();
        final StreamOverlay stream = new StreamOverlay();
        int reads;
        @Override public DivineConfig config() { return config.copy(); }
        @Override public void save(DivineConfig saved) { config = saved.copy(); }
        @Override public GodJournal journal() { return new GodJournal(); }
        @Override public List<AdminProtocol.Action> actions() { return List.of(); }
        @Override public String run(String id, Map<String, String> args) { return ""; }
        @Override public StreamOverlay stream() { return stream; }
        @Override public StreamOverlay.Snapshot overlay() {
            reads++;
            return new StreamOverlay.Snapshot(STREAMER.uuid(),
                    List.of(new StreamOverlay.Soul(STREAMER, -40, 3, 2, false), new StreamOverlay.Soul(FRIEND, 60, 0, 0, false)),
                    List.of(new StreamOverlay.Active("curse", "cain", "печать Каина", STREAMER, 1_700_000_600_000L, 0, 0),
                            new StreamOverlay.Active("boss", "boss_seraph", "Шестикрылый серафим", STREAMER, 0, 180, 300),
                            new StreamOverlay.Active("boss", null, "Страж церкви", null, 0, 50, 100),
                            new StreamOverlay.Active("blessing", "buff", "Сила и скорость", FRIEND, 1_700_000_300_000L, 0, 0)),
                    List.of(new StreamOverlay.Event("court", "Небесный суд", STREAMER, 1_700_000_360_000L)));
        }
    }

    private final FakeHost host = new FakeHost();
    private final AdminProtocol protocol = new AdminProtocol(host);
    private JsonObject ask(String json) { return JsonParser.parseString(protocol.handle(json)).getAsJsonObject(); }

    private void wrathOnStreamer(long t) {
        host.stream.verdict(new StreamOverlay.Shown(t, STREAMER, "wrath", "lightning", "Молния", List.of("хула и дерзость к богу — бог в бешенстве", "ты грешник", "бог на тебя зол", "лишняя"), true));
    }

    @Test void answersEveryPartWithoutCoordinatesOrPrayers() {
        long now = System.currentTimeMillis();
        wrathOnStreamer(now - 1000);
        host.stream.line(now - 900, "Ты хулил меня — так прими гром.", List.of(STREAMER.uuid(), FRIEND.uuid()));
        JsonObject out = ask("{\"op\":\"overlay\",\"since\":0}");
        assertTrue(out.get("ok").getAsBoolean(), out.toString());

        JsonObject god = out.getAsJsonObject("god");
        int mood = god.get("mood").getAsInt();
        assertTrue(mood >= -100 && mood <= 100);
        assertTrue(mood < 0, "fresh fury makes him angry: " + out);
        assertEquals(StreamOverlay.word(mood), god.get("word").getAsString());

        JsonObject verdict = out.getAsJsonObject("verdict");
        assertEquals("wrath", verdict.get("act").getAsString());
        assertEquals("Молния", verdict.get("title").getAsString());
        assertEquals(STREAMER.name(), verdict.getAsJsonObject("player").get("name").getAsString());
        assertEquals(3, verdict.getAsJsonArray("reasons").size(), "three main reasons at most");

        JsonArray souls = out.getAsJsonArray("souls");
        assertEquals(2, souls.size());
        JsonObject dima = souls.get(0).getAsJsonObject();
        assertEquals(30, dima.get("mercy").getAsInt(), "righteousness −40 is mercy 30 of 100");
        assertEquals(3, dima.get("sins").getAsInt());
        assertEquals(2, dima.get("ladder").getAsInt());
        assertFalse(dima.get("banned").getAsBoolean());
        assertEquals(80, souls.get(1).getAsJsonObject().get("mercy").getAsInt());

        JsonArray active = out.getAsJsonArray("active");
        assertEquals(4, active.size());
        JsonObject boss = active.get(1).getAsJsonObject();
        assertEquals("boss", boss.get("kind").getAsString());
        assertEquals(180, boss.get("health").getAsDouble());
        assertEquals(300, boss.get("maxHealth").getAsDouble());
        assertTrue(active.get(2).getAsJsonObject().get("target").isJsonNull(), "no target — on everyone");
        assertEquals("court", out.getAsJsonArray("events").get(0).getAsJsonObject().get("kind").getAsString());

        JsonObject line = out.getAsJsonArray("lines").get(0).getAsJsonObject();
        assertEquals("Ты хулил меня — так прими гром.", line.get("text").getAsString());
        assertEquals(2, line.getAsJsonArray("listeners").size());
        assertEquals(0, line.get("millis").getAsLong());

        String text = out.toString();
        for (String secret : List.of("\"x\"", "\"y\"", "\"z\"", "dimension", "prayer", "deed")) assertFalse(text.contains(secret), secret + " in " + text);
    }

    @Test void linesComeAfterSinceAndTheRingKeepsFifty() {
        for (int i = 1; i <= 60; i++) host.stream.line(i * 1000L, "реплика " + i, List.of(STREAMER.uuid()));
        JsonArray all = ask("{\"op\":\"overlay\",\"since\":0}").getAsJsonArray("lines");
        assertEquals(StreamOverlay.LINES, all.size());
        assertEquals("реплика 11", all.get(0).getAsJsonObject().get("text").getAsString());
        JsonArray fresh = ask("{\"op\":\"overlay\",\"since\":58000}").getAsJsonArray("lines");
        assertEquals(2, fresh.size());
        assertEquals(59_000, fresh.get(0).getAsJsonObject().get("t").getAsLong());
        StreamOverlay.Line last = host.stream.since(59_000).get(0);
        StreamOverlay.voiced(last, 4200);
        assertEquals(4200, ask("{\"op\":\"overlay\",\"since\":59000}").getAsJsonArray("lines").get(0).getAsJsonObject().get("millis").getAsLong());
    }

    @Test void switchedOffAnswersOnlyThatAndReadsNothing() {
        host.config.overlayEnabled = false;
        JsonObject out = ask("{\"op\":\"overlay\",\"since\":0}");
        assertTrue(out.get("ok").getAsBoolean());
        assertTrue(out.get("disabled").getAsBoolean());
        assertEquals(2, out.size(), out.toString());
        assertEquals(0, host.reads);
    }

    @Test void settingsHideReasonsSinsOtherSoulsAndChosenDeeds() {
        wrathOnStreamer(System.currentTimeMillis());
        assertTrue(ask("{\"op\":\"patch\",\"values\":{\"overlayReasons\":false,\"overlaySins\":false,\"overlayOthersMercy\":false,\"overlayHidden\":\"lightning,boss_seraph,cain\"}}")
                .get("ok").getAsBoolean());
        JsonObject out = ask("{\"op\":\"overlay\",\"since\":0}");
        JsonObject verdict = out.getAsJsonObject("verdict");
        assertEquals(StreamOverlay.HIDDEN, verdict.get("title").getAsString());
        assertEquals("wrath", verdict.get("act").getAsString());
        assertEquals(0, verdict.getAsJsonArray("reasons").size());
        JsonArray souls = out.getAsJsonArray("souls");
        assertEquals(1, souls.size(), "only the streamer");
        assertEquals(STREAMER.uuid(), souls.get(0).getAsJsonObject().getAsJsonObject("player").get("uuid").getAsString());
        assertFalse(souls.get(0).getAsJsonObject().has("sins"));
        List<String> titles = new ArrayList<>();
        for (JsonElement a : out.getAsJsonArray("active")) titles.add(a.getAsJsonObject().get("title").getAsString());
        assertEquals(List.of(StreamOverlay.HIDDEN, StreamOverlay.HIDDEN, "Страж церкви", "Сила и скорость"), titles);
        assertFalse(out.toString().contains("Молния"));
        assertFalse(out.toString().contains("серафим"));
    }

    @Test void hiddenListTakesOnlyRealDeeds() {
        JsonObject answer = ask("{\"op\":\"patch\",\"values\":{\"overlayHidden\":\"lightning,nonsense\"}}");
        assertFalse(answer.get("ok").getAsBoolean());
        assertTrue(answer.getAsJsonObject("errors").has("overlayHidden"));
        assertTrue(host.config.overlayHidden.isEmpty());
    }

    @Test void describeHasTheGroupWithPunishmentsAndMerciesOnly() {
        JsonObject stream = null;
        for (JsonElement group : ask("{\"op\":\"describe\"}").getAsJsonArray("groups"))
            if (group.getAsJsonObject().get("id").getAsString().equals("stream")) stream = group.getAsJsonObject();
        assertNotNull(stream);
        assertEquals("Бог на стриме", stream.get("title").getAsString());
        List<String> keys = new ArrayList<>();
        JsonObject hidden = null;
        for (JsonElement f : stream.getAsJsonArray("fields")) {
            keys.add(f.getAsJsonObject().get("key").getAsString());
            if (f.getAsJsonObject().get("key").getAsString().equals("overlayHidden")) hidden = f.getAsJsonObject();
        }
        assertEquals(List.of("overlayEnabled", "overlayReasons", "overlayOthersMercy", "overlaySins", "overlayHidden"), keys);
        assertEquals("flags", hidden.get("type").getAsString());
        for (JsonElement o : hidden.getAsJsonArray("options"))
            assertTrue(List.of("wrath", "miracle").contains(o.getAsJsonObject().get("group").getAsString()), o.toString());
    }

    @Test void moodIsStableAndBounded() {
        long now = 10 * StreamOverlay.MOOD_WINDOW_MS;
        assertEquals(0, StreamOverlay.mood(List.of(), List.of(), now));
        assertEquals(40, StreamOverlay.mood(List.of(100.0), List.of(), now), "souls alone speak softly");
        List<StreamOverlay.Shown> furies = new ArrayList<>();
        for (int i = 0; i < 20; i++) furies.add(new StreamOverlay.Shown(now - i, STREAMER, "wrath", "lightning", "Молния", List.of(), true));
        assertEquals(-100, StreamOverlay.mood(List.of(-100.0), furies, now));
        List<StreamOverlay.Shown> old = List.of(new StreamOverlay.Shown(now - StreamOverlay.MOOD_WINDOW_MS - 1, STREAMER, "wrath", "lightning", "Молния", List.of(), true));
        assertEquals(0, StreamOverlay.mood(List.of(), old, now), "an old verdict no longer weighs");
        assertEquals("гневен", StreamOverlay.word(-100));
        assertEquals("спокоен", StreamOverlay.word(0));
        assertEquals("милостив", StreamOverlay.word(100));
    }
}
