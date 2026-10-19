package io.github.mobtalk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** The decider that asks OpenRouter's Decisions API ({@code ~typesafe/jev-latest}) with the same key the conversations use:
 * one POST with the compact state and a few typed questions, under a second and a fraction of a cent. On an error or a
 * timeout the rules answer instead; the state never carries secrets. Never on the game thread. */
public final class JevDecider implements Decider {
    static final URI ENDPOINT = URI.create("https://openrouter.ai/api/alpha/decisions");
    public static final String MODEL = "~typesafe/jev-latest";
    /** The whole call, connect included: a decision that takes longer is not worth waiting for. */
    public static final Duration TIMEOUT = Duration.ofMillis(1500);
    private static final int MAX_BODY = 64 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String key;
    private final URI endpoint;
    private final Decider fallback;
    /** Calls made, answered by the model, and answered by the rules instead (for the log and the self-test). */
    public volatile int calls, answered, fellBack;
    /** Cost of the last answered call in dollars, as the API reports it. */
    public volatile double lastCost;

    public JevDecider(String key, Decider fallback) { this(key, fallback, ENDPOINT); }
    JevDecider(String key, Decider fallback, URI endpoint) {
        this.key = OpenRouterConversationClient.validKey(key); this.fallback = fallback == null ? new RuleDecider() : fallback; this.endpoint = endpoint;
    }
    @Override public String name() { return "jev"; }

    /** The decider the config asks for: Jev on the OpenRouter key when chosen and the key is there, else the rules. Loads the key
     * (disk, DPAPI): call from a worker. */
    public static Decider forConfig(MobTalkConfig config, java.nio.file.Path configDirectory) {
        RuleDecider rules = new RuleDecider();
        if (config == null || !config.usesJev()) return rules;
        try { return new JevDecider(OpenRouterToken.load(configDirectory), rules); }
        catch (IOException | RuntimeException missing) {
            OpenRouterConversationClient.diagnostics.accept("решатель Jev без ключа OpenRouter, отвечают правила");
            return rules;
        }
    }

    /** The parsed answers of one call. */
    public record Answers(JsonObject answers, double cost) {
        /** A yes/no question's probability of yes, or -1 when absent. */
        public double noul(String question) { JsonObject a = answer(question); return a != null && a.has("noul") ? a.get("noul").getAsDouble() : -1; }
        /** A choice question's pick, or "" when absent. */
        public String choice(String question) { JsonObject a = answer(question); return a != null && a.has("choice") && a.get("choice").isJsonPrimitive() ? a.get("choice").getAsString() : ""; }
        /** A score question's value, or NaN when absent. */
        public double score(String question) { JsonObject a = answer(question); return a != null && a.has("score") ? a.get("score").getAsDouble() : Double.NaN; }
        private JsonObject answer(String question) { JsonElement a = answers == null ? null : answers.get(question); return a != null && a.isJsonObject() ? a.getAsJsonObject() : null; }
    }
    /** Reads one response body; a body without answers is malformed. */
    public static Answers parse(String body) throws IOException {
        try {
            JsonObject response = JsonParser.parseString(body).getAsJsonObject();
            JsonObject answers = response.getAsJsonObject("answers");
            if (answers == null) throw new IOException("Decisions API: в ответе нет answers.");
            double cost = response.has("usage") && response.getAsJsonObject("usage").has("cost") ? response.getAsJsonObject("usage").get("cost").getAsDouble() : 0;
            return new Answers(answers, cost);
        } catch (RuntimeException malformed) { throw new IOException("Decisions API: ответ не разобран."); }
    }

