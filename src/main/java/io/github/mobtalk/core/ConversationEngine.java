package io.github.mobtalk.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
        this(config, worldDirectory, new OpenRouterConversationClient(chatKey, config), new OpenRouterSpeechClient(speechKey, config, speechFallbackKey(config, configDirectory)), configDirectory, speechKey);
    }

    /** Venice speech: the OpenRouter key that voices instead when Venice is empty or refuses; null when there is none. */
    static String speechFallbackKey(MobTalkConfig config, Path configDirectory) {
        if (!config.usesVeniceSpeech()) return null;
        try { return OpenRouterToken.load(configDirectory, OpenRouterToken.Provider.OPENROUTER); } catch (IOException missing) { return null; }
    }

    /** The chosen provider's key is required: without it there is nobody to answer. */
    private static String chatKey(MobTalkConfig config, Path configDirectory) throws IOException {
        return OpenRouterToken.load(configDirectory, config.usesVenice() ? OpenRouterToken.Provider.VENICE : OpenRouterToken.Provider.OPENROUTER);
    }

    /** The speech provider's key; when it differs from the chat provider's, a missing one only leaves replies unvoiced. */
    private static String speechKey(MobTalkConfig config, Path configDirectory) throws IOException {
        OpenRouterToken.Provider provider = config.usesVeniceSpeech() ? OpenRouterToken.Provider.VENICE : OpenRouterToken.Provider.OPENROUTER;
        if (config.usesVenice() == config.usesVeniceSpeech()) return OpenRouterToken.load(configDirectory, provider);
        try { return OpenRouterToken.load(configDirectory, provider); } catch (IOException missing) { return null; }
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

    /** Something said that was not the player talking to this mob: an exchange a bystander heard (who the player spoke to, what was
     * said and what came back), an occasion for this mob to speak first ({@code said} holds the occasion, see {@link Initiative#cueText}),
     * or another creature's line addressed to this mob ({@code speaker} and {@code answer}). */
    public record Overheard(String speaker, String said, String answer, Kind kind) {
        public enum Kind { BYSTANDER, INITIATIVE, DIALOGUE }
        public Overheard {
            speaker = TextBounds.characters(speaker, 120); said = TextBounds.characters(said, 2000); answer = TextBounds.characters(answer, 2000);
            kind = kind == null ? Kind.BYSTANDER : kind;
        }
        public Overheard(String speaker, String said, String answer) { this(speaker, said, answer, Kind.BYSTANDER); }
        /** The occasion for a creature to speak first, in words for the prompt. */
        public static Overheard initiative(String cue) { return new Overheard("", cue, "", Kind.INITIATIVE); }
        /** Another creature's line to this one. */
        public static Overheard dialogue(String speaker, String line) { return new Overheard(speaker, "", line, Kind.DIALOGUE); }
        public boolean spontaneous() { return kind != Kind.BYSTANDER; }
        /** How the exchange goes into the mob's own history. */
        String asHeard() {
            return switch (kind) {
                case BYSTANDER -> "(ты слышал) Игрок — " + speaker + ": «" + said + "»; " + speaker + ": «" + answer + "»";
                case INITIATIVE -> "(ты заговорил первым) " + said;
                case DIALOGUE -> "(тебе сказал " + speaker + ") «" + answer + "»";
            };
        }
    }

    /** The village's chronicle when it is due: one background request turns the day's events into rumours ({@link VillageRumors});
     * nothing here waits or touches the network. */
    public void chronicleIfDue(String villageId, String playerName, long day) {
        if (closed || villageId == null || villageId.isBlank()) return;
        long now = System.currentTimeMillis();
        VillageRumors.Input input = store.chronicleDue(villageId, playerName, day, now);
        if (input == null) return;
        BackgroundTasks.submit("chronicle:" + villageId, () -> {
            if (closed) return;
            List<VillageRumors.View> rumors = VillageRumors.request(llm, input);
            int added = store.addRumors(villageId, rumors, System.currentTimeMillis());
            OpenRouterConversationClient.diagnostics.accept("хроника деревни «" + input.villageName() + "»: " + added + " новых слухов из " + input.facts().size() + " фактов");
        });
    }

    /** One line of village small talk, voiced in its speaker's voice ({@code audio} null when speech is unavailable). */
    public record ChatterLine(java.util.UUID speaker, String name, String text, SpeechStream audio) { }

    /** Village small talk ({@link VillageChatter}): one cheap request writes the exchange, each line is voiced in turn. Blocking:
     * call from a worker, never from the game thread. Empty when the model gave nothing usable. */
    public List<ChatterLine> chatter(VillageChatter.Input input) throws IOException, InterruptedException {
        if (closed) throw new IOException("Разговор Mob Talk уже завершён.");
        List<VillageChatter.Line> lines = VillageChatter.request(llm, input);
        List<ChatterLine> voiced = new java.util.ArrayList<>();
        String reserve = SpeciesCatalog.reserve("minecraft:villager").id();
        try {
            for (VillageChatter.Line line : lines) {
                VillageChatter.Speaker speaker = input.speaker(line.speaker());
                SpeechStream audio = null;
                try {
                    audio = speech.streamWithFallback(speaker.voiceId(), reserve, line.text()).stream();
                    if (!audio.awaitPrebuffer(PREBUFFER_BYTES, speech.timeout().toNanos())) { audio.close(); audio = null; }
                } catch (IOException speechUnavailable) { if (audio != null) audio.close(); audio = null; }
                voiced.add(new ChatterLine(speaker.id(), speaker.name(), line.text(), audio));
                if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException("Болтовня отменена.");
            }
        } catch (InterruptedException cancelled) { for (ChatterLine line : voiced) if (line.audio() != null) line.audio().close(); throw cancelled; }
        return voiced;
    }

    /** A villager's chat announcement ({@link VillageNotices}): one cheap request, "" when nothing usable came back. Blocking. */
    public String announce(VillageNotices.Input input) throws IOException, InterruptedException {
        if (closed) throw new IOException("Разговор Mob Talk уже завершён.");
        return VillageNotices.request(llm, input);
    }

    /** Тексты свадьбы ({@link WeddingScript}): один фоновый запрос. Блокирует: только из рабочего потока. */
    public Wedding.Script weddingScript(WeddingScript.Input input) throws IOException, InterruptedException {
        if (closed) throw new IOException("Разговор Mob Talk уже завершён.");
        if (WeddingFaults.llm) throw new IOException("самотест: модель недоступна");
        return WeddingScript.request(llm, input);
    }
    /** Клятва пары в ответ на слова игрока; "" — модель не дала пригодного. Блокирует. */
    public String weddingVow(WeddingScript.Input input, String playerVow) throws IOException, InterruptedException {
        if (closed) throw new IOException("Разговор Mob Talk уже завершён.");
        if (WeddingFaults.llm) throw new IOException("самотест: модель недоступна");
        return WeddingScript.spouseVow(llm, input, playerVow);
    }
    /** Одна фраза голосом {@code voiceId} (запасной — голос жителя); null — озвучка недоступна, тогда реплика идёт только текстом.
     * Блокирует до первых долей секунды звука. */
    public SpeechStream voice(String voiceId, String text) throws InterruptedException {
        if (closed || text == null || text.isBlank() || WeddingFaults.tts) return null;
        String reserve = SpeciesCatalog.reserve("minecraft:villager").id();
        SpeechStream audio = null;
        try {
            audio = speech.streamWithFallback(voiceId, reserve, text).stream();
            if (!audio.awaitPrebuffer(PREBUFFER_BYTES, speech.timeout().toNanos())) { audio.close(); return null; }
            return audio;
        } catch (IOException unavailable) { if (audio != null) audio.close(); return null; }
        catch (InterruptedException cancelled) { if (audio != null) audio.close(); throw cancelled; }
    }

    public Reply converse(MobSnapshot snapshot, String userText) throws IOException, InterruptedException {
        PreparedReply prepared = prepare(snapshot, userText);
        if (!commitDelivered(prepared, snapshot.interactionRevision()))
            throw new InterruptedException("Разговор отменён: состояние моба изменилось.");
        return prepared.reply();
    }

    /** Generate off the game thread; authoritative actions must succeed before the caller commits this reply. */
    public PreparedReply prepare(MobSnapshot snapshot, String userText) throws IOException, InterruptedException { return prepare(snapshot, userText, null); }

    /** {@code overheard} non-null: this mob only heard the exchange and may answer with silence (a blank reply, no speech). */
    public PreparedReply prepare(MobSnapshot snapshot, String userText, Overheard overheard) throws IOException, InterruptedException { return prepare(snapshot, userText, overheard, null); }

    /** {@code picture} non-null: a JPEG of what the player sees, attached to the question ({@link Vision}); ignored for bystanders. */
    public PreparedReply prepare(MobSnapshot snapshot, String userText, Overheard overheard, byte[] picture) throws IOException, InterruptedException {
        if (closed) throw new IOException("Разговор Mob Talk уже завершён.");
        String question = overheard != null ? overheard.asHeard() : TextBounds.characters(userText, 2000);
        if (question.isBlank()) throw new IOException("Речь не распознана. Удерживайте V и произнесите вопрос.");
        ConversationStore.Profile profile = store.profile(snapshot);
        checkCurrent(snapshot);
        // The player came back after a long silence: the earlier conversation ended, whether or not the game noticed; this reply still
        // sees its tail, the summary (if it arrives) replaces it from the next one on.
        long now = System.currentTimeMillis();
        if (overheard == null && profile.lastTurnMillis >= 0 && now - profile.lastTurnMillis > ConversationSummary.silenceMillis())
            endConversation(snapshot.id(), snapshot.playerId(), playerName(snapshot));
        String reserve = SpeciesCatalog.reserve(profile.type).id();
        EarlySpeech early = new EarlySpeech();
        OpenRouterConversationClient.Answer answer;
        try {
            // Voice starts while the model still writes facts, action and bargain; checks below decide whether it is used.
            answer = llm.respond(snapshot, profile, question, overheard, overheard == null ? picture : null,
                    text -> early.start(text, () -> speech.streamWithFallback(profile.voiceId, reserve, text)));
            checkCurrent(snapshot);
        } catch (IOException | InterruptedException | RuntimeException failure) { early.discard(); throw failure; }
        SpeechStream audio = null;
        String voiceId = speech.selectedVoice(profile.voiceId);
        if (answer.reply().isBlank()) { early.discard(); }
        else try {
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
        if (audio != null && configDirectory != null && speechKey != null && !speech.fellBack()) {
            try { SpeechPreview.mark(configDirectory, voiceId, speechKey, speech.model()); }
            catch (IOException cacheUnavailable) { /* A status-cache failure must never discard successful audio. */ }
        }
        if (answer.biography() != null) adopt(snapshot, answer.biography());
        String invented = answer.biography() == null ? "" : answer.biography().name();
        String name = !invented.isBlank() ? invented : profile.givenName != null && !profile.givenName.isBlank() ? profile.givenName : profile.name.isBlank() ? profile.type : profile.name;
        Object custom = snapshot.mobDetails().get("customName");
        if (custom instanceof String text && !text.isBlank()) name = TextBounds.characters(text, 120);
        else if (Boolean.TRUE.equals(custom) || snapshot.playerId() == null && snapshot.mobDetails().isEmpty()) {
            if (snapshot.name() != null && !snapshot.name().isBlank()) name = TextBounds.characters(snapshot.name(), 120);
        }
        Reply reply = new Reply(question, answer.reply(), name, voiceId, audio, answer.action(), snapshot.interactionRevision(), answer.bargain(),
                answer.deal(), answer.unlock(), answer.quest(), answer.socialIntent(), overheard != null);
        return new PreparedReply(this, reply, snapshot, answer.memory(), answer.socialIntent(), audio == null ? null : voiceId, answer.thought(), answer.fact(),
                overheard == null ? proposalKept(profile, question, answer.gesture()) : GestureIntent.none());
    }

    /** Трек L: игрок явно сделал предложение с кольцом, игра его приняла, а модель не отметила жест — согласие всё равно в силе,
     * иначе житель скажет «да», а помолвки не будет и кольцо останется в руке. */
    static GestureIntent proposalKept(ConversationStore.Profile profile, String question, GestureIntent chosen) {
        if (chosen != null && chosen.present() || profile == null || profile.socialTurn == null || !Wedding.proposes(question)) return chosen;
        GestureIntent proposal = profile.socialTurn.intent(Gesture.PROPOSE);
        return proposal.present() && proposal.outcome() == Gesture.Outcome.ACCEPTED ? proposal : chosen;
    }

    private static String playerName(MobSnapshot snapshot) { return snapshot.playerDetails().get("name") instanceof String name ? name : ""; }

    /** The conversation with this mob is over (the player turned away, fell silent or left): its exchanges are summarised in the
     * background by {@link ConversationSummary}, one cheap request nobody waits for. Only a copy under the store lock happens here. */
    public void endConversation(java.util.UUID mob, java.util.UUID player, String playerName) {
        if (closed || mob == null) return;
        ConversationSummary.Pending pending = store.pendingSummary(mob, player, playerName);
        if (pending == null) return;
        BackgroundTasks.submit("summary:" + mob, () -> { if (!closed) ConversationSummary.summarize(store, llm, pending, System.currentTimeMillis()); });
    }

    /** After a committed reply: when enough has happened since the mob last thought this player over, one background request
     * draws conclusions ({@link Reflection}); the counters restart as soon as it is queued. */
    private void maybeReflect(MobSnapshot delivered) {
        if (closed || delivered.playerId() == null) return;
        long now = System.currentTimeMillis();
        Reflection.Input input = store.reflectionDue(delivered.id(), delivered.playerId(), playerName(delivered), now);
        if (input == null) return;
        BackgroundTasks.submit("reflection:" + delivered.id(), () -> { if (!closed) Reflection.reflect(store, llm, input, now); });
    }

    /** Waits for the background lane (summaries, reflections) to drain; tests only, never the game thread. */
    public void awaitBackground(long millis) throws InterruptedException, java.util.concurrent.TimeoutException { BackgroundTasks.awaitIdle(millis); }

    /** Remember only a delivered answer, once, at the revision returned by authoritative game application. */
    public boolean commitDelivered(PreparedReply prepared, long expectedCurrentRevision) {
        synchronized (prepared) {
            if (closed || prepared.owner != this || prepared.completed) return false;
            prepared.completed = true;
            // A bystander that chose silence has nothing to remember.
            if (prepared.reply.overheard() && prepared.reply.responseText().isBlank()) return true;
            MobSnapshot observed = prepared.snapshot;
            MobSnapshot delivered = new MobSnapshot(observed.id(), observed.type(), observed.name(), observed.health(), observed.maxHealth(),
                    observed.dimension(), observed.biome(), observed.timeOfDay(), observed.weather(), observed.distance(),
                    observed.heldItem(), observed.nearby(), observed.recentEvents(), observed.playerId(), observed.playerDetails(),
                    observed.mobDetails(), observed.environmentDetails(), observed.observedEntities(), observed.availableActions(), expectedCurrentRevision);
            Reply reply = prepared.reply;
            boolean committed = store.commit(delivered, reply.recognizedText(), reply.responseText(), prepared.memory, prepared.socialIntent,
                    reply.action(), System.currentTimeMillis(), prepared.successfulVoice, prepared.thought, prepared.fact);
            if (committed && prepared.gesture.present())
                store.social().applyGesture(delivered.id(), delivered.playerId(), playerName(delivered), prepared.gesture, System.currentTimeMillis());
            if (committed && !reply.overheard()) maybeReflect(delivered);
            return committed;
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

    /** Where one-line diagnostics go (rejected intents and their corrections); the game points this at its log. */
    public static void setDiagnostics(java.util.function.Consumer<String> sink) {
        OpenRouterConversationClient.diagnostics = sink == null ? line -> { } : sink;
        BoundedHttp.diagnostics = sink == null ? line -> { } : sink;
    }

    /** Writes the mob's life story (and its village's, for the first villager met there) once; later calls return at once.
     * One model request; safe to call from any worker, never from the game thread. */
    public void ensureBackstory(MobSnapshot snapshot) throws IOException, InterruptedException {
        if (closed || store.hasBackstory(snapshot.id())) return;
        ConversationStore.Profile profile = store.profile(snapshot);
        boolean villager = "minecraft:villager".equals(snapshot.type()), golem = "minecraft:iron_golem".equals(snapshot.type());
        int[] anchor = villager || golem ? villageAnchor(snapshot) : null;
        // A golem belongs to the village it stands in but never founds one: villages are born with their first villager.
        ConversationStore.Village village = anchor == null ? null : store.villageNear(snapshot.dimension(), anchor[0], anchor[1]);
        BiographyWriter.Biography written = llm.biography(snapshot, profile, village, store.givenNames(snapshot.type()),
                store.storyOpenings(snapshot.type()), store.villageNames());
        adopt(snapshot, written);
    }

    /** Stores a life the model wrote (inside a reply or by a separate request), founding the village for its first villager. */
    private void adopt(MobSnapshot snapshot, BiographyWriter.Biography written) {
        if (closed || written == null || written.story().isBlank() || store.hasBackstory(snapshot.id())) return;
        boolean villager = "minecraft:villager".equals(snapshot.type()), golem = "minecraft:iron_golem".equals(snapshot.type());
        int[] anchor = villager || golem ? villageAnchor(snapshot) : null;
        ConversationStore.Village village = anchor == null ? null : store.villageNear(snapshot.dimension(), anchor[0], anchor[1]);
        if (villager && village == null && anchor != null && !written.villageStory().isBlank())
            village = store.createVillage(snapshot.dimension(), anchor[0], anchor[1], written.villageName(), written.villageStory());
        store.setBackstory(snapshot.id(), written.name(), written.story(), written.desire(), written.secret(), village == null ? "" : village.id());
    }

    /** Where this villager's village is: its bell, else its bed, else where it stands. */
    static int[] villageAnchor(MobSnapshot snapshot) {
        for (String key : java.util.List.of("meetingPoint", "home", "position")) {
            Object value = snapshot.mobDetails().get(key);
            Object position = value instanceof java.util.Map<?,?> place && place.get("position") instanceof java.util.Map<?,?> inner ? inner : value;
            if (position instanceof java.util.Map<?,?> point && point.get("x") instanceof Number x && point.get("z") instanceof Number z)
                return new int[]{x.intValue(), z.intValue()};
        }
        return null;
    }

    public void resetPersonality(java.util.UUID mobId) { store.resetPersonality(mobId); }

    private void checkCurrent(MobSnapshot snapshot) throws InterruptedException {
        // A relationship that moved mid-answer no longer cancels the answer: the next turn sees it. Only closing or an interrupt does.
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException("Разговор отменён.");
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
        private final String successfulVoice, thought;
        private final MemoryFact fact;
        private final GestureIntent gesture;
        private boolean completed;

        private PreparedReply(ConversationEngine owner, Reply reply, MobSnapshot snapshot, String memory,
                              SocialIntent socialIntent, String successfulVoice, String thought, MemoryFact fact, GestureIntent gesture) {
            this.owner = owner;
            this.reply = reply;
            this.snapshot = snapshot;
            this.memory = memory;
            this.socialIntent = socialIntent;
            this.successfulVoice = successfulVoice;
            this.thought = thought;
            this.fact = fact;
            this.gesture = gesture == null ? GestureIntent.none() : gesture;
        }

        public Reply reply() { return reply; }
        /** The social gesture of this reply with its decided outcome; the game takes the item (gift, ring) before the commit applies it. */
        public GestureIntent gesture() { return gesture; }
    }

    /** speech is null when audio is unavailable; it may still be downloading while the reply is delivered. */
    public record Reply(String recognizedText, String responseText, String speakerName, String voiceId, SpeechStream speech,
                        ActionIntent action, long interactionRevision, BargainIntent bargain,
                        DealIntent deal, String unlock, QuestIntent quest, SocialIntent socialIntent, boolean overheard) {
        public Reply {
            if (deal == null) deal = DealIntent.none();
            unlock = unlock == null ? "" : unlock;
            if (quest == null) quest = QuestIntent.none();
            if (socialIntent == null) socialIntent = SocialIntent.NONE;
        }
        public Reply(String recognizedText, String responseText, String speakerName, String voiceId, SpeechStream speech,
                     ActionIntent action, long interactionRevision, BargainIntent bargain,
                     DealIntent deal, String unlock, QuestIntent quest) {
            this(recognizedText, responseText, speakerName, voiceId, speech, action, interactionRevision, bargain, deal, unlock, quest, SocialIntent.NONE, false);
        }
        public Reply(String recognizedText, String responseText, String speakerName, String voiceId, SpeechStream speech,
                     ActionIntent action, long interactionRevision, BargainIntent bargain) {
            this(recognizedText, responseText, speakerName, voiceId, speech, action, interactionRevision, bargain, DealIntent.none(), "", QuestIntent.none());
        }
        /** A bystander that chose to keep quiet: nothing to show, nothing to voice. */
        public boolean silent() { return responseText == null || responseText.isBlank(); }
        /** The complete audio, waiting for the download to end; empty without speech. */
        public byte[] pcm() throws InterruptedException {
            return speech == null ? new byte[0] : speech.awaitAll(java.util.concurrent.TimeUnit.SECONDS.toNanos(90));
        }
        /** Stops downloading audio for a reply that will not be played. */
        public void discardSpeech() { if (speech != null) speech.close(); }
    }
}
