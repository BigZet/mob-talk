package io.github.mobtalk.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Blocking local STT; call from the microphone/conversation worker only. */
public final class WhisperClient implements AutoCloseable {
    private static final int MAX_WAV_BYTES = 2 * 1024 * 1024;
    private final URI endpoint;
    private final BoundedHttp http;

    public WhisperClient(MobTalkConfig config) {
        URI base;
        try { base = URI.create(config.whisperUrl); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Whisper должен работать на локальном HTTP адресе."); }
        if (!"http".equals(base.getScheme()) || !("127.0.0.1".equals(base.getHost()) || "localhost".equals(base.getHost()))
                || base.getPort() < 1 || base.getPort() > 65535 || base.getRawUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null || !(base.getPath().isEmpty() || "/".equals(base.getPath())))
            throw new IllegalArgumentException("Whisper должен работать на локальном HTTP адресе.");
        endpoint = base.resolve("/transcribe");
        http = new BoundedHttp(config);
    }

    public String transcribe(byte[] wav) throws IOException, InterruptedException {
        return transcribeDetailed(wav, "").text();
    }

    public String transcribe(byte[] wav, String context) throws IOException, InterruptedException {
        return transcribeDetailed(wav, context).text();
    }

    public Result transcribeDetailed(byte[] wav, String context) throws IOException, InterruptedException {
        if (wav == null || wav.length < 44 || wav.length > MAX_WAV_BYTES)
            throw new IOException("Запись пуста или превышает допустимый размер.");
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(http.timeout()).header("Content-Type", "audio/wav")
                .header("X-MobTalk-Context", Base64.getUrlEncoder().withoutPadding().encodeToString(
                        TextBounds.characters(context, 500).getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofByteArray(wav)).build();
        byte[] body = http.send(request, 64 * 1024, "Whisper");
        try {
            var json = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement text = json.get("text");
            if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
            return new Result(TextBounds.characters(text.getAsString(), 2000),
                    json.has("model") ? json.get("model").getAsString() : "unknown",
                    json.has("device") ? json.get("device").getAsString() : "unknown",
                    json.has("elapsedSeconds") ? json.get("elapsedSeconds").getAsDouble() : -1);
        } catch (RuntimeException malformed) {
            throw new IOException("Whisper вернул неверный ответ. Проверьте локальный помощник.");
        }
    }

    public record Result(String text, String model, String device, double elapsedSeconds) { }

    @Override public void close() { http.close(); }
}
