package io.github.mobtalk.server;

import static org.junit.jupiter.api.Assertions.*;

import io.github.mobtalk.core.MobTalkConfig;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Путь server-local: Whisper самого сервера, при его сбое — OpenRouter, если он разрешён; пути, которые видит игрок (трек B). */
class ServerLocalWhisperTest {
    private static final UUID DIMA = UUID.randomUUID();

    private static TranscriptionService answering(String text, AtomicInteger calls) {
        return (player, audio, format, seconds, hints) -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(text); };
    }

    @Test void localWhisperRecognisesAndChargesOnlySeconds() throws Exception {
        MobTalkConfig config = new MobTalkConfig();
        config.sttServerLocal = true;
        OpenRouterTranscriptionTest.Ledger budget = new OpenRouterTranscriptionTest.Ledger();
        ServerWhisperTranscription local = new ServerWhisperTranscription(() -> config, (c, wav, hints) -> " крипер ", () -> budget);
        AtomicInteger remote = new AtomicInteger();
        Transcriptions router = new Transcriptions(() -> config, local, answering("облако", remote));
        assertEquals("крипер", router.transcribe(DIMA, new byte[100], "wav16k", 4, "").get());
        assertEquals(0, remote.get());
        assertEquals(List.of("TRANSCRIPTION 0.0 4"), budget.charges);
        local.close();
    }

    @Test void unavailableHelperFallsBackToOpenRouter() throws Exception {
        MobTalkConfig config = new MobTalkConfig();
        config.sttServerLocal = true;
        ServerWhisperTranscription local = new ServerWhisperTranscription(() -> config, (c, wav, hints) -> {
            throw new IOException("Whisper ещё не установлен");
        }, OpenRouterTranscriptionTest.Ledger::new);
        AtomicInteger remote = new AtomicInteger();
        Transcriptions router = new Transcriptions(() -> config, local, answering("облако", remote));
        assertEquals("облако", router.transcribe(DIMA, new byte[100], "wav16k", 4, "").get());
        assertEquals(1, remote.get());
        // Не WAV Whisper сервера не примет — сразу следующий путь.
        assertEquals("облако", router.transcribe(DIMA, new byte[100], "mp3", 4, "").get());
        config.sttServer = false;
        ExecutionException failure = assertThrows(ExecutionException.class, () -> router.transcribe(DIMA, new byte[100], "wav16k", 4, "").get());
        assertTrue(Transcriptions.reason(failure).contains("не установлен"), Transcriptions.reason(failure));
        local.close();
    }

    @Test void bothOffRefusesAndPathsFollowTheSettings() {
        MobTalkConfig config = new MobTalkConfig();
        assertEquals(List.of("local", "server", "text"), Transcriptions.paths(config));
        config.sttServer = false;
        assertEquals(List.of("local", "text"), Transcriptions.paths(config));
        AtomicInteger calls = new AtomicInteger();
        Transcriptions router = new Transcriptions(() -> config, answering("x", calls), answering("y", calls));
        ExecutionException failure = assertThrows(ExecutionException.class, () -> router.transcribe(DIMA, new byte[100], "wav16k", 4, "").get());
        assertEquals(Transcriptions.OFF, Transcriptions.reason(failure));
        assertEquals(0, calls.get());
        config.sttServerLocal = true;
        assertEquals(List.of("local", "server", "text"), Transcriptions.paths(config));
    }
}
