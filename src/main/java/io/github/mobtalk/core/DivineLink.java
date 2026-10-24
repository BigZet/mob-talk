package io.github.mobtalk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Связь с Божественным блоком для исповеди: его источник {@code mobtalk-admin} (JSON на входе и выходе, общего кода нет) —
 * грехи души ({@code soul_deeds}), отпущение ({@code absolve}, {@code absolve_rest}, {@code absolve_release}) и молитвы
 * ({@code prayers}). Без Божественного блока исповеди нет. Источник ставит мод при запуске, тесты — свой. Любой сбой — пустой ответ,
 * исповедь от него не ломается. */
public final class DivineLink {
    private DivineLink() { }
    private static volatile UnaryOperator<String> source;

    public static void set(UnaryOperator<String> divine) { source = divine; }
    public static boolean available() { return source != null; }

    /** Запрос как есть; {@code {"ok":false,"error":…}} — источника нет или он упал. */
    public static JsonObject call(JsonObject request) {
        UnaryOperator<String> divine = source;
        if (divine == null) return failure("Божественного блока нет");
        try {
            JsonElement answer = JsonParser.parseString(divine.apply(request.toString()));
            return answer.isJsonObject() ? answer.getAsJsonObject() : failure("ответ не объект");
        } catch (RuntimeException broken) { return failure(broken.getClass().getSimpleName()); }
    }
    private static JsonObject failure(String why) { JsonObject out = new JsonObject(); out.addProperty("ok", false); out.addProperty("error", why); return out; }
    public static boolean ok(JsonObject answer) { return answer != null && (!answer.has("ok") || answer.get("ok").getAsBoolean()) && !answer.has("error"); }

    /** Душа для исповеди: дела, идёт ли на игроке кара, милость; {@code reachable} — бог ответил. */
    public record Soul(boolean reachable, List<Confession.Deed> deeds, boolean punished, double mercy) {
        public static final Soul NONE = new Soul(false, List.of(), false, 0);
    }
    public static Soul soul(UUID player) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "soul_deeds"); request.addProperty("player", player.toString());
        JsonObject answer = call(request);
        if (!ok(answer) || !answer.has("deeds")) return Soul.NONE;
        List<Confession.Deed> deeds = new ArrayList<>();
        for (JsonElement item : answer.getAsJsonArray("deeds")) {
            if (!item.isJsonObject()) continue;
            JsonObject d = item.getAsJsonObject();
            deeds.add(new Confession.Deed(number(d, "id"), text(d, "kind"), text(d, "text"), decimal(d, "weight"), flag(d, "heavy"), flag(d, "relapse"), flag(d, "recent")));
        }
        return new Soul(true, deeds, flag(answer, "punished"), decimal(answer, "mercy"));
    }

    /** Одно дело в ответе отпущения. */
    public record Item(long id, String kind, String text, boolean heavy, int percent, double restored, double remaining) { }
    /** Что сделало отпущение; {@code reachable} — бог ответил. */
    public record Outcome(boolean reachable, List<Item> absolved, List<Item> deferred, List<Long> concealed, boolean refused, int curses, double before, double after) {
        public static final Outcome NONE = new Outcome(false, List.of(), List.of(), List.of(), false, 0, 0, 0);
        /** Что-то отпущено сейчас: душе стало легче. */
        public boolean lighter() { return absolved.stream().anyMatch(i -> i.restored() > 0) || deferred.stream().anyMatch(i -> i.restored() > 0); }
    }
    public static Outcome absolve(UUID player, Collection<Long> named, int sincerity, Confession.Place place, String priest) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "absolve"); request.addProperty("player", player.toString());
        request.add("named", ids(named)); request.addProperty("sincerity", sincerity);
        request.addProperty("place", place.id()); request.addProperty("priest", priest == null ? "" : priest);
        return outcome(call(request));
    }
    public static Outcome absolveRest(UUID player, Collection<Long> deeds, String priest) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "absolve_rest"); request.addProperty("player", player.toString());
        request.add("deeds", ids(deeds)); request.addProperty("priest", priest == null ? "" : priest);
        return outcome(call(request));
    }
    /** Срок епитимьи вышел: остаток этих грехов снова ждёт исповеди (без наказаний). */
    public static void release(UUID player, Collection<Long> deeds) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "absolve_release"); request.addProperty("player", player.toString()); request.add("deeds", ids(deeds));
        call(request);
    }
    /** Была ли с {@code sinceMillis} молитва этого игрока с тоном покаяния (епитимья «молитва»). */
    public static boolean prayedPenitent(UUID player, long sinceMillis) {
        JsonObject request = new JsonObject();
        request.addProperty("op", "prayers"); request.addProperty("player", player.toString()); request.addProperty("since", sinceMillis); request.addProperty("limit", 20);
        JsonObject answer = call(request);
        if (!ok(answer) || !answer.has("items")) return false;
        for (JsonElement item : answer.getAsJsonArray("items"))
            if (item.isJsonObject() && "penitent".equals(text(item.getAsJsonObject(), "tone"))) return true;
        return false;
    }

    private static Outcome outcome(JsonObject answer) {
        if (!ok(answer)) return Outcome.NONE;
        List<Long> concealed = new ArrayList<>();
        if (answer.has("concealed") && answer.get("concealed").isJsonArray())
            for (JsonElement id : answer.getAsJsonArray("concealed")) try { concealed.add(id.getAsLong()); } catch (RuntimeException skip) { }
        return new Outcome(true, items(answer, "absolved"), items(answer, "deferred"), concealed, flag(answer, "refused"), (int) number(answer, "curses"),
                decimal(answer, "mercyBefore"), decimal(answer, "mercyAfter"));
    }
    private static List<Item> items(JsonObject answer, String key) {
        List<Item> out = new ArrayList<>();
        if (!answer.has(key) || !answer.get(key).isJsonArray()) return out;
        for (JsonElement item : answer.getAsJsonArray(key)) {
            if (!item.isJsonObject()) continue;
            JsonObject i = item.getAsJsonObject();
            out.add(new Item(number(i, "id"), text(i, "kind"), text(i, "text"), flag(i, "heavy"), (int) number(i, "percent"), decimal(i, "restored"), decimal(i, "remaining")));
        }
        return out;
    }
    private static JsonArray ids(Collection<Long> ids) { JsonArray out = new JsonArray(); if (ids != null) ids.forEach(out::add); return out; }
    private static String text(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : ""; }
    private static boolean flag(JsonObject o, String key) { try { return o.has(key) && o.get(key).getAsBoolean(); } catch (RuntimeException bad) { return false; } }
    private static long number(JsonObject o, String key) { try { return o.has(key) ? o.get(key).getAsLong() : 0; } catch (RuntimeException bad) { return 0; } }
    private static double decimal(JsonObject o, String key) { try { return o.has(key) ? o.get(key).getAsDouble() : 0; } catch (RuntimeException bad) { return 0; } }
}
