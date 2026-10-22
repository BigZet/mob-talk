package io.github.mobtalk.server;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.SttRoute;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Распознавание на сервере по настройкам админа: {@code sttServerLocal} — сначала Whisper самого сервера, при его сбое
 * (не установлен, не поднялся, не WAV) — OpenRouter, если включён {@code sttServer}; иначе сразу OpenRouter. Какие пути видит
 * игрок, решает {@link #paths}. */
public final class Transcriptions implements TranscriptionService {
    public static final String OFF = "Распознавание на сервере выключено: говорите через свой Whisper или пишите текстом";

    private final Supplier<MobTalkConfig> config;
    private final TranscriptionService local, remote;

    public Transcriptions(Supplier<MobTalkConfig> config, TranscriptionService local, TranscriptionService remote) {
        this.config = config;
        this.local = local;
        this.remote = remote;
    }

    @Override public CompletableFuture<String> transcribe(UUID player, byte[] audio, String format, int seconds, String hints) {
        MobTalkConfig current = config.get();
        if (current.sttServerLocal) {
            CompletableFuture<String> first = local.transcribe(player, audio, format, seconds, hints);
            if (!current.sttServer) return first;
            return first.exceptionallyCompose(failure -> remote.transcribe(player, audio, format, seconds, hints));
        }
        if (current.sttServer) return remote.transcribe(player, audio, format, seconds, hints);
        return CompletableFuture.failedFuture(new IllegalStateException(OFF));
    }

    /** Пути, которые сервер объявляет игрокам в {@code ServerFeatures}: свой Whisper у игрока всегда, сервер — если включён
     * хоть один серверный способ, текст всегда. */
    public static List<String> paths(MobTalkConfig config) {
        List<String> paths = new ArrayList<>(List.of(SttRoute.LOCAL));
        if (config.sttServer || config.sttServerLocal) paths.add(SttRoute.SERVER);
        paths.add(SttRoute.TEXT);
        return List.copyOf(paths);
    }

    /** Понятная игроку причина из упавшего распознавания. */
    public static String reason(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "Сервер не смог распознать речь" : message;
    }
}
