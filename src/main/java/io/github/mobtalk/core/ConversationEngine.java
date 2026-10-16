package io.github.mobtalk.core;

import java.io.IOException;
import java.nio.file.Path;

/** Blocking pipeline intended for one conversation worker, with a store per single-player world. */
public final class ConversationEngine implements AutoCloseable {
    private final ConversationStore store;
    private final OpenRouterConversationClient llm;
    private final OpenRouterSpeechClient speech;
    private final Path configDirectory;
    private final String speechKey;
    private volatile boolean closed;

    public ConversationEngine(MobTalkConfig config, Path worldDirectory, Path configDirectory) throws IOException {
        this(config, worldDirectory, configDirectory, OpenRouterToken.load(configDirectory));
    }

    private ConversationEngine(MobTalkConfig config, Path worldDirectory, Path configDirectory, String key) throws IOException {
        this(config, worldDirectory, new OpenRouterConversationClient(key, config), new OpenRouterSpeechClient(key, config), configDirectory, key);
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
        OpenRouterConversationClient.Answer answer = llm.respond(snapshot, profile, question);
        checkCurrent(snapshot);
        byte[] pcm;
        String voiceId = speech.selectedVoice(profile.voiceId);
        try {
            OpenRouterSpeechClient.Audio audio = speech.synthesizeWithFallback(profile.voiceId, SpeciesCatalog.reserve(profile.type).id(), answer.reply());
            pcm = audio.pcm();
            voiceId = audio.voiceId();
        }
        catch (IOException speechUnavailable) { pcm = new byte[0]; }
        checkCurrent(snapshot);
        if (pcm.length > 0 && configDirectory != null && speechKey != null) {
            try { SpeechPreview.mark(configDirectory, voiceId, speechKey); }
            catch (IOException cacheUnavailable) { /* A status-cache failure must never discard successful audio. */ }
        }
        String name = profile.name.isBlank() ? profile.type : profile.name;
        Object custom = snapshot.mobDetails().get("customName");
        if (custom instanceof String text && !text.isBlank()) name = TextBounds.characters(text, 120);
        else if (Boolean.TRUE.equals(custom) || snapshot.playerId() == null && snapshot.mobDetails().isEmpty()) {
            if (snapshot.name() != null && !snapshot.name().isBlank()) name = TextBounds.characters(snapshot.name(), 120);
        }
        Reply reply = new Reply(question, answer.reply(), name, voiceId, pcm, answer.action(), snapshot.interactionRevision(), answer.bargain());
        return new PreparedReply(this, reply, snapshot, answer.memory(), answer.socialIntent(), pcm.length == 0 ? null : voiceId);
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

    public record Reply(String recognizedText, String responseText, String speakerName, String voiceId, byte[] pcm,
                        ActionIntent action, long interactionRevision, BargainIntent bargain) {
        public Reply(String recognizedText, String responseText, String speakerName, String voiceId, byte[] pcm,
                     ActionIntent action, long interactionRevision) {
            this(recognizedText, responseText, speakerName, voiceId, pcm, action, interactionRevision, BargainIntent.none());
        }
        public Reply(String recognizedText, String responseText, String speakerName, String voiceId, byte[] pcm) {
            this(recognizedText, responseText, speakerName, voiceId, pcm, ActionIntent.none(), 0);
        }
    }
}
