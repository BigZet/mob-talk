package io.github.mobtalk.server;

import com.google.gson.JsonObject;
import io.github.mobtalk.server.web.Json;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class OverlayStateTest {
    private static final UUID DIMA = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PETYA = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID GONE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** Ответ операции overlay Божественного блока, как его отдаёт ветка bigzet/overlay, плюс лишние поля, которые не должны пройти. */
    private static JsonObject overlayAnswer() {
        return Json.parseObject("""
                {"ok":true,"god":{"mood":-156,"word":"хмур"},
                 "verdict":{"t":1761336120000,"player":{"uuid":"11111111-1111-1111-1111-111111111111","name":"Дима"},"act":"wrath","title":"Молния",
                            "reasons":["хула","ты грешник","бог зол","четвёртая"],"prayer":"секретный текст молитвы"},
                 "souls":[{"player":{"uuid":"11111111-1111-1111-1111-111111111111","name":"Дима"},"mercy":30,"sins":3,"ladder":2,"banned":false,"x":10},
                          {"player":{"uuid":"22222222-2222-2222-2222-222222222222","name":"Петя"},"mercy":80,"sins":0,"ladder":0,"banned":false},
                          {"player":{"uuid":"33333333-3333-3333-3333-333333333333","name":"Ушёл"},"mercy":50,"sins":0,"ladder":0,"banned":false}],
                 "active":[{"kind":"curse","title":"печать Каина","target":{"uuid":"11111111-1111-1111-1111-111111111111","name":"Дима"},"until":1761336720000,"health":0.0,"maxHealth":0.0,"x":1,"y":2,"z":3},
                           {"kind":"boss","title":"Серафим","target":null,"until":0,"health":180.0,"maxHealth":300.0,"dimension":"minecraft:overworld"}],
                 "events":[{"kind":"court","title":"Небесный суд","player":{"uuid":"22222222-2222-2222-2222-222222222222","name":"Петя"},"until":1761336480000}],
                 "lines":[{"t":1761336118000,"text":"§cТы хулил меня — так прими гром.","millis":4200,"listeners":["11111111-1111-1111-1111-111111111111"]}]}
                """);
    }

    @Test void overlayAnswerKeepsOnlyListedFields() {
        JsonObject state = OverlayState.fromOverlay(overlayAnswer(), Set.of(DIMA, PETYA), new HashSet<>());
        String json = Json.GSON.toJson(state);
        assertEquals(-100, state.getAsJsonObject("god").get("mood").getAsInt(), "настроение в пределах −100…100");
        assertEquals(3, state.getAsJsonObject("verdict").getAsJsonArray("reasons").size(), "не больше трёх причин");
        assertEquals(2, state.getAsJsonArray("souls").size(), "только игроки в сети");
        assertEquals(180, state.getAsJsonArray("active").get(1).getAsJsonObject().get("health").getAsInt());
        assertEquals("Ты хулил меня — так прими гром.", state.getAsJsonArray("lines").get(0).getAsJsonObject().get("text").getAsString(), "коды цвета убраны");
        for (String secret : new String[]{"секретный", "\"x\"", "\"y\"", "\"z\"", "dimension", "overworld"}) assertFalse(json.contains(secret), secret);
    }

    @Test void hiddenPlayerLeavesMercyAndVerdict() {
        Set<UUID> hidden = new HashSet<>(Set.of(DIMA));
        JsonObject state = OverlayState.fromOverlay(overlayAnswer(), Set.of(DIMA, PETYA), hidden);
        assertEquals(1, state.getAsJsonArray("souls").size());
        assertEquals("Петя", state.getAsJsonArray("souls").get(0).getAsJsonObject().getAsJsonObject("player").get("name").getAsString());
        JsonObject verdict = state.getAsJsonObject("verdict");
        assertTrue(verdict.get("player").isJsonNull(), "имя скрытого игрока не уходит");
        assertEquals(0, verdict.getAsJsonArray("reasons").size(), "причины скрытого игрока не уходят");
        assertTrue(state.getAsJsonArray("active").get(0).getAsJsonObject().get("target").isJsonNull(), "цель кары — скрытый игрок");
    }

    @Test void disabledOverlayGivesNothing() {
        assertEquals(0, OverlayState.fromOverlay(Json.parseObject("{\"ok\":true,\"disabled\":true}"), Set.of(DIMA), new HashSet<>()).size());
    }

    @Test void fallbackFromSoulsActiveAndPrayers() {
        long now = 1_761_336_200_000L;
        JsonObject souls = Json.parseObject("""
                {"souls":[{"player":{"uuid":"11111111-1111-1111-1111-111111111111","name":"Дима"},"sins":4,"ladder":2,"banned":false,"mercy":-12.5,"label":"грешник",
                           "deeds":[{"text":"убил жителя","t":1,"day":3}],"curses":[{"title":"Мор","until":1761336900000}]}]}
                """);
        JsonObject active = Json.parseObject("""
                {"bosses":[{"uuid":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","id":"goliath","title":"Голиаф","health":120,"maxHealth":300,"since":1,"phase":1,
                            "target":{"uuid":"11111111-1111-1111-1111-111111111111","name":"Дима"},"dimension":"minecraft:overworld","x":5,"y":6,"z":7}],
                 "curses":[{"title":"Мор","until":1761336900000,"player":{"uuid":"11111111-1111-1111-1111-111111111111","name":"Дима"}}],
                 "punishments":[{"player":{"uuid":"11111111-1111-1111-1111-111111111111","name":"Дима"},"deed":"PLAGUES","title":"Мор","since":1,"until":1761336900000}],
                 "blessings":[],"godAvatar":{"active":false,"player":null},"skyCourt":{"active":true,"player":{"uuid":"11111111-1111-1111-1111-111111111111","name":"Дима"}}}
                """);
        JsonObject prayers = Json.parseObject("""
                {"items":[{"t":1761336190000,"player":{"uuid":"11111111-1111-1111-1111-111111111111","name":"Дима"},"text":"секретная молитва",
                           "choice":{"kind":"wrath","deedTitle":"Мор","power":3}}]}
                """);
        JsonObject state = OverlayState.fromParts(souls, active, prayers, Set.of(DIMA), new HashSet<>(), now);
        String json = Json.GSON.toJson(state);
        assertTrue(state.getAsJsonObject("god").get("mood").getAsInt() < 0, "кара — гнев");
        assertEquals("Мор", state.getAsJsonObject("verdict").get("title").getAsString());
        JsonObject soul = state.getAsJsonArray("souls").get(0).getAsJsonObject();
        assertTrue(soul.get("mercy").isJsonNull(), "шкала мода неизвестна — свечей нет, только ярлык");
        assertTrue(soul.get("cursed").getAsBoolean());
        assertEquals(2, state.getAsJsonArray("active").size(), "мор один раз (кара и проклятие с одним названием), плюс босс");
        assertEquals("court", state.getAsJsonArray("events").get(0).getAsJsonObject().get("kind").getAsString());
        for (String secret : new String[]{"секретная", "убил жителя", "\"x\"", "overworld"}) assertFalse(json.contains(secret), secret);
    }

    @Test void oldVerdictFromFallbackIsDropped() {
        JsonObject prayers = Json.parseObject("{\"items\":[{\"t\":1000,\"player\":{\"uuid\":\"" + GONE + "\",\"name\":\"x\"},\"choice\":{\"kind\":\"miracle\",\"deedTitle\":\"Дождь\"}}]}");
        JsonObject state = OverlayState.fromParts(null, null, prayers, Set.of(), new HashSet<>(), 1000 + OverlayState.VERDICT_MILLIS + 1);
        assertFalse(state.has("verdict"));
        assertEquals("спокоен", state.getAsJsonObject("god").get("word").getAsString());
    }
}
