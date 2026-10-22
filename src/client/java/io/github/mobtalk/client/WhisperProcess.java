package io.github.mobtalk.client;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.WhisperHelper;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** The player's own Whisper helper; the work is in {@link WhisperHelper}, which a server with a GPU uses too. */
final class WhisperProcess {
    private final WhisperHelper helper = new WhisperHelper();

    void ensureAvailable(MobTalkConfig config, BooleanSupplier cancelled) throws IOException, InterruptedException {
        helper.ensureAvailable(config, cancelled);
    }
    void ensureAvailable(MobTalkConfig config, BooleanSupplier cancelled, Consumer<String> progress) throws IOException, InterruptedException {
        helper.ensureAvailable(config, cancelled, progress);
    }
    void stopOwned() { helper.stopOwned(); }
    void cancelLoading(MobTalkConfig config) { helper.cancelLoading(config); }
}
