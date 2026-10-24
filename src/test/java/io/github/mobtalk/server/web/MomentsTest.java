package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Моменты для монтажа: weighing, merging, the threshold, manual marks, sessions from joins and OBS, edits, restart, exports. */
class MomentsTest {
    @TempDir Path dir;
    private final AtomicLong clock = new AtomicLong(1_792_000_000_000L);
    private final MomentRules rules = new MomentRules();
    private HistoryStore history;
    private Moments moments;
    private final List<Moments.Live> live = new ArrayList<>();
    private final JsonObject dima = Json.player(UUID.fromString("461baa90-bc98-4f86-b889-50f67d759b9a"), "BigZet");
    private final JsonObject petya = Json.player(UUID.fromString("2f83646d-2eeb-4a75-8358-03e02e2ef99f"), "Петя");
    private final JsonObject tereza = Json.obj("uuid", "fb57b8eb-d3e2-4f1e-8da3-2101caf9f459", "name", "Тереза", "species", "villager");

    @BeforeEach void open() { history = new HistoryStore(dir, clock::get, ZoneId.of("Europe/Moscow"), 90, msg -> fail(msg)); moments = start(); }
    @AfterEach void close() { history.close(); }

    private Moments start() {
        Moments created = new Moments(history, clock::get, () -> rules, msg -> fail(msg));
        created.streamer(uuid -> uuid.equals(Json.string(dima, "uuid")));
        created.onLive(live::add);
        return created;
    }

    private long at(long seconds) { clock.set(1_792_000_000_000L + seconds * 1000); return clock.get(); }
    private JsonArray items(String session, boolean everyone) { return moments.moments(session, 1, everyone, false, 24).getAsJsonArray("items"); }
    private String session() { return moments.sessions(null).getAsJsonArray("sessions").get(0).getAsJsonObject().get("id").getAsString(); }
    private void join(JsonObject who, long seconds) { history.append(at(seconds), "player_join", who, null, "вошёл", new JsonObject()); }

    @Test void wrathAndDeathMergeIntoOneMomentWithTimecode() {
        join(dima, 0);
        String session = session();
        history.append(at(600), "verdict", dima, null, "BigZet: кара «Молния»", Json.obj("act", "wrath", "deed", "Молния", "verdict", "Сам напросился"));
        assertEquals(1, live.size(), "живая метка сразу, до записи");
        assertEquals(3, live.get(0).weight());
        history.append(at(615), "death", dima, null, "BigZet был убит молнией", new JsonObject());
        at(700); moments.tick();
        JsonArray list = items(session, false);
        assertEquals(1, list.size());
        JsonObject one = list.get(0).getAsJsonObject();
        assertEquals("Кара: Молния — BigZet → Смерть: BigZet был убит молнией", one.get("title").getAsString());
        assertEquals(580, one.get("timecode").getAsLong(), "20 секунд до кары");
        assertEquals("Сам напросился", one.get("quote").getAsString());
        assertEquals("god", one.get("type").getAsString());
    }

    @Test void weakEventsStayBelowThresholdAndFarEventsSplit() {
        join(dima, 0);
        history.append(at(100), "trade", dima, tereza, "торг", Json.obj("outcome", "refused"));
        history.append(at(400), "death", dima, null, "BigZet утонул", new JsonObject());
        history.append(at(500), "death", dima, null, "BigZet упал", new JsonObject());
        at(1000); moments.tick();
        JsonArray list = items(session(), false);
        assertEquals(2, list.size(), "торг ниже порога 2, смерти в 100 с друг от друга — два момента");
        rules.threshold = 1;
        history.append(at(1100), "trade", dima, tereza, "торг", Json.obj("outcome", "refused"));
        at(1200); moments.tick();
        assertEquals(3, items(session(), false).size());
    }

    @Test void othersMomentsOnlyWithEveryone() {
        join(dima, 0);
        join(petya, 10);
        history.append(at(200), "death", petya, null, "Петя сгорел", new JsonObject());
        history.append(at(300), "boss", null, null, "Босс", Json.obj("boss", "Архангел", "event", "spawn"));
        at(500); moments.flushAll();
        String session = session();
        assertEquals(1, items(session, false).size(), "у Димы — только босс мира");
        assertEquals(2, items(session, true).size());
    }

    @Test void manualMarkReachesBackAndAnnouncesLive() {
        join(dima, 0);
        at(1000);
        moments.mark(dima, "Петя в лаве", -1, "BigZet");
        JsonObject one = items(session(), false).get(0).getAsJsonObject();
        assertEquals(970, one.get("timecode").getAsLong());
        assertEquals("manual", one.get("type").getAsString());
        assertTrue(live.get(0).manual());
        moments.mark(dima, null, 0, "BigZet");
        assertEquals("Метка: BigZet", items(session(), false).get(1).getAsJsonObject().get("title").getAsString());
    }

