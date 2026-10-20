package io.github.divineblock.core;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** The god's voice: OpenRouter text to speech (ElevenLabs, a deep Russian voice by default), 16-bit mono PCM at 24 kHz.
 * Never on the game thread; any failure leaves the god with subtitles only. */
public final class GodVoice {
    static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/audio/speech");
    public static final int SAMPLE_RATE = 24_000;
    private static final int MAX_AUDIO_BYTES = 2 * 1024 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NEVER).build();
    /** Voices the settings offer: the ElevenLabs id and a name for the menu. */
    public static final String[][] VOICES = {
            { "cPoqAvGWCPfCfyPMwe4z", "древний и грозный" },
            { "pM78bgjPVk0JXtaEnFoj", "суровый воевода" },
            { "bwCXcoVxWNYMlC6Esa8u", "глубокий и таинственный" } };

    private final DivineConfig config;
    private final Path configDirectory;

    public GodVoice(DivineConfig config, Path configDirectory) { this.config = config.copy(); this.configDirectory = configDirectory; }

    public static boolean validVoiceId(String id) { return id != null && id.matches("[A-Za-z0-9]{20,64}"); }

    /** The whole line at once (the preview, tests). */
    public byte[] synthesize(String line) throws IOException, InterruptedException {
        try (java.io.InputStream stream = stream(line)) {
            byte[] pcm = stream.readNBytes(MAX_AUDIO_BYTES + 1);
            if (pcm.length < 2 || pcm.length > MAX_AUDIO_BYTES) throw new IOException("Озвучка вернула неверный звук.");
            return (pcm.length & 1) == 0 ? pcm : java.util.Arrays.copyOf(pcm, pcm.length - 1);
        }
    }

    /** The line as it is synthesised: returns once the response starts, and the PCM keeps flowing while the god already
     * speaks. Read it on a worker and close it to stop the download. */
    public java.io.InputStream stream(String line) throws IOException, InterruptedException {
        if (line == null || line.isBlank() || line.length() > 600) throw new IOException("Нечего озвучивать.");
        if (!validVoiceId(config.voiceId)) throw new IOException("Голос бога задан неверно.");
        String key = ApiKeys.load(configDirectory, ApiKeys.Provider.OPENROUTER);
        JsonObject body = new JsonObject();
        body.addProperty("model", config.ttsModel);
        body.addProperty("input", line);
        body.addProperty("voice", config.voiceId);
        body.addProperty("response_format", "pcm");
        if (config.ttsModel.startsWith("elevenlabs/")) {
            JsonObject elevenlabs = new JsonObject(); elevenlabs.addProperty("output_format", "pcm_24000");
            JsonObject options = new JsonObject(); options.add("elevenlabs", elevenlabs);
            JsonObject provider = new JsonObject(); provider.add("options", options);
            body.add("provider", provider);
        }
        HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(40)).header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json").header("Accept", "audio/pcm")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
        HttpResponse<java.io.InputStream> response;
        try { response = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).get(20, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException slow) { throw new IOException("Озвучка не началась вовремя."); }
        catch (java.util.concurrent.ExecutionException failure) { throw new IOException("Озвучка недоступна."); }
        if (response.statusCode() != 200) { response.body().close(); throw new IOException("Озвучка: ошибка HTTP " + response.statusCode() + "."); }
        return response.body();
    }
}
