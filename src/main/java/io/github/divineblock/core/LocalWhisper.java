package io.github.divineblock.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Speech to text through the local Whisper helper (the same one Mob Talk runs, loopback only). Never on the game thread. */
public final class LocalWhisper {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final int MAX_WAV_BYTES = 44 + PcmWav.MAX_PCM_BYTES;
    private final URI endpoint;

    public LocalWhisper(String url) { endpoint = local(url).resolve("/transcribe"); }

    /** Only a loopback HTTP address: the player's voice never leaves the machine on the way to Whisper. */
    public static URI local(String url) {
        URI base;
        try { base = URI.create(url == null ? "" : url.strip()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Whisper должен работать на локальном HTTP адресе."); }
        if (!"http".equals(base.getScheme()) || !("127.0.0.1".equals(base.getHost()) || "localhost".equals(base.getHost()))
                || base.getPort() < 1 || base.getPort() > 65535 || base.getRawUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || !(base.getPath().isEmpty() || "/".equals(base.getPath())))
            throw new IllegalArgumentException("Whisper должен работать на локальном HTTP адресе.");
        return base;
    }

    public String transcribe(byte[] wav) throws IOException, InterruptedException {
        if (wav == null || wav.length < 44 || wav.length > MAX_WAV_BYTES) throw new IOException("Запись пуста или длиннее 15 секунд.");
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60)).header("Content-Type", "audio/wav")
                .header("X-MobTalk-Context", Base64.getUrlEncoder().withoutPadding().encodeToString("Молитва богу у алтаря".getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofByteArray(wav)).build();
        HttpResponse<byte[]> response;
        try { response = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).get(60, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException slow) { throw new IOException("Whisper не ответил вовремя."); }
        catch (java.util.concurrent.ExecutionException failure) { throw new IOException("Whisper недоступен."); }
        if (response.statusCode() != 200 || response.body().length > 64 * 1024) throw new IOException("Whisper: ошибка HTTP " + response.statusCode() + ".");
        try {
            JsonElement text = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject().get("text");
            String value = text.getAsString().strip();
            return value.length() > 600 ? value.substring(0, 600) : value;
        } catch (RuntimeException malformed) { throw new IOException("Whisper вернул неверный ответ."); }
    }
}
