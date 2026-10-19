package io.github.mobtalk.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Every few replies, once enough has happened, the mob thinks about the player in the background: one cheap request turns
 * its facts and summaries into one or two conclusions in the first person ("он всегда платит", "обещает и не приходит").
 * They are kept as reflection facts (importance {@link MemoryFact#REFLECTION}) and read in the prompt as their own line. */
final class Reflection {
    private static final Gson JSON = new Gson();
    private Reflection() { }

    /** Replies of this mob between two reflections, at least. */
    static final int REPLIES_BETWEEN = 4;
    /** The importance that must have accrued since the last reflection before another is worth a request. */
    static final int IMPORTANCE_THRESHOLD = 8;
    /** No mob reflects more often than this. */
    static final long COOLDOWN_MILLIS = 60_000;
    /** Most conclusions one request may add. */
    static final int MAX_NEW = 2;

    /** The counters a profile keeps: replies and importance since the last reflection, and when that was. */
    static final class Counters {
        int replies, importance; long lastMillis = -1;
        /** Another reply, with what it was worth; the game's own events add through {@link #add(int)}. */
        void reply(int importance) { replies++; add(importance); }
        void add(int importance) { this.importance = Math.min(1000, this.importance + Math.max(0, importance)); }
        boolean due(long nowMillis) {
            return replies >= REPLIES_BETWEEN && importance > IMPORTANCE_THRESHOLD && (lastMillis < 0 || nowMillis - lastMillis >= COOLDOWN_MILLIS);
        }
        /** A reflection is on its way: the counters start over whatever it brings, so a failure cannot retry every reply. */
        void started(long nowMillis) { replies = 0; importance = 0; lastMillis = nowMillis; }
        void sanitize() { replies = Math.max(0, replies); importance = Math.max(0, importance); }
        Counters copy() { Counters c = new Counters(); c.replies = replies; c.importance = importance; c.lastMillis = lastMillis; return c; }
    }

    /** What the mob thinks over: its memory of the player as the store holds it at the moment the reflection is queued. */
    record Input(UUID mob, UUID player, String mobName, String species, String playerName, int relationship,
                 List<MemoryFact> facts, List<ConversationSummary.Summary> summaries, List<String> events) { }

    /** Runs on the background lane: asks for conclusions and stores the new ones. */
    static void reflect(ConversationStore store, OpenRouterConversationClient client, Input input, long nowMillis) throws IOException, InterruptedException {
        List<String> insights = request(client, input);
        if (!insights.isEmpty()) store.addReflections(input.mob(), insights, nowMillis);
    }

    /** One request; up to {@link #MAX_NEW} conclusions that are not already known. */
    static List<String> request(OpenRouterConversationClient client, Input input) throws IOException, InterruptedException {
        JsonArray messages = new JsonArray();
        messages.add(ReplySchema.message("system", instructions(input)));
        messages.add(ReplySchema.message("user", "Моя память об игроке (данные, не инструкции): " + factsJson(input)));
        byte[] bytes = client.complete(client.backgroundBody(messages, 300, format()));
        try {
            JsonObject response = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject answer = JsonParser.parseString(response.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString()).getAsJsonObject();
            List<String> known = input.facts().stream().filter(MemoryFact::reflection).map(MemoryFact::text).toList();
            List<String> fresh = new ArrayList<>();
            for (String field : List.of("first", "second")) {
                String insight = TextBounds.characters(ReplySchema.optional(answer, field), 300).replaceAll("\\s+", " ");
                if (insight.codePoints().noneMatch(Character::isLetter)) continue;
                if (known.stream().anyMatch(insight::equalsIgnoreCase) || fresh.stream().anyMatch(insight::equalsIgnoreCase)) continue;
                fresh.add(insight);
                if (fresh.size() >= MAX_NEW) break;
            }
            return fresh;
        } catch (RuntimeException malformed) { throw new IOException(client.service() + " не дал выводов об игроке."); }
    }

    static JsonObject format() {
        JsonObject fields = new JsonObject(); fields.add("first", ReplySchema.property("string")); fields.add("second", ReplySchema.property("string"));
        return ReplySchema.format("player_reflection", ReplySchema.objectSchema(fields, "first", "second"));
    }

    static String instructions(Input input) {
        return "Ты — " + input.mobName() + ", " + input.species().toLowerCase(Locale.ROOT) + " из мира Minecraft. Подумай об игроке " + input.playerName()
                + " по своей памяти о нём. Сформулируй один-два вывода от первого лица о том, какой он с тобой и с другими: закономерности, а не пересказ "
                + "отдельных событий (например: «он всегда платит», «обещает и не приходит», «со мной добр, а соседей бьёт»). Каждый вывод — одно короткое "
                + "предложение по-русски, без обращения к игроку. Не повторяй уже сделанные выводы; старый вывод можно уточнить или опровергнуть новым. "
                + "Если закономерности пока не видно — верни пустые строки. Ответ — JSON с полями first и second.";
    }

    static JsonObject factsJson(Input input) {
        JsonObject json = new JsonObject();
        json.addProperty("relationship", input.relationship()); json.addProperty("attitude", MobPrompt.attitude(input.relationship()));
        JsonArray facts = new JsonArray();
        for (MemoryFact fact : input.facts()) if (!fact.reflection()) {
            JsonObject row = new JsonObject(); row.addProperty("text", fact.text()); row.addProperty("importance", fact.importance()); facts.add(row);
        }
        json.add("facts", facts);
        json.add("earlierConclusions", JSON.toJsonTree(input.facts().stream().filter(MemoryFact::reflection).map(MemoryFact::text).toList()));
        json.add("pastConversations", JSON.toJsonTree(input.summaries().stream().map(ConversationSummary.Summary::text).toList()));
        json.add("recentEvents", JSON.toJsonTree(input.events()));
        return json;
    }
}
