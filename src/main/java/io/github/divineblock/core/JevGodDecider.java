package io.github.divineblock.core;

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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Asks OpenRouter's Decisions API ({@code ~typesafe/jev-latest}, as Mob Talk's JevDecider) which act fits the verdict. Jev
 * sees the named reasons and can only answer with an id from our list; the power it picks is cut to the budget. Any error
 * or a reply slower than 1.5 s hands the choice to the rules. Never on the game thread. */
public final class JevGodDecider implements GodDecider {
    static final URI ENDPOINT = URI.create("https://openrouter.ai/api/alpha/decisions");
    public static final String MODEL = "~typesafe/jev-latest";
    public static final Duration TIMEOUT = Duration.ofMillis(4000);
    private static final int MAX_BODY = 64 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    /** Where one-line diagnostics go; the game points it at its log. Never carries the key. */
    public static volatile Consumer<String> diagnostics = line -> { };

    private final String key;
    private final URI endpoint;
    private final GodDecider fallback;

    public JevGodDecider(String key, GodDecider fallback) { this(key, fallback, ENDPOINT); }
    JevGodDecider(String key, GodDecider fallback, URI endpoint) {
        if (key == null || key.isBlank() || key.contains("\n")) throw new IllegalArgumentException("Ключ OpenRouter пуст или имеет неверный формат.");
        this.key = key.strip(); this.fallback = fallback == null ? new RuleGodDecider() : fallback; this.endpoint = endpoint;
    }
    @Override public String name() { return "jev"; }

    /** The parsed answers of one call. */
    public record Answers(JsonObject answers, double cost) {
        public double noul(String q) { JsonObject a = answer(q); return a != null && a.has("noul") ? a.get("noul").getAsDouble() : -1; }
        public String choice(String q) { JsonObject a = answer(q); return a != null && a.has("choice") && a.get("choice").isJsonPrimitive() ? a.get("choice").getAsString() : ""; }
        public double score(String q) { JsonObject a = answer(q); return a != null && a.has("score") ? a.get("score").getAsDouble() : Double.NaN; }
        private JsonObject answer(String q) { JsonElement a = answers == null ? null : answers.get(q); return a != null && a.isJsonObject() ? a.getAsJsonObject() : null; }
    }

    public static Answers parse(String body) throws IOException {
        try {
            JsonObject response = JsonParser.parseString(body).getAsJsonObject();
            JsonObject answers = response.getAsJsonObject("answers");
            if (answers == null) throw new IOException("Decisions API: в ответе нет answers.");
            double cost = response.has("usage") && response.getAsJsonObject("usage").has("cost") ? response.getAsJsonObject("usage").get("cost").getAsDouble() : 0;
            return new Answers(answers, cost);
        } catch (RuntimeException malformed) { throw new IOException("Decisions API: ответ не разобран."); }
    }

    /** The state Jev reads: the prayer and the verdict's reasons, never a secret. */
    static JsonObject state(Verdict verdict) {
        JsonObject state = new JsonObject();
        state.addProperty("prayer", verdict.prayer.length() > 600 ? verdict.prayer.substring(0, 600) : verdict.prayer);
        JsonArray reasons = new JsonArray();
        for (Verdict.Reason reason : verdict.reasons) reasons.add(reason.signed());
        state.add("reasons", reasons);
        state.addProperty("favor", verdict.favor);
        state.addProperty("force", verdict.force);
        state.addProperty("inclination", verdict.inclination.label);
        state.addProperty("budget", verdict.budget);
        state.addProperty("level", verdict.level());
        JsonArray recent = new JsonArray();
        if (verdict.recentActs != null) for (String act : verdict.recentActs) recent.add(act);
        state.add("recent_acts", recent);
        return state;
    }

    static JsonObject questions(Verdict verdict, List<Miracle> candidates) {
        JsonObject questions = new JsonObject();
        JsonObject options = new JsonObject();
        for (Miracle miracle : candidates) options.addProperty(miracle.id(), miracle.kind.label + ", сила " + miracle.minTier + "–" + miracle.maxTier + ": " + miracle.description);
        questions.add("miracle", question("choice", "Своенравный бог Minecraft услышал молитву prayer. Его решение уже взвешено причинами reasons "
                + "(итог: inclination, бюджет силы budget, ступень гнева или милости level из 10). Какое из дозволенных деяний лучше всего отвечает "
                + "и на молитву, и на эти причины, и на ступень? Будь разнообразен: не повторяй недавних деяний recent_acts, удивляй.", options));
        JsonArray levels = new JsonArray();
        String[] words = { "0: только знак", "1: малое", "2: среднее", "3: большое", "4: апокалипсис" };
        for (int tier = 0; tier <= Math.max(1, verdict.budget); tier++) levels.add(words[Math.min(4, tier)]);
        questions.add("power", question("score", "Насколько сильным должно быть это деяние при таких причинах?", levels));
        return questions;
    }

    /** Jev's reading of an utterance at the altar: is it a prayer, and does it insult the god. */
    public record Reading(boolean prayer, boolean insult) { }

