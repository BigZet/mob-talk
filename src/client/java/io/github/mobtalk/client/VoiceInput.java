package io.github.mobtalk.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.SttRoute;
import io.github.mobtalk.core.WhisperHelper;
import io.github.mobtalk.net.MobTalkNet;
import io.github.mobtalk.net.ScreenDataS2C;
import io.github.mobtalk.net.TalkAudioC2S;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import java.util.function.Consumer;

/** Куда идёт запись игрока (трек B, раздел 3 плана): личный {@code sttPath} ∩ пути из {@code ServerFeatures} ∩ установлен ли
 * Whisper ({@link SttRoute}). {@code local} — как раньше, {@code WhisperProcess} → {@code TalkTextC2S(fromVoice=true)};
 * {@code server} — WAV 16 кГц одним пакетом {@link TalkAudioC2S} (15 с = 480 КБ, предел 900 КБ); {@code text} — окно ввода. */
public final class VoiceInput {
    private VoiceInput() { }
    /** Проверка микрофона: {@code mobId} такого пакета — сервер вернёт текст в {@code ScreenDataS2C("stt-test")}, а не в диалог. */
    public static final int TEST_MOB = -1;
    private static volatile Boolean installed;
    private static volatile Consumer<TestResult> testListener;

    public record TestResult(String text, String error, long millis, String model) { }

    /** Путь этой записи и подсказка, если выбранный недоступен. Не трогает диск после первого вызова. */
    public static SttRoute.Choice route(MobTalkConfig config) {
        return SttRoute.choose(config.sttPath, ClientNet.features().sttPaths(), localReady());
    }

    /** Установлен ли helper; проверка файлов один раз за игру. */
    static boolean localReady() {
        Boolean known = installed;
        if (known == null) installed = known = WhisperHelper.installed();
        return known;
    }

    /** Отправить запись серверу; false, если сервер её не примет (нет канала или запись больше предела пакета). */
    public static boolean sendAudio(int mobId, byte[] wav) {
        if (wav == null || wav.length <= 44 || wav.length > MobTalkNet.MAX_AUDIO || !ClientPlayNetworking.canSend(TalkAudioC2S.ID)) return false;
        int seconds = (int) Math.max(1, Math.ceil((wav.length - 44) / 32_000.0));
        ClientPlayNetworking.send(new TalkAudioC2S(mobId, "wav16k", seconds, wav));
        return true;
    }

    /** Экран проверки микрофона ждёт ответа сервера; null — перестал ждать. */
    static void onTestResult(Consumer<TestResult> listener) { testListener = listener; }

    /** Из приёмника {@code ScreenDataS2C}: true, если это ответ проверки микрофона (дальше пакет не нужен). */
    static boolean screenData(ScreenDataS2C payload) {
        if (!"stt-test".equals(payload.screen())) return false;
        Consumer<TestResult> listener = testListener;
        if (listener == null) return true;
        try {
            JsonObject json = JsonParser.parseString(payload.json()).getAsJsonObject();
            listener.accept(new TestResult(text(json, "text"), text(json, "error"), json.has("millis") ? json.get("millis").getAsLong() : 0, text(json, "model")));
        } catch (RuntimeException malformed) {
            listener.accept(new TestResult("", "Сервер прислал непонятный ответ", 0, "server"));
        }
        return true;
    }

    private static String text(JsonObject json, String name) {
        return json.has(name) && json.get(name).isJsonPrimitive() ? json.get(name).getAsString() : "";
    }
}
