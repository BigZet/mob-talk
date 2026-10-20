package io.github.divineblock.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** The god's words. The decision is already made; a chat model only puts it into a line that names the main reason. Venice
 * when chosen, OpenRouter when Venice fails, and a template when both are silent: the god never stays mute because of the
 * network. Never on the game thread. */
public final class GodSpeech {
    static final URI OPENROUTER = URI.create("https://openrouter.ai/api/v1/chat/completions");
    static final URI VENICE = URI.create("https://api.venice.ai/api/v1/chat/completions");
    private static final int MAX_BODY = 256 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NEVER).build();

    private final DivineConfig config;
    private final Path configDirectory;

    public GodSpeech(DivineConfig config, Path configDirectory) { this.config = config.copy(); this.configDirectory = configDirectory; }

    /** The line for a fresh verdict. */
    public String announce(Verdict verdict) {
        String line = ask(system(), announcement(verdict));
        return line != null ? line : template(verdict);
    }

    /** The line that retells a past verdict when the player asks "why?". */
    public String explain(Verdict verdict) {
        String line = ask(system(), explanation(verdict));
        return line != null ? line : explainTemplate(verdict);
    }

    String system() {
        return "Ты своенравный бог мира Minecraft, к которому игрок обращается через алтарь. Нрав: " + config.personality.label + ". "
                + "Речь твоя старорусская, как в былинах и летописях: «ныне», «аще», «зане», «отрок», «внемли», «сие», «очи», «глаголю», "
                + "с древними оборотами и звательным падежом («смертне», «чадо»), но так, чтобы современный игрок понял смысл. "
                + "Говоришь загадочно и иносказательно, как оракул: притчами, знаками и намёками, торжественно и с характером, порой с "
                + "лукавой усмешкой; не угодничаешь. Отвечаешь одной-двумя короткими фразами, всего не длиннее 30 слов, без кавычек, эмодзи и пояснений. Решение уже "
                + "принято; ты лишь возвещаешь его и непременно поминаешь хотя бы одну из главных причин, пусть иносказанием, но узнаваемо. "
                + "Не обещай того, чего нет в решении, и не называй чисел.";
    }

    static String announcement(Verdict verdict) {
        Miracle miracle = verdict.chosen();
        StringBuilder out = new StringBuilder("Молитва смертного: «").append(clip(verdict.prayer, 400)).append("»\n");
        out.append("Твоё решение: ").append(miracle.kind.label).append(" — ").append(miracle.description).append(" (сила ").append(verdict.power).append(").\n");
        if (verdict.warned) out.append("Сначала ты лишь предупреждаешь: кара придёт через несколько секунд, если смертный не покается.\n");
        out.append("Главные причины: ").append(reasonList(verdict.mainReasons(3))).append(".");
        return out.toString();
    }

    static String explanation(Verdict verdict) {
        return "Смертный спрашивает, почему ты так поступил в прошлый раз. Тогда он молился: «" + clip(verdict.prayer, 300) + "», и ты ответил так: "
                + verdict.chosen().kind.label + " — " + verdict.chosen().description + (verdict.forgiven ? " (но он покаялся, и ты смилостивился)" : "")
                + ". Причины: " + reasonList(verdict.mainReasons(4)) + ". Объясни своё решение этими причинами.";
    }

    /** A line without a model: the act and its main reason. */
    static String template(Verdict verdict) {
        List<Verdict.Reason> main = verdict.mainReasons(1);
        String reason = main.isEmpty() ? "так я решил" : main.get(0).text();
        return switch (verdict.chosen().kind) {
            case HELP -> "Внемлю тебе, чадо. Зане " + reasonClause(reason) + ", приими милость мою.";
            case JEST -> "Хе-хе… Аще " + reasonClause(reason) + ", то и потеха тебе будет.";
            case WRATH -> verdict.warned ? "Трепещи, смертне: " + reasonClause(reason) + ". Близок гнев мой, яко гроза за холмом."
                    : "Сам того восхотел еси: " + reasonClause(reason) + ".";
            case OMEN -> verdict.chosen() == Miracle.IGNORE ? "…" : "Слышу тя. Ныне лишь знамение даю: " + reasonClause(reason) + ".";
        };
    }

    static String explainTemplate(Verdict verdict) {
        return "Вопрошаешь, отчего так? " + capitalize(reasonList(verdict.mainReasons(3))) + ". Имеяй уши да слышит.";
    }

    private static String reasonClause(String reason) { return reason.toLowerCase(Locale.ROOT); }
    private static String reasonList(List<Verdict.Reason> reasons) {
        if (reasons.isEmpty()) return "так я решил";
        StringBuilder out = new StringBuilder();
        for (Verdict.Reason reason : reasons) { if (!out.isEmpty()) out.append("; "); out.append(reason.text()); }
        return out.toString();
    }
    private static String capitalize(String text) { return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1); }
    private static String clip(String text, int max) { return text == null ? "" : text.length() <= max ? text : text.substring(0, max) + "…"; }

    /** One completion, Venice first when chosen, then OpenRouter; null when neither answered. */
    private String ask(String system, String user) {
        if (config.provider == DivineConfig.Provider.VENICE) {
            String line = complete(ApiKeys.Provider.VENICE, VENICE, config.veniceModel, system, user);
            if (line != null) return line;
            JevGodDecider.diagnostics.accept("Venice молчит, речь бога через OpenRouter");
        }
        return complete(ApiKeys.Provider.OPENROUTER, OPENROUTER, config.chatModel, system, user);
    }

    private String complete(ApiKeys.Provider provider, URI endpoint, String model, String system, String user) {
        try {
            String key = ApiKeys.load(configDirectory, provider);
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            JsonArray messages = new JsonArray();
            messages.add(message("system", system)); messages.add(message("user", user));
            body.add("messages", messages);
            body.addProperty("max_tokens", 220);
            body.addProperty("temperature", 0.9);
            if (provider == ApiKeys.Provider.VENICE) {
                JsonObject venice = new JsonObject(); venice.addProperty("include_venice_system_prompt", false); venice.addProperty("disable_thinking", true);
                body.add("venice_parameters", venice);
            } else {
                JsonObject reasoning = new JsonObject(); reasoning.addProperty("effort", "minimal"); body.add("reasoning", reasoning);
            }
            Duration timeout = Duration.ofSeconds(config.speechTimeoutSeconds);
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<byte[]> response = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) { JevGodDecider.diagnostics.accept(provider.label + " LLM: HTTP " + response.statusCode()); return null; }
            if (response.body().length > MAX_BODY) return null;
            return clean(parse(new String(response.body(), StandardCharsets.UTF_8)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return null;
        } catch (Exception failure) {
            JevGodDecider.diagnostics.accept(provider.label + " LLM недоступен: " + failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : " " + failure.getMessage()));
            return null;
        }
    }

    static String parse(String body) throws IOException {
        try {
            JsonObject message = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
            return message.get("content").getAsString();
        } catch (RuntimeException malformed) { throw new IOException("ответ модели не разобран"); }
    }

    /** One or two sentences of plain text, or null when the model said nothing usable. */
    static String clean(String text) {
        if (text == null) return null;
        String line = text.replaceAll("(?s)<think>.*?</think>", "").replaceAll("[*_#`]", "").replaceAll("\\s+", " ").strip();
        if (line.startsWith("«") && line.endsWith("»") || line.startsWith("\"") && line.endsWith("\"")) line = line.substring(1, line.length() - 1).strip();
        if (line.isBlank()) return null;
        // Spoken aloud, a god keeps it short: two sentences at most.
        java.util.regex.Matcher sentences = java.util.regex.Pattern.compile("[^.!?…]+[.!?…]+").matcher(line);
        StringBuilder kept = new StringBuilder();
        for (int count = 0; count < 2 && sentences.find(); count++) kept.append(sentences.group());
        if (!kept.isEmpty()) line = kept.toString().strip();
        return line.length() > 320 ? line.substring(0, 320).replaceAll("\\s+\\S*$", "") + "…" : line;
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject(); m.addProperty("role", role); m.addProperty("content", content); return m;
    }
}
