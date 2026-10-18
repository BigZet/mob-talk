package io.github.mobtalk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The chat-completions transport (OpenRouter or Venice) and the answer loop: build the request from {@link MobPrompt},
 * stream the reply so speech can start early, check it with {@link AnswerValidator}, and send up to two corrections.
 * Provider quirks (no streaming, no temperature, no strict schema) are learned once per client and kept. */
final class OpenRouterConversationClient implements AutoCloseable {
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");
    static final URI VENICE_ENDPOINT = URI.create("https://api.venice.ai/api/v1/chat/completions");
    private final String key;
    private final MobTalkConfig config;
    private final URI endpoint;
    private final BoundedHttp http;
    private final MobPrompt prompt;
    private boolean jsonObjectOnly, noTemperature, noStreaming;
    /** One-line diagnostics (rejected answers and their hints); the game points this at its log. */
    static volatile Consumer<String> diagnostics = line -> { };

    /** The chat endpoint of the provider chosen in the config. */
    OpenRouterConversationClient(String key, MobTalkConfig config) { this(key, config, config.usesVenice() ? VENICE_ENDPOINT : ENDPOINT); }
    OpenRouterConversationClient(String key, MobTalkConfig config, URI endpoint) {
        this.key = validKey(key); this.config = config.copy(); this.endpoint = endpoint; http = new BoundedHttp(config); prompt = new MobPrompt(this.config);
    }
    String service() { return config.usesVenice() ? "Venice LLM" : "OpenRouter LLM"; }

    static String validKey(String key) {
        if (key == null || key.isBlank() || key.contains("\r") || key.contains("\n")) throw new IllegalArgumentException("Локальный ключ OpenRouter пуст или имеет неверный формат.");
        return key.strip();
    }

    /** The system prompt this client would send; tests read it. */
    String systemPrompt(SpeciesCatalog.Species species, ConversationStore.Profile profile, MobSnapshot snapshot) { return prompt.system(species, profile, snapshot); }

    Answer respond(MobSnapshot snapshot, ConversationStore.Profile profile, String question) throws IOException, InterruptedException {
        return respond(snapshot, profile, question, ignored -> { });
    }

    /** earlyReply receives the spoken text as soon as it is complete in the stream, before memory, action and bargain;
     * it may be called again for a corrected retry, and the returned Answer is what counts. */
    Answer respond(MobSnapshot snapshot, ConversationStore.Profile profile, String question, Consumer<String> earlyReply)
            throws IOException, InterruptedException {
        SpeciesCatalog.Species species = SpeciesCatalog.effective(snapshot.type(), config);
        TradingView view = TradingView.of(snapshot);
        JsonArray messages = new JsonArray();
        messages.add(ReplySchema.message("system", prompt.system(species, profile, snapshot)));
        int history = Math.max(1, Math.min(64, config.historyTurns));
        for (int i = Math.max(0, profile.turns.size() - history); i < profile.turns.size(); i++) {
            ConversationStore.Turn turn = profile.turns.get(i);
            messages.add(ReplySchema.message("user", turn.user())); messages.add(ReplySchema.message("assistant", turn.assistant()));
        }
        messages.add(ReplySchema.message("user", prompt.userMessage(question, snapshot, profile)));
        JsonObject body = body(messages, 4096, view.windowOpen() ? 0.6 : 0.95, ReplySchema.replyFormat(snapshot), !noStreaming);
        long deadline = System.nanoTime() + http.timeout().toNanos();
        int words = prompt.words();
        AnswerValidator.Bounds bounds = new AnswerValidator.Bounds(words, profile.memory, Math.max(200, Math.min(4000, config.maxMemoryChars)),
                profile.relationship, jsonObjectOnly, service());
        byte[] bytes = send(body, deadline, text -> earlyReply.accept(TextBounds.reply(text, words)));
        for (int correction = 0; ; correction++) {
            try { return AnswerValidator.parse(bytes, snapshot, bounds); }
            catch (AnswerValidator.Rejected invalid) {
                diagnostics.accept("ответ отклонён (попытка " + (correction + 1) + ", " + snapshot.type() + "): " + invalid.getMessage());
                if (correction >= 2 || deadline <= System.nanoTime())
                    throw new IOException(invalid.trade ? service() + " не подтвердил допустимые условия торга. Цена не изменена; повторите предложение."
                            : service() + " не дал годной реплики. Повторите вопрос.");
                // Speech for a rejected attempt was paid for nothing; corrections are voiced only after their checks pass.
                if (correction == 1) {
                    // Two invalid trades in a row: answer in character without bargaining instead of failing the conversation.
                    messages.add(ReplySchema.message("system", PromptTexts.FINAL_CORRECTION));
                    ReplySchema.forbidIntents(body);
                } else if (!invalid.trade) messages.add(ReplySchema.message("system", PromptTexts.plainCorrection(invalid.getMessage())));
                else if (invalid.own) messages.add(ReplySchema.message("system", PromptTexts.ownTradeCorrection(invalid.getMessage(), view.permittedDealTypes(), view.permittedQuestTypes())));
                else messages.add(ReplySchema.message("system", PromptTexts.bargainCorrection(invalid.getMessage(), view.permittedBargainTypes())));
                bytes = send(body, deadline, ignored -> { });
            }
        }
    }