    /** Both questions in one call; null when Jev could not answer. */
    public Reading read(String text) {
        JsonObject state = new JsonObject();
        state.addProperty("utterance", text == null ? "" : text.length() > 600 ? text.substring(0, 600) : text);
        state.addProperty("where", "игрок стоит у алтаря бога в Minecraft");
        JsonObject prayer = new JsonObject();
        prayer.addProperty("true", "молитва: обращение к богу — просьба, мольба, хвала, благодарность, покаяние, вопрос, брань или дерзость, сказанная богу");
        prayer.addProperty("false", "не молитва: болтовня с другими, мысли вслух, случайные слова, шум, речь не к богу");
        JsonObject insult = new JsonObject();
        insult.addProperty("true", "оскорбляет, хулит, высмеивает бога, бранится на него или требует нагло и с презрением");
        insult.addProperty("false", "почтительно, смиренно или хотя бы нейтрально");
        JsonObject questions = new JsonObject();
        questions.add("is_prayer", question("noul", "Игрок у алтаря произнёс utterance. Обращена ли эта речь к богу как молитва?", prayer));
        questions.add("insult", question("noul", "Если игрок обращается к богу, дерзит ли он богу или оскорбляет его?", insult));
        try {
            Answers answers = ask(state, questions);
            double yes = answers.noul("is_prayer"), rude = answers.noul("insult");
            return yes < 0 ? null : new Reading(yes >= 0.5, rude >= 0.6);
        } catch (IOException | RuntimeException failure) {
            diagnostics.accept("Jev не прочёл молитву: " + failure.getMessage());
            return null;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return null; }
    }

    /** Whether the words are a prayer the god hears, or idle talk he lets pass; null when Jev could not answer. */
    public Boolean isPrayer(String text) { Reading reading = read(text); return reading == null ? null : reading.prayer(); }
    private static JsonObject question(String type, String instructions, JsonElement criteria) {
        JsonObject q = new JsonObject(); q.addProperty("type", type); q.addProperty("instructions", instructions); q.add("criteria", criteria); return q;
    }

    /** One retry: a dropped connection (the first call after a while often is) should not hand the god to the rules. */
    Answers ask(JsonObject state, JsonObject questions) throws IOException, InterruptedException {
        try { return askOnce(state, questions); }
        catch (IOException failure) {
            if (failure.getMessage() == null || !failure.getMessage().contains("не удалось выполнить")) throw failure;
            return askOnce(state, questions);
        }
    }

    private Answers askOnce(JsonObject state, JsonObject questions) throws IOException, InterruptedException {
        JsonObject body = new JsonObject(); body.addProperty("model", MODEL); body.add("state", state); body.add("questions", questions);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT).header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<byte[]> response;
        try { response = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException slow) { throw new IOException("Decisions API: превышено время ожидания."); }
        catch (java.util.concurrent.ExecutionException failure) { throw new IOException("Decisions API: не удалось выполнить запрос."); }
        if (response.statusCode() != 200) throw new IOException("Decisions API: ошибка HTTP " + response.statusCode() + ".");
        if (response.body().length > MAX_BODY) throw new IOException("Decisions API: ответ превышает допустимый размер.");
        return parse(new String(response.body(), StandardCharsets.UTF_8));
    }

    @Override public Choice choose(Verdict verdict, DivineConfig config) {
        List<Miracle> candidates = GodDecider.candidates(verdict, config);
        if (verdict.inclination == Miracle.Kind.OMEN || candidates.size() < 3) return fallback.choose(verdict, config);
        Answers answers;
        try { answers = ask(state(verdict), questions(verdict, candidates)); }
        catch (IOException | RuntimeException failure) {
            diagnostics.accept("Jev недоступен, решают правила: " + failure.getMessage());
            return fallback.choose(verdict, config);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return fallback.choose(verdict, config); }
        return interpret(answers, verdict, candidates, config);
    }

    /** Jev's answer held to our list and our budget; anything outside them is the rules' turn. */
    Choice interpret(Answers answers, Verdict verdict, List<Miracle> candidates, DivineConfig config) {
        Miracle picked = Miracle.byId(answers.choice("miracle"));
        if (picked == null || !candidates.contains(picked)) {
            diagnostics.accept("Jev выбрал недопустимое «" + answers.choice("miracle") + "», решают правила");
            return fallback.choose(verdict, config);
        }
        double score = answers.score("power");
        int wanted = Double.isNaN(score) ? verdict.budget : (int) Math.round(score) - 1; // Scores run 1..N over the N levels.
        if (picked.kind == Miracle.Kind.WRATH) wanted = verdict.budget; // Jev picks the punishment; its weight is what was earned.
        int power = GodDecider.power(picked, wanted, verdict.budget);
        boolean warned = GodDecider.mustWarn(picked, power);
        String note = "Jev: " + picked.id() + ", сила " + wanted + (power != wanted ? " → " + power + (wanted > power ? " по бюджету" : ", меньше деяние не бывает") : "")
                + (answers.cost() > 0 ? String.format(java.util.Locale.ROOT, ", $%.5f", answers.cost()) : "");
        return new Choice(picked, power, warned, name(), note);
    }
}
