package io.github.mobtalk.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Shared world memory: profiles, each mob's relations with players (standing, deals, favours), villages. Every mutation runs under this
 * lock and queues a write through {@link WorldMemoryFile}; {@link ProfileDefaults} fills what a profile is born with. */
public final class ConversationStore {
    private static final Map<Path,ConversationStore> WORLDS = new HashMap<>();
    private final WorldMemoryFile file;
    private State state;
    private MobTalkConfig config;

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
            store.file.shutdown();
        }
    }

    private ConversationStore(Path worldDirectory, MobTalkConfig config) throws IOException {
        this.config = config.copy();
        WorldMemoryFile.Loaded loaded = WorldMemoryFile.read(WorldMemoryFile.of(worldDirectory));
        state = loaded.state() == null ? new State() : loaded.state();
        file = new WorldMemoryFile(WorldMemoryFile.of(worldDirectory), this::snapshot);
        boolean enriched = false;
        // Back up v1 before assigning its old shared voices to the new disjoint pools.
        for (Profile profile : state.profiles.values()) enriched |= ProfileDefaults.refresh(profile, state.profiles, this.config);
        if (loaded.migrated() || enriched) scheduleSave();
    }

    /** A deep copy for the writer thread, taken under the store lock. */
    private synchronized State snapshot() { return state.copy(); }
    private void scheduleSave() { file.markDirty(); }
    /** Blocking writer barrier for shutdown/tests, never the game thread. */
    public void flush() throws IOException { file.flush(); }

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
        Profile detached = p.copy();
        detached.village = p.villageId == null || p.villageId.isBlank() ? null : state.villages.get(p.villageId);
        MobCard current = card(snapshot.id(), snapshot.playerId());
        detached.relationship = current.relationship();
        detached.recentEvents = current.events();
        Relation own = snapshot.playerId() == null ? null : p.relations.get(snapshot.playerId().toString());
        detached.hits = own == null ? 0 : own.hits;
        return detached;
    }

    /** Create/view a profile from immutable context without waiting for any disk operation. */
    public synchronized MobCard observe(MobSnapshot snapshot) { profile(snapshot); return card(snapshot.id(), snapshot.playerId()); }

    public synchronized void recordDamage(UUID mob, String type, String name, UUID player, String playerName, long nowMillis) {
        Profile p = ensure(mob, type, name); Relation r = relation(p, player);
        // A blow always ends a friendship or a truce: the first drops a stranger to −80, every later one leaves the mob displeased.
        r.score = r.hits++ == 0 ? Math.min(-80, r.score) : Math.max(-100, Math.min(r.score, 0) - 20);
        p.revision++;
        event(p, nowMillis, "Игрок " + safePlayer(playerName) + " ударил моба; отношение " + r.score); scheduleSave();
    }

    public synchronized void recordHelp(UUID mob, String type, String name, UUID player, String playerName, long nowMillis) {
        Profile p = ensure(mob, type, name); Relation r = relation(p, player);
        if (!cooledDown(r.lastHelp, nowMillis, 30_000)) return;
        r.lastHelp = nowMillis; r.score = clamp(r.score + 20, hostile(p)); p.revision++;
        event(p, nowMillis, "Игрок " + safePlayer(playerName) + " помог мобу; отношение " + r.score); scheduleSave();
    }

    /** A mob that saw what the player did to another: a smaller drop than the victim's, once per {@code WITNESS_COOLDOWN}, and a
     * memory of it. Only mobs with a profile notice; strangers are not worth one. */
    public static final long WITNESS_COOLDOWN = 5_000;
    public synchronized void recordWitnessed(UUID mob, UUID player, String playerName, String seen, int delta, long nowMillis) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        if (p == null || player == null) return;
        Relation r = relation(p, player);
        int applied = delta;
        if (!cooledDown(r.lastWitness, nowMillis, WITNESS_COOLDOWN)) {
            // Within the cooldown only something worse than what was already counted gets through — the kill after the blows, not the blows.
            if (delta >= r.lastWitnessDelta) return;
            applied = delta - r.lastWitnessDelta;
        }
        r.lastWitness = nowMillis; r.lastWitnessDelta = delta; r.score = clamp(r.score + applied, hostile(p));
        // No revision bump: what the mob saw happen to another must not cancel the reply it is giving right now.
        event(p, nowMillis, "Видел, как игрок " + safePlayer(playerName) + " " + seen + "; отношение " + r.score); scheduleSave();
    }
    /** What others call this mob: the name the model gave it, else what the game shows; "" for a stranger. */
    public synchronized String displayName(UUID mob) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        return p == null ? "" : p.givenName.isBlank() ? p.name : p.givenName;
    }

    public synchronized void recordEvent(UUID mob, String type, String name, String text, long nowMillis) {
        Profile p = ensure(mob, type, name); p.revision++; event(p, nowMillis, text); scheduleSave();
    }

    public synchronized long revision(UUID mob) { Profile p = state.profiles.get(mob.toString()); return p == null ? 0 : p.revision; }
    /** Whether this mob has a profile at all: a diary entry is only worth keeping for a mob the player already knows. */
    public synchronized boolean known(UUID mob) { return mob != null && state.profiles.containsKey(mob.toString()); }
    /** A mob the player has actually dealt with — spoken to, or given a life story — as opposed to one merely bumped into. */
    public synchronized boolean acquainted(UUID mob) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        return p != null && (!p.turns.isEmpty() || !p.backstory.isBlank());
    }
    /** Drops the profile of a mob the player never talked to (no turns, no life story): a dead one would only bloat the file.
     * Returns whether it was dropped; a mob with a conversation keeps its memory. */
    public synchronized boolean forgetStranger(UUID mob) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        if (p == null || !p.turns.isEmpty() || !p.backstory.isBlank()) return false;
        state.profiles.remove(mob.toString());
        state.bargains.keySet().removeIf(key -> key.startsWith(mob + ":"));
        scheduleSave();
        return true;
    }
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
    // ---- Deals and favours of the mob's own catalog (trades.json): every mob keeps its own ledger with each player. ----
    // Reads never create anything; writes need an existing profile and a real player. Ids are not checked against TradeCatalog
    // here: the game layer validates intents through Trading first.

    /** Never null: a deal the player has not touched yet is {@link TradeState#initial()}. */
    public synchronized TradeState tradeState(UUID mob, UUID player, String dealId) {
        Relation r = existing(mob, player);
        TradeState state = r == null || dealId == null ? null : r.deals.get(dealId);
        return state == null ? TradeState.initial() : state;
    }
    /** All deal states of this player with this mob, keyed by deal id; missing deals are initial. */
    public synchronized Map<String, TradeState> tradeStates(UUID mob, UUID player) {
        Relation r = existing(mob, player);
        return r == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(r.deals));
    }
    private Relation existing(UUID mob, UUID player) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        return p == null || player == null ? null : p.relations.get(player.toString());
    }
    /** Stores an accepted OFFER (already checked with Trading.permitted) and bumps the profile revision. */
    public synchronized void setDealPrice(UUID mob, UUID player, String dealId, int price, long nowMillis) {
        Profile p = require(mob); Relation r = tradingRelation(p, player);
        r.deals.put(dealId, Trading.offered(r.deals.get(dealId), price, nowMillis));
        p.revision++; scheduleSave();
    }
    /** Opens a secret deal for this player (already checked with Trading.canUnlock) and records an event. */
    public synchronized void unlockDeal(UUID mob, UUID player, String dealId, long nowMillis) {
        Profile p = require(mob); Relation r = tradingRelation(p, player);
        TradeState state = r.deals.getOrDefault(dealId, TradeState.initial());
        if (state.unlocked()) return;
        r.deals.put(dealId, new TradeState(state.price(), true, state.rounds(), state.sessionStartMillis(), state.boughtToday(), state.boughtDay()));
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
    private Relation tradingRelation(Profile p, UUID player) {
        if (player == null) throw new IllegalArgumentException("Сделки и задания требуют игрока.");
        return relation(p, player);
    }
    private static String dealName(Profile p, String dealId) { TradeDeal deal = TradeCatalog.deal(p.type, dealId); return deal == null ? String.valueOf(dealId) : deal.name(); }
    // ---- Generated life stories: one per mob at first meeting, one per village at its first villager. ----

    /** A village the player has met, anchored where its first villager stood (or its bell). */
    public record Village(String id, String dimension, int x, int z, String name, String story) { }
    /** How far (blocks, same dimension) a villager may stand from a village's anchor and still belong to it. */
    public static final int VILLAGE_RADIUS = 160;

    public synchronized boolean hasBackstory(UUID mob) { Profile p = state.profiles.get(mob.toString()); return p != null && !p.backstory.isBlank(); }
    /** The model's invented name and story for this mob; blank parts stay blank. villageId may be "". */
    public synchronized void setBackstory(UUID mob, String givenName, String story, String wish, String hidden, String villageId) {
        Profile p = require(mob);
        p.givenName = TextBounds.characters(givenName, 60); p.backstory = TextBounds.characters(story, 1500);
        p.wish = TextBounds.characters(wish, 400); p.hidden = TextBounds.characters(hidden, 400);
        p.villageId = villageId == null ? "" : villageId;
        scheduleSave(); // No revision bump: a story arriving mid-answer invalidates nothing.
    }
    /** Opening lines of the stories already written for this species, so the next one takes another path. */
    public synchronized List<String> storyOpenings(String type) {
        String canonical = canonical(type);
        return state.profiles.values().stream().filter(p -> canonical.equals(p.type) && !p.backstory.isBlank())
                .map(p -> TextBounds.characters(p.backstory, 90)).distinct().limit(20).toList();
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

    // ---- Favours the mob itself asks for (bring N of an item): its own debt, its own gratitude. ----

    /** The favour this mob is waiting on from the player, or null. */
    public synchronized Favor favor(UUID mob, UUID player) { Relation r = existing(mob, player); return r == null ? null : r.favor; }
    /** Titles of favours this player did for this mob, oldest first. */
    public synchronized List<String> favorsDone(UUID mob, UUID player) { Relation r = existing(mob, player); return r == null ? List.of() : List.copyOf(r.favorsDone); }
    /** The mob asks for a favour; refused while it already waits for one. */
    public synchronized void startFavor(UUID mob, UUID player, Favor favor, long nowMillis) {
        Profile p = require(mob); Relation r = tradingRelation(p, player);
        if (r.favor != null) throw new IllegalStateException("Сначала нужно закончить услугу «" + r.favor.title() + "».");
        r.favor = new Favor(favor.item(), favor.count(), favor.title(), favor.rewardDeal(), favor.rewardPercent(), mob, nowMillis);
        p.revision++;
        event(p, nowMillis, "Попросил игрока об услуге: " + r.favor.title() + " (" + favor.count() + "× " + favor.item() + ")"); scheduleSave();
    }
    /** The player delivered (the game already took the items): this mob warms up by {@link Favor#RELATIONSHIP}, the promised
     * deal opens and/or its price drops. Returns the favour. */
    public synchronized Favor completeFavor(UUID mob, UUID player, String playerName, long nowMillis) {
        Profile p = require(mob); Relation r = tradingRelation(p, player);
        Favor favor = r.favor;
        if (favor == null) throw new IllegalStateException("Сейчас никакой услуги не ждут.");
        r.favor = null;
        r.favorsDone.add(favor.title());
        while (r.favorsDone.size() > 50) r.favorsDone.removeFirst();
        r.score = clamp(r.score + Favor.RELATIONSHIP, hostile(p));
        TradeDeal promised = TradeCatalog.deal(p.type, favor.rewardDeal());
        if (promised != null) {
            TradeState s = r.deals.getOrDefault(promised.id(), TradeState.initial());
            int paying = Trading.currentPrice(promised, s);
            int lowered = favor.rewardPercent() > 0 ? Math.max(promised.floorPrice(), paying * (100 - favor.rewardPercent()) / 100) : paying;
            r.deals.put(promised.id(), new TradeState(lowered < paying ? lowered : s.price(), s.unlocked() || promised.secret(), s.rounds(), s.sessionStartMillis(), s.boughtToday(), s.boughtDay()));
        }
        p.revision++;
        event(p, nowMillis, "Игрок " + safePlayer(playerName) + " выполнил услугу: " + favor.title() + "; отношение " + r.score); scheduleSave();
        return favor;
    }
    /** Village gossip: what the player owes to, or did for, the other villagers of this mob's village, one line each.
     * Mobs outside a village hear nothing. */
    public synchronized List<String> neighbourFavors(UUID mob, UUID player) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        if (p == null || player == null || p.villageId == null || p.villageId.isBlank()) return List.of();
        List<String> lines = new ArrayList<>();
        for (Profile q : state.profiles.values()) {
            if (q == p || !p.villageId.equals(q.villageId)) continue;
            Relation r = q.relations.get(player.toString());
            if (r == null || r.favor == null && r.favorsDone.isEmpty()) continue;
            String who = q.givenName.isBlank() ? q.name : q.givenName;
            if (r.favor != null) lines.add(who + " ждёт от него «" + r.favor.title() + "» (" + r.favor.count() + "× " + r.favor.item() + ")");
            if (!r.favorsDone.isEmpty()) lines.add(who + " уже получил от него: " + String.join(", ", r.favorsDone));
            if (lines.size() >= 12) break;
        }
        return List.copyOf(lines);
    }

    public synchronized List<String> events(UUID mob) { Profile p = state.profiles.get(mob.toString()); return p == null ? List.of() : p.events.stream().map(e -> e.text).toList(); }

    /** The bare score, for checks that run every tick: no card, no event list. */
    public synchronized int relationship(UUID mob, UUID player) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        if (p == null) return 0;
        Relation r = player == null ? null : p.relations.get(player.toString());
        return r == null ? (hostile(p) ? -80 : 0) : r.score;
    }
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
        ProfileDefaults.refresh(p, state.profiles, config);
        p.revision++; scheduleSave();
    }
    public synchronized ActionIntent action(UUID mob) { Profile p = state.profiles.get(mob.toString()); return p == null ? ActionIntent.none() : p.action; }
    /** A mob without a profile (a dead stranger whose lease is still being stopped) has nothing to persist. */
    public synchronized void recordAction(UUID mob, ActionIntent action) {
        Profile p = state.profiles.get(mob.toString());
        if (p == null) return;
        p.action = action == null ? ActionIntent.none() : action; p.revision++; scheduleSave();
    }

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
        if (p != null) { if (ProfileDefaults.refresh(p, state.profiles, config)) scheduleSave(); return p; }
        SpeciesCatalog.Species species = SpeciesCatalog.effective(type, config);
        p = new Profile(); p.id = id; p.type = species.id(); p.name = TextBounds.characters(observedName, 120);
        ProfileDefaults.refresh(p, state.profiles, config);
        state.profiles.put(id.toString(), p); scheduleSave(); return p;
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
                if (r.lastWitness >= 0) facts.append("; ты видел, как он обходился с другими");
                if (r.lastPurchase >= 0) facts.append("; он уже покупал у моба");
                List<String> unlocked = r.deals.entrySet().stream().filter(e -> e.getValue().unlocked()).map(e -> dealName(p, e.getKey())).toList();
                if (!unlocked.isEmpty()) facts.append("; ему открыты твои секретные сделки: ").append(String.join(", ", unlocked));
                if (r.favor != null) facts.append("; ты ждёшь от него услугу «").append(r.favor.title()).append("» (")
                        .append(r.favor.count()).append("× ").append(r.favor.item()).append(")");
                if (!r.favorsDone.isEmpty()) facts.append("; он оказал тебе услуги: ").append(String.join(", ", r.favorsDone));
            }
            if (score < 0 && hits == 0 && (r == null || r.lastInsult < 0 && r.lastThreat < 0))
                facts.append(hostile(p) ? "; есть исходная видовая враждебность" : "; конкретная личная причина неприязни не сохранена");
            result.add(facts.append(". Остальные события могут относиться к другим игрокам.").toString());
        }
        p.events.forEach(event -> result.add(event.text));
        return List.copyOf(result);
    }
    private static boolean hostile(Profile p) { return p.hostile(); }
    /** Hostile species start at −80 but may be won over all the way: favours, help and purchases lift them past the truce. */
    static int clamp(int score, boolean hostile) { return Math.max(-100, Math.min(100, score)); }
    private static boolean cooledDown(long last, long now, long delay) { return last < 0 || now >= last && now - last >= delay; }
    private static String safePlayer(String name) { return TextBounds.characters(name == null || name.isBlank() ? "неизвестный" : name, 120); }
    private static String canonical(String type) { return type.contains(":") ? type : "minecraft:" + type; }
    private Profile require(UUID id) { Profile p = state.profiles.get(id.toString()); if (p == null) throw new IllegalArgumentException("Профиль моба ещё не создан."); return p; }
    private static void event(Profile p, long time, String text) { p.events.add(new Event(time, TextBounds.characters(text, 500))); while (p.events.size() > 20) p.events.removeFirst(); }

    static final class Profile {
        transient UUID id; transient int relationship, hits; transient List<String> recentEvents = List.of(); transient Village village;
        String type, name, personality, voiceId; String memory = "";
        /** What the model called this mob and the life it invented for it; blank until the first meeting is written up. */
        String givenName = "", backstory = "", villageId = "";
        /** This individual's own wish and secret, invented with the story; blank means the species defaults apply. */
        String wish = "", hidden = "";
        String customPersonality, preference, fear;
        int calm = -1, confidence = -1, curiosity = -1, sociability = -1;
        int variantSlot = -1; long revision; ActionIntent action = ActionIntent.none();
        List<String> traits = new ArrayList<>();
        List<Turn> turns = new ArrayList<>(); List<Event> events = new ArrayList<>(); Map<String,Relation> relations = new LinkedHashMap<>();

        boolean hostile() { return SpeciesCatalog.get(type).hostile(); }
        /** Bounds and defaults for a profile read from disk; a damaged entry is dropped rather than failing the whole world. */
        void sanitize() {
            type = type.contains(":") ? type : "minecraft:" + type; name = TextBounds.characters(name, 120);
            personality = TextBounds.characters(personality, 4000); memory = TextBounds.characters(memory, 4000);
            if (turns == null) turns = new ArrayList<>();
            turns.removeIf(t -> t == null || t.user() == null || t.assistant() == null);
            while (turns.size() > 100) turns.removeFirst();
            if (events == null) events = new ArrayList<>();
            events.removeIf(e -> e == null || e.text == null);
            while (events.size() > 20) events.removeFirst();
            if (relations == null) relations = new LinkedHashMap<>();
            relations.entrySet().removeIf(e -> e.getValue() == null);
            relations.values().forEach(r -> { r.score = clamp(r.score, hostile()); r.sanitize(); });
            if (action == null) action = ActionIntent.none();
            if (traits == null) traits = new ArrayList<>();
            if (givenName == null) givenName = ""; if (backstory == null) backstory = ""; if (villageId == null) villageId = "";
            if (wish == null) wish = ""; if (hidden == null) hidden = "";
        }
        Profile copy() {
            Profile p = new Profile(); p.id = id; p.type = type; p.name = name; p.personality = personality;
            p.voiceId = voiceId; p.memory = memory; p.variantSlot = variantSlot; p.revision = revision; p.action = action;
            p.givenName = givenName; p.backstory = backstory; p.villageId = villageId; p.wish = wish; p.hidden = hidden;
            p.customPersonality = customPersonality; p.calm = calm; p.confidence = confidence;
            p.curiosity = curiosity; p.sociability = sociability; p.preference = preference; p.fear = fear;
            p.turns = new ArrayList<>(turns); p.events = new ArrayList<>(events); p.traits = new ArrayList<>(traits);
            relations.forEach((key, r) -> p.relations.put(key, r.copy()));
            return p;
        }
    }
    public record Turn(String user, String assistant) { }
    private record Event(long time, String text) { }
    /** One player as this mob knows them: standing, what they did, their prices on its deals, the favour it waits for. */
    static final class Relation {
        int score, hits, lastWitnessDelta; long lastHelp = -1, lastApology = -1, lastInsult = -1, lastThreat = -1, lastPurchase = -1, lastWitness = -1; boolean ownerKnown;
        Map<String,TradeState> deals = new LinkedHashMap<>();
        Favor favor; List<String> favorsDone = new ArrayList<>();
        void sanitize() {
            if (deals == null) deals = new LinkedHashMap<>();
            deals.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
            if (favorsDone == null) favorsDone = new ArrayList<>();
            favorsDone.removeIf(Objects::isNull);
            if (favor != null && (favor.item() == null || favor.count() < 1)) favor = null;
        }
        Relation copy() {
            Relation c = new Relation(); c.score = score; c.hits = hits; c.lastHelp = lastHelp; c.lastApology = lastApology;
            c.lastInsult = lastInsult; c.lastThreat = lastThreat; c.lastPurchase = lastPurchase; c.lastWitness = lastWitness; c.lastWitnessDelta = lastWitnessDelta; c.ownerKnown = ownerKnown;
            c.deals = new LinkedHashMap<>(deals); c.favor = favor; c.favorsDone = new ArrayList<>(favorsDone); // Immutable records.
            return c;
        }
    }
    /** The whole file. */
    static final class State {
        int version = 2;
        Map<String,Profile> profiles = new LinkedHashMap<>();
        Map<String,Bargaining.Session> bargains = new LinkedHashMap<>();
        Map<String,Village> villages = new LinkedHashMap<>();
        void sanitize() {
            if (bargains == null) bargains = new LinkedHashMap<>();
            if (villages == null) villages = new LinkedHashMap<>();
            villages.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue().name() == null || e.getValue().dimension() == null);
        }
        State copy() {
            State c = new State(); c.version = version;
            profiles.forEach((key, p) -> c.profiles.put(key, p.copy()));
            c.bargains.putAll(bargains); c.villages.putAll(villages); // Immutable records.
            return c;
        }
    }
}
