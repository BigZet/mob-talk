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
                if (state.species == null) state.species = new LinkedHashMap<>();
                if (state.villages == null) state.villages = new LinkedHashMap<>();
                state.villages.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue().name() == null || e.getValue().dimension() == null);
                state.species.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
                state.species.values().forEach(ConversationStore::sanitize);
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
                    p.relations.forEach((player, r) -> { r.score = clamp(r.score, hostile(p)); sanitize(r); adopt(p.type, player, r); });
                    if (p.action == null) p.action = ActionIntent.none();
                    if (p.traits == null) p.traits = new ArrayList<>();
                    if (p.givenName == null) p.givenName = ""; if (p.backstory == null) p.backstory = ""; if (p.villageId == null) p.villageId = "";
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
        detached.village = p.villageId == null || p.villageId.isBlank() ? null : state.villages.get(p.villageId);
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
    // ---- Deals and quests of the mob's own catalog (trades.json). ----
    // Negotiated prices and stock are per mob and player; favours and opened secrets are shared by the whole species for that
    // player (a village does not ask for the same bell twice). Reads never create anything; writes need an existing profile
    // and a real player. Ids are not checked against TradeCatalog here: the game layer validates intents through Trading first.

    /** Never null: a deal the player has not touched yet is {@link TradeState#initial()}. */
    public synchronized TradeState tradeState(UUID mob, UUID player, String dealId) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        Relation r = p == null || player == null ? null : p.relations.get(player.toString());
        TradeState state = r == null || dealId == null ? null : r.deals.get(dealId);
        return withUnlocks(p, player, dealId, state == null ? TradeState.initial() : state);
    }
    /** All deal states of this player with this mob, keyed by deal id; missing deals are initial. */
    public synchronized Map<String, TradeState> tradeStates(UUID mob, UUID player) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        if (p == null || player == null) return Map.of();
        Relation r = p.relations.get(player.toString());
        Map<String,TradeState> result = new LinkedHashMap<>();
        if (r != null) r.deals.forEach((id, value) -> result.put(id, withUnlocks(p, player, id, value)));
        Shared shared = state.species.get(sharedKey(p.type, player));
        if (shared != null) for (String id : shared.unlocked) result.computeIfAbsent(id, ignored -> withUnlocks(p, player, id, TradeState.initial()));
        return Collections.unmodifiableMap(result);
    }
    private TradeState withUnlocks(Profile p, UUID player, String dealId, TradeState value) {
        if (p == null || player == null || value.unlocked()) return value;
        Shared shared = state.species.get(sharedKey(p.type, player));
        return shared != null && shared.unlocked.contains(dealId)
                ? new TradeState(value.price(), true, value.rounds(), value.sessionStartMillis(), value.boughtToday(), value.boughtDay()) : value;
    }
    /** Stores an accepted OFFER (already checked with Trading.permitted) and bumps the profile revision. */
    public synchronized void setDealPrice(UUID mob, UUID player, String dealId, int price, long nowMillis) {
        Profile p = require(mob); Relation r = tradingRelation(p, player);
        r.deals.put(dealId, Trading.offered(r.deals.get(dealId), price, nowMillis));
        p.revision++; scheduleSave();
    }
    /** Opens a secret deal for this player (already checked with Trading.canUnlock) and records an event. */
    public synchronized void unlockDeal(UUID mob, UUID player, String dealId, long nowMillis) {
        Profile p = require(mob); tradingRelation(p, player);
        if (!shared(p.type, player).unlocked.add(dealId)) return;
        p.revision++;
        event(p, nowMillis, "Открыл игроку секретную сделку «" + dealName(p, dealId) + "»"); scheduleSave();
    }
    /** Records a completed purchase on {@code worldDay}: stock, a memory event and a small relationship gain. */
    public synchronized void recordPurchase(UUID mob, UUID player, String dealId, String playerName, long worldDay, long nowMillis) {
        Profile p = require(mob); Relation r = tradingRelation(p, player);
        TradeState before = r.deals.getOrDefault(dealId, TradeState.initial());
        r.deals.put(dealId, Trading.bought(before, worldDay));
        if (cooledDown(r.lastPurchase, nowMillis, 60_000)) { r.lastPurchase = nowMillis; r.score = clamp(r.score + 5, hostile(p)); }
        p.revision++;
        TradeDeal deal = TradeCatalog.deal(p.type, dealId);
        String what = deal == null ? "товар «" + dealId + "»"
                : deal.sell().count() + "× " + deal.sell().name() + " за " + Trading.currentPrice(deal, before) + "× " + deal.price().name();
        event(p, nowMillis, "Игрок " + safePlayer(playerName) + " купил " + what + "; отношение " + r.score); scheduleSave();
    }
    private static String sharedKey(String type, UUID player) { return type + ":" + player; }
    private Shared shared(String type, UUID player) { return state.species.computeIfAbsent(sharedKey(type, player), ignored -> new Shared()); }
    /** 0.3.0 kept opened secrets per mob; fold them into the species record so the whole village knows. */
    private void adopt(String type, String player, Relation r) {
        if (r.deals.values().stream().noneMatch(TradeState::unlocked)) return;
        Shared shared = state.species.computeIfAbsent(type + ":" + player, ignored -> new Shared());
        r.deals.forEach((id, value) -> { if (value.unlocked()) shared.unlocked.add(id); });
    }
    private Relation tradingRelation(Profile p, UUID player) {
        if (player == null) throw new IllegalArgumentException("Сделки и задания требуют игрока.");
        return relation(p, player);
    }
    private static String dealName(Profile p, String dealId) { TradeDeal deal = TradeCatalog.deal(p.type, dealId); return deal == null ? String.valueOf(dealId) : deal.name(); }
    /** Files written before 0.3 have no deal maps; a damaged entry is dropped rather than failing the whole world. */
    private static void sanitize(Relation r) {
        if (r.deals == null) r.deals = new LinkedHashMap<>();
        r.deals.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
    }
    private static void sanitize(Shared shared) {
        if (shared.unlocked == null) shared.unlocked = new LinkedHashSet<>();
        shared.unlocked.removeIf(Objects::isNull);
        if (shared.favorsDone == null) shared.favorsDone = new ArrayList<>();
        shared.favorsDone.removeIf(Objects::isNull);
        if (shared.favor != null && (shared.favor.item() == null || shared.favor.count() < 1)) shared.favor = null;
    }

    // ---- Generated life stories: one per mob at first meeting, one per village at its first villager. ----

    /** A village the player has met, anchored where its first villager stood (or its bell). */
    public record Village(String id, String dimension, int x, int z, String name, String story) { }
    /** How far (blocks, same dimension) a villager may stand from a village's anchor and still belong to it. */
    public static final int VILLAGE_RADIUS = 160;

    public synchronized boolean hasBackstory(UUID mob) { Profile p = state.profiles.get(mob.toString()); return p != null && !p.backstory.isBlank(); }
    /** The model's invented name and story for this mob; blank parts stay blank. villageId may be "". */
    public synchronized void setBackstory(UUID mob, String givenName, String story, String villageId) {
        Profile p = require(mob);
        p.givenName = TextBounds.characters(givenName, 60); p.backstory = TextBounds.characters(story, 1500);
        p.villageId = villageId == null ? "" : villageId;
        scheduleSave(); // No revision bump: a story arriving mid-answer invalidates nothing.
    }
    /** Names the model already gave to mobs of this species, so the next one differs. */
    public synchronized List<String> givenNames(String type) {
        String canonical = canonical(type);
        return state.profiles.values().stream().filter(p -> canonical.equals(p.type) && !p.givenName.isBlank()).map(p -> p.givenName).distinct().limit(40).toList();
    }
    public synchronized Village village(String id) { return id == null || id.isBlank() ? null : state.villages.get(id); }
    public synchronized List<String> villageNames() { return state.villages.values().stream().map(Village::name).toList(); }
    /** The known village whose anchor is within {@link #VILLAGE_RADIUS} blocks in this dimension, nearest first; null when none. */
    public synchronized Village villageNear(String dimension, int x, int z) {
        Village best = null; long bestDistance = Long.MAX_VALUE;
        for (Village village : state.villages.values()) {
            if (!village.dimension().equals(dimension)) continue;
            long dx = village.x() - x, dz = village.z() - z, distance = dx * dx + dz * dz;
            if (distance <= (long) VILLAGE_RADIUS * VILLAGE_RADIUS && distance < bestDistance) { best = village; bestDistance = distance; }
        }
        return best;
    }
    public synchronized Village createVillage(String dimension, int x, int z, String name, String story) {
        Village village = new Village("village-" + UUID.randomUUID().toString().substring(0, 8), dimension, x, z,
                TextBounds.characters(name == null || name.isBlank() ? "Безымянная деревня" : name, 60), TextBounds.characters(story, 1500));
        state.villages.put(village.id(), village); scheduleSave();
        return village;
    }

    // ---- Favours the mob itself asks for (bring N of an item). Shared by the species for this player. ----

    /** The favour this species is waiting on from the player, or null. */
    public synchronized Favor favor(UUID mob, UUID player) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        Shared shared = p == null || player == null ? null : state.species.get(sharedKey(p.type, player));
        return shared == null ? null : shared.favor;
    }
    /** Titles of favours this player has done for the species, oldest first. */
    public synchronized List<String> favorsDone(UUID mob, UUID player) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        Shared shared = p == null || player == null ? null : state.species.get(sharedKey(p.type, player));
        return shared == null ? List.of() : List.copyOf(shared.favorsDone);
    }
    /** The mob asks for a favour; refused while another one is pending for this species and player. */
    public synchronized void startFavor(UUID mob, UUID player, Favor favor, long nowMillis) {
        Profile p = require(mob); tradingRelation(p, player);
        Shared shared = shared(p.type, player);
        if (shared.favor != null) throw new IllegalStateException("Сначала нужно закончить услугу «" + shared.favor.title() + "».");
        shared.favor = new Favor(favor.item(), favor.count(), favor.title(), favor.rewardDeal(), favor.rewardPercent(), mob, nowMillis);
        p.revision++;
        event(p, nowMillis, "Попросил игрока об услуге: " + shared.favor.title() + " (" + favor.count() + "× " + favor.item() + ")"); scheduleSave();
    }
    /** The player delivered (the game already took the items): the favour is done for the species, this mob warms up by
     * {@link Favor#RELATIONSHIP}, the promised deal opens and/or its price drops for this mob. Returns the favour. */
    public synchronized Favor completeFavor(UUID mob, UUID player, String playerName, long nowMillis) {
        Profile p = require(mob); Relation r = tradingRelation(p, player);
        Shared shared = shared(p.type, player);
        Favor favor = shared.favor;
        if (favor == null) throw new IllegalStateException("Сейчас никакой услуги не ждут.");
        shared.favor = null;
        shared.favorsDone.add(favor.title());
        while (shared.favorsDone.size() > 50) shared.favorsDone.removeFirst();
        r.score = clamp(r.score + Favor.RELATIONSHIP, hostile(p));
        TradeDeal promised = TradeCatalog.deal(p.type, favor.rewardDeal());
        if (promised != null) {
            if (promised.secret()) shared.unlocked.add(promised.id());
            if (favor.rewardPercent() > 0) {
                TradeState s = r.deals.getOrDefault(promised.id(), TradeState.initial());
                int paying = Trading.currentPrice(promised, s);
                int lowered = Math.max(promised.floorPrice(), paying * (100 - favor.rewardPercent()) / 100);
                if (lowered < paying) r.deals.put(promised.id(), new TradeState(lowered, s.unlocked(), s.rounds(), s.sessionStartMillis(), s.boughtToday(), s.boughtDay()));
            }
        }
        p.revision++;
        event(p, nowMillis, "Игрок " + safePlayer(playerName) + " выполнил услугу: " + favor.title() + "; отношение " + r.score); scheduleSave();
        return favor;
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
            // A spoken threat to kill the mob or those it guards weighs more than an insult: two of them let a golem fight back.
            else if (social == SocialIntent.THREAT && cooledDown(r.lastThreat, nowMillis, 20_000)) { r.lastThreat = nowMillis; r.score = clamp(r.score - 25, hostile(p)); }
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
                if (r.lastThreat >= 0) facts.append("; он угрожал убийством");
                if (r.lastPurchase >= 0) facts.append("; он уже покупал у моба");
                Shared shared = state.species.getOrDefault(sharedKey(p.type, player), new Shared());
                List<String> unlocked = shared.unlocked.stream().map(id -> dealName(p, id)).toList();
                if (!unlocked.isEmpty()) facts.append("; ему открыты секретные сделки: ").append(String.join(", ", unlocked));
                if (shared.favor != null) facts.append("; сородичи ждут от него услугу «").append(shared.favor.title()).append("» (")
                        .append(shared.favor.count()).append("× ").append(shared.favor.item()).append(")");
                if (!shared.favorsDone.isEmpty()) facts.append("; оказал услуги (это знают все сородичи): ").append(String.join(", ", shared.favorsDone));
            }
            if (score < 0 && hits == 0 && (r == null || r.lastInsult < 0 && r.lastThreat < 0))
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
                snapshot.villages.putAll(state.villages); // Immutable records.
                state.species.forEach((key, shared) -> {
                    Shared c = new Shared(); c.unlocked.addAll(shared.unlocked);
                    c.favor = shared.favor; c.favorsDone.addAll(shared.favorsDone); snapshot.species.put(key, c);
                });
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
        p.givenName = source.givenName; p.backstory = source.backstory; p.villageId = source.villageId;
        p.customPersonality = source.customPersonality; p.calm = source.calm; p.confidence = source.confidence;
        p.curiosity = source.curiosity; p.sociability = source.sociability; p.preference = source.preference; p.fear = source.fear;
        p.turns = new ArrayList<>(source.turns); p.events = new ArrayList<>(source.events); p.traits = new ArrayList<>(source.traits);
        source.relations.forEach((key, r) -> {
            Relation c = new Relation(); c.score = r.score; c.hits = r.hits; c.lastHelp = r.lastHelp; c.lastApology = r.lastApology;
            c.lastInsult = r.lastInsult; c.lastThreat = r.lastThreat; c.lastPurchase = r.lastPurchase; c.ownerKnown = r.ownerKnown;
            c.deals = new LinkedHashMap<>(r.deals); // Immutable records.
            p.relations.put(key, c);
        });
        return p;
    }
    static final class Profile {
        transient UUID id; transient int relationship; transient List<String> recentEvents = List.of(); transient Village village;
        String type, name, personality, voiceId; String memory = "";
        /** What the model called this mob and the life it invented for it; blank until the first meeting is written up. */
        String givenName = "", backstory = "", villageId = "";
        String customPersonality, preference, fear;
        int calm = -1, confidence = -1, curiosity = -1, sociability = -1;
        int variantSlot = -1; long revision; ActionIntent action = ActionIntent.none();
        List<String> traits = new ArrayList<>();
        List<Turn> turns = new ArrayList<>(); List<Event> events = new ArrayList<>(); Map<String,Relation> relations = new LinkedHashMap<>();
    }
    public record Turn(String user, String assistant) { }
    private record Event(long time, String text) { }
    private static final class Relation {
        int score, hits; long lastHelp = -1, lastApology = -1, lastInsult = -1, lastThreat = -1, lastPurchase = -1; boolean ownerKnown;
        Map<String,TradeState> deals = new LinkedHashMap<>();
    }
    /** What a whole species knows about one player: key "type:player". */
    private static final class Shared { Set<String> unlocked = new LinkedHashSet<>(); Favor favor; List<String> favorsDone = new ArrayList<>(); }
    private static final class State {
        int version = 2;
        Map<String,Profile> profiles = new LinkedHashMap<>();
        Map<String,Bargaining.Session> bargains = new LinkedHashMap<>();
        Map<String,Shared> species = new LinkedHashMap<>();
        Map<String,Village> villages = new LinkedHashMap<>();
    }
}
