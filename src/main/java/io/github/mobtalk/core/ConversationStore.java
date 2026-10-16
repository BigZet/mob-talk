package io.github.mobtalk.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared world memory. In-memory mutations enqueue one daemon disk writer. */
public final class ConversationStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<Path,ConversationStore> WORLDS = new HashMap<>();
    private final Path file;
    private final ExecutorService writer;
    private State state = new State();
    private MobTalkConfig config;
    private boolean dirty, writing;
    private IOException saveFailure;

    /** Initial load touches disk: call from a maintenance/conversation worker. */
    public static ConversationStore open(Path worldDirectory, MobTalkConfig config) throws IOException {
        Path key = worldDirectory.toAbsolutePath().normalize();
        synchronized (WORLDS) {
            ConversationStore cached = WORLDS.get(key);
            if (cached != null) { synchronized (cached) { cached.config = config.copy(); } return cached; }
            ConversationStore created = new ConversationStore(key, config);
            WORLDS.put(key, created);
            return created;
        }
    }

    /** Blocking flush/eviction, after cancelling this world's requests; never call on the game thread. */
    public static void closeWorld(Path worldDirectory) throws IOException {
        if (worldDirectory == null) return;
        synchronized (WORLDS) {
            Path key = worldDirectory.toAbsolutePath().normalize();
            ConversationStore store = WORLDS.get(key);
            if (store == null) return;
            store.flush();
            WORLDS.remove(key);
            store.writer.shutdown();
        }
    }

    private ConversationStore(Path worldDirectory, MobTalkConfig config) throws IOException {
        file = worldDirectory.resolve("data/mobtalk/conversations.json");
        this.config = config.copy();
        boolean migrated = false, enriched = false;
        if (Files.exists(file)) {
            try {
                String savedJson = Files.readString(file, StandardCharsets.UTF_8);
                state = JSON.fromJson(savedJson, State.class);
                if (state == null || state.profiles == null || state.version > 2) throw new IllegalArgumentException();
                if (!JsonParser.parseString(savedJson).getAsJsonObject().has("bargains")) {
                    Path backup = file.resolveSibling("conversations.before-bargaining.backup.json");
                    if (!Files.exists(backup)) Files.copy(file, backup);
                }
                if (state.bargains == null) state.bargains = new LinkedHashMap<>();
                migrated = state.version < 2;
                for (var entry : state.profiles.entrySet()) {
                    Profile p = entry.getValue();
                    UUID id = UUID.fromString(entry.getKey());
                    if (p == null || p.type == null || p.personality == null || p.voiceId == null) throw new IllegalArgumentException();
                    p.id = id; p.type = canonical(p.type); p.name = TextBounds.characters(p.name, 120);
                    p.personality = TextBounds.characters(p.personality, 4000); p.memory = TextBounds.characters(p.memory, 4000);
                    if (p.turns == null) p.turns = new ArrayList<>();
                    p.turns.removeIf(t -> t == null || t.user() == null || t.assistant() == null);
                    while (p.turns.size() > 100) p.turns.removeFirst();
                    if (p.events == null) p.events = new ArrayList<>();
                    p.events.removeIf(e -> e == null || e.text == null);
                    while (p.events.size() > 20) p.events.removeFirst();
                    if (p.relations == null) p.relations = new LinkedHashMap<>();
                    p.relations.entrySet().removeIf(e -> e.getValue() == null);
                    p.relations.values().forEach(r -> r.score = clamp(r.score, hostile(p)));
                    if (p.action == null) p.action = ActionIntent.none();
                    if (p.traits == null) p.traits = new ArrayList<>();
                    if (migrated) p.variantSlot = -1;
                }
                if (migrated) {
                    Path backup = file.resolveSibling("conversations.v1.backup.json");
                    if (!Files.exists(backup)) Files.copy(file, backup);
                    state.version = 2;
                }
                // Back up v1 before assigning its old shared voices to the new disjoint pools.
                for (Profile profile : state.profiles.values()) enriched |= refreshProfile(profile);
            } catch (IOException | RuntimeException failure) { throw new IOException("Не удалось прочитать память Mob Talk в папке мира."); }
        }
        writer = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "mobtalk-world-memory"); thread.setDaemon(true); return thread; });
        if (migrated || enriched) scheduleSave();
    }

    synchronized Profile profile(MobSnapshot snapshot) {
        Profile p = ensure(snapshot.id(), snapshot.type(), snapshot.name());
        if (snapshot.playerId() != null) {
            Relation r = relation(p, snapshot.playerId());
            Object owner = snapshot.mobDetails().get("ownerId");
            if (owner == null) owner = snapshot.mobDetails().get("ownerUuid");
            if (owner == null) owner = snapshot.mobDetails().get("owner");
            if (snapshot.playerId().toString().equals(String.valueOf(owner)) && !r.ownerKnown) {
                if (r.hits == 0) r.score = clamp(Math.max(65, r.score), hostile(p));
                r.ownerKnown = true; scheduleSave();
            }
        }
        Profile detached = copy(p);
        MobCard current = card(snapshot.id(), snapshot.playerId());
        detached.relationship = current.relationship();
        detached.recentEvents = current.events();
        return detached;
    }

    /** Create/view a profile from immutable context without waiting for any disk operation. */
    public synchronized MobCard observe(MobSnapshot snapshot) { profile(snapshot); return card(snapshot.id(), snapshot.playerId()); }

    public synchronized void recordDamage(UUID mob, String type, String name, UUID player, String playerName, long nowMillis) {
        Profile p = ensure(mob, type, name); Relation r = relation(p, player);
        r.score = r.hits++ == 0 ? Math.min(-80, r.score) : Math.max(-100, r.score - 20);
        p.revision++;
        event(p, nowMillis, "Игрок " + safePlayer(playerName) + " ударил моба; отношение " + r.score); scheduleSave();
    }

    public synchronized void recordHelp(UUID mob, String type, String name, UUID player, String playerName, long nowMillis) {
        Profile p = ensure(mob, type, name); Relation r = relation(p, player);
        if (!cooledDown(r.lastHelp, nowMillis, 30_000)) return;
        r.lastHelp = nowMillis; r.score = clamp(r.score + 20, hostile(p)); p.revision++;
        event(p, nowMillis, "Игрок " + safePlayer(playerName) + " помог мобу; отношение " + r.score); scheduleSave();
    }

    public synchronized void recordEvent(UUID mob, String type, String name, String text, long nowMillis) {
        Profile p = ensure(mob, type, name); p.revision++; event(p, nowMillis, text); scheduleSave();
    }

    public synchronized long revision(UUID mob) { Profile p = state.profiles.get(mob.toString()); return p == null ? 0 : p.revision; }
    public synchronized Bargaining.Session bargain(UUID mob, UUID player) {
        if (mob == null || player == null) return null;
        return state.bargains.get(mob + ":" + player);
    }
    public synchronized void setBargain(UUID mob, UUID player, Bargaining.Session session) {
        if (mob == null || player == null) throw new IllegalArgumentException("Bargaining requires a villager and a player");
        String key = mob + ":" + player;
        if (session == null) state.bargains.remove(key); else state.bargains.put(key, session);
        Profile profile = state.profiles.get(mob.toString());
        if (profile != null) profile.revision++;
        scheduleSave();
    }
    public synchronized List<String> events(UUID mob) { Profile p = state.profiles.get(mob.toString()); return p == null ? List.of() : p.events.stream().map(e -> e.text).toList(); }

    public synchronized MobCard card(UUID mob, UUID player) {
        Profile p = state.profiles.get(mob.toString()); if (p == null) return null;
        Relation r = player == null ? null : p.relations.get(player.toString());
        return new MobCard(mob, p.type, p.name, p.personality, p.voiceId, p.variantSlot,
                r == null ? (hostile(p) ? -80 : 0) : r.score, p.memory, p.turns,
                eventsForPlayer(p, player), p.revision, p.action,
                p.calm, p.confidence, p.curiosity, p.sociability, p.preference, p.fear, p.customPersonality);
    }
    public synchronized List<MobCard> cards(UUID player) { return state.profiles.keySet().stream().map(UUID::fromString).map(id -> card(id, player)).toList(); }

    /** Null edit fields retain their current values. */
    public synchronized void updateCard(UUID mob, String name, String personality, String voiceId, String memory) {
        Profile p = require(mob);
        if (voiceId != null && !voiceId.isBlank() && !voiceId.equals(p.voiceId) && !OpenRouterSpeechClient.validVoiceId(voiceId))
            throw new IllegalArgumentException("ID голоса должен содержать 20–64 латинских букв или цифр.");
        if (name != null) p.name = TextBounds.characters(name, 120);
        if (personality != null) {
            String edited = TextBounds.characters(personality, 4000);
            // Saving an unchanged form must not accidentally freeze the generated archetype default.
            if (!edited.equals(p.personality) || !p.customPersonality.isBlank()) p.customPersonality = edited;
            p.personality = p.customPersonality.isBlank()
                    ? SpeciesCatalog.effective(p.type, config).variants().get(p.variantSlot).prompt() : p.customPersonality;
        }
        if (voiceId != null) p.voiceId = voiceId.strip();
        if (memory != null) p.memory = TextBounds.characters(memory, 4000);
        p.revision++; scheduleSave();
    }
    public synchronized void clearHistory(UUID mob) { Profile p = require(mob); p.turns.clear(); p.revision++; scheduleSave(); }
    /** Reroll the individual's temperament without changing its voice, memories, or relationships. */
    public synchronized void resetPersonality(UUID mobId) {
        Profile p = require(mobId);
        p.customPersonality = "";
        p.calm = -1; p.confidence = -1; p.curiosity = -1; p.sociability = -1;
        p.preference = ""; p.fear = ""; p.traits = new ArrayList<>();
        refreshProfile(p);
        p.revision++; scheduleSave();
    }
    public synchronized ActionIntent action(UUID mob) { Profile p = state.profiles.get(mob.toString()); return p == null ? ActionIntent.none() : p.action; }
    public synchronized void recordAction(UUID mob, ActionIntent action) { Profile p = require(mob); p.action = action == null ? ActionIntent.none() : action; p.revision++; scheduleSave(); }

    /** Atomic revision check rejects a network response captured before a hit or profile edit. */
    synchronized boolean commit(MobSnapshot snapshot, String user, String reply, String memory, SocialIntent social, ActionIntent action, long nowMillis) {
        return commit(snapshot, user, reply, memory, social, action, nowMillis, null);
    }

    /** Voice replacement is committed with the same revision check, without cancelling its own valid reply. */
    synchronized boolean commit(MobSnapshot snapshot, String user, String reply, String memory, SocialIntent social,
                                ActionIntent action, long nowMillis, String successfulVoice) {
        Profile p = require(snapshot.id());
        if (p.revision != snapshot.interactionRevision()) return false;
        if (successfulVoice != null && OpenRouterSpeechClient.validVoiceId(successfulVoice)) p.voiceId = successfulVoice.strip();
        p.turns.add(new Turn(TextBounds.characters(user, 2000), TextBounds.characters(reply, 8000)));
        while (p.turns.size() > 100) p.turns.removeFirst();
        p.memory = TextBounds.characters(memory, Math.max(200, Math.min(4000, config.maxMemoryChars)));
        // Action state is persisted only by recordAction after authoritative native validation.
        if (snapshot.playerId() != null) {
            Relation r = relation(p, snapshot.playerId());
            if (social == SocialIntent.APOLOGY && cooledDown(r.lastApology, nowMillis, 60_000)) { r.lastApology = nowMillis; r.score = clamp(r.score + 5, hostile(p)); }
            else if (social == SocialIntent.INSULT && cooledDown(r.lastInsult, nowMillis, 60_000)) { r.lastInsult = nowMillis; r.score = clamp(r.score - 10, hostile(p)); }
            // KIND speech never substitutes for an observed helpful action.
        }
        scheduleSave(); return true;
    }

    private Profile ensure(UUID id, String type, String observedName) {
        Profile p = state.profiles.get(id.toString());
        if (p != null) { if (refreshProfile(p)) scheduleSave(); return p; }
        SpeciesCatalog.Species species = SpeciesCatalog.effective(type, config);
        p = new Profile(); p.id = id; p.type = species.id(); p.name = TextBounds.characters(observedName, 120);
        refreshProfile(p);
        state.profiles.put(id.toString(), p); scheduleSave(); return p;
    }

    private int allocateSlot(String canonical) {
        int[] counts = new int[3];
        for (Profile existing : state.profiles.values()) if (canonical.equals(existing.type) && existing.variantSlot >= 0 && existing.variantSlot < 3) counts[existing.variantSlot]++;
        int least = Math.min(counts[0], Math.min(counts[1], counts[2])); List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < 3; i++) if (counts[i] == least) candidates.add(i);
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private boolean refreshProfile(Profile p) {
        SpeciesCatalog.Species species = SpeciesCatalog.effective(p.type, config);
        boolean changed = false, legacy = p.variantSlot < 0 || p.variantSlot >= 3;
        if (legacy) {
            p.variantSlot = allocateSlot(species.id()); p.voiceId = species.variants().get(p.variantSlot).voice().id();
            p.customPersonality = ""; p.traits = new ArrayList<>(); changed = true;
        }
        if (p.customPersonality == null) {
            boolean generated = p.personality == null || p.personality.isBlank()
                    || SpeciesCatalog.get(p.type).variants().stream().anyMatch(v -> v.prompt().equals(p.personality))
                    || species.variants().stream().anyMatch(v -> v.prompt().equals(p.personality));
            p.customPersonality = generated ? "" : TextBounds.characters(p.personality, 4000); changed = true;
        }
        if (p.calm < 0 || p.calm > 100) { p.calm = randomAxis(); changed = true; }
        if (p.confidence < 0 || p.confidence > 100) { p.confidence = randomAxis(); changed = true; }
        if (p.curiosity < 0 || p.curiosity > 100) { p.curiosity = randomAxis(); changed = true; }
        if (p.sociability < 0 || p.sociability > 100) { p.sociability = randomAxis(); changed = true; }
        if (p.preference == null || p.preference.isBlank()) { p.preference = concern(species.interests()); changed = true; }
        if (p.fear == null || p.fear.isBlank()) { p.fear = concern(species.fears()); changed = true; }
        if (p.traits == null || p.traits.isEmpty()) {
            p.traits = new ArrayList<>(List.of("Особенно ценит: " + p.preference, "Лично тревожит: " + p.fear)); changed = true;
        }
        String personality = p.customPersonality.isBlank() ? species.variants().get(p.variantSlot).prompt() : p.customPersonality;
        if (!personality.equals(p.personality)) { p.personality = TextBounds.characters(personality, 4000); changed = true; }
        return changed;
    }

    private static int randomAxis() { return ThreadLocalRandom.current().nextInt(101); }
    private static String concern(String themes) {
        String[] choices = themes.replaceAll("[.]+$", "").split(",|\\s+и\\s+");
        return TextBounds.characters(choices[ThreadLocalRandom.current().nextInt(choices.length)].strip(), 300);
    }

    private Relation relation(Profile p, UUID player) {
        return p.relations.computeIfAbsent(player == null ? "unknown" : player.toString(), ignored -> { Relation r = new Relation(); r.score = hostile(p) ? -80 : 0; return r; });
    }
    /** Derive durable causes from this player's relation without expanding the persisted event log. */
    private List<String> eventsForPlayer(Profile p, UUID player) {
        List<String> result = new ArrayList<>();
        if (player != null) {
            Relation r = p.relations.get(player.toString());
            int score = r == null ? (hostile(p) ? -80 : 0) : r.score;
            int hits = r == null ? 0 : r.hits;
            StringBuilder facts = new StringBuilder("Факты только о текущем игроке ").append(player)
                    .append(": отношение ").append(score).append("; зафиксированных ударов от него: ").append(hits);
            if (r != null) {
                if (r.ownerKnown) facts.append("; подтверждён как владелец");
                if (r.lastHelp >= 0) facts.append("; наблюдалась его реальная помощь");
                if (r.lastApology >= 0) facts.append("; его реплика распознана как извинение");
                if (r.lastInsult >= 0) facts.append("; его реплика распознана как оскорбление");
            }
            if (score < 0 && hits == 0 && (r == null || r.lastInsult < 0))
                facts.append(hostile(p) ? "; есть исходная видовая враждебность" : "; конкретная личная причина неприязни не сохранена");
            result.add(facts.append(". Остальные события могут относиться к другим игрокам.").toString());
        }
        p.events.forEach(event -> result.add(event.text));
        return List.copyOf(result);
    }
    private boolean hostile(Profile p) { return SpeciesCatalog.get(p.type).hostile(); }
    private static int clamp(int score, boolean hostile) { return Math.max(-100, Math.min(hostile ? -1 : 100, score)); }
    private static boolean cooledDown(long last, long now, long delay) { return last < 0 || now >= last && now - last >= delay; }
    private static String safePlayer(String name) { return TextBounds.characters(name == null || name.isBlank() ? "неизвестный" : name, 120); }
    private static String canonical(String type) { return type.contains(":") ? type : "minecraft:" + type; }
    private Profile require(UUID id) { Profile p = state.profiles.get(id.toString()); if (p == null) throw new IllegalArgumentException("Профиль моба ещё не создан."); return p; }
    private static void event(Profile p, long time, String text) { p.events.add(new Event(time, TextBounds.characters(text, 500))); while (p.events.size() > 20) p.events.removeFirst(); }

    /** Blocking writer barrier for shutdown/tests, never the game thread. */
    public void flush() throws IOException {
        Future<?> barrier;
        synchronized (this) { if (dirty && !writing) scheduleSave(); barrier = writer.submit(() -> { }); }
        try { barrier.get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Сохранение памяти Mob Talk прервано."); }
        catch (ExecutionException failure) { throw new IOException("Не удалось сохранить память Mob Talk."); }
        synchronized (this) { if (saveFailure != null) throw saveFailure; }
    }
    private synchronized void scheduleSave() { dirty = true; if (!writing) { writing = true; writer.execute(this::writeLoop); } }
    private void writeLoop() {
        while (true) {
            State snapshot;
            synchronized (this) {
                if (!dirty) { writing = false; return; }
                dirty = false;
                snapshot = new State();
                state.profiles.forEach((key, p) -> snapshot.profiles.put(key, copy(p)));
                snapshot.bargains.putAll(state.bargains); // Sessions are immutable records.
            }
            try { write(snapshot); synchronized (this) { saveFailure = null; } }
            catch (IOException failure) { synchronized (this) { saveFailure = failure; dirty = true; writing = false; } return; }
        }
    }
    private void write(State snapshot) throws IOException {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent()); temporary = Files.createTempFile(file.getParent(), "conversations-", ".tmp");
            Files.writeString(temporary, JSON.toJson(snapshot), StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException failure) { throw new IOException("Не удалось сохранить память Mob Talk в папке мира."); }
        finally { if (temporary != null) Files.deleteIfExists(temporary); }
    }
    private static Profile copy(Profile source) {
        Profile p = new Profile(); p.id = source.id; p.type = source.type; p.name = source.name; p.personality = source.personality;
        p.voiceId = source.voiceId; p.memory = source.memory; p.variantSlot = source.variantSlot; p.revision = source.revision; p.action = source.action;
        p.customPersonality = source.customPersonality; p.calm = source.calm; p.confidence = source.confidence;
        p.curiosity = source.curiosity; p.sociability = source.sociability; p.preference = source.preference; p.fear = source.fear;
        p.turns = new ArrayList<>(source.turns); p.events = new ArrayList<>(source.events); p.traits = new ArrayList<>(source.traits);
        source.relations.forEach((key, r) -> { Relation c = new Relation(); c.score = r.score; c.hits = r.hits; c.lastHelp = r.lastHelp; c.lastApology = r.lastApology; c.lastInsult = r.lastInsult; c.ownerKnown = r.ownerKnown; p.relations.put(key, c); });
        return p;
    }
    static final class Profile {
        transient UUID id; transient int relationship; transient List<String> recentEvents = List.of();
        String type, name, personality, voiceId; String memory = "";
        String customPersonality, preference, fear;
        int calm = -1, confidence = -1, curiosity = -1, sociability = -1;
        int variantSlot = -1; long revision; ActionIntent action = ActionIntent.none();
        List<String> traits = new ArrayList<>();
        List<Turn> turns = new ArrayList<>(); List<Event> events = new ArrayList<>(); Map<String,Relation> relations = new LinkedHashMap<>();
    }
    public record Turn(String user, String assistant) { }
    private record Event(long time, String text) { }
    private static final class Relation { int score, hits; long lastHelp = -1, lastApology = -1, lastInsult = -1; boolean ownerKnown; }
    private static final class State {
        int version = 2;
        Map<String,Profile> profiles = new LinkedHashMap<>();
        Map<String,Bargaining.Session> bargains = new LinkedHashMap<>();
    }
}