    @Test void obsSessionCorrectsReopensAndStops() {
        at(1000);
        moments.obs(dima, "stream", true, 120_000);
        String id = session();
        history.append(at(1100), "death", dima, null, "BigZet упал", new JsonObject());
        at(1200); moments.flushAll();
        assertEquals(205, items(id, false).get(0).getAsJsonObject().get("timecode").getAsLong(), "1100 − 15 − (1000 − 120)");
        moments.obs(dima, "stream", true, 320_000 + 3_000);
        assertEquals(208, items(id, false).get(0).getAsJsonObject().get("timecode").getAsLong(), "сдвиг больше 1,5 с поправлен");
        moments.obs(dima, "record", true, 10_000);
        assertEquals("stream", moments.sessions(null).getAsJsonArray("sessions").get(0).getAsJsonObject().get("output").getAsString(), "эфир важнее записи");
        history.append(at(1300), "player_leave", dima, null, "вышел", new JsonObject());
        assertTrue(moments.sessions(null).getAsJsonArray("open").size() == 1, "выход не закрывает сессию OBS");
        moments.obs(dima, "stream", false, 0);
        assertEquals(0, moments.sessions(null).getAsJsonArray("open").size());
        at(1310);
        moments.obs(dima, "stream", true, 433_000);
        assertEquals(1, moments.sessions(null).getAsJsonArray("sessions").size(), "переподключение открывает ту же сессию");
    }

    @Test void editsShiftAndRestartSurvive() {
        join(dima, 0);
        history.append(at(100), "boss", null, null, "Босс", Json.obj("boss", "Архангел", "event", "death"));
        at(300); moments.tick();
        String session = session();
        String id = items(session, false).get(0).getAsJsonObject().get("id").getAsString();
        moments.edit(id, Json.obj("title", "Архангел пал", "starred", true), "админка");
        moments.shift(session, -5_000);
        history.flush();
        moments = start();
        JsonObject one = items(session, false).get(0).getAsJsonObject();
        assertEquals("Архангел пал", one.get("title").getAsString());
        assertTrue(one.get("starred").getAsBoolean());
        assertEquals(80, one.get("timecode").getAsLong(), "100 − 15 − 5");
        moments.edit(id, Json.obj("hidden", true), "админка");
        assertEquals(0, items(session, false).size());
        assertThrows(ApiException.class, () -> moments.edit("dialogue.1", Json.obj("title", "x"), "админка"));
    }

    @Test void replayFileAttachesBeforeAndAfterWriting() {
        join(dima, 0);
        history.append(at(100), "death", dima, null, "BigZet упал", new JsonObject());
        moments.replayFile(live.get(0).id(), Json.string(dima, "uuid"), "C:\\Videos\\Replay 1.mkv");
        history.append(at(400), "death", dima, null, "BigZet утонул", new JsonObject());
        at(600); moments.tick();
        moments.replayFile(live.get(1).id(), Json.string(dima, "uuid"), "C:\\Videos\\Replay 2.mkv");
        JsonArray list = items(session(), false);
        assertEquals("C:\\Videos\\Replay 1.mkv", list.get(0).getAsJsonObject().getAsJsonObject("replay").get(Json.string(dima, "uuid")).getAsString());
        assertEquals("C:\\Videos\\Replay 2.mkv", list.get(1).getAsJsonObject().getAsJsonObject("replay").get(Json.string(dima, "uuid")).getAsString());
    }

    @Test void exportsYoutubeCsvEdlXml() {
        join(dima, 0);
        history.append(at(5), "death", dima, null, "BigZet упал", new JsonObject());
        history.append(at(3700), "verdict", dima, null, "кара", Json.obj("act", "wrath", "deed", "Мор"));
        history.append(at(3695), "boss", null, null, "Босс", Json.obj("boss", "Архангел", "event", "spawn"));
        at(5000); moments.flushAll();
        String session = session();
        String youtube = new String(moments.export(session, "youtube", false, 1, false).body(), StandardCharsets.UTF_8);
        assertEquals("0:00:00 Смерть: BigZet упал\n1:01:20 Кара: Мор — BigZet\n", youtube, "первая глава в 0:00, босс ближе 10 с к каре пропущен");
        String csv = new String(moments.export(session, "csv", false, 1, false).body(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFFтаймкод;"));
        assertTrue(csv.contains("1:01:20;3680;"));
        String edl = new String(moments.export(session, "edl", false, 1, false).body(), StandardCharsets.UTF_8);
        assertTrue(edl.contains("002  001      V     C        02:01:20:00 02:01:20:01 02:01:20:00 02:01:20:01"), edl);
        assertTrue(edl.contains("|C:ResolveColorYellow |M:Кара: Мор — BigZet |D:1"));
        String xml = new String(moments.export(session, "xml", false, 1, false).body(), StandardCharsets.UTF_8);
        assertTrue(xml.contains("<in>110400</in>"), xml);
        assertThrows(ApiException.class, () -> moments.export(session, "mp4", false, 1, false));
        assertEquals("moments-BigZet-20261022-1213.txt".substring(0, 15), moments.export(session, "youtube", false, 1, false).filename().substring(0, 15));
    }

    @Test void disabledRulesWriteNothingButManualStill() {
        rules.enabled = false;
        join(dima, 0);
        history.append(at(100), "death", dima, null, "BigZet упал", new JsonObject());
        at(300); moments.tick();
        assertEquals(0, items(session(), false).size());
        moments.mark(dima, "сам", 0, "BigZet");
        assertEquals(1, items(session(), false).size());
    }
}
