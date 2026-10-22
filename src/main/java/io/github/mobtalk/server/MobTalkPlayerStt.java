package io.github.mobtalk.server;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/** Entrypoint {@code mobtalk-stt-player}: как {@link MobTalkStt}, но с UUID игрока, чтобы расход записывался на него. */
public final class MobTalkPlayerStt implements BiFunction<UUID, byte[], CompletableFuture<String>> {
    @Override public CompletableFuture<String> apply(UUID player, byte[] wav) { return MobTalkStt.transcribe(player, wav); }
}
