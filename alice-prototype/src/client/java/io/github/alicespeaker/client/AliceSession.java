package io.github.alicespeaker.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * One question to the real Alice over the undocumented web protocol (wss://uniproxy.alice.ya.ru/uni.ws),
 * as the alice.yandex.ru site speaks it. Handshake and TextInput follow FreeAliceAPI (MIT); voice_session=true
 * asks Alice to voice the answer herself. Everything runs off the game thread.
 */
final class AliceSession implements WebSocket.Listener {
    static final String URL = "wss://uniproxy.alice.ya.ru/uni.ws";
    private static final String ORIGIN = "https://alice.yandex.ru";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
    private static final String APP_ID = "ru.yandex.webdesktop", APP_VERSION = "1.0.281-home-static/alice-web/15";
    private boolean placeholderSeen;
    private int emptyAfterPlaceholder;
    private static final Pattern PLACEHOLDER = Pattern.compile("^(одну секунду|секунду|подождите|подумаю)[.!?…]*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final ScheduledExecutorService TIMERS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "alicespeaker-timer");
        thread.setDaemon(true);
        return thread;
    });
    private static final int VOICE_STREAM = 1;

    /** What Alice said: final text, what she heard (voice input), her voice per audio stream, protocol notes. */
    /** {@code musicUri}: the musicsdk:// link of her player directive (a logged-in "включи…"), "" when none. */
    record Answer(String text, String heard, List<byte[]> voice, String voiceFormat, List<String> notes, String musicUri) { }

    /** Yandex refused the login in the handshake: an expired token, renewed from the x_token by the caller. */
    static final class InvalidAuth extends IllegalStateException {
        InvalidAuth() { super("Яндекс не принял вход: войдите заново (N → «Войти в Яндекс»)"); }
    }

    private final String text;
    private final byte[] pcm16k;
    private final String cookie, authToken, sync, oauthToken;
    private final Consumer<String> log;
    private final CompletableFuture<Answer> result = new CompletableFuture<>();
    private String uuid = UUID.randomUUID().toString(), appId = APP_ID, appVersion = APP_VERSION, platform = "windows";
    private final Map<Integer, ByteArrayOutputStream> streams = new LinkedHashMap<>();
    private final List<Integer> closedStreams = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final StringBuilder textFrame = new StringBuilder();
    private final ByteArrayOutputStream binaryFrame = new ByteArrayOutputStream();
    private CompletableFuture<WebSocket> sending;
    private WebSocket socket;
    private int seq = 1, followUps;
    private String finalText = "", heard = "", lastRequestId, stackSessionId, musicUri = "";
    private boolean speakSeen;
    private String voiceFormat = "audio/opus";

    private AliceSession(String text, byte[] pcm16k, AliceAuth auth, Consumer<String> log) {
        this.text = text;
        this.pcm16k = pcm16k;
        this.cookie = auth.cookie();
        this.authToken = auth.authToken();
        this.sync = auth.sync();
        this.oauthToken = auth.oauthToken();
        this.log = log;
    }

    static AliceSession text(String text, AliceAuth auth, Consumer<String> log) { return new AliceSession(text, null, auth, log); }
    static AliceSession voice(byte[] pcm16k, AliceAuth auth, Consumer<String> log) { return new AliceSession(null, pcm16k, auth, log); }

    CompletableFuture<Answer> start() {
        WebSocket.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                .newWebSocketBuilder().header("Origin", ORIGIN).header("User-Agent", USER_AGENT);
        if (cookie != null && !cookie.isBlank()) builder.header("Cookie", cookie);
        builder.buildAsync(URI.create(System.getProperty("alicespeaker.url", URL)), this).whenComplete((ws, failure) -> {
            if (failure != null) fail(new IllegalStateException("Не удалось подключиться к Алисе: " + rootMessage(failure)));
        });
        TIMERS.schedule(() -> finish(true), 40, TimeUnit.SECONDS);
        return result;
    }

    void cancel() {
        result.cancel(false);
        close();
    }

    @Override public void onOpen(WebSocket webSocket) {
        socket = webSocket;
        sending = CompletableFuture.completedFuture(webSocket);
        JsonObject payload = siteHandshake();
        if (payload == null) {
            JsonObject application = new JsonObject();
            application.addProperty("app_id", APP_ID);
            application.addProperty("platform", "windows");
            JsonObject vins = new JsonObject();
            vins.add("application", application);
            payload = new JsonObject();
            payload.addProperty("auth_token", authToken.isBlank() ? UUID.randomUUID().toString() : authToken);
            payload.addProperty("uuid", uuid);
            payload.add("vins", vins);
        }
        // A user OAuth token in the handshake, as Yandex's SpeechKit clients log in (the value is never logged).
        if (!oauthToken.isBlank()) payload.addProperty("oauth_token", oauthToken);
        send(event("System", "SynchronizeState", payload, null));
        webSocket.request(1);
    }

    /**
     * The payload of the site's own SynchronizeState (sync.json), sent as is: it carries the login. Its uuid and
     * application become this session's, so later requests look like the same browser. Only key names are logged.
     */
    private JsonObject siteHandshake() {
        if (sync.isBlank()) return null;
        try {
            JsonObject root = JsonParser.parseString(sync).getAsJsonObject();
            JsonObject event = obj(root, "event");
            JsonObject payload = event != null ? obj(event, "payload") : obj(root, "payload");
            if (payload == null) { log.accept("sync.json: no event.payload, using the anonymous handshake"); return null; }
            if (!str(payload, "uuid").isBlank()) uuid = str(payload, "uuid");
            JsonObject vins = obj(payload, "vins");
            JsonObject application = vins == null ? null : obj(vins, "application");
            if (application != null) {
                if (!str(application, "app_id").isBlank()) appId = str(application, "app_id");
                if (!str(application, "app_version").isBlank()) appVersion = str(application, "app_version");
                if (!str(application, "platform").isBlank()) platform = str(application, "platform");
            }
            log.accept("sync.json keys " + payload.keySet() + ", app " + appId + " " + appVersion);
            return payload;
        } catch (RuntimeException broken) {
            log.accept("sync.json unreadable (" + broken.getClass().getSimpleName() + "), using the anonymous handshake");
            return null;
        }
    }

    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        textFrame.append(data);
        if (last) {
            String frame = textFrame.toString();
            textFrame.setLength(0);
            try { handle(JsonParser.parseString(frame).getAsJsonObject()); }
            catch (RuntimeException broken) { log.accept("unparsed frame: " + cut(frame, 200)); }
        }
        webSocket.request(1);
        return null;
    }

    @Override public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        binaryFrame.writeBytes(chunk);
        if (last) {
            byte[] frame = binaryFrame.toByteArray();
            binaryFrame.reset();
            if (frame.length > 4) {
                // Each binary frame is a 4-byte big-endian stream id followed by audio bytes.
                int stream = ((frame[0] & 255) << 24) | ((frame[1] & 255) << 16) | ((frame[2] & 255) << 8) | (frame[3] & 255);
                synchronized (this) { streams.computeIfAbsent(stream, s -> new ByteArrayOutputStream()).write(frame, 4, frame.length - 4); }
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        log.accept("closed " + statusCode + " " + reason);
        finish(false);
        return null;
    }

    @Override public void onError(WebSocket webSocket, Throwable error) {
        fail(new IllegalStateException("Связь с Алисой оборвалась: " + rootMessage(error)));
    }

    private synchronized void handle(JsonObject root) {
        if (root.has("streamcontrol")) {
            JsonObject control = root.getAsJsonObject("streamcontrol");
            int stream = control.has("streamId") ? control.get("streamId").getAsInt() : -1;
            log.accept("streamcontrol " + control);
            if (stream != VOICE_STREAM || pcm16k == null) closedStreams.add(stream);
            maybeFinish();
            return;
        }
        JsonObject directive = root.has("directive") ? root.getAsJsonObject("directive") : null;
        if (directive == null) { log.accept("frame " + cut(root.toString(), 300)); return; }
        JsonObject header = directive.has("header") ? directive.getAsJsonObject("header") : new JsonObject();
        String namespace = str(header, "namespace"), name = str(header, "name");
        JsonObject payload = directive.has("payload") && directive.get("payload").isJsonObject() ? directive.getAsJsonObject("payload") : new JsonObject();
        if ("System".equals(namespace) && "Ping".equals(name)) {
            JsonObject pong = event("System", "Pong", new JsonObject(), null);
            pong.getAsJsonObject("event").getAsJsonObject("header").addProperty("refMessageId", str(header, "messageId"));
            send(pong);
            return;
        }
        log.accept(namespace + "." + name + " " + cut(payload.toString(), 600));
        String lower = (namespace + "." + name + " " + payload).toLowerCase();
        if (lower.contains("music") || lower.contains("audio_play") || lower.contains("audioplayer")) notes.add("music");
        switch (namespace + "." + name) {
            case "System.SynchronizeStateResponse" -> ask();
            case "System.InvalidAuth" -> fail(new InvalidAuth());
            case "System.EventException" -> fail(new IllegalStateException("Ошибка Алисы: " + cut(payload.toString(), 200)));
            case "ASR.Result" -> heard = recognized(payload);
            case "TTS.Speak" -> { speakSeen = true; if (!str(payload, "format").isBlank()) voiceFormat = str(payload, "format"); }
            case "Vins.VinsResponse" -> onVinsResponse(payload);
            default -> { }
        }
        maybeFinish();
    }

    private void onVinsResponse(JsonObject payload) {
        JsonObject response = obj(payload, "response");
        if (response != null && response.has("directives") && response.get("directives").isJsonArray())
            for (JsonElement d : response.getAsJsonArray("directives"))
                if (d.isJsonObject()) {
                    log.accept("directive " + str(d.getAsJsonObject(), "name") + " " + cut(shape(d.getAsJsonObject()), 1500));
                    JsonObject p = obj(d.getAsJsonObject(), "payload");
                    String uri = p == null ? "" : str(p, "uri");
                    if (uri.startsWith("musicsdk://")) { musicUri = uri; notes.add("music"); }
                }
        String answer = extractText(payload);
        if (!answer.isBlank() && !PLACEHOLDER.matcher(answer.trim()).matches()) {
            finalText = answer;
            // Alice's voice for the answer arrives after the text; give it a few seconds, then play what came.
            TIMERS.schedule(() -> finish(false), speakSeen ? 8 : 4, TimeUnit.SECONDS);
            return;
        }
        boolean placeholder = !answer.isBlank();
        if (placeholder) placeholderSeen = true;
        if (!placeholderSeen) {
            // An answer with no words (a player command the web chat cannot carry out): asking for "the next part"
            // only brings more empty answers, so end the dialogue with what came.
            TIMERS.schedule(() -> { if (finalText.isBlank()) finish(false); }, speakSeen ? 8 : 2, TimeUnit.SECONDS);
            return;
        }
        if (!placeholder && ++emptyAfterPlaceholder > 3) { finish(false); return; }
        if (followUps++ < 20) {
            // Long answers come in steps; the site asks for the next one with this server action.
            JsonObject action = new JsonObject();
            action.addProperty("type", "server_action");
            action.addProperty("name", "@@mm_stack_engine_get_next");
            JsonObject args = new JsonObject();
            args.add("@recovery_params", new JsonObject());
            args.addProperty("@request_id", lastRequestId);
            args.addProperty("stack_session_id", stackSessionId);
            args.addProperty("@scenario_name", "Dialogovo");
            args.addProperty("stack_product_scenario_name", "dialogovo");
            action.add("payload", args);
            TIMERS.schedule(() -> sendVins("TextInput", action, false), 50, TimeUnit.MILLISECONDS);
        }
    }

    private void ask() {
        if (pcm16k == null) {
            JsonObject input = new JsonObject();
            input.addProperty("type", "text_input");
            input.addProperty("text", text);
            sendVins("TextInput", input, true);
            return;
        }
        JsonObject input = new JsonObject();
        input.addProperty("type", "voice_input");
        sendVins("VoiceInput", input, true);
        // Microphone audio as raw 16 kHz PCM in ~100 ms frames, then close the stream like the site does.
        for (int offset = 0; offset < pcm16k.length; offset += 3_200) {
            int length = Math.min(3_200, pcm16k.length - offset);
            ByteBuffer frame = ByteBuffer.allocate(4 + length).putInt(VOICE_STREAM).put(pcm16k, offset, length).flip();
            synchronized (this) { sending = sending.thenCompose(ws -> ws.sendBinary(frame, true)); }
        }
        JsonObject control = new JsonObject();
        control.addProperty("streamId", VOICE_STREAM);
        control.addProperty("action", 0);
        control.addProperty("reason", 0);
        control.addProperty("messageId", lastVoiceMessageId);
        JsonObject root = new JsonObject();
        root.add("streamcontrol", control);
        send(root);
    }

    private String lastVoiceMessageId;

    private void sendVins(String name, JsonObject requestEvent, boolean first) {
        String requestId = UUID.randomUUID().toString();
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Moscow"));
        JsonObject application = new JsonObject();
        application.addProperty("app_id", appId);
        application.addProperty("app_version", appVersion);
        application.addProperty("platform", platform);
        application.addProperty("os_version", "windows");
        application.addProperty("uuid", uuid);
        application.addProperty("lang", "ru");
        application.addProperty("client_time", now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")));
        application.addProperty("timezone", "Europe/Moscow");
        application.addProperty("timestamp", String.valueOf(now.toEpochSecond()));
        JsonObject header = new JsonObject();
        header.addProperty("prev_req_id", first ? null : lastRequestId);
        header.addProperty("request_id", requestId);
        header.addProperty("dialog_type", 1);
        JsonArray features = new JsonArray();
        for (String feature : new String[] {"open_link", "server_action", "cloud_ui", "print_text_in_message_view", "supports_print_text_in_message_view", "player_pause_directive", "audio_client", "music_sdk_client"})
            features.add(feature);
        JsonObject options = new JsonObject();
        options.add("supported_features", features);
        options.add("unsupported_features", new JsonArray());
        JsonObject request = new JsonObject();
        request.add("event", requestEvent);
        request.addProperty("voice_session", true);
        request.add("additional_options", options);
        JsonObject payload = new JsonObject();
        payload.add("application", application);
        payload.add("header", header);
        payload.add("request", request);
        payload.addProperty("format", "audio/ogg;codecs=opus");
        payload.addProperty("mime", "audio/webm;codecs=opus");
        payload.addProperty("topic", "desktopgeneral");
        if ("VoiceInput".equals(name)) {
            payload.addProperty("format", "audio/x-pcm;bit=16;rate=16000");
            payload.addProperty("mime", "audio/x-pcm;bit=16;rate=16000");
            payload.addProperty("tts_format", "audio/ogg;codecs=opus");
        }
        synchronized (this) {
            lastRequestId = requestId;
            if (first) stackSessionId = requestId;
        }
        JsonObject event = event("Vins", name, payload, "VoiceInput".equals(name) ? VOICE_STREAM : null);
        if ("VoiceInput".equals(name)) lastVoiceMessageId = event.getAsJsonObject("event").getAsJsonObject("header").get("messageId").getAsString();
        send(event);
    }

    private JsonObject event(String namespace, String name, JsonObject payload, Integer streamId) {
        JsonObject header = new JsonObject();
        header.addProperty("namespace", namespace);
        header.addProperty("name", name);
        header.addProperty("messageId", UUID.randomUUID().toString());
        synchronized (this) { header.addProperty("seqNumber", seq++); }
        if (streamId != null) header.addProperty("streamId", streamId);
        JsonObject event = new JsonObject();
        event.add("header", header);
        event.add("payload", payload);
        JsonObject root = new JsonObject();
        root.add("event", event);
        return root;
    }

    private synchronized void send(JsonObject message) {
        String json = message.toString();
        sending = sending.thenCompose(ws -> ws.sendText(json, true));
        sending.exceptionally(failure -> { fail(new IllegalStateException("Не отправилось Алисе: " + rootMessage(failure))); return null; });
    }

    private synchronized void maybeFinish() {
        if (finalText.isBlank()) return;
        // Done once every audio stream Alice opened has been closed (or she sent no voice at all).
        // Without TTS.Speak or before any audio, the timer scheduled on the final text decides.
        boolean audio = streams.keySet().stream().anyMatch(s -> !(pcm16k != null && s == VOICE_STREAM));
        boolean audioOpen = streams.keySet().stream().anyMatch(s -> !closedStreams.contains(s) && !(pcm16k != null && s == VOICE_STREAM));
        if (speakSeen && audio && !audioOpen) finish(false);
    }

    private void finish(boolean timeout) {
        Answer answer;
        synchronized (this) {
            if (result.isDone()) return;
            if (timeout && finalText.isBlank()) { fail(new IllegalStateException("Алиса не ответила за 40 секунд")); return; }
            List<byte[]> voice = new ArrayList<>();
            streams.forEach((stream, bytes) -> { if (!(pcm16k != null && stream == VOICE_STREAM) && bytes.size() > 0) voice.add(bytes.toByteArray()); });
            answer = new Answer(finalText, heard, voice, voiceFormat, List.copyOf(notes), musicUri);
        }
        result.complete(answer);
        close();
    }

    private void fail(Throwable failure) {
        if (result.completeExceptionally(failure)) close();
    }

    private void close() {
        WebSocket ws = socket;
        if (ws != null) {
            try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye"); } catch (RuntimeException ignored) { }
            ws.abort();
        }
    }

    static String extractText(JsonObject payload) {
        JsonObject response = obj(payload, "response");
        if (response == null) return "";
        if (response.has("directives") && response.get("directives").isJsonArray()) {
            for (JsonElement d : response.getAsJsonArray("directives")) {
                JsonObject p = d.isJsonObject() ? obj(d.getAsJsonObject(), "payload") : null;
                String t = p == null ? "" : str(p, "text");
                if (!t.isBlank()) return t;
            }
        }
        JsonObject card = obj(response, "card");
        if (card != null && !str(card, "text").isBlank()) return str(card, "text");
        if (response.has("cards") && response.get("cards").isJsonArray()) {
            for (JsonElement c : response.getAsJsonArray("cards")) {
                String t = c.isJsonObject() ? str(c.getAsJsonObject(), "text") : "";
                if (!t.isBlank()) return t;
            }
        }
        if (response.has("chat_dialog_update") && response.get("chat_dialog_update").isJsonArray()) {
            for (JsonElement u : response.getAsJsonArray("chat_dialog_update")) {
                JsonObject add = u.isJsonObject() ? obj(u.getAsJsonObject(), "add_message_request") : null;
                if (add == null || !add.has("messages")) continue;
                for (JsonElement m : add.getAsJsonArray("messages")) {
                    JsonObject content = m.isJsonObject() ? obj(m.getAsJsonObject(), "content") : null;
                    String t = content == null ? "" : str(content, "plain_response_text");
                    if (!t.isBlank()) return t;
                }
            }
        }
        return "";
    }

    private static String recognized(JsonObject payload) {
        if (!payload.has("recognition") || !payload.get("recognition").isJsonArray() || payload.getAsJsonArray("recognition").isEmpty()) return "";
        JsonElement first = payload.getAsJsonArray("recognition").get(0);
        if (!first.isJsonObject()) return "";
        String normalized = str(first.getAsJsonObject(), "normalized");
        if (!normalized.isBlank()) return normalized;
        StringBuilder words = new StringBuilder();
        if (first.getAsJsonObject().has("words"))
            for (JsonElement w : first.getAsJsonObject().getAsJsonArray("words"))
                if (w.isJsonObject()) words.append(words.isEmpty() ? "" : " ").append(str(w.getAsJsonObject(), "value"));
        return words.toString();
    }

    /** A directive's structure for the log: keys everywhere, values only for ids, types, names and titles (no URLs). */
    static String shape(JsonElement e) {
        if (e.isJsonObject()) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                if (out.length() > 1) out.append(',');
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                boolean show = value.isJsonPrimitive() && key.toLowerCase().matches(".*(id|type|name|title|kind)$") && !key.toLowerCase().matches(".*(token|session|uuid|device).*");
                if (value.isJsonPrimitive() && key.toLowerCase().matches("uri|url")) show = true;
                String text = show && key.toLowerCase().matches("uri|url") ? safeUri(value.getAsString()) : show ? value.getAsString() : "";
                out.append(key).append(show ? "=" + text : value.isJsonPrimitive() ? "" : ":" + shape(value));
            }
            return out.append('}').toString();
        }
        if (e.isJsonArray()) return e.getAsJsonArray().isEmpty() ? "[]" : "[" + e.getAsJsonArray().size() + "×" + shape(e.getAsJsonArray().get(0)) + "]";
        return "";
    }

    /** A URI for the log: scheme, host, path and query keys; query values only for ids, kinds and types. */
    static String safeUri(String uri) {
        int q = uri.indexOf('?');
        String base = q < 0 ? uri : uri.substring(0, q);
        if (q < 0) return base;
        StringBuilder out = new StringBuilder(base).append('?');
        for (String part : uri.substring(q + 1).split("&")) {
            String[] kv = part.split("=", 2);
            String k = java.net.URLDecoder.decode(kv[0], java.nio.charset.StandardCharsets.UTF_8);
            boolean show = kv.length > 1 && k.toLowerCase().matches(".*(id|kind|type|owner|from)$|track|album|artist|playlist") && !k.toLowerCase().matches(".*(token|session|uuid|device).*");
            out.append(k).append(show ? "=" + java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8) : "").append('&');
        }
        return out.toString();
    }

    private static JsonObject obj(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
    }

    private static String str(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonPrimitive() ? parent.get(key).getAsString() : "";
    }

    private static String cut(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "…"; }

    private static String rootMessage(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }
}
