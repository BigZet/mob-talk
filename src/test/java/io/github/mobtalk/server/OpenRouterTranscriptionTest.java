package io.github.mobtalk.server;

import static org.junit.jupiter.api.Assertions.*;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.OpenRouterTranscriber;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Путь server на сервере: бюджет до и после запроса, ошибка понятным текстом, новый клиент после смены модели (трек B). */
class OpenRouterTranscriptionTest {
    private static final UUID DIMA = UUID.randomUUID();

    /** Бюджет, который помнит списания и умеет отказать. */
    static final class Ledger implements PlayerBudget {
        final List<String> charges = new ArrayList<>();
        String refusal = "";
        @Override public Verdict allow(UUID player, Kind kind) { return refusal.isEmpty() ? Verdict.ok() : Verdict.no(refusal); }
        @Override public synchronized void charge(UUID player, Kind kind, double usd, int units) { charges.add(kind + " " + usd + " " + units); }
        @Override public String report() { return String.join("\n", charges); }
    }

    @Test void chargesProviderCostAndBilledSeconds() throws Exception {
        Ledger budget = new Ledger();
        MobTalkConfig config = new MobTalkConfig();
        List<String> languages = new ArrayList<>();
        OpenRouterTranscription stt = new OpenRouterTranscription(() -> config, c -> (audio, format, language) -> {
            languages.add(language);
            return new OpenRouterTranscriber.Result("здравствуй", c.sttModel, 7.2, 0.0008, 900);
        }, () -> budget, player -> "ru", Runnable::run);
        assertEquals("здравствуй", stt.transcribe(DIMA, new byte[100], "wav16k", 7, "").get());
        assertEquals(List.of("TRANSCRIPTION 8.0E-4 8"), budget.charges);
        assertEquals(List.of("ru"), languages);
    }

    @Test void budgetRefusalSkipsTheRequest() {
        Ledger budget = new Ledger();
        budget.refusal = "Лимит на сегодня исчерпан";
        AtomicInteger calls = new AtomicInteger();
        OpenRouterTranscription stt = new OpenRouterTranscription(MobTalkConfig::new, c -> (audio, format, language) -> {
            calls.incrementAndGet(); return new OpenRouterTranscriber.Result("", "", 0, 0, 0);
        }, () -> budget, player -> "ru", Runnable::run);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> stt.transcribe(DIMA, new byte[100], "wav16k", 3, "").get());
        assertEquals("Лимит на сегодня исчерпан", Transcriptions.reason(failure));
        assertEquals(0, calls.get());
        assertTrue(budget.charges.isEmpty());
    }

    @Test void providerErrorReachesThePlayerAndCostsNothing() {
        Ledger budget = new Ledger();
        OpenRouterTranscription stt = new OpenRouterTranscription(MobTalkConfig::new, c -> (audio, format, language) -> {
            throw new IOException("OpenRouter STT: закончились кредиты (HTTP 402).");
        }, () -> budget, player -> "ru", Runnable::run);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> stt.transcribe(DIMA, new byte[100], "wav16k", 3, "").get());
        assertTrue(Transcriptions.reason(failure).contains("кредиты"));
        assertTrue(budget.charges.isEmpty());
    }

    @Test void newModelMakesNewClient() throws Exception {
        MobTalkConfig config = new MobTalkConfig();
        AtomicInteger created = new AtomicInteger();
        OpenRouterTranscription stt = new OpenRouterTranscription(() -> config, c -> {
            created.incrementAndGet();
            String model = c.sttModel;
            return (audio, format, language) -> new OpenRouterTranscriber.Result(model, model, 1, 0, 1);
        }, Ledger::new, player -> "ru", Runnable::run);
        assertEquals("mistralai/voxtral-mini-transcribe-3-0", stt.transcribe(DIMA, new byte[100], "wav16k", 1, "").get());
        stt.transcribe(DIMA, new byte[100], "wav16k", 1, "").get();
        assertEquals(1, created.get());
        config.sttModel = "openai/whisper-large-v3";
        assertEquals("openai/whisper-large-v3", stt.transcribe(DIMA, new byte[100], "wav16k", 1, "").get());
        assertEquals(2, created.get());
    }
}
