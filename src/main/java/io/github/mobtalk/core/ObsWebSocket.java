package io.github.mobtalk.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** Связь с OBS Studio по obs-websocket 5 (метки для монтажа, идея З4): узнаёт, идёт ли эфир или запись и сколько они длятся,
 * ставит главу в запись (OBS 30.2+, Hybrid MP4) и сохраняет буфер повтора. Только локальный адрес игрока; пароль — из
 * {@link OpenRouterToken.Provider#OBS}. События приходят на потоке HttpClient: получатель сам переносит их на свой поток. */
public final class ObsWebSocket implements WebSocket.Listener {
    /** Outputs: StreamStateChanged, RecordStateChanged, ReplayBufferSaved. */
    static final int OUTPUT_EVENTS = 1 << 6;

    public interface Events {
        /** {@code output} — stream или record; {@code elapsedMillis} — сколько уже идёт (0 у только что начавшегося). */
        void state(String output, boolean active, long elapsedMillis);
        void replaySaved(String path);
        /** Подключились (ok) или отвалились / не пустили, с понятной причиной. */
        void status(boolean ok, String text);
    }

    private final Events events;
    private final String password;
    private final StringBuilder partial = new StringBuilder();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile WebSocket socket;
    private volatile boolean identified, closed;

    private ObsWebSocket(Events events, String password) { this.events = events; this.password = password == null ? "" : password; }

    /** {@code address} — host:port, по умолчанию 127.0.0.1:4455. */
    public static CompletableFuture<ObsWebSocket> connect(String address, String password, Events events) {
        ObsWebSocket obs = new ObsWebSocket(events, password);
        URI uri;
        try { uri = uri(address); } catch (IllegalArgumentException invalid) { return CompletableFuture.failedFuture(invalid); }
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build().newWebSocketBuilder()
                .subprotocols("obswebsocket.json").buildAsync(uri, obs)
                .thenApply(socket -> { obs.socket = socket; return obs; });
    }

    static URI uri(String address) {
        String value = address == null || address.isBlank() ? "127.0.0.1:4455" : address.strip();
        if (!value.contains("://")) value = "ws://" + value;
        URI uri = URI.create(value);
        if (!"ws".equals(uri.getScheme()) && !"wss".equals(uri.getScheme())) throw new IllegalArgumentException("Адрес OBS: ws://хост:порт");
        if (uri.getHost() == null) throw new IllegalArgumentException("Адрес OBS: хост:порт, например 127.0.0.1:4455");
        return uri;
    }

    public boolean ready() { return identified && !closed; }

    public void close() {
        closed = true; identified = false;
        WebSocket current = socket;
        if (current != null) current.sendClose(WebSocket.NORMAL_CLOSURE, "bye").exceptionally(ignored -> null);
    }

    /** Спросить состояние эфира и записи: ответы придут в {@link Events#state}. */
    public void refresh() { request("GetStreamStatus", null); request("GetRecordStatus", null); }
    /** Глава в идущую запись; OBS без Hybrid MP4 ответит ошибкой — молча пропускаем. */
    public void chapter(String name) { request("CreateRecordChapter", Json.obj("chapterName", name)); }
    /** Сохранить буфер повтора (если он запущен в OBS); путь придёт в {@link Events#replaySaved}. */
    public void saveReplay() { request("SaveReplayBuffer", null); }

    private void request(String type, JsonObject data) {
        if (!ready()) return;
        JsonObject d = Json.obj("requestType", type, "requestId", type + "-" + requests.incrementAndGet());
        if (data != null) d.add("requestData", data);
        send(Json.obj("op", 6, "d", d));
    }

    private synchronized void send(JsonObject message) {
        WebSocket current = socket;
        if (current != null && !closed) current.sendText(message.toString(), true).exceptionally(ignored -> null);
    }

    // ---- приём ----

    @Override public void onOpen(WebSocket webSocket) { socket = webSocket; webSocket.request(1); }

    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String text = partial.toString();
            partial.setLength(0);
            try { handle(JsonParser.parseString(text).getAsJsonObject()); }
            catch (RuntimeException broken) { events.status(false, "OBS прислал непонятное: " + broken.getMessage()); }
        }
        webSocket.request(1);
        return null;
    }

    void handle(JsonObject message) {
        int op = (int) Json.number(message, "op", -1);
        JsonObject d = message.has("d") && message.get("d").isJsonObject() ? message.getAsJsonObject("d") : new JsonObject();
        switch (op) {
            case 0 -> { // Hello
                JsonObject identify = Json.obj("rpcVersion", 1, "eventSubscriptions", OUTPUT_EVENTS);
                if (d.has("authentication") && d.get("authentication").isJsonObject()) {
                    JsonObject auth = d.getAsJsonObject("authentication");
                    identify.addProperty("authentication", authentication(password, Json.string(auth, "salt"), Json.string(auth, "challenge")));
                }
                send(Json.obj("op", 1, "d", identify));
            }
            case 2 -> { identified = true; events.status(true, "OBS подключён"); refresh(); }
            case 5 -> event(Json.string(d, "eventType"), d.has("eventData") && d.get("eventData").isJsonObject() ? d.getAsJsonObject("eventData") : new JsonObject());
            case 7 -> response(d);
            default -> { }
        }
    }

    private void event(String type, JsonObject data) {
        if (type == null) return;
        switch (type) {
            case "StreamStateChanged", "RecordStateChanged" -> {
                String state = String.valueOf(Json.string(data, "outputState"));
                // STARTING/STOPPING — переходные: ждём STARTED/STOPPED.
                if (!state.endsWith("_STARTED") && !state.endsWith("_STOPPED")) return;
                events.state(type.startsWith("Stream") ? "stream" : "record", data.has("outputActive") && data.get("outputActive").getAsBoolean(), 0);
            }
            case "ReplayBufferSaved" -> { String path = Json.string(data, "savedReplayPath"); if (path != null) events.replaySaved(path); }
            default -> { }
        }
    }

    private void response(JsonObject d) {
        String type = Json.string(d, "requestType");
        JsonObject status = d.has("requestStatus") && d.get("requestStatus").isJsonObject() ? d.getAsJsonObject("requestStatus") : new JsonObject();
        if (!status.has("result") || !status.get("result").getAsBoolean()) return;
        JsonObject data = d.has("responseData") && d.get("responseData").isJsonObject() ? d.getAsJsonObject("responseData") : new JsonObject();
        if ("GetStreamStatus".equals(type) || "GetRecordStatus".equals(type)) {
            boolean active = data.has("outputActive") && data.get("outputActive").getAsBoolean();
            events.state("GetStreamStatus".equals(type) ? "stream" : "record", active, active ? Json.number(data, "outputDuration", 0) : 0);
        }
    }

    @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        boolean was = identified;
        identified = false;
        if (!closed) events.status(false, statusCode == 4009 ? "OBS не принял пароль" : was ? "OBS отключился" : "OBS закрыл связь: " + statusCode + (reason == null || reason.isBlank() ? "" : " " + reason));
        closed = true;
        return null;
    }

    @Override public void onError(WebSocket webSocket, Throwable error) {
        identified = false;
        if (!closed) events.status(false, "Связь с OBS оборвалась: " + error.getMessage());
        closed = true;
    }

    /** obs-websocket 5: base64(sha256(base64(sha256(пароль + соль)) + вызов)). */
    static String authentication(String password, String salt, String challenge) {
        String secret = base64Sha256((password == null ? "" : password) + (salt == null ? "" : salt));
        return base64Sha256(secret + (challenge == null ? "" : challenge));
    }

    private static String base64Sha256(String text) {
        try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Small JSON helpers without the server web package. */
    static final class Json {
        static JsonObject obj(Object... pairs) {
            JsonObject out = new JsonObject();
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                Object value = pairs[i + 1];
                String key = String.valueOf(pairs[i]);
                if (value instanceof JsonObject json) out.add(key, json);
                else if (value instanceof Number number) out.addProperty(key, number);
                else if (value instanceof Boolean flag) out.addProperty(key, flag);
                else if (value != null) out.addProperty(key, String.valueOf(value));
            }
            return out;
        }
        static String string(JsonObject object, String key) {
            return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
        }
        static long number(JsonObject object, String key, long fallback) {
            try { return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsLong() : fallback; }
            catch (NumberFormatException notNumber) { return fallback; }
        }
    }
}
