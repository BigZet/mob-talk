package io.github.mobtalk.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Живая проба OpenRouter STT (трек B): три фразы голоса Дмитрия из набора дообучения Whisper ({@code %LOCALAPPDATA%\MobTalk\train\
 * dataset\val.jsonl}, там же, где брала разведка F) распознаются моделью по умолчанию, в ответе есть ключевые слова эталона.
 * Только с {@code MOBTALK_LIVE=1}; ключ — {@code OPENROUTER_API_KEY} или папка {@code MOBTALK_CONFIG_DIR}. Стоит меньше цента. */
class TranscriptionLiveProbeTest {
    @Test void threePhrasesWithTheDefaultModel() throws Exception {
        assumeTrue("1".equals(System.getenv("MOBTALK_LIVE")), "live probes run only with MOBTALK_LIVE=1");
        Path dataset = Path.of(System.getenv().getOrDefault("LOCALAPPDATA", "."), "MobTalk", "train", "dataset");
        assumeTrue(Files.isRegularFile(dataset.resolve("val.jsonl")), "no val.jsonl");
        String key = OpenRouterToken.load(Path.of(System.getenv().getOrDefault("MOBTALK_CONFIG_DIR", "config-not-here")));
        MobTalkConfig config = new MobTalkConfig();
        List<String> lines = Files.readAllLines(dataset.resolve("val.jsonl")).stream().filter(line -> !line.isBlank()).limit(3).toList();
        double cost = 0;
        try (OpenRouterTranscriber stt = new OpenRouterTranscriber(key, config, OpenRouterTranscriber.ENDPOINT)) {
            for (String line : lines) {
                JsonObject phrase = JsonParser.parseString(line).getAsJsonObject();
                byte[] wav = Files.readAllBytes(dataset.getParent().resolve(phrase.get("path").getAsString()));
                OpenRouterTranscriber.Result result = stt.transcribe(wav, "wav16k", "ru");
                cost += result.usd();
                String expected = phrase.get("text").getAsString();
                System.out.printf(Locale.ROOT, "%s %d мс $%.5f%n  эталон: %s%n  модель: %s%n", result.model(), result.millis(), result.usd(), expected, result.text());
                long matched = words(expected).stream().filter(words(result.text())::contains).count();
                assertTrue(matched * 2 >= words(expected).size(), "меньше половины слов: " + result.text());
            }
        }
        System.out.printf(Locale.ROOT, "Итого $%.5f%n", cost);
    }

    private static List<String> words(String text) {
        return List.of(text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N} ]", " ").trim().split("\\s+"));
    }
}