    /** The mob's invented life, see {@link BiographyWriter}. */
    BiographyWriter.Biography biography(MobSnapshot snapshot, ConversationStore.Profile profile, ConversationStore.Village village,
                                        List<String> takenNames, List<String> takenStories, List<String> takenVillages) throws IOException, InterruptedException {
        return BiographyWriter.write(this, config, new BiographyWriter.Facts(snapshot, profile, village, takenNames, takenStories, takenVillages));
    }

    /** A request body with this provider's parameters and whatever quirks this client has learned. */
    JsonObject body(JsonArray messages, int maxTokens, double temperature, JsonObject responseFormat, boolean stream) {
        JsonObject body = new JsonObject(); body.addProperty("model", config.chatModel()); body.add("messages", messages);
        body.addProperty("max_tokens", maxTokens); if (!noTemperature) body.addProperty("temperature", temperature); body.add("response_format", responseFormat);
        if (config.usesVenice()) {
            // Venice otherwise prepends its own system prompt, and its reasoning models think for seconds before replying.
            JsonObject venice = new JsonObject(); venice.addProperty("include_venice_system_prompt", false); venice.addProperty("disable_thinking", true);
            body.add("venice_parameters", venice);
        } else {
            JsonObject provider = new JsonObject(); provider.addProperty("require_parameters", true); provider.addProperty("sort", "latency"); body.add("provider", provider);
        }
        if (jsonObjectOnly) ReplySchema.useJsonObject(body);
        if (stream) body.addProperty("stream", true);
        return body;
    }

    /** One completion without streaming, within the configured timeout. */
    byte[] complete(JsonObject body) throws IOException, InterruptedException { return send(body, System.nanoTime() + http.timeout().toNanos(), ignored -> { }); }

    private byte[] send(JsonObject body, long deadline, Consumer<String> spoken) throws IOException, InterruptedException { return send(body, deadline, spoken, true); }

