package io.github.mobtalk.core;

import java.io.IOException;
import java.nio.file.Path;

/** Blocking pipeline intended for one conversation worker, with a store per single-player world. */
public final class ConversationEngine implements AutoCloseable {
    /** About 0.4 s of 24 kHz 16-bit speech: enough for Minecraft's first queued buffers while the rest downloads. */
    static final int PREBUFFER_BYTES = 19_200;
    private final ConversationStore store;
    private final OpenRouterConversationClient llm;
    private final OpenRouterSpeechClient speech;
    private final Path configDirectory;
    private final String speechKey;
    private volatile boolean closed;

    public ConversationEngine(MobTalkConfig config, Path worldDirectory, Path configDirectory) throws IOException {
        this(config, worldDirectory, configDirectory, chatKey(config, configDirectory), speechKey(config, configDirectory));
    }

    private ConversationEngine(MobTalkConfig config, Path worldDirectory, Path configDirectory, String chatKey, String speechKey) throws IOException {
        this(config, worldDirectory, new OpenRouterConversationClient(chatKey, config), new OpenRouterSpeechClient(speechKey, config), configDirectory, speechKey);
    }

    /** The chosen provider's key is required: without it there is nobody to answer. */
    private static String chatKey(MobTalkConfig config, Path configDirectory) throws IOException {
        return OpenRouterToken.load(configDirectory, config.usesVenice() ? OpenRouterToken.Provider.VENICE : OpenRouterToken.Provider.OPENROUTER);
    }

    /** Speech always goes through OpenRouter; with Venice chatting, a missing OpenRouter key only leaves replies unvoiced. */
    private static String speechKey(MobTalkConfig config, Path configDirectory) throws IOException {
        if (!config.usesVenice()) return OpenRouterToken.load(configDirectory);
        try { return OpenRouterToken.load(configDirectory); } catch (IOException missing) { return null; }
    }

    ConversationEngine(MobTalkConfig config, Path worldDirectory, OpenRouterConversationClient llm, OpenRouterSpeechClient speech) throws IOException {
        this(config, worldDirectory, llm, speech, null, null);
    }

    ConversationEngine(MobTalkConfig config, Path worldDirectory, OpenRouterConversationClient llm,
                       OpenRouterSpeechClient speech, Path configDirectory, String key) throws IOException {
        this.llm = llm;
        this.speech = speech;
        this.configDirectory = configDirectory;
        this.speechKey = key;
        try { store = ConversationStore.open(worldDirectory, config); }
        catch (IOException failure) { llm.close(); speech.close(); throw failure; }
    }

    public Reply converse(MobSnapshot snapshot, String userText) throws IOException, InterruptedException {
        PreparedReply prepared = prepare(snapshot, userText);
        if (!commitDelivered(prepared, snapshot.interactionRevision()))
            throw new InterruptedException("Разговор отменён: состояние моба изменилось.");
        return prepared.reply();
    }

    /** Generate off the game thread; authoritative actions must succeed before the caller commits this reply. */
    public PreparedReply prepare(MobSnapshot snapshot, String userText) throws IOException, InterruptedException {
        if (closed) throw new IOException("Разговор Mob Talk уже завершён.");
        String question = TextBounds.characters(userText, 2000);
        if (question.isBlank()) throw new IOException("Речь не распознана. Удерживайте V и произнесите вопрос.");
        ConversationStore.Profile profile = store.profile(snapshot);
        checkCurrent(snapshot);
        String reserve = SpeciesCatalog.reserve(profile.type).id();
        EarlySpeech early = new EarlySpeech();
        OpenRouterConversationClient.Answer answer;
        try {
            // Voice starts while the model still writes facts, action and bargain; checks below decide whether it is used.
            answer = llm.respond(snapshot, profile, question, text -> early.start(text, () -> speech.streamWithFallback(profile.voiceId, reserve, text)));
            checkCurrent(snapshot);
        } catch (IOException | InterruptedException | RuntimeException failure) { early.discard(); throw failure; }
        SpeechStream audio = null;
        String voiceId = speech.selectedVoice(profile.voiceId);
        try {
            java.util.concurrent.CompletableFuture<OpenRouterSpeechClient.StreamedAudio> started = early.take(answer.reply());
            OpenRouterSpeechClient.StreamedAudio streamed = started == null ? speech.streamWithFallback(profile.voiceId, reserve, answer.reply())
                    : awaitStarted(started);
            audio = streamed.stream();
            voiceId = streamed.voiceId();
            // Speak from the first fraction of a second; the rest keeps downloading during playback.
            if (!audio.awaitPrebuffer(PREBUFFER_BYTES, speech.timeout().toNanos())) { audio.close(); audio = null; }
        }
        catch (IOException speechUnavailable) { audio = null; }
        catch (InterruptedException cancelled) { if (audio != null) audio.close(); throw cancelled; }
        try { checkCurrent(snapshot); }
        catch (InterruptedException cancelled) { if (audio != null) audio.close(); throw cancelled; }
        if (audio != null && configDirectory != null && speechKey != null) {
            try { SpeechPreview.mark(configDirectory, voiceId, speechKey); }
            catch (IOException cacheUnavailable) { /* A status-cache failure must never discard successful audio. */ }
        }
        String name = profile.name.isBlank() ? profile.type : profile.name;
        Object custom = snapshot.mobDetails().get("customName");
        if (custom instanceof String text && !text.isBlank()) name = TextBounds.characters(text, 120);
        else if (Boolean.TRUE.equals(custom) || snapshot.playerId() == null && snapshot.mobDetails().isEmpty()) {
            if (snapshot.name() != null && !snapshot.name().isBlank()) name = TextBounds.characters(snapshot.name(), 120);
        }
        Reply reply = new Reply(question, answer.reply(), name, voiceId, audio, answer.action(), snapshot.interactionRevision(), answer.bargain());
        return new PreparedReply(this, reply, snapshot, answer.memory(), answer.socialIntent(), audio == null ? null : voiceId);
    }