    /** One call; throws on any failure, which the public methods turn into a fallback. */
    Answers ask(JsonObject state, JsonObject questions) throws IOException, InterruptedException {
        JsonObject body = new JsonObject(); body.addProperty("model", MODEL); body.add("state", state); body.add("questions", questions);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT).header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        calls++;
        HttpResponse<byte[]> response;
        try { response = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException slow) { throw new IOException("Decisions API: превышено время ожидания."); }
        catch (java.util.concurrent.ExecutionException failure) { throw new IOException("Decisions API: не удалось выполнить запрос."); }
        if (response.statusCode() != 200) throw new IOException("Decisions API: ошибка HTTP " + response.statusCode() + ".");
        byte[] bytes = response.body();
        if (bytes.length > MAX_BODY) throw new IOException("Decisions API: ответ превышает допустимый размер.");
        Answers answers = parse(new String(bytes, StandardCharsets.UTF_8));
        answered++; lastCost = answers.cost();
        return answers;
    }
    private Answers tryAsk(JsonObject state, JsonObject questions) {
        try { return ask(state, questions); }
        catch (IOException | RuntimeException failure) {
            fellBack++;
            OpenRouterConversationClient.diagnostics.accept("решатель Jev недоступен, отвечают правила: " + TextBounds.characters(String.valueOf(failure.getMessage()), 160));
            return null;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); fellBack++; return null; }
    }

    // ---- The questions. ----

    static JsonObject noul(String instructions, String yes, String no) {
        JsonObject q = new JsonObject(); q.addProperty("type", "noul"); q.addProperty("instructions", instructions);
        JsonObject criteria = new JsonObject(); criteria.addProperty("true", yes); criteria.addProperty("false", no); q.add("criteria", criteria); return q;
    }
    static JsonObject choice(String instructions, JsonObject criteria) {
        JsonObject q = new JsonObject(); q.addProperty("type", "choice"); q.addProperty("instructions", instructions); q.add("criteria", criteria); return q;
    }
    static JsonObject score(String instructions, String... levels) {
        JsonObject q = new JsonObject(); q.addProperty("type", "score"); q.addProperty("instructions", instructions);
        JsonArray criteria = new JsonArray(); for (String level : levels) criteria.add(level); q.add("criteria", criteria); return q;
    }
    static JsonObject speakQuestions() {
        JsonObject questions = new JsonObject();
        questions.add("speaks_up", noul("Персонаж Minecraft в состоянии state стоит рядом с игроком; есть повод occasion. Заговорит ли он первым прямо сейчас? "
                + "Учитывай характер (sociability, curiosity), настроение (state), отношение к игроку, важность повода и то, что было недавно.",
                "заговорит сам, не дожидаясь игрока", "промолчит и займётся своим"));
        JsonObject reactions = new JsonObject();
        for (Initiative.Reaction reaction : Initiative.Reaction.values()) reactions.addProperty(reaction.name().toLowerCase(Locale.ROOT), reaction.manner);
        questions.add("reaction", choice("Если персонаж заговорит, в каком настрое он это сделает?", reactions));
        questions.add("level", score("Насколько многословен он будет?", "низко: одно-два слова", "средне: одна короткая фраза", "высоко: одна-две фразы"));
        return questions;
    }
    @Override public Initiative.Verdict speaksUp(DecisionState state) {
        if (state == null || state.trigger() == null) return Initiative.Verdict.QUIET;
        Answers answers = tryAsk(state.json(), speakQuestions());
        if (answers == null) return fallback.speaksUp(state);
        double yes = answers.noul("speaks_up");
        if (yes < 0) return fallback.speaksUp(state);
        if (yes < 0.5) return Initiative.Verdict.QUIET;
        Initiative.Reaction reaction = Initiative.Reaction.REMARK;
        String picked = answers.choice("reaction").toUpperCase(Locale.ROOT);
        for (Initiative.Reaction candidate : Initiative.Reaction.values()) if (candidate.name().equals(picked)) reaction = candidate;
        if (reaction == Initiative.Reaction.SILENT) return Initiative.Verdict.QUIET;
        double level = answers.score("level");
        int rounded = Double.isNaN(level) ? 1 : (int) Math.round(level) - 1; // Scores run 1..3 over the three criteria.
        return new Initiative.Verdict(true, reaction, rounded);
    }

    @Override public UUID nextSpeaker(DecisionState state, List<Initiative.Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        JsonObject options = new JsonObject();
        options.addProperty("nobody", "никто не отвечает, разговор затихает");
        for (Initiative.Candidate candidate : candidates)
            options.addProperty(candidate.id().toString(), candidate.name() + " (в " + Math.round(candidate.distance()) + " блоках, отношение к говорившему " + candidate.bond() + ")");
        JsonObject questions = new JsonObject();
        questions.add("next_speaker", choice("Персонаж state только что сказал justSaid; рядом стоят другие существа. Кто из них ответит следующим, или никто?", options));
        Answers answers = tryAsk(state.json(), questions);
        if (answers == null) return fallback.nextSpeaker(state, candidates);
        String picked = answers.choice("next_speaker");
        if (picked.isEmpty()) return fallback.nextSpeaker(state, candidates);
        if ("nobody".equals(picked)) return null;
        for (Initiative.Candidate candidate : candidates) if (candidate.id().toString().equals(picked)) return candidate.id();
        return fallback.nextSpeaker(state, candidates);
    }

    @Override public boolean bystanderAnswers(DecisionState state) {
        if (state != null && state.eventful()) return true;
        JsonObject questions = new JsonObject();
        questions.add("speaks", noul("Персонаж state слышал, как игрок говорил с другим (justSaid). Вставит ли он слово по характеру и обстановке?",
                "вмешается: ему есть что сказать", "промолчит: не его дело"));
        Answers answers = tryAsk(state == null ? new JsonObject() : state.json(), questions);
        if (answers == null) return fallback.bystanderAnswers(state);
        double yes = answers.noul("speaks");
        return yes < 0 ? fallback.bystanderAnswers(state) : yes >= 0.5;
    }
}