    private byte[] send(JsonObject body, long deadline, Consumer<String> spoken, boolean retryDropped) throws IOException, InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException(service() + ": превышено время ожидания.");
        try { return exchange(body, remaining, spoken); }
        catch (BoundedHttp.SafeFailure unavailable) {
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw unavailable;
            if (retryDropped && BoundedHttp.dropped(unavailable)) return send(body, deadline, spoken, false);
            if (unavailable.status == 400 && body.has("stream")) { body.remove("stream"); noStreaming = true; return send(body, deadline, spoken, retryDropped); }
            if (unavailable.status != 404) throw unavailable;
            // Claude 5.5 on OpenRouter has no endpoint taking temperature together with a strict schema.
            if (body.has("temperature")) { body.remove("temperature"); noTemperature = true; return send(body, deadline, spoken, retryDropped); }
            if (jsonObjectOnly) throw unavailable;
            // Providers without structured_outputs may still support response_format=json_object.
            jsonObjectOnly = true;
            ReplySchema.useJsonObject(body);
            return exchange(body, remaining, spoken);
        } catch (IOException failure) {
            if (retryDropped && BoundedHttp.dropped(failure) && deadline - System.nanoTime() > 0) return send(body, deadline, spoken, false);
            throw failure;
        }
    }

    /** Streams when asked: the reply is handed over the moment its closing quote arrives, then the whole answer is rebuilt
     * as an ordinary completion for the validator. */
    private byte[] exchange(JsonObject body, long remainingNanos, Consumer<String> spoken) throws IOException, InterruptedException {
        HttpRequest request = request(body, Duration.ofNanos(remainingNanos));
        if (!body.has("stream")) return http.send(request, 256 * 1024, service());
        StringBuilder content = new StringBuilder();
        boolean[] seen = new boolean[2]; // [0] any event, [1] reply already handed over
        String[] error = new String[1];
        byte[] raw = http.sendEvents(request, 4 * 1024 * 1024, service(), data -> {
            JsonObject event = JsonParser.parseString(data).getAsJsonObject();
            seen[0] = true;
            if (event.has("error")) { error[0] = service() + ": ошибка провайдера во время ответа."; return; }
            JsonArray choices = event.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) return;
            JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
            if (delta == null || !delta.has("content") || delta.get("content").isJsonNull()) return;
            content.append(delta.get("content").getAsString());
            if (!seen[1]) {
                String reply = completedReply(content);
                if (reply != null) { seen[1] = true; spoken.accept(reply); }
            }
        });
        if (error[0] != null) throw new IOException(error[0]);
        if (!seen[0]) return raw;
        JsonObject message = new JsonObject(); message.addProperty("content", content.toString());
        JsonObject choice = new JsonObject(); choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject completion = new JsonObject(); completion.add("choices", choices);
        return completion.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static final Pattern REPLY_START = Pattern.compile("\"reply\"\\s*:\\s*\"");

    /** The reply string once its closing quote has streamed in, else null. */
    static String completedReply(CharSequence partial) {
        Matcher start = REPLY_START.matcher(partial);
        if (!start.find()) return null;
        boolean escaped = false;
        for (int i = start.end(); i < partial.length(); i++) {
            char c = partial.charAt(i);
            if (escaped) escaped = false;
            else if (c == '\\') escaped = true;
            else if (c == '"') return JsonParser.parseString(partial.subSequence(start.end() - 1, i + 1).toString()).getAsString();
        }
        return null;
    }

    private HttpRequest request(JsonObject body, Duration timeout) {
        return HttpRequest.newBuilder(endpoint).timeout(timeout).header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
    }

    /** deal/unlock/quest concern the mob's own catalog (trades.json); bargain stays the vanilla villager window. */
    record Answer(String reply, String memory, SocialIntent socialIntent, ActionIntent action, BargainIntent bargain,
                  DealIntent deal, String unlock, QuestIntent quest) {
        Answer {
            if (deal == null) deal = DealIntent.none();
            unlock = unlock == null ? "" : unlock;
            if (quest == null) quest = QuestIntent.none();
        }
        Answer(String reply, String memory, SocialIntent socialIntent, ActionIntent action, BargainIntent bargain) {
            this(reply, memory, socialIntent, action, bargain, DealIntent.none(), "", QuestIntent.none());
        }
        Answer(String reply, String memory, SocialIntent socialIntent, ActionIntent action) { this(reply, memory, socialIntent, action, BargainIntent.none()); }
        Answer(String reply, String memory) { this(reply, memory, SocialIntent.NONE, ActionIntent.none()); }
    }
    @Override public void close() { http.close(); }
}
