package io.github.mobtalk.core;

import java.io.IOException;
import java.nio.file.Path;

/** Blocking pipeline intended for one conversation worker, with a store per single-player world. */
public final class ConversationEngine implements AutoCloseable {
    private final ConversationStore store;
    private final OpenRouterConversationClient llm;
    private final OpenRouterSpeechClient speech;
    private volatile boolean closed;

    public ConversationEngine(MobTalkConfig config, Path worldDirectory, Path configDirectory) throws IOException {
        this(config, worldDirectory, OpenRouterToken.load(configDirectory));
    }

    private ConversationEngine(MobTalkConfig config, Path worldDirectory, String key) throws IOException {
        this(config, worldDirectory, new OpenRouterConversationClient(key, config), new OpenRouterSpeechClient(key, config));
    }

    ConversationEngine(MobTalkConfig config, Path worldDirectory, OpenRouterConversationClient llm, OpenRouterSpeechClient speech) throws IOException {
        this.llm = llm;
        this.speech = speech;
        try { store = new ConversationStore(worldDirectory, config); }
        catch (IOException failure) { llm.close(); speech.close(); throw failure; }
    }

    public Reply converse(MobSnapshot snapshot, String userText) throws IOException, InterruptedException {
        if (closed) throw new IOException("Разговор Mob Talk уже завершён.");
        String question = TextBounds.characters(userText, 2000);
        if (question.isBlank()) throw new IOException("Речь не распознана. Удерживайте V и произнесите вопрос.");
        ConversationStore.Profile profile = store.profile(snapshot);
        OpenRouterConversationClient.Answer answer = llm.respond(snapshot, profile, question);
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException("Разговор отменён.");
        store.remember(profile, question, answer.reply(), answer.memory());
        byte[] pcm;
        try { pcm = speech.synthesize(profile.voiceId, answer.reply()); }
        catch (IOException speechUnavailable) { pcm = new byte[0]; }
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException("Разговор отменён.");
        String name = TextBounds.characters(snapshot.name(), 120);
        if (name.isBlank()) name = profile.name.isBlank() ? profile.type : profile.name;
        return new Reply(question, answer.reply(), name, profile.voiceId, pcm);
    }

    /** Cancels network work immediately; no file or network waiting in close. */
    @Override public void close() {
        closed = true;
        llm.close();
        speech.close();
    }
    public record Reply(String recognizedText, String responseText, String speakerName, String voiceId, byte[] pcm) { }
}
