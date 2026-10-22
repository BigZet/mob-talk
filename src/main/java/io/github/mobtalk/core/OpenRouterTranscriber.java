package io.github.mobtalk.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

/** Распознавание речи через OpenRouter ({@code POST /api/v1/audio/transcriptions}) для пути {@code server}: запрос и ответ
 * найдены пробой в разведке F (docs/server-research.md, раздел 2). Тело — JSON с base64-аудио, ответ — {@code text} и
 * {@code usage.seconds/cost}; оплата за целые секунды. Своё короткое ожидание ({@code sttTimeoutSeconds}) и одна повторная
 * попытка на запасной модели: у voxtral бывают выбросы по 25 с. Блокирующий: вызывать с рабочего потока. */
public final class OpenRouterTranscriber implements AutoCloseable {
    static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/audio/transcriptions");
    static final String SERVICE = "OpenRouter STT";
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private final String key;
    private final URI endpoint;
    private final BoundedHttp http;
    private final String model, fallbackModel;

    /** {@code seconds} и {@code usd} — из {@code usage} ответа (0, если провайдер их не прислал). */
    public record Result(String text, String model, double seconds, double usd, long millis) { }

    /** Ключ OpenRouter сервера: окружение {@code OPENROUTER_API_KEY}, DPAPI или файл в папке настроек. */
    public static OpenRouterTranscriber open(MobTalkConfig config, Path configDirectory) throws IOException {
        return new OpenRouterTranscriber(OpenRouterToken.load(configDirectory, OpenRouterToken.Provider.OPENROUTER), config, ENDPOINT);
    }

    OpenRouterTranscriber(String key, MobTalkConfig config, URI endpoint) {
        this.key = key == null ? null : OpenRouterConversationClient.validKey(key);
        this.endpoint = endpoint;
        MobTalkConfig own = config.copy();
        own.requestTimeoutSeconds = Math.max(1, config.sttTimeoutSeconds);
        own.firstTokenSeconds = 0;
        this.http = new BoundedHttp(own);
        this.model = config.sttModel;
        String fallback = config.sttFallbackModel == null ? "" : config.sttFallbackModel.strip();
        this.fallbackModel = fallback.equals(model) ? "" : fallback;
    }

    /** {@code format}: {@code wav16k}/{@code wav}, {@code mp3} или {@code ogg}; {@code language} — код ISO ({@code ru}), пусто — модель
     * определит сама. Пустая строка — речи в записи нет. */
    public Result transcribe(byte[] audio, String format, String language) throws IOException, InterruptedException {
        if (audio == null || audio.length == 0) throw new IOException("Запись пустая.");
        String provided = providerFormat(format);
        try { return once(model, audio, provided, language); }
        catch (IOException failure) {
            if (fallbackModel.isEmpty() || !worthFallback(failure)) throw failure;
            return once(fallbackModel, audio, provided, language);
        }
    }

    /** Ключ, деньги и неверный запрос запасная модель не исправит; таймаут, обрыв, 429 и 5xx — может. */
    static boolean worthFallback(IOException failure) {
        if (!(failure instanceof BoundedHttp.SafeFailure safe) || safe.status <= 0) return true;
        return safe.status != 401 && safe.status != 402 && safe.status != 403;
    }

    private Result once(String model, byte[] audio, String format, String language) throws IOException, InterruptedException {
        if (key == null) throw new IOException("На сервере нет ключа OpenRouter для распознавания.");
        long started = System.nanoTime();
        byte[] body = http.send(request(model, audio, format, language), MAX_RESPONSE_BYTES, SERVICE);
        return parse(body, model, (System.nanoTime() - started) / 1_000_000);
    }

    static String providerFormat(String format) throws IOException {
        String value = format == null ? "" : format.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "wav16k", "wav" -> "wav";
            case "mp3" -> "mp3";
            case "ogg", "opus" -> "ogg";
            default -> throw new IOException("Неизвестный формат записи: " + value);
        };
    }

    static String body(String model, byte[] audio, String format, String language) {
        JsonObject input = new JsonObject();
        input.addProperty("data", Base64.getEncoder().encodeToString(audio));
        input.addProperty("format", format);
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("input_audio", input);
        if (language != null && !language.isBlank() && !"auto".equalsIgnoreCase(language.strip())) body.addProperty("language", language.strip());
        return body.toString();
    }

    private HttpRequest request(String model, byte[] audio, String format, String language) {
        return HttpRequest.newBuilder(endpoint).timeout(http.timeout())
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(model, audio, format, language), StandardCharsets.UTF_8)).build();
    }

    static Result parse(byte[] body, String model, long millis) throws IOException {
        JsonObject json;
        try { json = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException malformed) { throw new IOException(SERVICE + ": непонятный ответ."); }
        JsonElement text = json.get("text");
        if (text == null || !text.isJsonPrimitive()) throw new IOException(SERVICE + ": в ответе нет текста.");
        double seconds = 0, usd = 0;
        if (json.get("usage") instanceof JsonObject usage) {
            seconds = number(usage, "seconds");
            usd = number(usage, "cost");
        }
        return new Result(TextBounds.characters(text.getAsString().strip(), 2000), model, seconds, usd, millis);
    }

    private static double number(JsonObject object, String name) {
        try { JsonElement value = object.get(name); return value != null && value.isJsonPrimitive() ? Math.max(0, value.getAsDouble()) : 0; }
        catch (RuntimeException notANumber) { return 0; }
    }

    @Override public void close() { http.close(); }
}
