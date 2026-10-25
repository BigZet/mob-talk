package io.github.mobtalk.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Shared world memory: profiles, each mob's relations with players (standing, deals, favours), villages. Every mutation runs under this
 * lock and queues a write through {@link WorldMemoryFile}; {@link ProfileDefaults} fills what a profile is born with. */
public final class ConversationStore {
    private static final Map<Path,ConversationStore> WORLDS = new HashMap<>();
    private final WorldMemoryFile file;
    State state;
    private MobTalkConfig config;
    private final SocialLedger social = new SocialLedger(this);
    private final JealousyLedger jealousy = new JealousyLedger(this);
    private final RivalryLedger rivalry = new RivalryLedger(this);
    private final WeddingLedger wedding = new WeddingLedger(this);
    private final ChildLedger children = new ChildLedger(this);

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
        for (Profile profile : state.profiles.values())
            enriched |= SocialLedger.renameToSex(profile, state.family, state.profiles.values()) | ProfileDefaults.refresh(profile, state.profiles, this.config);
        if (loaded.migrated() || enriched) scheduleSave();
    }

    /** A deep copy for the writer thread, taken under the store lock. */
    private synchronized State snapshot() { return state.copy(); }
    void scheduleSave() { file.markDirty(); }
    /** The social layer of this world (characters, gestures, gifts, family, village standing); see {@link SocialLedger}. */
    public SocialLedger social() { return social; }
    /** Ревность пар игрока и ночи вместе (трек J); см. {@link JealousyLedger}. */
    public JealousyLedger jealousy() { return jealousy; }
    /** Соперники за одно сердце (П3); см. {@link RivalryLedger}. */
    public RivalryLedger rivalry() { return rivalry; }
    /** Трек L: помолвка, свидетели и свадьба у колокола; см. {@link WeddingLedger}. */
    public WeddingLedger wedding() { return wedding; }
    /** Трек N: особая ночь, беременность, роды и экран семьи; см. {@link ChildLedger}. */
    public ChildLedger children() { return children; }
    /** Трек M: пол, родители (мать первой) и стадия роста жителя с профилем — облик людей-жителей; рендер читает копию. */
    public record Lineage(Gender gender, List<UUID> parents, String stage) { }
    public synchronized Map<UUID, Lineage> lineages() {
        Map<UUID, Lineage> out = new HashMap<>();
        for (Map.Entry<String, Profile> e : state.profiles.entrySet()) {
            Profile p = e.getValue();
            if (p.social == null) continue;
            UUID id; try { id = UUID.fromString(e.getKey()); } catch (RuntimeException bad) { continue; }
            out.put(id, new Lineage(p.social.gender(), state.family.parents(id), p.social.stage == null ? "" : p.social.stage));
        }
        return out;
    }
    /** The preferences this store was opened with, for the social layer. */
    synchronized MobTalkConfig config() { return config; }
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
        if (p.backstory.isBlank()) {
            // No life story yet: the first reply invents one, so it needs the names already taken and the village (if any) around.
            detached.takenNames = givenNames(p.type);
            boolean villager = "minecraft:villager".equals(p.type), golem = "minecraft:iron_golem".equals(p.type);
            int[] anchor = villager || golem ? ConversationEngine.villageAnchor(snapshot) : null;
            if (anchor != null && detached.village == null) detached.village = villageFor(snapshot.dimension(), anchor[0], anchor[1], ConversationEngine.villageSite(snapshot));
            detached.founds = villager && (detached.village == null || detached.village.story().isBlank());
        }
        MobCard current = card(snapshot.id(), snapshot.playerId());
        detached.relationship = current.relationship();
        detached.recentEvents = current.events();
        Relation own = snapshot.playerId() == null ? null : p.relations.get(snapshot.playerId().toString());
        detached.hits = own == null ? 0 : own.hits;
        long now = System.currentTimeMillis();
        detached.bonds = state.relations.views(p.id, now);
        // What the village says: a villager nobody talked to yet picks up the loudest rumours and the player's fame at once.
        String villageId = detached.village != null ? detached.village.id() : p.villageId;
        if (villageId != null && !villageId.isBlank()) {
            detached.rumors = p.turns.isEmpty() ? state.rumors.newcomer(p.id, villageId, now) : state.rumors.heardBy(p.id, villageId, now);
            detached.repute = state.rumors.reputation(villageId, snapshot.playerId(), now);
            if (p.turns.isEmpty() && !detached.rumors.isEmpty()) scheduleSave();
        }
        // Whole and not under attack: the nerves settle a little, however the player is regarded.
        boolean attacked = snapshot.mobDetails().get("lastAttacker") instanceof String attacker && !"none".equals(attacker);
        if (snapshot.maxHealth() > 0 && snapshot.health() >= snapshot.maxHealth() && !attacked && p.state.safe(baseline(p, current.relationship()), now)) scheduleSave();
        detached.stateView = p.state.view(baseline(p, current.relationship()), now);
        SocialLedger.prepare(state, p, detached, own, snapshot, detached.stateView, config, now, children);
        return detached;
    }

    /** The current emotional state as this player sees it, or null for a stranger; cheap enough for a tick loop. */
    public synchronized MobState.View stateView(UUID mob, UUID player) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        if (p == null) return null;
        return p.state.view(baseline(p, relationship(mob, player)), System.currentTimeMillis());
    }
    static MobState.Baseline baseline(Profile p, int relationship) { return SocialLedger.baseline(p, MobState.Baseline.of(p.calm, p.confidence, p.curiosity, p.sociability, relationship)); }
    static void feel(Profile p, Relation r, MobState.Event event, long nowMillis) { p.state.shift(event, baseline(p, r.score), nowMillis); }

    /** Create/view a profile from immutable context without waiting for any disk operation. */
    public synchronized MobCard observe(MobSnapshot snapshot) { profile(snapshot); return card(snapshot.id(), snapshot.playerId()); }

    public synchronized void recordDamage(UUID mob, String type, String name, UUID player, String playerName, long nowMillis) {
        Profile p = ensure(mob, type, name); Relation r = relation(p, player);
        // A blow always ends a friendship or a truce: the first drops a stranger to −80, every later one leaves the mob displeased.
        r.score = r.hits++ == 0 ? Math.min(-80, r.score) : Math.max(-100, Math.min(r.score, 0) - 20);
        feel(p, r, MobState.Event.HIT, nowMillis);
        p.revision++;
        event(p, nowMillis, "Игрок " + named(player, playerName) + " ударил моба; отношение " + r.score, MemoryRanking.HIT); scheduleSave();
    }

    public synchronized void recordHelp(UUID mob, String type, String name, UUID player, String playerName, long nowMillis) {
        Profile p = ensure(mob, type, name); Relation r = relation(p, player);
        if (!cooledDown(r.lastHelp, nowMillis, 30_000)) return;
        r.lastHelp = nowMillis; r.score = clamp(r.score + 20, hostile(p)); feel(p, r, MobState.Event.HELP, nowMillis); p.revision++;
        event(p, nowMillis, "Игрок " + named(player, playerName) + " помог мобу; отношение " + r.score, MemoryRanking.HELP); scheduleSave();
    }

    /** A mob that saw what the player did to another: a smaller drop than the victim's, once per {@code WITNESS_COOLDOWN}, and a
     * memory of it. Only mobs with a profile notice; strangers are not worth one. */
    public static final long WITNESS_COOLDOWN = 5_000; // The default of MobTalkConfig.witnessCooldownSeconds.
    public synchronized void recordWitnessed(UUID mob, UUID player, String playerName, String seen, int delta, long nowMillis) {
        recordWitnessed(mob, null, null, player, playerName, seen, delta, null, nowMillis);
    }
    /** {@code type} non-null: a villager (or golem) that saw a misdeed gets a profile even as a stranger — a village remembers;
     * {@code felt} names the state shift, null derives it from {@code seen} (a killing or a blow). */
    public synchronized void recordWitnessed(UUID mob, String type, String name, UUID player, String playerName, String seen, int delta, MobState.Event felt, long nowMillis) {
        if (mob == null || player == null) return;
        Profile p = type != null ? ensure(mob, type, name) : state.profiles.get(mob.toString());
        if (p == null) return;
        Relation r = relation(p, player);
        int applied = delta;
        if (!cooledDown(r.lastWitness, nowMillis, config.witnessCooldownSeconds * 1000L)) {
            // Within the cooldown only something worse than what was already counted gets through — the kill after the blows, not the blows.
            if (delta >= r.lastWitnessDelta) return;
            applied = delta - r.lastWitnessDelta;
        }
        r.lastWitness = nowMillis; r.lastWitnessDelta = delta; r.score = clamp(r.score + applied, hostile(p));
        boolean kill = seen != null && seen.startsWith("убил");
        feel(p, r, felt != null ? felt : kill ? MobState.Event.WITNESSED_KILL : MobState.Event.WITNESSED_HIT, nowMillis);
        // No revision bump: what the mob saw happen to another must not cancel the reply it is giving right now.
        event(p, nowMillis, "Видел, как игрок " + named(player, playerName) + " " + seen + "; отношение " + r.score, kill ? MemoryRanking.WITNESSED_KILL : MemoryRanking.WITNESSED_HIT); scheduleSave();
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
        state.relations.remove(mob);
        state.rumors.remove(mob);
        state.notices.remove(mob);
        scheduleSave();
        return true;
    }
    // ---- Village rumours, see VillageRumors: the chronicle's input, its result, spreading, and what a mob knows. ----

    /** The id of the village this mob belongs to, or "". */
    public synchronized String villageOf(UUID mob) { Profile p = mob == null ? null : state.profiles.get(mob.toString()); return p == null || p.villageId == null ? "" : p.villageId; }
    /** When the player last talked with this mob, -1 for never. */
    public synchronized long lastTurnMillis(UUID mob) { Profile p = mob == null ? null : state.profiles.get(mob.toString()); return p == null ? -1 : p.lastTurnMillis; }
    /** The chronicle request's input when the village's chronicle is due (a new day, or enough importance since the last one):
     * the events and facts of at least {@link VillageRumors#MIN_IMPORTANCE} of every known inhabitant since then. The chronicle
     * counts as started once this returns non-null, whatever the request brings. */
    public synchronized VillageRumors.Input chronicleDue(String villageId, String playerName, long day, long now) {
        Village village = village(villageId);
        if (village == null) return null;
        long since = state.rumors.since(villageId);
        List<VillageRumors.Fact> facts = new ArrayList<>(); List<String> names = new ArrayList<>();
        for (Profile q : state.profiles.values()) {
            if (!villageId.equals(q.villageId)) continue;
            String who = q.givenName.isBlank() ? q.name : q.givenName;
            names.add(who);
            for (Event event : q.events) if (event.importance() >= VillageRumors.MIN_IMPORTANCE && event.time() > since) facts.add(new VillageRumors.Fact(who, event.text(), event.importance(), event.time()));
            for (MemoryFact fact : q.facts) if (!fact.reflection() && fact.importance() >= VillageRumors.MIN_IMPORTANCE && fact.time() > since) facts.add(new VillageRumors.Fact(who, fact.text(), fact.importance(), fact.time()));
        }
        VillageRumors.Input input = new VillageRumors.Input(villageId, village.name(), village.story(), names, facts, safePlayer(playerName), day, List.copyOf(state.players.values()));
        if (!state.rumors.due(villageId, day, input.importance(), !facts.isEmpty(), now)) return null;
        state.rumors.started(villageId, day, now); scheduleSave();
        return input;
    }
    /** Stores the rumours a chronicle request produced; each teller knows its own at once. */
    public synchronized int addRumors(String villageId, List<VillageRumors.View> rumors, long now) {
        Map<String, String> ids = new HashMap<>();
        for (Profile q : state.profiles.values()) if (villageId.equals(q.villageId)) ids.put(q.givenName.isBlank() ? q.name : q.givenName, q.id.toString());
        int added = state.rumors.add(villageId, rumors, ids, playerIdsByName(), now);
        if (added > 0) scheduleSave();
        return added;
    }
    /** Two inhabitants of one village stand together: rumours cross between them (faster between family and friends). */
    public synchronized boolean spreadRumors(UUID a, UUID b, java.util.function.DoubleSupplier roll, long now) {
        String village = villageOf(a);
        if (village.isBlank() || !village.equals(villageOf(b))) return false;
        boolean close = state.relations.views(a, now).stream().anyMatch(v -> v.other().equals(b) && (v.friend() || v.tags().contains(MobRelations.Tag.NEIGHBOR)));
        int crossed = state.rumors.spread(village, a, b, close, roll, now);
        if (crossed > 0) scheduleSave();
        return crossed > 0;
    }
    /** A wandering trader stood with a villager: it takes that village's rumours along, or hands over the ones it brought. */
    public synchronized boolean tradeRumors(UUID trader, UUID villager, long now) {
        String village = villageOf(villager);
        if (village.isBlank()) return false;
        int moved = state.rumors.deliver(trader, village, villager, now) + state.rumors.carry(trader, village, now);
        if (moved > 0) scheduleSave();
        return moved > 0;
    }
    /** What this mob knows of the village talk (or carries, for a trader), for the debug line. */
    public synchronized List<VillageRumors.View> rumorsOf(UUID mob, long now) {
        List<VillageRumors.View> carried = state.rumors.carriedBy(mob, now);
        return carried.isEmpty() ? state.rumors.heardBy(mob, villageOf(mob), now) : carried;
    }
    // ---- The god's deeds in the villages (idea Б1), see DivineTidings: the ledger and what one village makes of one deed. ----

    /** Whether the divine block's event is new; it counts as seen from now on. */
    public synchronized boolean divineFirst(String id, long now) { boolean first = state.divine.first(id, now); if (first) scheduleSave(); return first; }
    /** The newest event time the pull fallback has already read. */
    public synchronized long divinePulledSince() { return state.divine.pulledSince(); }
    public synchronized void divinePulled(long time) { if (time > state.divine.pulledSince()) { state.divine.pulled(time); scheduleSave(); } }

    /** What one village made of one deed: the rumour's id ("" none), the change to the blamed player's relationships there, and how
     * many punishments for that player fell on the village today (0 when the village does not know whom to blame). */
    public record DivineOutcome(String rumorId, int change, int wraths, String blamed) {
        public static final DivineOutcome NONE = new DivineOutcome("", 0, 0, "");
    }
    /** One village learns of one deed ({@link DivineTidings}). {@code known}: the village knows whose sins (or whose prayer) it was;
     * {@code far}: it only heard the news — a step quieter, nobody suffered or saw. {@code sufferers} and {@code witnesses}: the
     * villagers (UUID → the name the game shows) whose homes or fields lay in the deed's reach, and those who saw it fall on the
     * player. The rumour goes to every known resident; the relationship changes keep to the daily cap and, unless the god alone may
     * exile, never take a villager below {@link DivineTidings#RELATION_FLOOR}. */
    public synchronized DivineOutcome divineVillage(DivineTidings.Deed deed, String villageId, boolean known, boolean far,
                                                    Map<UUID, String> sufferers, Map<UUID, String> witnesses, long now) {
        Village village = village(villageId);
        if (village == null || deed == null) return DivineOutcome.NONE;
        MobTalkConfig tuning = Tuning.get();
        DivineTidings.Reaction reaction = DivineTidings.reaction(deed, tuning.divineStrength);
        if (far) reaction = reaction.far();
        if (reaction.importance() <= 0) return DivineOutcome.NONE;
        DivineTidings.Soul soul = deed.kind() == DivineTidings.Kind.MIRACLE ? deed.beneficiary() : deed.culprit();
        boolean named = known && soul != null && !soul.name().isBlank();
        String name = named ? soul.name() : "";
        if (named) named(soul.id(), soul.name());
        List<String> residents = new ArrayList<>();
        for (Profile q : state.profiles.values()) if (villageId.equals(q.villageId)) residents.add(q.id.toString());
        Map<UUID, String> everyone = new LinkedHashMap<>();
        if (!far) { if (witnesses != null) everyone.putAll(witnesses); if (sufferers != null) sufferers.forEach(everyone::putIfAbsent); }
        String teller = "", tellerId = "";
        for (Map.Entry<UUID, String> one : everyone.entrySet()) {
            Profile q = state.profiles.get(one.getKey().toString());
            teller = q == null ? one.getValue() : q.givenName.isBlank() ? q.name : q.givenName; tellerId = one.getKey().toString();
            break;
        }
        String text = DivineTidings.rumorText(deed, name);
        String rumorId = state.rumors.addDivine(villageId, deed.id(), text, teller, tellerId, name, named ? soul.id().toString() : "",
                reaction.importance(), named ? reaction.sign() : 0, residents, now);
        int change = 0, wraths = 0;
        if (named && !far) {
            UUID player = soul.id(); String pid = player.toString();
            Map<UUID, Integer> deltas = new LinkedHashMap<>();
            if (sufferers != null) for (UUID id : sufferers.keySet()) deltas.merge(id, reaction.sufferer(), DivineTidings::stronger);
            if (witnesses != null) for (UUID id : witnesses.keySet()) deltas.merge(id, reaction.witness(), DivineTidings::stronger);
            for (Map.Entry<UUID, Integer> one : deltas.entrySet()) {
                int delta = one.getValue();
                if (delta == 0 || one.getKey().equals(player)) continue;
                Profile p = ensure(one.getKey(), "minecraft:villager", everyone.getOrDefault(one.getKey(), ""));
                if (p.villageId.isBlank()) p.villageId = villageId;
                Relation r = relation(p, player);
                int applied = delta;
                if (delta < 0) {
                    applied = -state.divine.allowance(villageId, pid, -delta, tuning.divineDailyCap, now);
                    if (!tuning.divineMayExile) applied = Math.max(applied, Math.min(0, DivineTidings.RELATION_FLOOR - r.score));
                }
                int before = r.score;
                r.score = clamp(r.score + applied, hostile(p));
                applied = r.score - before;
                boolean suffered = sufferers != null && sufferers.containsKey(one.getKey());
                event(p, now, (suffered ? "Пострадал: " : "Видел своими глазами: ") + text, MemoryFact.ORDINARY);
                if (applied == 0) continue;
                if (applied < 0) state.divine.lose(villageId, pid, -applied, now);
                r.divine += applied;
                state.divine.record(deed.id(), pid, one.getKey().toString(), applied, now);
                feel(p, r, applied < 0 ? MobState.Event.WITNESSED_HIT : MobState.Event.KIND, now);
                change += applied;
            }
            if (deed.kind().wrath() && deed.kind() != DivineTidings.Kind.BAN) wraths = state.divine.punished(villageId, pid, now);
        }
        scheduleSave();
        return new DivineOutcome(rumorId, change, wraths, name);
    }
    /** After every village had its say: a curse hangs over the villages it reached; a miracle blesses its beneficiary; penance gives
     * back half of what the penitent's punishments of the last game day cost, lifts their curses and dismisses their assemblies; a
     * curse that ended leaves its villages. Returns the villages whose curse lifted (for the «слава Богу» in the street). */
    public synchronized List<String> divineSettle(DivineTidings.Deed deed, List<String> villages, long now) {
        List<String> lifted = new ArrayList<>();
        switch (deed.kind()) {
            case WRATH, BOSS -> state.divine.curse(deed, villages == null ? List.of() : villages, now);
            case MIRACLE -> { if (deed.beneficiary() != null) state.divine.bless(deed.beneficiary().id(), deed.title(), now); }
            case FORGIVEN -> {
                for (String id : deed.of()) lifted.addAll(state.divine.lift(id));
                if (deed.culprit() != null) {
                    UUID player = deed.culprit().id();
                    for (DivineTidings.Delta d : state.divine.restore(player.toString(), now)) {
                        Profile q = state.profiles.get(d.mob);
                        Relation r = q == null ? null : q.relations.get(d.player);
                        if (r == null) continue;
                        int back = -d.delta / 2, before = r.score;
                        r.score = clamp(r.score + back, hostile(q));
                        r.divine += r.score - before;
                        q.revision++;
                    }
                    lifted.addAll(state.divine.liftAll(player.toString()));
                    state.divine.dismiss(player);
                }
            }
            case CURSE_END -> { lifted.addAll(state.divine.lift(deed.id())); for (String id : deed.of()) lifted.addAll(state.divine.lift(id)); }
            default -> { }
        }
        state.divine.trim(now);
        scheduleSave();
        return lifted.stream().distinct().toList();
    }
    /** The curse hanging over this village now, or null. */
    public synchronized DivineTidings.CurseView divineCurse(String villageId, long now) { return villageId == null ? null : state.divine.curseOn(villageId, now); }
    /** A villager died while a curse hung over its village: its living relatives blame the sinner hard (unless the setting is off).
     * Returns the sinner's name, or "" when no known curse explains the death. */
    public synchronized String divineMourn(UUID dead, String villageId, String deadName, long now) {
        DivineTidings.CurseView curse = divineCurse(villageId, now);
        if (curse == null || !curse.known() || dead == null) return "";
        UUID player;
        try { player = UUID.fromString(curse.culpritId()); } catch (IllegalArgumentException bad) { return ""; }
        String text = deadName + " погиб во время божьей кары «" + curse.title() + "» за грехи игрока " + curse.culpritName();
        Profile deadProfile = state.profiles.get(dead.toString());
        if (deadProfile != null) event(deadProfile, now, text, MemoryRanking.WITNESSED_KILL);
        if (!Tuning.get().divineMourners) { scheduleSave(); return curse.culpritName(); }
        for (FamilyTree.Kin kin : state.family.kin(dead)) {
            if (kin.player() || kin.dead()) continue;
            Profile q = state.profiles.get(kin.id().toString());
            if (q == null) continue;
            Relation r = relation(q, player);
            int applied = DivineTidings.MOURNER_DELTA;
            if (!Tuning.get().divineMayExile) applied = Math.max(applied, Math.min(0, DivineTidings.RELATION_FLOOR - r.score));
            int before = r.score;
            r.score = clamp(r.score + applied, hostile(q));
            r.divine += r.score - before;
            feel(q, r, MobState.Event.WITNESSED_KILL, now);
            event(q, now, "Мой " + kin.word() + " " + deadName + " погиб во время кары за грехи игрока " + curse.culpritName(), MemoryRanking.WITNESSED_KILL);
            q.revision++;
        }
        scheduleSave();
        return curse.culpritName();
    }
    /** The bell assembly: opened (true when new), told, decided; and what waits. */
    public synchronized boolean divineAssemble(String villageId, UUID player, String name, long deadline) { boolean fresh = state.divine.assemble(villageId, player, name, deadline); if (fresh) scheduleSave(); return fresh; }
    public synchronized void divineAnnounced(String villageId, UUID player) { state.divine.announced(villageId, player); scheduleSave(); }
    public synchronized void divineExile(String villageId, UUID player) { state.divine.exile(villageId, player); scheduleSave(); }
    public synchronized List<DivineTidings.AssemblyView> divineAssemblies() { return state.divine.assemblies(); }
    /** Whether the bell assembly drove this player out of the village. */
    public synchronized boolean divineExiled(String villageId, UUID player) { return state.divine.exiled(villageId, player); }

    // ---- Village small talk and announcements, see VillageChatter and VillageNotices. ----

    /** Two villagers about to talk: both get a profile on the spot (name, sex, character and voice from the social layer, the
     * village when one is known), the topic is chosen from what they know. {@code a} tells the rumour when one of them has one
     * the other has not; the two may be swapped for that. */
    public synchronized VillageChatter.Input chatterInput(VillageChatter.Seat first, VillageChatter.Seat second, String villageKey, String villageName,
                                                          String playerName, String weather, java.util.function.DoubleSupplier roll, long now) {
        Profile p = chatterProfile(first, villageKey), q = chatterProfile(second, villageKey);
        String village = p.villageId.isBlank() ? q.villageId : p.villageId;
        VillageRumors.View told = state.rumors.untold(village, p.id, q.id, now);
        if (told == null) {
            VillageRumors.View back = state.rumors.untold(village, q.id, p.id, now);
            if (back != null) { Profile swap = p; p = q; q = swap; told = back; }
        }
        VillageChatter.Speaker a = speaker(p), b = speaker(q);
        String recent = p.events.isEmpty() ? "" : p.events.getLast().text();
        String repute = village.isBlank() ? "" : state.rumors.reputation(village, playerByName(playerName), now).describe();
        if (repute.indexOf(':') >= 0) repute = repute.substring(0, repute.indexOf(':'));
        UUID other = q.id;
        boolean acquainted = state.relations.views(p.id, now).stream().anyMatch(v -> v.other().equals(other));
        VillageRumors.View god = village.isBlank() || !Tuning.get().divineTidings ? null : state.rumors.latestDivine(village, VillageChatter.GOD_TOPIC_MILLIS, now);
        VillageChatter.Subject subject = VillageChatter.subject(a, b, new VillageChatter.Facts(told, repute, weather, recent, god == null ? "" : god.text()), roll);
        scheduleSave();
        return new VillageChatter.Input(villageKey, villageName, a, b, subject, safePlayer(playerName), weather, acquainted);
    }
    private Profile chatterProfile(VillageChatter.Seat seat, String villageKey) {
        Profile p = ensure(seat.id(), seat.type(), seat.observedName());
        if (p.villageId.isBlank() && villageKey != null && state.villages.containsKey(villageKey)) p.villageId = villageKey;
        return p;
    }
    private static VillageChatter.Speaker speaker(Profile p) {
        Gender gender = p.social != null ? p.social.gender() : SocialProfile.genderOf(p.id);
        return new VillageChatter.Speaker(p.id, p.givenName.isBlank() ? p.name : p.givenName, gender, p.name, p.social == null ? "" : p.social.persona().word, p.voiceId);
    }
    /** The voice of this mob's profile, "" without one. */
    public synchronized String voiceOf(UUID mob) { Profile p = mob == null ? null : state.profiles.get(mob.toString()); return p == null ? "" : p.voiceId; }
    /** The talk was heard: the two know each other a little better, the rumour passed, and each remembers whom it talked to. */
    public synchronized void recordChatter(VillageChatter.Input input, List<VillageChatter.Line> lines, long now) {
        Profile p = state.profiles.get(input.a().id().toString()), q = state.profiles.get(input.b().id().toString());
        if (p == null || q == null || lines == null || lines.isEmpty()) return;
        MobRelations.Kinship kinship = MobRelations.kinship(p.type, p.villageId, q.type, q.villageId);
        state.relations.together(p.id, input.a().name(), p.type, q.id, input.b().name(), q.type, kinship, VillageChatter.CHAT_GAIN, VillageChatter.CHAT_INTERVAL, now);
        if (input.subject().topic() == VillageChatter.Topic.RUMOR) state.rumors.tell(q.id, input.subject().rumorId());
        String said = TextBounds.characters(lines.getFirst().text(), 120);
        event(p, now, "Болтал с " + input.b().name() + ": «" + said + "»", MemoryFact.ORDINARY - 1);
        event(q, now, "Болтал с " + input.a().name() + ", тот сказал: «" + said + "»", MemoryFact.ORDINARY - 1);
        scheduleSave();
    }

    /** What a villager's next chat announcement needs; null for a stranger. {@code work} is the item drawn for a call to work. */
    public synchronized VillageNotices.Input noticeInput(UUID mob, UUID player, String playerName, VillageNotices.Kind kind, VillageNotices.Work work, long now) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        if (p == null) return null;
        Village village = village(p.villageId);
        List<String> rumors = VillageRumors.lines(state.rumors.heardBy(p.id, p.villageId, now));
        List<String> events = p.events.stream().skip(Math.max(0, p.events.size() - 3)).map(Event::text).toList();
        VillageChatter.Speaker self = speaker(p);
        return new VillageNotices.Input(p.id, player, self.name(), self.gender(), p.name, self.persona(), village == null ? "" : village.name(), safePlayer(playerName),
                kind, work, p.wish, rumors, events, state.notices.earlier(mob));
    }
    /** When this villager last wrote to the chat, −1 for never. */
    public synchronized long lastNotice(UUID mob) { return state.notices.last(mob); }
    /** Whether this villager is waiting for this player to come over for work. */
    public synchronized boolean invited(UUID mob, UUID player, long now) { return state.notices.invited(mob, player, now); }
    /** The announcement went to the chat: the ledger keeps it (and opens the call to work), the villager remembers it. */
    public synchronized void recordNotice(VillageNotices.Input input, String text, long now) {
        Profile p = state.profiles.get(input.mob().toString());
        if (p == null || text == null || text.isBlank()) return;
        state.notices.record(input, text, now);
        boolean work = input.kind() == VillageNotices.Kind.WORK;
        event(p, now, (work ? "Позвал игрока " + input.playerName() + " в чате, есть для него работа: «" : "Написал в чат деревни: «")
                + TextBounds.characters(text, 160) + "»", work ? MemoryFact.ORDINARY : MemoryFact.ORDINARY - 1);
        scheduleSave();
    }
    /** The open calls to work for this player. */
    public synchronized List<VillageNotices.Invitation> invitations(UUID player, long now) { return state.notices.open(player, now); }
    /** The call is answered (or no longer can be): it leaves the ledger. */
    public synchronized VillageNotices.Invitation takeInvitation(UUID mob, UUID player) {
        VillageNotices.Invitation taken = state.notices.take(mob, player);
        if (taken != null) scheduleSave();
        return taken;
    }

    /** What a decider sees of this mob right now; null for a stranger. {@code cue} for a creature speaking first, {@code speaker}/{@code said}
     * for one that heard something. */
    public synchronized DecisionState decision(UUID mob, UUID player, Initiative.Cue cue, String speaker, String said, boolean eventful) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        if (p == null) return null;
        int score = relationship(mob, player);
        long now = System.currentTimeMillis();
        String name = p.givenName.isBlank() ? p.name : p.givenName, species = SpeciesCatalog.get(p.type).name();
        List<String> events = p.events.stream().map(e -> e.text).toList();
        return cue != null ? DecisionState.forCue(mob, name, species, p.state.view(baseline(p, score), now), p.sociability, p.curiosity, score, state.relations.views(mob, now), cue, events)
                : DecisionState.forHeard(mob, name, species, p.state.view(baseline(p, score), now), p.sociability, p.curiosity, score, state.relations.views(mob, now), speaker, said, eventful, events);
    }
    // ---- What mobs think of each other, see MobRelations: reads give detached views, writes run under the lock. ----
    public synchronized List<MobRelations.View> bonds(UUID mob, long nowMillis) { return state.relations.views(mob, nowMillis); }
    /** Runs one change against the relations under the lock; a change that reports true is saved. */
    public synchronized boolean updateRelations(java.util.function.Predicate<MobRelations> change) { boolean changed = change.test(state.relations); if (changed) scheduleSave(); return changed; }
    /** Family for two mobs of one village, kin for one species, nothing for strangers or a mob without a profile. */
    public synchronized MobRelations.Kinship kinship(UUID a, UUID b) {
        Profile p = a == null ? null : state.profiles.get(a.toString()), q = b == null ? null : state.profiles.get(b.toString());
        return p == null || q == null ? MobRelations.Kinship.NONE : MobRelations.kinship(p.type, p.villageId, q.type, q.villageId);
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
        feel(p, r, MobState.Event.CONCESSION, nowMillis);
        p.revision++; scheduleSave();
    }
    /** Opens a secret deal for this player (already checked with Trading.canUnlock) and records an event. */
    public synchronized void unlockDeal(UUID mob, UUID player, String dealId, long nowMillis) {
        Profile p = require(mob); Relation r = tradingRelation(p, player);
        TradeState state = r.deals.getOrDefault(dealId, TradeState.initial());
        if (state.unlocked()) return;
        r.deals.put(dealId, new TradeState(state.price(), true, state.rounds(), state.sessionStartMillis(), state.boughtToday(), state.boughtDay()));
        p.revision++;
        event(p, nowMillis, "Открыл игроку секретную сделку «" + dealName(p, dealId) + "»", MemoryRanking.UNLOCK); scheduleSave();
    }
    /** Records a completed purchase on {@code worldDay}: stock, a memory event and a small relationship gain. */
    public synchronized void recordPurchase(UUID mob, UUID player, String dealId, String playerName, long worldDay, long nowMillis) {
        Profile p = require(mob); Relation r = tradingRelation(p, player);
        TradeState before = r.deals.getOrDefault(dealId, TradeState.initial());
        r.deals.put(dealId, Trading.bought(before, worldDay));
        if (cooledDown(r.lastPurchase, nowMillis, 60_000)) { r.lastPurchase = nowMillis; r.score = clamp(r.score + 5, hostile(p)); }
        feel(p, r, MobState.Event.PURCHASE, nowMillis);
        p.revision++;
        TradeDeal deal = TradeCatalog.deal(p.type, dealId);
        String what = deal == null ? "товар «" + dealId + "»"
                : deal.sell().count() + "× " + deal.sell().name() + " за " + Trading.currentPrice(deal, before) + "× " + deal.price().name();
        event(p, nowMillis, "Игрок " + named(player, playerName) + " купил " + what + "; отношение " + r.score, MemoryRanking.PURCHASE); scheduleSave();
    }
    /** The player bought what the villager promised in conversation ({@link SpokenOffer}) in its vanilla window: a kept bargain —
     * the same small gain and warmth as a catalog purchase, and a memory of what changed hands. */
    public synchronized void recordTradeDone(UUID mob, UUID player, String playerName, String what, long nowMillis) {
        Profile p = require(mob); Relation r = tradingRelation(p, player);
        if (cooledDown(r.lastPurchase, nowMillis, 60_000)) { r.lastPurchase = nowMillis; r.score = clamp(r.score + 5, hostile(p)); }
        feel(p, r, MobState.Event.PURCHASE, nowMillis);
        p.revision++;
        event(p, nowMillis, "Игрок " + named(player, playerName) + " купил по договорённости " + TextBounds.characters(what, 200) + "; отношение " + r.score); scheduleSave();
    }
    private Relation tradingRelation(Profile p, UUID player) {
        if (player == null) throw new IllegalArgumentException("Сделки и задания требуют игрока.");
        return relation(p, player);
    }
    private static String dealName(Profile p, String dealId) { TradeDeal deal = TradeCatalog.deal(p.type, dealId); return deal == null ? String.valueOf(dealId) : deal.name(); }
    // ---- Generated life stories: one per mob at first meeting, one per village at its first villager. ----

    /** A village the player has met, anchored where its first villager stood (or its bell, or the centre of its structure).
     * {@code site} names the village structure it was founded in ("ctov:large/village_plains@12,-40"), "" for villages founded
     * before sites were known or outside any structure. */
    public record Village(String id, String dimension, int x, int z, String name, String story, String site) {
        public Village { site = site == null ? "" : site; story = story == null ? "" : story; name = name == null ? "" : name; }
    }
    /** The village structure a villager lives in, as the game read it: a key unique in the world, the structure's centre and its
     * bounds on the ground. */
    public record Site(String key, int x, int z, int minX, int minZ, int maxX, int maxZ) {
        public boolean holds(int px, int pz) { return holds(px, pz, 0); }
        /** Within the bounds widened by {@code margin} blocks on every side. */
        public boolean holds(int px, int pz, int margin) { return px >= minX - margin && px <= maxX + margin && pz >= minZ - margin && pz <= maxZ + margin; }
    }
    /** An old village without a site is the village of a structure when its anchor lies this close to the structure's bounds: its
     * first villager may have stood in the fields or on a path just outside the houses. */
    static final int ADOPT_MARGIN = 32;
    /** How far (blocks, same dimension) a villager may stand from a village's anchor and still belong to it. */
    public static final int VILLAGE_RADIUS = 160;

    public synchronized boolean hasBackstory(UUID mob) { Profile p = state.profiles.get(mob.toString()); return p != null && !p.backstory.isBlank(); }
    /** The model's invented name and story for this mob; blank parts stay blank. villageId may be "". */
    public synchronized void setBackstory(UUID mob, String givenName, String story, String wish, String hidden, String villageId) {
        Profile p = require(mob);
        // An explicit name (the model's story, the card in F7) replaces the one the social layer seeded; the prompt asks the model to keep
        // it, and a name of the other sex never goes on a villager (its body and voice follow its sex: «Валентина» with a man's voice).
        boolean otherSex = p.social != null && VillagerNames.sexOf(givenName) == p.social.gender().opposite();
        if (givenName != null && !givenName.isBlank() && !otherSex || p.social == null) p.givenName = TextBounds.characters(givenName, 60);
        p.backstory = TextBounds.characters(story, 1500);
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

    /** The village around a point forgives the player: residents' relationships below zero go to zero, blows and witnessed deeds
     * are forgotten, and the village's rumours and what its residents heard are dropped. Returns a line for the chat. */
    public synchronized String forgiveVillage(String dimension, int x, int z, UUID player) { return forgiveVillage(villageNear(dimension, x, z), player); }
    /** As above for a village the game already found (by its structure); null: no known village around. */
    public synchronized String forgiveVillage(Village village, UUID player) {
        if (village == null || player == null) return "Рядом нет известной деревни";
        int residents = 0, forgiven = 0;
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, Profile> entry : state.profiles.entrySet()) {
            Profile p = entry.getValue();
            if (!village.id().equals(p.villageId) || !"minecraft:villager".equals(p.type)) continue;
            residents++; ids.add(entry.getKey());
            Relation r = p.relations.get(player.toString());
            if (r == null) continue;
            if (r.score < 0) { r.score = 0; forgiven++; }
            r.hits = 0; r.lastWitnessDelta = 0; r.lastWitness = -1; r.divine = 0;
            p.revision++;
        }
        state.rumors.forgetVillage(village.id(), ids);
        state.divine.forgive(village.id(), player);
        scheduleSave();
        return "Деревня «" + village.name() + "» простила: жителей " + residents + ", обид снято " + forgiven + ", слухи забыты";
    }
    public synchronized List<String> villageNames() { return state.villages.values().stream().map(Village::name).toList(); }
    /** Words Whisper should expect: the villages' names and the given names of known creatures, the most recently spoken to first,
     * {@code priority} ahead of all. Whisper's prompt is short, so the list is cut to {@code budget} characters at whole names. */
    public synchronized String hotwords(List<String> priority, int budget) {
        LinkedHashSet<String> words = new LinkedHashSet<>();
        if (priority != null) for (String word : priority) if (word != null && !word.isBlank()) words.add(word.strip());
        for (Village village : state.villages.values()) if (village.name() != null && !village.name().isBlank()) words.add(village.name().strip());
        state.profiles.values().stream().filter(p -> p.givenName != null && !p.givenName.isBlank())
                .sorted(Comparator.comparingLong((Profile p) -> -p.lastTurnMillis)).forEach(p -> words.add(p.givenName.strip()));
        StringBuilder text = new StringBuilder();
        for (String word : words) {
            if (text.length() + word.length() + 2 > budget) break;
            if (text.length() > 0) text.append(", ");
            text.append(word);
        }
        return text.toString();
    }
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
    /** The village of a resident whose structure is known: the one founded in that structure wherever its anchor is (a large
     * village does not split); else an old village without a site whose anchor lies in (or just by) this structure, which takes
     * the site from now on; else an old village without a site within the radius, by the old rule, so old worlds keep their
     * villages. A village founded in another structure is a neighbour, not this one (null: the resident founds its own).
     * Without a site, as {@link #villageNear}. */
    public synchronized Village villageFor(String dimension, int x, int z, Site site) { return villageFor(dimension, x, z, site, true); }
    /** As above; {@code adopt} false only looks (a lookup by position never changes the memory, only a resident does). */
    public synchronized Village villageFor(String dimension, int x, int z, Site site, boolean adopt) {
        if (site == null || site.key() == null || site.key().isBlank()) return villageNear(dimension, x, z);
        Village legacy = null; long best = Long.MAX_VALUE;
        for (Village village : state.villages.values()) {
            if (!village.dimension().equals(dimension)) continue;
            if (site.key().equals(village.site())) return village;
            long dx = village.x() - x, dz = village.z() - z, distance = dx * dx + dz * dz;
            if (village.site().isBlank() && site.holds(village.x(), village.z(), ADOPT_MARGIN) && distance < best) { legacy = village; best = distance; }
        }
        if (legacy != null) {
            if (!adopt) return legacy;
            Village adopted = new Village(legacy.id(), legacy.dimension(), legacy.x(), legacy.z(), legacy.name(), legacy.story(), site.key());
            state.villages.put(adopted.id(), adopted); scheduleSave();
            return adopted;
        }
        Village near = villageNear(dimension, x, z);
        return near != null && near.site().isBlank() ? near : null;
    }
    /** A village the god's deed founded before anyone talked there (no story yet) takes the name and story the first villager's
     * biography wrote; any other village keeps its own. Returns the village as it is now. */
    public synchronized Village nameVillage(String id, String name, String story) {
        Village village = village(id);
        if (village == null || !village.story().isBlank() || story == null || story.isBlank()) return village;
        Village named = new Village(village.id(), village.dimension(), village.x(), village.z(),
                TextBounds.characters(name == null || name.isBlank() ? village.name() : name, 60), TextBounds.characters(story, 1500), village.site());
        state.villages.put(named.id(), named); scheduleSave();
        return named;
    }
    public synchronized Village createVillage(String dimension, int x, int z, String name, String story) { return createVillage(dimension, x, z, name, story, ""); }
    public synchronized Village createVillage(String dimension, int x, int z, String name, String story, String site) {
        Village village = new Village("village-" + UUID.randomUUID().toString().substring(0, 8), dimension, x, z,
                TextBounds.characters(name == null || name.isBlank() ? "Безымянная деревня" : name, 60), TextBounds.characters(story, 1500), site);
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
        event(p, nowMillis, "Попросил игрока об услуге: " + r.favor.title() + " (" + favor.count() + "× " + favor.item() + ")", MemoryRanking.FAVOR_ASKED); scheduleSave();
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
        feel(p, r, MobState.Event.FAVOR_DONE, nowMillis);
        TradeDeal promised = TradeCatalog.deal(p.type, favor.rewardDeal());
        if (promised != null) {
            TradeState s = r.deals.getOrDefault(promised.id(), TradeState.initial());
            int paying = Trading.currentPrice(promised, s);
            int lowered = favor.rewardPercent() > 0 ? Math.max(promised.floorPrice(), paying * (100 - favor.rewardPercent()) / 100) : paying;
            r.deals.put(promised.id(), new TradeState(lowered < paying ? lowered : s.price(), s.unlocked() || promised.secret(), s.rounds(), s.sessionStartMillis(), s.boughtToday(), s.boughtDay()));
        }
        p.revision++;
        event(p, nowMillis, "Игрок " + named(player, playerName) + " выполнил услугу: " + favor.title() + "; отношение " + r.score, MemoryRanking.FAVOR_DONE); scheduleSave();
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
        int score = r == null ? (hostile(p) ? -80 : 0) : r.score;
        return new MobCard(mob, p.type, p.name, p.personality, p.voiceId, p.variantSlot,
                score, p.memory, p.turns,
                eventsForPlayer(p, player), p.revision, p.action,
                p.calm, p.confidence, p.curiosity, p.sociability, p.preference, p.fear, p.customPersonality,
                p.state.view(baseline(p, score), System.currentTimeMillis()),
                p.social == null || p.social.age() == null ? "" : p.social.age().word(p.social.gender()));
    }
    public synchronized List<MobCard> cards(UUID player) { return state.profiles.keySet().stream().map(UUID::fromString).map(id -> card(id, player)).toList(); }

    // ---- Для веб-админки (трек H): чтение без игры и правка карточки. ----

    /** Something that happened to a mob, with its time. */
    public record Happening(long time, String text) { }
    /** Every mob the world remembers. */
    public synchronized List<UUID> knownMobs() { return state.profiles.keySet().stream().map(UUID::fromString).toList(); }
    /** The mob's relation score with every player it knows, by player UUID. */
    public synchronized Map<UUID, Integer> relations(UUID mob) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        Map<UUID, Integer> out = new LinkedHashMap<>();
        if (p != null) p.relations.forEach((id, r) -> { try { out.put(UUID.fromString(id), r.score); } catch (IllegalArgumentException ignored) { } });
        return out;
    }
    /** The mob's events newest first, with times. */
    public synchronized List<Happening> happenings(UUID mob, int limit) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        if (p == null) return List.of();
        List<Happening> out = new ArrayList<>();
        for (int i = p.events.size() - 1; i >= 0 && out.size() < limit; i--) out.add(new Happening(p.events.get(i).time(), p.events.get(i).text()));
        return out;
    }
    /** A word for the mob's character: the villager's first trait or the custom personality; "" for a stranger. */
    public synchronized String traitOf(UUID mob) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        if (p == null) return "";
        if (p.social != null && p.social.traits() != null && !p.social.traits().isEmpty()) return p.social.traits().stream().filter(trait -> trait.spoken && !trait.fact.isBlank()).map(trait -> trait.fact).findFirst().orElse("");
        return p.customPersonality == null ? "" : p.customPersonality;
    }
    public synchronized String givenNameOf(UUID mob) { Profile p = mob == null ? null : state.profiles.get(mob.toString()); return p == null ? "" : p.givenName.isBlank() ? p.name : p.givenName; }
    public synchronized String typeOf(UUID mob) { Profile p = mob == null ? null : state.profiles.get(mob.toString()); return p == null ? "" : p.type == null ? "" : p.type; }
    public synchronized String backstoryOf(UUID mob) { Profile p = mob == null ? null : state.profiles.get(mob.toString()); return p == null ? "" : p.backstory; }
    /** Relatives from the family tree (spouse, children, parents), empty outside the social layer. */
    public synchronized List<FamilyTree.Kin> kin(UUID mob) { return mob == null ? List.of() : state.family.kin(mob); }
    public synchronized UUID spouseOf(UUID mob) { return mob == null ? null : state.family.spouse(mob); }
    public synchronized List<UUID> childrenOf(UUID mob) { return mob == null ? List.of() : state.family.children(mob); }
    /** Every village the world knows. */
    public synchronized List<Village> villages() { return List.copyOf(state.villages.values()); }
    /** How many mobs live in a village. */
    public synchronized int residents(String villageId) {
        int n = 0;
        for (Profile p : state.profiles.values()) if (villageId != null && villageId.equals(p.villageId)) n++;
        return n;
    }
    /** What the village says right now, weightiest first. */
    public synchronized List<VillageRumors.View> villageRumors(String villageId, long now) { return villageId == null ? List.of() : state.rumors.village(villageId, now); }
    /** A player's fame in a village from its rumours. */
    public synchronized VillageRumors.Reputation reputation(String villageId, UUID player, long now) {
        return villageId == null ? VillageRumors.Reputation.NONE : state.rumors.reputation(villageId, player, now);
    }
    /** The admin edits a mob's card; blank fields are left as they are. */
    public synchronized void editCard(UUID mob, String name, String personality, String voiceId, String memory) {
        Profile p = require(mob);
        if (name != null && !name.isBlank()) p.givenName = TextBounds.characters(name.strip(), 40);
        if (personality != null && !personality.isBlank()) p.customPersonality = TextBounds.characters(personality.strip(), 600);
        if (voiceId != null && !voiceId.isBlank() && voiceId.matches("[A-Za-z0-9_.:-]{1,64}")) p.voiceId = voiceId;
        if (memory != null) {
            p.memory = TextBounds.characters(memory.strip(), 4000);
            p.facts.clear();
            for (String line : p.memory.split("\n")) if (!line.isBlank()) p.facts.add(new MemoryFact(line.strip(), MemoryFact.ORDINARY, System.currentTimeMillis(), false));
        }
        p.revision++; scheduleSave();
    }
    /** State and memory back to a fresh mob; the name, species and personality stay. */
    public synchronized void resetMob(UUID mob) {
        Profile p = require(mob);
        p.memory = ""; p.facts.clear(); p.summaries.clear(); p.turns.clear(); p.turnsDropped = 0; p.summarizedTurns = 0;
        p.events.clear(); p.relations.clear(); p.state = new MobState(); p.reflection = new Reflection.Counters();
        p.revision++; scheduleSave();
    }

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
        if (memory != null) { p.facts = MemoryRanking.edited(p.facts, TextBounds.characters(memory, 4000), System.currentTimeMillis()); p.memory = MemoryRanking.render(p.facts); }
        p.revision++; scheduleSave();
    }
    /** Forgets the exchanges and the summaries made of them; the facts stay. */
    public synchronized void clearHistory(UUID mob) {
        Profile p = require(mob); p.turnsDropped += p.turns.size(); p.turns.clear(); p.summarizedTurns = p.turnsDropped; p.summaries.clear(); p.revision++; scheduleSave();
    }
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
        return commit(snapshot, user, reply, memory, social, action, nowMillis, successfulVoice, "");
    }

    /** {@code thought}: what the mob said to itself in this answer; it colours the next one. The exchange itself, and the
     * player's words, move the emotional state along with the score. */
    synchronized boolean commit(MobSnapshot snapshot, String user, String reply, String memory, SocialIntent social,
                                ActionIntent action, long nowMillis, String successfulVoice, String thought) {
        return commit(snapshot, user, reply, memory, social, action, nowMillis, successfulVoice, thought, null);
    }

    /** {@code fact}: the new fact of this answer with its importance, kept by {@link MemoryRanking}; null when the answer added none.
     * {@code memory}: a whole memory text from an older or fallback answer, replacing the facts when it differs; null for a newFacts answer. */
    synchronized boolean commit(MobSnapshot snapshot, String user, String reply, String memory, SocialIntent social,
                                ActionIntent action, long nowMillis, String successfulVoice, String thought, MemoryFact fact) {
        Profile p = require(snapshot.id());
        // The revision may have moved while the model wrote (a blow, a gift): the exchange is still recorded; the next turn sees the change.
        if (successfulVoice != null && OpenRouterSpeechClient.validVoiceId(successfulVoice)) p.voiceId = successfulVoice.strip();
        p.turns.add(new Turn(TextBounds.characters(user, 2000), TextBounds.characters(reply, 8000)));
        while (p.turns.size() > 100) { p.turns.removeFirst(); p.turnsDropped++; }
        p.lastTurnMillis = nowMillis;
        int limit = Math.max(200, Math.min(4000, config.maxMemoryChars));
        if (fact != null) { MemoryRanking.remember(p.facts, fact, limit, nowMillis); p.memory = MemoryRanking.render(p.facts); }
        else if (memory != null && !TextBounds.characters(memory, limit).equals(TextBounds.characters(p.memory, limit))) {
            p.memory = TextBounds.characters(memory, limit); p.facts = MemoryRanking.parse(p.memory, nowMillis);
        }
        p.reflection.reply(fact == null ? MemoryFact.ORDINARY : Math.max(MemoryFact.ORDINARY, fact.importance()));
        if (social == SocialIntent.THREAT) p.reflection.add(MemoryRanking.THREAT);
        p.state.think(thought);
        // Action state is persisted only by recordAction after authoritative native validation.
        if (snapshot.playerId() != null) {
            Relation r = relation(p, snapshot.playerId());
            if (social == SocialIntent.APOLOGY && cooledDown(r.lastApology, nowMillis, 60_000)) { r.lastApology = nowMillis; r.score = clamp(r.score + 5, hostile(p)); }
            else if (social == SocialIntent.INSULT && cooledDown(r.lastInsult, nowMillis, 60_000)) { r.lastInsult = nowMillis; r.score = clamp(r.score - 10, hostile(p)); }
            // A spoken threat to kill the mob or those it guards weighs more than an insult: two of them let a golem fight back.
            else if (social == SocialIntent.THREAT && cooledDown(r.lastThreat, nowMillis, 20_000)) { r.lastThreat = nowMillis; r.score = clamp(r.score - 25, hostile(p)); }
            // KIND speech never substitutes for an observed helpful action.
            feel(p, r, MobState.Event.TALK, nowMillis);
            MobState.Event felt = switch (social) {
                case APOLOGY -> MobState.Event.APOLOGY; case KIND -> MobState.Event.KIND;
                case INSULT -> MobState.Event.INSULT; case THREAT -> MobState.Event.THREAT; case NONE -> null;
            };
            if (felt != null) feel(p, r, felt, nowMillis);
        }
        scheduleSave(); return true;
    }

    Profile ensure(UUID id, String type, String observedName) {
        Profile p = state.profiles.get(id.toString());
        if (p != null) { if (ProfileDefaults.refresh(p, state.profiles, config) | SocialLedger.adopt(p, config, state.profiles.values())) scheduleSave(); return p; }
        SpeciesCatalog.Species species = SpeciesCatalog.effective(type, config);
        p = new Profile(); p.id = id; p.type = species.id(); p.name = TextBounds.characters(observedName, 120);
        ProfileDefaults.refresh(p, state.profiles, config);
        SocialLedger.adopt(p, config, state.profiles.values());
        state.profiles.put(id.toString(), p); scheduleSave(); return p;
    }

    Relation relation(Profile p, UUID player) {
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
    /** The name for a memory line, and the world learns which player goes by it (rumours name players, {@link #playerIdsByName}). */
    private String named(UUID player, String playerName) {
        String name = safePlayer(playerName);
        if (player != null && playerName != null && !playerName.isBlank() && !name.equals(state.players.put(player.toString(), name))) scheduleSave();
        return name;
    }
    /** Every player the world knows by name, name to UUID: the last name each went by. */
    public synchronized Map<String, String> playerIdsByName() {
        Map<String, String> out = new LinkedHashMap<>();
        state.players.forEach((id, name) -> out.put(name, id));
        return out;
    }
    /** The UUID of the player who goes by this name, or null. */
    public synchronized UUID playerByName(String name) {
        if (name == null || name.isBlank()) return null;
        for (Map.Entry<String, String> known : state.players.entrySet()) if (known.getValue().equalsIgnoreCase(name.strip())) {
            try { return UUID.fromString(known.getKey()); } catch (IllegalArgumentException bad) { return null; }
        }
        return null;
    }
    private static String canonical(String type) { return type.contains(":") ? type : "minecraft:" + type; }
    private Profile require(UUID id) { Profile p = state.profiles.get(id.toString()); if (p == null) throw new IllegalArgumentException("Профиль моба ещё не создан."); return p; }
    private static void event(Profile p, long time, String text) { event(p, time, text, MemoryFact.ORDINARY); }
    /** The texts of the last {@code count} events, oldest first. */
    static List<String> lastEvents(Profile p, int count) { return p.events.stream().skip(Math.max(0, p.events.size() - count)).map(Event::text).toList(); }
    /** An event the game recorded, with how much it matters: the least important and oldest go first when the list is full. */
    static void event(Profile p, long time, String text, int importance) {
        p.events.add(new Event(time, TextBounds.characters(text, 500), MemoryFact.clamp(importance)));
        p.reflection.add(importance);
        MemoryRanking.evict(p.events, MemoryRanking.MAX_EVENTS, e -> MemoryRanking.score(e.importance(), e.time(), time));
    }

    // ---- Background memory work (see ConversationSummary, Reflection, BackgroundTasks): reads copy, writes check the history moved.

    /** The exchanges of the conversation that just ended with this mob, or null when fewer than {@link ConversationSummary#MIN_TURNS}
     * are still unsummarised. Cheap: a copy under the lock, no network. */
    synchronized ConversationSummary.Pending pendingSummary(UUID mob, UUID player, String playerName) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        if (p == null) return null;
        int from = p.unsummarizedFrom();
        ConversationSummary.Pending pending = new ConversationSummary.Pending(mob, p.turnsDropped + from, p.turnsDropped + p.turns.size(),
                List.copyOf(p.turns.subList(from, p.turns.size())), p.givenName.isBlank() ? p.name : p.givenName, SpeciesCatalog.get(p.type).name(), safePlayer(playerName));
        return pending.worthIt() ? pending : null;
    }
    /** Stores a summary of exchanges [fromTurn, toTurn), unless the history was cleared or summarised meanwhile. */
    synchronized void addSummary(UUID mob, long fromTurn, long toTurn, ConversationSummary.Summary summary) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        if (p == null || summary == null || summary.text().isBlank() || p.summarizedTurns != fromTurn || toTurn > p.turnsDropped + p.turns.size()) return;
        p.summarizedTurns = toTurn;
        p.summaries.add(summary);
        while (p.summaries.size() > ConversationSummary.MAX_SUMMARIES) p.summaries.removeFirst();
        scheduleSave(); // No revision bump: a summary arriving mid-answer invalidates nothing.
    }
    /** Past conversations with this mob, oldest first. */
    public synchronized List<ConversationSummary.Summary> summaries(UUID mob) { Profile p = mob == null ? null : state.profiles.get(mob.toString()); return p == null ? List.of() : List.copyOf(p.summaries); }
    /** What the mob remembers about players, as facts with importance. */
    public synchronized List<MemoryFact> facts(UUID mob) { Profile p = mob == null ? null : state.profiles.get(mob.toString()); return p == null ? List.of() : List.copyOf(p.facts); }

    /** Whether enough has happened since the last reflection ({@link Reflection.Counters#due}); when so, the counters restart
     * and the mob's memory is copied for the request. Null otherwise. */
    synchronized Reflection.Input reflectionDue(UUID mob, UUID player, String playerName, long nowMillis) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        if (p == null || !p.reflection.due(nowMillis)) return null;
        p.reflection.started(nowMillis); scheduleSave();
        return new Reflection.Input(mob, player, p.givenName.isBlank() ? p.name : p.givenName, SpeciesCatalog.get(p.type).name(), safePlayer(playerName),
                relationship(mob, player), List.copyOf(p.facts), List.copyOf(p.summaries), p.events.stream().map(e -> e.text).toList());
    }
    /** Adds conclusions about the player as reflection facts (importance {@link MemoryFact#REFLECTION}). */
    synchronized void addReflections(UUID mob, List<String> insights, long nowMillis) {
        Profile p = mob == null ? null : state.profiles.get(mob.toString());
        if (p == null || insights == null || insights.isEmpty()) return;
        int limit = Math.max(200, Math.min(4000, config.maxMemoryChars));
        for (String insight : insights) MemoryRanking.remember(p.facts, new MemoryFact(insight, MemoryFact.REFLECTION, nowMillis, true), limit, nowMillis);
        p.memory = MemoryRanking.render(p.facts);
        scheduleSave(); // No revision bump: a thought that landed mid-answer invalidates nothing.
    }

    static final class Profile {
        transient UUID id; transient int relationship, hits; transient List<String> recentEvents = List.of(); transient Village village;
        /** For a mob without a story: names its species already uses, and whether it is the first villager of a new village. */
        transient List<String> takenNames = List.of(); transient boolean founds;
        /** How the mob feels towards the current player at the moment the profile was read; null on a profile built by hand. */
        transient MobState.View stateView;
        /** What this mob thinks of other mobs at the moment the profile was read, strongest first; see {@link MobRelations}. */
        transient List<MobRelations.View> bonds = List.of();
        /** What this villager has heard the village say, weightiest first, and the player's fame there; see {@link VillageRumors}. */
        transient List<VillageRumors.View> rumors = List.of(); transient VillageRumors.Reputation repute = VillageRumors.Reputation.NONE;
        /** The outcomes of the player's gestures for this reply, decided before the request ({@link SocialTurn}); null outside the social layer. */
        transient SocialTurn socialTurn;
        /** This villager's relatives from the family tree, for «Свои»; empty outside the social layer. */
        transient List<FamilyTree.Kin> family = List.of();
        /** How the village sees the current player ({@link VillageStanding}); NONE outside the social layer. */
        transient VillageStanding.View standing = VillageStanding.View.NONE;
        /** Б1: what the village knows of the god's deeds about this player, for the prompt; set with {@link #standing}. */
        transient String divine = "";
        /** Sex, character, marks and growth of a villager in the social layer ({@link SocialProfile}); null for other mobs. */
        SocialProfile social;
        /** Соперники за этого жителя ({@link Rivalry}, П3); null, пока за ним никто не ухаживал. */
        Rivalry rivalry;
        String type, name, personality, voiceId; String memory = "";
        /** The memory as facts with importance and age ({@link MemoryRanking}); {@code memory} above is the same text, one line each. */
        List<MemoryFact> facts = new ArrayList<>();
        /** Past conversations in a few sentences each, oldest first ({@link ConversationSummary}). */
        List<ConversationSummary.Summary> summaries = new ArrayList<>();
        /** Absolute history indices: exchanges dropped from the front of {@code turns}, and the first exchange no summary covers yet. */
        long turnsDropped, summarizedTurns;
        /** When the last exchange was committed; −1 before the first. */
        long lastTurnMillis = -1;
        /** Replies and importance since the mob last thought the player over ({@link Reflection}). */
        Reflection.Counters reflection = new Reflection.Counters();
        /** The emotional state: four drifting axes and the last thought, see {@link MobState}. */
        MobState state = new MobState();
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
            while (turns.size() > 100) { turns.removeFirst(); turnsDropped++; }
            turnsDropped = Math.max(0, turnsDropped);
            summarizedTurns = Math.max(turnsDropped, Math.min(turnsDropped + turns.size(), summarizedTurns));
            if (summaries == null) summaries = new ArrayList<>();
            summaries.removeIf(s -> s == null || s.text() == null || s.text().isBlank());
            while (summaries.size() > ConversationSummary.MAX_SUMMARIES) summaries.removeFirst();
            if (reflection == null) reflection = new Reflection.Counters();
            reflection.sanitize();
            long now = System.currentTimeMillis();
            if (facts == null) facts = new ArrayList<>();
            facts.removeIf(f -> f == null || f.text() == null || f.text().isBlank());
            // Saved before importance existed: every line of the old memory text becomes a fact of middling importance.
            if (facts.isEmpty() && !memory.isBlank()) facts = MemoryRanking.parse(memory, now);
            memory = MemoryRanking.render(facts);
            if (events == null) events = new ArrayList<>();
            events.removeIf(e -> e == null || e.text == null);
            events.replaceAll(e -> e.importance() > 0 ? e : new Event(e.time(), e.text(), MemoryFact.MIGRATED));
            MemoryRanking.evict(events, MemoryRanking.MAX_EVENTS, e -> MemoryRanking.score(e.importance(), e.time(), now));
            if (relations == null) relations = new LinkedHashMap<>();
            relations.entrySet().removeIf(e -> e.getValue() == null);
            relations.values().forEach(r -> { r.score = clamp(r.score, hostile()); r.sanitize(); });
            if (action == null) action = ActionIntent.none();
            if (traits == null) traits = new ArrayList<>();
            if (givenName == null) givenName = ""; if (backstory == null) backstory = ""; if (villageId == null) villageId = "";
            if (wish == null) wish = ""; if (hidden == null) hidden = "";
            if (state == null) state = new MobState();
            state.sanitize();
            if (social != null) social.sanitize();
            if (rivalry != null) rivalry.sanitize();
        }
        Profile copy() {
            Profile p = new Profile(); p.id = id; p.type = type; p.name = name; p.personality = personality; p.state = state.copy();
            p.voiceId = voiceId; p.memory = memory; p.variantSlot = variantSlot; p.revision = revision; p.action = action;
            p.givenName = givenName; p.backstory = backstory; p.villageId = villageId; p.wish = wish; p.hidden = hidden;
            p.customPersonality = customPersonality; p.calm = calm; p.confidence = confidence;
            p.curiosity = curiosity; p.sociability = sociability; p.preference = preference; p.fear = fear;
            p.turns = new ArrayList<>(turns); p.events = new ArrayList<>(events); p.traits = new ArrayList<>(traits);
            p.facts = new ArrayList<>(facts); p.summaries = new ArrayList<>(summaries); // Immutable records.
            p.turnsDropped = turnsDropped; p.summarizedTurns = summarizedTurns; p.lastTurnMillis = lastTurnMillis; p.reflection = reflection.copy();
            relations.forEach((key, r) -> p.relations.put(key, r.copy()));
            p.social = social == null ? null : social.copy();
            p.rivalry = rivalry == null ? null : rivalry.copy();
            return p;
        }
        /** The index in {@code turns} where the current, unsummarised conversation begins. */
        int unsummarizedFrom() { return (int) Math.max(0, Math.min(turns.size(), summarizedTurns - turnsDropped)); }
    }
    public record Turn(String user, String assistant) { }
    /** {@code importance} 1–5 ({@link MemoryRanking}); 0 in files saved before it existed, read as {@link MemoryFact#MIGRATED}. */
    private record Event(long time, String text, int importance) { }
    /** One player as this mob knows them: standing, what they did, their prices on its deals, the favour it waits for. */
    static final class Relation {
        int score, hits, lastWitnessDelta;
        /** The part of {@code score} the god's deeds made ({@link DivineTidings}): without it the village would not exile the player. */
        int divine;
        long lastHelp = -1, lastApology = -1, lastInsult = -1, lastThreat = -1, lastPurchase = -1, lastWitness = -1; boolean ownerKnown;
        Map<String,TradeState> deals = new LinkedHashMap<>();
        Favor favor; List<String> favorsDone = new ArrayList<>();
        /** Gestures, gifts and courtship with this player ({@link Rapport}); null until the first gesture. */
        Rapport rapport;
        void sanitize() {
            if (rapport != null) rapport.sanitize();
            if (deals == null) deals = new LinkedHashMap<>();
            deals.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
            if (favorsDone == null) favorsDone = new ArrayList<>();
            favorsDone.removeIf(Objects::isNull);
            if (favor != null && (favor.item() == null || favor.count() < 1)) favor = null;
        }
        Relation copy() {
            Relation c = new Relation(); c.score = score; c.hits = hits; c.lastHelp = lastHelp; c.lastApology = lastApology;
            c.lastInsult = lastInsult; c.lastThreat = lastThreat; c.lastPurchase = lastPurchase; c.lastWitness = lastWitness; c.lastWitnessDelta = lastWitnessDelta; c.ownerKnown = ownerKnown; c.divine = divine;
            c.deals = new LinkedHashMap<>(deals); c.favor = favor; c.favorsDone = new ArrayList<>(favorsDone); // Immutable records.
            c.rapport = rapport == null ? null : rapport.copy();
            return c;
        }
    }
    /** The whole file. */
    static final class State {
        int version = 2;
        Map<String,Profile> profiles = new LinkedHashMap<>();
        Map<String,Bargaining.Session> bargains = new LinkedHashMap<>();
        Map<String,Village> villages = new LinkedHashMap<>();
        /** Bonds between mobs; absent in files written before them. */
        MobRelations relations = new MobRelations();
        /** What the villages say; absent in files written before it. */
        VillageRumors rumors = new VillageRumors();
        /** The world's family tree (social layer); absent in files written before it. */
        FamilyTree family = new FamilyTree();
        /** Villagers' chat announcements and open calls to work; absent in files written before them. */
        VillageNotices notices = new VillageNotices();
        /** Players the world knows by name, UUID to the name they last went by (rumours name them); absent in files written before it. */
        Map<String,String> players = new LinkedHashMap<>();
        /** What the villages know of the god's deeds (Б1); absent in files written before it. */
        DivineTidings divine = new DivineTidings();
        void sanitize() {
            if (divine == null) divine = new DivineTidings();
            divine.sanitize();
            if (notices == null) notices = new VillageNotices();
            if (players == null) players = new LinkedHashMap<>();
            players.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue().isBlank());
            notices.sanitize();
            if (family == null) family = new FamilyTree();
            family.sanitize();
            if (bargains == null) bargains = new LinkedHashMap<>();
            if (villages == null) villages = new LinkedHashMap<>();
            villages.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue().name() == null || e.getValue().dimension() == null);
            if (relations == null) relations = new MobRelations();
            relations.sanitize();
            if (rumors == null) rumors = new VillageRumors();
            rumors.sanitize();
        }
        State copy() {
            State c = new State(); c.version = version;
            profiles.forEach((key, p) -> c.profiles.put(key, p.copy()));
            c.bargains.putAll(bargains); c.villages.putAll(villages); // Immutable records.
            c.relations = relations.copy(); c.rumors = rumors.copy(); c.family = family.copy(); c.notices = notices.copy(); c.players = new LinkedHashMap<>(players); c.divine = divine.copy();
            return c;
        }
    }
}
