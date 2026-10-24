package io.github.mobtalk.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mobtalk.server.web.Json;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Сборка данных оверлея для OBS (идея З3, docs/overlay.md) из ответов Божественного блока. Только перечисленные поля переходят
 * в пакет: координаты, тексты молитв, поступки и всё, чего здесь нет, не уходят никогда. Игроки, выключившие в F7 «Показывать меня
 * в оверлее», пропадают из «Милости», а из вердикта уходят их имя и причины. Без состояния: вызывает {@code OverlayHub}, тесты — напрямую. */
final class OverlayState {
    private OverlayState() { }
    static final int MAX_TEXT = 600;
    /** Вердикт старше этого из запасного пути (операция {@code prayers}) не показывается. */
    static final long VERDICT_MILLIS = 5 * 60_000L;
    /** Запасное настроение бога считается по вердиктам за это время. */
    static final long MOOD_MILLIS = 15 * 60_000L;

    /** Ответ операции {@code overlay} (Божественный блок с поддержкой оверлея). {@code lines} остаются с {@code listeners}: их
     * раздаёт по игрокам {@code OverlayHub}. */
    static JsonObject fromOverlay(JsonObject answer, Set<UUID> online, Set<UUID> hidden) {
        JsonObject out = new JsonObject();
        if (answer.has("disabled") && answer.get("disabled").getAsBoolean()) return out;
        JsonObject god = object(answer, "god");
        if (god != null) out.add("god", Json.obj("mood", clamp(Json.number(god, "mood", 0), -100, 100), "word", text(Json.string(god, "word"))));
        JsonObject verdict = verdict(object(answer, "verdict"), hidden, Long.MAX_VALUE);
        if (verdict != null) out.add("verdict", verdict);
        JsonArray souls = new JsonArray();
        for (JsonObject soul : objects(answer, "souls")) {
            UUID id = uuid(object(soul, "player"));
            if (id == null || !online.contains(id) || hidden.contains(id)) continue;
            souls.add(Json.obj("player", player(object(soul, "player")), "mercy", soul.has("mercy") ? clamp(Json.number(soul, "mercy", 50), 0, 100) : null,
                    "label", text(Json.string(soul, "label")), "sins", soul.has("sins") ? Json.number(soul, "sins", 0) : null,
                    "ladder", Json.number(soul, "ladder", 0), "banned", bool(soul, "banned"), "cursed", bool(soul, "cursed")));
        }
        out.add("souls", souls);
        JsonArray active = new JsonArray();
        for (JsonObject item : objects(answer, "active")) active.add(active(text(Json.string(item, "kind")), item, object(item, "target"), hidden));
        out.add("active", active);
        JsonArray events = new JsonArray();
        for (JsonObject event : objects(answer, "events")) events.add(Json.obj("kind", text(Json.string(event, "kind")), "title", text(Json.string(event, "title")),
                "player", hiddenPlayer(object(event, "player"), hidden), "until", Json.number(event, "until", 0)));
        out.add("events", events);
        JsonArray lines = new JsonArray();
        for (JsonObject line : objects(answer, "lines")) {
            JsonArray listeners = new JsonArray();
            if (line.has("listeners") && line.get("listeners").isJsonArray()) for (JsonElement who : line.getAsJsonArray("listeners")) if (who.isJsonPrimitive()) listeners.add(who.getAsString());
            lines.add(Json.obj("t", Json.number(line, "t", 0), "text", text(Json.string(line, "text")), "millis", Json.number(line, "millis", 0), "listeners", listeners));
        }
        out.add("lines", lines);
        return out;
    }

    /** Запасной путь для Божественного блока без операции {@code overlay}: души ({@code souls}), идущее ({@code active}) и журнал молитв
     * ({@code prayers}); любой ответ может быть null. Милость здесь в шкале мода, поэтому вместо свечей — ярлык. */
    static JsonObject fromParts(JsonObject souls, JsonObject active, JsonObject prayers, Set<UUID> online, Set<UUID> hidden, long now) {
        JsonObject out = new JsonObject();
        if (prayers != null) {
            JsonArray items = prayers.has("items") && prayers.get("items").isJsonArray() ? prayers.getAsJsonArray("items") : new JsonArray();
            double mood = 0;
            JsonObject newest = null; long newestT = 0;
            for (JsonElement element : items) {
                if (!element.isJsonObject()) continue;
                JsonObject p = element.getAsJsonObject();
                long t = Json.number(p, "t", 0);
                JsonObject choice = object(p, "choice");
                String kind = choice == null ? "none" : text(Json.string(choice, "kind"));
                long age = now - t;
                if (age >= 0 && age < MOOD_MILLIS) mood += weight(kind) * (1 - (double) age / MOOD_MILLIS);
                if (t > newestT) { newestT = t; newest = p; }
            }
            int value = (int) clamp(Math.round(mood), -100, 100);
            out.add("god", Json.obj("mood", value, "word", word(value)));
            if (newest != null) {
                JsonObject choice = object(newest, "choice");
                JsonObject verdict = Json.obj("t", newestT, "player", object(newest, "player"), "act", choice == null ? "none" : text(Json.string(choice, "kind")),
                        "title", choice == null ? "" : text(Json.string(choice, "deedTitle")), "reasons", new JsonArray());
                JsonObject cleaned = verdict(verdict, hidden, now);
                if (cleaned != null) out.add("verdict", cleaned);
            }
        }
        JsonArray soulList = new JsonArray();
        if (souls != null) for (JsonObject soul : objects(souls, "souls")) {
            UUID id = uuid(object(soul, "player"));
            if (id == null || !online.contains(id) || hidden.contains(id)) continue;
            JsonArray curses = soul.has("curses") && soul.get("curses").isJsonArray() ? soul.getAsJsonArray("curses") : new JsonArray();
            soulList.add(Json.obj("player", player(object(soul, "player")), "mercy", null, "label", text(Json.string(soul, "label")),
                    "sins", Json.number(soul, "sins", 0), "ladder", Json.number(soul, "ladder", 0), "banned", bool(soul, "banned"), "cursed", !curses.isEmpty()));
        }
        out.add("souls", soulList);
        JsonArray list = new JsonArray();
        JsonArray events = new JsonArray();
        if (active != null) {
            Set<String> seen = new HashSet<>();
            for (JsonObject curse : objects(active, "punishments")) addOnce(list, seen, active("curse", curse, object(curse, "player"), hidden));
            for (JsonObject curse : objects(active, "curses")) addOnce(list, seen, active("curse", curse, object(curse, "player"), hidden));
            for (JsonObject blessing : objects(active, "blessings")) addOnce(list, seen, active("blessing", blessing, object(blessing, "player"), hidden));
            for (JsonObject boss : objects(active, "bosses")) addOnce(list, seen, active("boss", boss, object(boss, "target"), hidden));
            JsonObject court = object(active, "skyCourt");
            if (court != null && bool(court, "active")) events.add(Json.obj("kind", "court", "title", "Небесный суд", "player", hiddenPlayer(object(court, "player"), hidden), "until", 0));
            JsonObject avatar = object(active, "godAvatar");
            if (avatar != null && bool(avatar, "active")) events.add(Json.obj("kind", "avatar", "title", "Бог сошёл с булавой", "player", hiddenPlayer(object(avatar, "player"), hidden), "until", 0));
        }
        out.add("active", list);
        out.add("events", events);
        out.add("lines", new JsonArray());
        return out;
    }