    /** Remember only a delivered answer, once, at the revision returned by authoritative game application. */
    public boolean commitDelivered(PreparedReply prepared, long expectedCurrentRevision) {
        synchronized (prepared) {
            if (closed || prepared.owner != this || prepared.completed) return false;
            prepared.completed = true;
            MobSnapshot observed = prepared.snapshot;
            MobSnapshot delivered = new MobSnapshot(observed.id(), observed.type(), observed.name(), observed.health(), observed.maxHealth(),
                    observed.dimension(), observed.biome(), observed.timeOfDay(), observed.weather(), observed.distance(),
                    observed.heldItem(), observed.nearby(), observed.recentEvents(), observed.playerId(), observed.playerDetails(),
                    observed.mobDetails(), observed.environmentDetails(), observed.observedEntities(), observed.availableActions(), expectedCurrentRevision);
            Reply reply = prepared.reply;
            return store.commit(delivered, reply.recognizedText(), reply.responseText(), prepared.memory, prepared.socialIntent,
                    reply.action(), System.currentTimeMillis(), prepared.successfulVoice);
        }
    }

    private OpenRouterSpeechClient.StreamedAudio awaitStarted(java.util.concurrent.CompletableFuture<OpenRouterSpeechClient.StreamedAudio> started)
            throws IOException, InterruptedException {
        try { return started.get(speech.timeout().toSeconds(), java.util.concurrent.TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException failure) {
            throw failure.getCause() instanceof IOException io ? io : new IOException("OpenRouter TTS: не удалось начать озвучку.");
        } catch (java.util.concurrent.TimeoutException timeout) {
            started.thenAccept(late -> late.stream().close());
            throw new IOException("OpenRouter TTS: превышено время ожидания.");
        } catch (InterruptedException cancelled) {
            started.thenAccept(late -> late.stream().close());
            throw cancelled;
        }
    }

    /** At most one speech request opened from a streamed reply; any earlier or mismatched one is closed. */
    private static final class EarlySpeech {
        interface Opener { OpenRouterSpeechClient.StreamedAudio open() throws IOException, InterruptedException; }
        private String text;
        private java.util.concurrent.CompletableFuture<OpenRouterSpeechClient.StreamedAudio> pending;

        synchronized void start(String reply, Opener opener) {
            discard();
            if (reply == null || reply.isBlank()) return;
            java.util.concurrent.CompletableFuture<OpenRouterSpeechClient.StreamedAudio> future = new java.util.concurrent.CompletableFuture<>();
            text = reply; pending = future;
            // Called from the HTTP client's thread: opening the speech request must not hold up the rest of the reply.
            Thread.ofVirtual().name("mobtalk-early-speech").start(() -> {
                try { future.complete(opener.open()); } catch (Throwable failure) { future.completeExceptionally(failure); }
            });
        }

        /** The early request when it voiced exactly the final reply; otherwise it is closed and null returned. */
        synchronized java.util.concurrent.CompletableFuture<OpenRouterSpeechClient.StreamedAudio> take(String reply) {
            if (pending != null && reply.equals(text)) {
                var started = pending; pending = null; return started;
            }
            discard();
            return null;
        }

        synchronized void discard() {
            if (pending != null) pending.thenAccept(late -> late.stream().close());
            pending = null;
        }
    }

    public ConversationStore memory() { return store; }

    public void resetPersonality(java.util.UUID mobId) { store.resetPersonality(mobId); }

    private void checkCurrent(MobSnapshot snapshot) throws InterruptedException {
        if (closed || Thread.currentThread().isInterrupted() || store.revision(snapshot.id()) != snapshot.interactionRevision())
            throw new InterruptedException("Разговор отменён: состояние моба изменилось.");
    }

    /** Cancels network work immediately; no file or network waiting in close. */
    @Override public void close() {
        closed = true;
        llm.close();
        speech.close();
    }

    public static final class PreparedReply {
        private final ConversationEngine owner;
        private final Reply reply;
        private final MobSnapshot snapshot;
        private final String memory;
        private final SocialIntent socialIntent;
        private final String successfulVoice;
        private boolean completed;

        private PreparedReply(ConversationEngine owner, Reply reply, MobSnapshot snapshot, String memory,
                              SocialIntent socialIntent, String successfulVoice) {
            this.owner = owner;
            this.reply = reply;
            this.snapshot = snapshot;
            this.memory = memory;
            this.socialIntent = socialIntent;
            this.successfulVoice = successfulVoice;
        }

        public Reply reply() { return reply; }
    }

    /** speech is null when audio is unavailable; it may still be downloading while the reply is delivered. */
    public record Reply(String recognizedText, String responseText, String speakerName, String voiceId, SpeechStream speech,
                        ActionIntent action, long interactionRevision, BargainIntent bargain) {
        /** The complete audio, waiting for the download to end; empty without speech. */
        public byte[] pcm() throws InterruptedException {
            return speech == null ? new byte[0] : speech.awaitAll(java.util.concurrent.TimeUnit.SECONDS.toNanos(90));
        }
        /** Stops downloading audio for a reply that will not be played. */
        public void discardSpeech() { if (speech != null) speech.close(); }
    }
}
