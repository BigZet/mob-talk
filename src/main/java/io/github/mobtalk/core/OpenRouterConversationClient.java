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
    private boolean jsonObjectOnly, noTemperature, noStreaming, noReasoning;
    /** One-line diagnostics (rejected answers and their hints); the game points this at its log. */
    static volatile Consumer<String> diagnostics = line -> { };
    /** At most three completions in flight across all clients, so a low-tier key is not rate-limited, in three lanes: the player's
     * own question never waits behind anyone; bystanders, initiative and dialogue share two permits; background work (summaries,
     * reflections, biographies, chatter, rumours) goes one at a time through those same two, so it can never take both. */
    enum Lane { PLAYER, OTHERS, BACKGROUND }
    private static final java.util.concurrent.Semaphore PLAYER_LANE = new java.util.concurrent.Semaphore(1, true);
    private static final java.util.concurrent.Semaphore OTHER_LANES = new java.util.concurrent.Semaphore(2, true);
    private static final java.util.concurrent.Semaphore BACKGROUND_LANE = new java.util.concurrent.Semaphore(1, true);
    /** Rate limits (429) are retried after this many milliseconds, doubling once. */
    static final long RATE_LIMIT_PAUSE_MILLIS = 1500;

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
        return respond(snapshot, profile, question, null, earlyReply);
    }

    /** The spoken text handed over while the rest of the answer still streams. {@link #shown()} says whether the player already
     * saw and heard it: from then on a rejected tail is dropped rather than corrected, since the line cannot be unsaid. */
    interface EarlyReply extends Consumer<String> {
        default boolean shown() { return false; }
    }

    /** {@code overheard} non-null: the mob is a bystander of that exchange, {@code question} is ignored, trading intents are off. */
    Answer respond(MobSnapshot snapshot, ConversationStore.Profile profile, String question, ConversationEngine.Overheard overheard, Consumer<String> earlyReply)
            throws IOException, InterruptedException {
        return respond(snapshot, profile, question, overheard, null, earlyReply);
    }

    /** The provider refused a picture once (HTTP 400): the rest of the session goes without. */
    private volatile boolean noVision;

    /** {@code picture} non-null: a JPEG of the player's view goes with the question as an image part ({@link Vision}). */
    Answer respond(MobSnapshot snapshot, ConversationStore.Profile profile, String question, ConversationEngine.Overheard overheard, byte[] picture, Consumer<String> earlyReply)
            throws IOException, InterruptedException {
        return respond(snapshot, profile, question, overheard, picture, earlyReply instanceof EarlyReply early ? early : earlyReply::accept);
    }

    Answer respond(MobSnapshot snapshot, ConversationStore.Profile profile, String question, ConversationEngine.Overheard overheard, byte[] picture, EarlyReply earlyReply)
            throws IOException, InterruptedException {
        SpeciesCatalog.Species species = SpeciesCatalog.effective(snapshot.type(), config);
        TradingView view = TradingView.of(snapshot);
        // A bystander or a creature speaking first makes no gesture with the player: no outcomes block, no gesture field.
        if (overheard != null) profile.socialTurn = null;
        JsonArray messages = new JsonArray();
        String aside = overheard == null ? "" : "\n\n" + switch (overheard.kind()) {
            case BYSTANDER -> PromptTexts.OVERHEARD; case INITIATIVE -> PromptTexts.INITIATIVE; case DIALOGUE -> PromptTexts.DIALOGUE; };
        messages.add(ReplySchema.message("system", prompt.system(species, profile, snapshot) + aside + (overheard == null && ReplySchema.offersCards(snapshot) ? "\n" + PromptTexts.CARD_GAME : "")));
        int history = Math.max(1, Math.min(64, config.historyTurns));
        // Only the current conversation's exchanges: the earlier ones are read as summaries in the system prompt (ConversationSummary).
        for (int i = Math.max(profile.unsummarizedFrom(), profile.turns.size() - history); i < profile.turns.size(); i++) {
            ConversationStore.Turn turn = profile.turns.get(i);
            messages.add(ReplySchema.message("user", turn.user())); messages.add(ReplySchema.message("assistant", turn.assistant()));
        }
        boolean withPicture = overheard == null && picture != null && picture.length > 0 && !noVision;
        if (withPicture) { messages.add(Vision.message(prompt.userMessage(question, snapshot, profile), picture)); Vision.ATTACHED.incrementAndGet(); diagnostics.accept("снимок " + picture.length / 1024 + " КБ приложен к вопросу"); }
        else messages.add(ReplySchema.message("user", overheard == null ? prompt.userMessage(question, snapshot, profile) : prompt.overheardMessage(overheard, snapshot, profile)));
        // A mob without a life story invents it inside its first reply: one request, not two.
        boolean needsStory = profile.backstory == null || profile.backstory.isBlank();
        // An anxious or distrustful mob cannot concede: the schema itself drops OFFER, the validator catches json_object providers.
        MobState.View state = profile.stateView != null ? profile.stateView : MobState.viewOf(profile, MobClock.now());
        boolean wary = state.refusesDeals();
        JsonObject format = ReplySchema.replyFormat(snapshot, needsStory, wary, overheard == null ? SocialPrompt.schemaGestures(profile) : List.of());
        if (overheard == null) ReplySchema.addGame(format, snapshot);
        JsonObject body = body(messages, 4096, view.windowOpen() ? 0.6 : 0.95, format, !noStreaming);
        if (overheard != null) ReplySchema.forbidIntents(body);
        long deadline = System.nanoTime() + http.timeout().toNanos();
        int words = prompt.words();
        Lane lane = overheard == null ? Lane.PLAYER : Lane.OTHERS;
        byte[] bytes = send(body, deadline, text -> earlyReply.accept(TextBounds.reply(text, words)), lane);
        for (int correction = 0; ; correction++) {
            // Read the provider quirks after each send: a 404 fallback to json_object may have happened inside it.
            java.util.List<String> recent = profile.turns == null ? java.util.List.of()
                    : profile.turns.subList(Math.max(0, profile.turns.size() - 3), profile.turns.size()).stream().map(ConversationStore.Turn::assistant).toList();
            AnswerValidator.Bounds bounds = new AnswerValidator.Bounds(words, profile.memory, Math.max(200, Math.min(4000, config.maxMemoryChars)),
                    profile.relationship, jsonObjectOnly, service(), overheard != null, wary, recent, overheard == null ? profile.socialTurn : null);
            try { return AnswerValidator.parse(bytes, snapshot, bounds); }
            catch (AnswerValidator.Rejected invalid) {
                diagnostics.accept("ответ отклонён (попытка " + (correction + 1) + ", " + snapshot.type() + "): " + invalid.getMessage());
                // The line already reached the player's ears: it stands as said, only its intents are dropped (no bargain, no action).
                if (correction == 0 && earlyReply.shown()) {
                    String reply = TextBounds.reply(spokenReply(bytes), words);
                    diagnostics.accept("реплика уже прозвучала: намерения этого ответа не применяются");
                    return new Answer(reply, null);
                }
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
                bytes = send(body, deadline, ignored -> { }, lane);
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
            // No require_parameters: OpenRouter keeps only opt-in tier endpoints for some models, and the default-tier ones then
            // fail a strict parameter match (404 "No endpoints found"). response_format stays a soft routing preference.
            JsonObject provider = new JsonObject(); provider.addProperty("sort", "latency"); body.add("provider", provider);
            // A reasoning model thinks for seconds before the first token; a mob's line does not need that. "none" is sent as such:
            // measured on gpt-6-luna, "minimal" still thought on most replies (1.5–2.5 s), an explicit "none" never did.
            if (!config.reasoningEffort.isBlank() && !noReasoning) {
                JsonObject reasoning = new JsonObject(); reasoning.addProperty("effort", config.reasoningEffort); body.add("reasoning", reasoning);
            }
        }
        if (jsonObjectOnly) ReplySchema.useJsonObject(body);
        if (stream) body.addProperty("stream", true);
        // Учёт OpenRouter: цена запроса в ответе ({@code usage.cost}) — для журнала админки и бюджета игрока на сервере.
        if (!config.usesVenice()) { JsonObject usage = new JsonObject(); usage.addProperty("include", true); body.add("usage", usage); }
        return body;
    }

    /** A body for a background task nobody waits for (summaries, reflections): the cheap model from the config when one is set,
     * minimal reasoning, no streaming. */
    JsonObject backgroundBody(JsonArray messages, int maxTokens, JsonObject responseFormat) {
        JsonObject body = body(messages, maxTokens, 0.5, responseFormat, false);
        if (config.backgroundModel != null && !config.backgroundModel.isBlank()) body.addProperty("model", config.backgroundModel.strip());
        if (body.has("reasoning") && !"none".equals(config.reasoningEffort)) body.getAsJsonObject("reasoning").addProperty("effort", "minimal");
        return body;
    }

    /** One completion without streaming, within the configured timeout, on the background lane. */
    byte[] complete(JsonObject body) throws IOException, InterruptedException { return send(body, System.nanoTime() + http.timeout().toNanos(), ignored -> { }, Lane.BACKGROUND); }

    private byte[] send(JsonObject body, long deadline, Consumer<String> spoken, Lane lane) throws IOException, InterruptedException { return send(body, deadline, spoken, lane, true, true, 0); }

    private byte[] send(JsonObject body, long deadline, Consumer<String> spoken, Lane lane, boolean retryDropped, boolean retryStalled, int rateLimited) throws IOException, InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException(service() + ": превышено время ожидания.");
        try { return guarded(body, remaining, spoken, lane); }
        catch (BoundedHttp.SafeFailure unavailable) {
            remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw unavailable;
            if (unavailable.status == 429 && rateLimited < 2) {
                long pause = RATE_LIMIT_PAUSE_MILLIS << rateLimited;
                if (remaining > pause * 1_000_000L) { Thread.sleep(pause); return send(body, deadline, spoken, lane, retryDropped, retryStalled, rateLimited + 1); }
            }
            if (retryDropped && BoundedHttp.dropped(unavailable)) return send(body, deadline, spoken, lane, false, retryStalled, rateLimited);
            // The model took the request and went silent: once more, instead of waiting the whole timeout for nothing.
            if (BoundedHttp.stalled(unavailable)) {
                if (retryStalled) return send(body, deadline, spoken, lane, retryDropped, false, rateLimited);
                throw new IOException(service() + ": модель не начала отвечать вовремя. Повторите вопрос.");
            }
            // A model that takes no images answers 400: the question goes again as text only, and no picture is sent again this session.
            if (unavailable.status == 400 && Vision.hasImage(body)) { Vision.stripImages(body); noVision = true; diagnostics.accept(service() + " не принял снимок: дальше без зрения"); return send(body, deadline, spoken, lane, retryDropped, retryStalled, rateLimited); }
            // An endpoint whose thinking cannot be switched off (Gemini Flash) answers 400 to reasoning: the rest of the session goes without the field.
            if (unavailable.status == 400 && body.has("reasoning")) { body.remove("reasoning"); noReasoning = true; diagnostics.accept(service() + " не принял параметр reasoning: дальше без него"); return send(body, deadline, spoken, lane, retryDropped, retryStalled, rateLimited); }
            if (unavailable.status == 400 && body.has("stream")) { body.remove("stream"); noStreaming = true; return send(body, deadline, spoken, lane, retryDropped, retryStalled, rateLimited); }
            if (unavailable.status != 404) throw unavailable;
            // Claude 5.5 on OpenRouter has no endpoint taking temperature together with a strict schema.
            if (body.has("temperature")) { body.remove("temperature"); noTemperature = true; return send(body, deadline, spoken, lane, retryDropped, retryStalled, rateLimited); }
            if (jsonObjectOnly) throw unavailable;
            // Providers without structured_outputs may still support response_format=json_object.
            jsonObjectOnly = true;
            ReplySchema.useJsonObject(body);
            return guarded(body, remaining, spoken, lane);
        } catch (IOException failure) {
            if (retryDropped && BoundedHttp.dropped(failure) && deadline - System.nanoTime() > 0) return send(body, deadline, spoken, lane, false, retryStalled, rateLimited);
            throw failure;
        }
    }

    /** At most a few completions in flight across all clients, by lane; the rest wait their turn within the deadline. */
    private byte[] guarded(JsonObject body, long remainingNanos, Consumer<String> spoken, Lane lane) throws IOException, InterruptedException {
        long waited = System.nanoTime();
        java.util.concurrent.Semaphore first = lane == Lane.BACKGROUND ? BACKGROUND_LANE : null, second = lane == Lane.PLAYER ? PLAYER_LANE : OTHER_LANES;
        if (first != null && !first.tryAcquire(remainingNanos, java.util.concurrent.TimeUnit.NANOSECONDS)) throw new IOException(service() + ": превышено время ожидания.");
        try {
            long left = remainingNanos - (System.nanoTime() - waited);
            if (left <= 0 || !second.tryAcquire(left, java.util.concurrent.TimeUnit.NANOSECONDS)) throw new IOException(service() + ": превышено время ожидания.");
            try { return exchange(body, remainingNanos - (System.nanoTime() - waited), spoken); }
            finally { second.release(); }
        } finally { if (first != null) first.release(); }
    }

    /** Streams when asked: the reply is handed over the moment its closing quote arrives, then the whole answer is rebuilt
     * as an ordinary completion for the validator. */
    private byte[] exchange(JsonObject body, long remainingNanos, Consumer<String> spoken) throws IOException, InterruptedException {
        HttpRequest request = request(body, Duration.ofNanos(remainingNanos));
        if (!body.has("stream")) { byte[] whole = http.send(request, 256 * 1024, service()); LlmSpend.add(usage(whole)); return whole; }
        StringBuilder content = new StringBuilder();
        boolean[] seen = new boolean[2]; // [0] any event, [1] reply already handed over
        String[] error = new String[1];
        JsonObject[] usage = new JsonObject[1];
        byte[] raw = http.sendEvents(request, 4 * 1024 * 1024, service(), data -> {
            JsonObject event = JsonParser.parseString(data).getAsJsonObject();
            seen[0] = true;
            if (event.has("error")) { error[0] = service() + ": ошибка провайдера во время ответа."; return; }
            if (event.has("usage") && event.get("usage").isJsonObject()) usage[0] = event.getAsJsonObject("usage");
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
        LlmSpend.add(usage[0] != null || seen[0] ? usage[0] : usage(raw)); // Без событий провайдер ответил целиком.
        if (error[0] != null) throw new IOException(error[0]);
        if (!seen[0]) return raw;
        JsonObject message = new JsonObject(); message.addProperty("content", content.toString());
        JsonObject choice = new JsonObject(); choice.add("message", message);
        JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject completion = new JsonObject(); completion.add("choices", choices);
        return completion.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** {@code usage} of a whole completion, null when it has none or is unreadable. */
    private static JsonObject usage(byte[] completion) {
        try {
            JsonObject response = JsonParser.parseString(new String(completion, StandardCharsets.UTF_8)).getAsJsonObject();
            return response.has("usage") && response.get("usage").isJsonObject() ? response.getAsJsonObject("usage") : null;
        } catch (RuntimeException unreadable) { return null; }
    }

    /** The reply string of a completion the validator refused: what the player already heard, "" when even that is unreadable. */
    static String spokenReply(byte[] completion) {
        try {
            String content = JsonParser.parseString(new String(completion, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("choices")
                    .get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
            try { return JsonParser.parseString(content).getAsJsonObject().get("reply").getAsString(); }
            catch (RuntimeException partial) { String reply = completedReply(content); return reply == null ? "" : reply; }
        } catch (RuntimeException unreadable) { return ""; }
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
    /** {@code thought}: the mob's one-line aside to itself, kept as its concern until the next answer ("" when it gave none). */
    /** {@code fact}: the newFacts of this answer with their importance (see {@link MemoryRanking}), or null when it added none;
     * {@code memory} is null for such answers and a whole memory text only for older or fallback ones. */
    record Answer(String reply, String memory, SocialIntent socialIntent, ActionIntent action, BargainIntent bargain,
                  DealIntent deal, String unlock, QuestIntent quest, BiographyWriter.Biography biography, String thought, MemoryFact fact, GestureIntent gesture,
                  boolean gameOffer) {
        Answer(String reply, String memory, SocialIntent socialIntent, ActionIntent action, BargainIntent bargain, DealIntent deal, String unlock,
               QuestIntent quest, BiographyWriter.Biography biography, String thought, MemoryFact fact, GestureIntent gesture) {
            this(reply, memory, socialIntent, action, bargain, deal, unlock, quest, biography, thought, fact, gesture, false);
        }
        Answer {
            if (gesture == null) gesture = GestureIntent.none();
            if (deal == null) deal = DealIntent.none();
            unlock = unlock == null ? "" : unlock;
            if (quest == null) quest = QuestIntent.none();
            thought = thought == null ? "" : thought;
        }
        Answer(String reply, String memory, SocialIntent socialIntent, ActionIntent action, BargainIntent bargain,
               DealIntent deal, String unlock, QuestIntent quest, BiographyWriter.Biography biography, String thought, MemoryFact fact) {
            this(reply, memory, socialIntent, action, bargain, deal, unlock, quest, biography, thought, fact, GestureIntent.none());
        }
        Answer(String reply, String memory, SocialIntent socialIntent, ActionIntent action, BargainIntent bargain,
               DealIntent deal, String unlock, QuestIntent quest, BiographyWriter.Biography biography, String thought) {
            this(reply, memory, socialIntent, action, bargain, deal, unlock, quest, biography, thought, null);
        }
        Answer(String reply, String memory, SocialIntent socialIntent, ActionIntent action, BargainIntent bargain,
               DealIntent deal, String unlock, QuestIntent quest, BiographyWriter.Biography biography) {
            this(reply, memory, socialIntent, action, bargain, deal, unlock, quest, biography, "");
        }
        Answer(String reply, String memory, SocialIntent socialIntent, ActionIntent action, BargainIntent bargain,
               DealIntent deal, String unlock, QuestIntent quest) {
            this(reply, memory, socialIntent, action, bargain, deal, unlock, quest, null);
        }
        Answer(String reply, String memory, SocialIntent socialIntent, ActionIntent action, BargainIntent bargain) {
            this(reply, memory, socialIntent, action, bargain, DealIntent.none(), "", QuestIntent.none());
        }
        Answer(String reply, String memory, SocialIntent socialIntent, ActionIntent action) { this(reply, memory, socialIntent, action, BargainIntent.none()); }
        Answer(String reply, String memory) { this(reply, memory, SocialIntent.NONE, ActionIntent.none()); }
    }
    @Override public void close() { http.close(); }
}