    static String word(int mood) {
        if (mood <= -50) return "разгневан";
        if (mood < -15) return "хмурится";
        if (mood < 15) return "спокоен";
        if (mood < 50) return "благосклонен";
        return "милостив";
    }
    private static double weight(String kind) {
        return switch (kind) { case "wrath" -> -35; case "warning" -> -10; case "miracle" -> 25; case "jest" -> 5; default -> 0; };
    }

    private static JsonObject verdict(JsonObject verdict, Set<UUID> hidden, long now) {
        if (verdict == null) return null;
        long t = Json.number(verdict, "t", 0);
        if (now != Long.MAX_VALUE && now - t > VERDICT_MILLIS) return null;
        JsonObject who = object(verdict, "player");
        boolean secret = hidden(hidden, uuid(who));
        JsonArray reasons = new JsonArray();
        if (!secret && verdict.has("reasons") && verdict.get("reasons").isJsonArray())
            for (JsonElement reason : verdict.getAsJsonArray("reasons")) if (reason.isJsonPrimitive() && reasons.size() < 3) reasons.add(text(reason.getAsString()));
        return Json.obj("t", t, "player", secret ? null : player(who), "act", text(Json.string(verdict, "act")), "title", text(Json.string(verdict, "title")), "reasons", reasons);
    }
    private static JsonObject active(String kind, JsonObject item, JsonObject target, Set<UUID> hidden) {
        return Json.obj("kind", kind.isEmpty() ? "curse" : kind, "title", text(Json.string(item, "title")), "target", hiddenPlayer(target, hidden),
                "until", Json.number(item, "until", 0), "health", Json.number(item, "health", 0), "maxHealth", Json.number(item, "maxHealth", 0));
    }
    private static void addOnce(JsonArray list, Set<String> seen, JsonObject item) {
        String key = Json.string(item, "kind") + "|" + Json.string(item, "title") + "|" + (item.get("target").isJsonObject() ? Json.string(item.getAsJsonObject("target"), "uuid") : "");
        if (seen.add(key)) list.add(item);
    }
    private static JsonObject hiddenPlayer(JsonObject player, Set<UUID> hidden) {
        return player == null || hidden(hidden, uuid(player)) ? null : player(player);
    }
    private static boolean hidden(Set<UUID> hidden, UUID id) { return id != null && hidden.contains(id); }
    private static JsonObject player(JsonObject player) {
        return player == null ? null : Json.obj("uuid", Json.string(player, "uuid"), "name", text(Json.string(player, "name")));
    }
    static UUID uuid(JsonObject player) {
        if (player == null) return null;
        try { String value = Json.string(player, "uuid"); return value == null ? null : UUID.fromString(value); }
        catch (IllegalArgumentException bad) { return null; }
    }
    private static JsonObject object(JsonObject from, String key) {
        return from != null && from.has(key) && from.get(key).isJsonObject() ? from.getAsJsonObject(key) : null;
    }
    private static java.util.List<JsonObject> objects(JsonObject from, String key) {
        java.util.List<JsonObject> out = new java.util.ArrayList<>();
        if (from != null && from.has(key) && from.get(key).isJsonArray()) for (JsonElement element : from.getAsJsonArray(key)) if (element.isJsonObject()) out.add(element.getAsJsonObject());
        return out;
    }
    private static boolean bool(JsonObject from, String key) {
        return from.has(key) && from.get(key).isJsonPrimitive() && from.get(key).getAsJsonPrimitive().isBoolean() && from.get(key).getAsBoolean();
    }
    private static String text(String value) {
        if (value == null) return "";
        String clean = value.replaceAll("§.", "").strip();
        return clean.length() > MAX_TEXT ? clean.substring(0, MAX_TEXT) + "…" : clean;
    }
    private static long clamp(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }
}
