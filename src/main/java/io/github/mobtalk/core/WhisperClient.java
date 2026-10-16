package io.github.mobtalk.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

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
        if (wav == null || wav.length < 44 || wav.length > MAX_WAV_BYTES)
            throw new IOException("Запись пуста или превышает допустимый размер.");
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(http.timeout()).header("Content-Type", "audio/wav")
                .POST(HttpRequest.BodyPublishers.ofByteArray(wav)).build();
        byte[] body = http.send(request, 64 * 1024, "Whisper");
        try {
            JsonElement text = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject().get("text");
            if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
            return TextBounds.characters(text.getAsString(), 2000);
        } catch (RuntimeException malformed) {
            throw new IOException("Whisper вернул неверный ответ. Проверьте локальный помощник.");
        }
    }

    @Override public void close() { http.close(); }
}
