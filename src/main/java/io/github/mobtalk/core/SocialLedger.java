package io.github.mobtalk.core;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** The social layer inside the world memory (docs/mca-social-spec.md): a villager's sex, character and marks, the gestures of a
 * conversation with their outcomes decided in advance, gifts, courtship and marriage with the player, the family tree (village
 * weddings, deaths and mourning) and their effects on the relationship, the emotional state and the memory. Every method that
 * touches the world memory runs under the {@link ConversationStore} lock; the static helpers are called by the store from inside its
 * own locked methods. */
public final class SocialLedger {
    /** Relationship changes of courtship: a bouquet or a ring accepted, a break-up, a divorce. */
    public static final int DATE_HEARTS = 5, WEDDING_HEARTS = 10, BREAKUP_HEARTS = -15, DIVORCE_HEARTS = -40;
    /** What a killer loses with the victim's relatives, wherever they are (spec §5.8). */
    public static final int KIN_KILLED = -20;
    private final ConversationStore store;
    SocialLedger(ConversationStore store) { this.store = store; }

    /** Villagers only, and only while the layer is on. */
    static boolean applies(String type, MobTalkConfig config) { return config != null && config.socialLayer && "minecraft:villager".equals(type); }
    static boolean applies(ConversationStore.Profile p, MobTalkConfig config) { return p != null && applies(p.type, config); }

    /** Gives a villager its social profile the first time the layer sees it: sex, character and marks from its UUID, and the
     * character's one-time shift of the temperament. Returns whether anything changed. */
    static boolean adopt(ConversationStore.Profile p, MobTalkConfig config) {
        if (!applies(p, config) || p.social != null) return false;
        p.social = SocialProfile.of(p.id, false);
        temper(p);
        return true;
    }
    /** The character's one-time shift of the temperament. */
    static void temper(ConversationStore.Profile p) {
        Persona persona = p.social.persona();
        p.calm = shifted(p.calm, persona.calm); p.confidence = shifted(p.confidence, persona.confidence);
        p.curiosity = shifted(p.curiosity, persona.curiosity); p.sociability = shifted(p.sociability, persona.sociability);
    }
    private static int shifted(int value, int shift) { return value < 0 ? value : Math.max(0, Math.min(100, value + shift)); }

    /** The rest point of the mood axis moved by the character (gloomy lower, cheerful higher). */
    static MobState.Baseline baseline(ConversationStore.Profile p, MobState.Baseline base) {
        return p == null || p.social == null ? base : base.moodShifted(p.social.persona().moodRest);
    }

    /** Whether the villager counts as grown up: not a vanilla baby, and past the teen stage when the family tree tracks its growth. */
    static boolean adult(ConversationStore.Profile p, MobSnapshot snapshot) {
        if (snapshot != null && Boolean.TRUE.equals(snapshot.mobDetails().get("baby"))) return false;
        return p.social == null || p.social.stage.isBlank() || ChildGrowth.Stage.of(p.social.stage) == ChildGrowth.Stage.ADULT;
    }
    static String profession(MobSnapshot snapshot) { return snapshot.mobDetails().get("profession") instanceof String id ? id : ""; }

    /** Before the request: the villager's family for «Свои», and the outcome of every gesture the player could make now (see
     * {@link SocialTurn}), into the detached profile the prompt is built from. */
    static void prepare(ConversationStore.State state, ConversationStore.Profile p, ConversationStore.Profile detached, ConversationStore.Relation r,
                        MobSnapshot snapshot, MobState.View view, MobTalkConfig config, long now) {
        if (!applies(p, config) || p.social == null) return;
        detached.family = state.family.kin(p.id).stream().filter(kin -> !kin.player()).toList();
        UUID player = snapshot.playerId();
        if (player == null) return;
        detached.standing = standing(state, p.villageId, player, now);
        Rapport rapport = r == null || r.rapport == null ? new Rapport() : r.rapport;
        int score = r == null ? 0 : r.score;
        boolean adult = adult(p, snapshot);
        boolean related = state.family.related(p.id, player);
        Courtship courtship = rapport.courtship();
        Map<Gesture, GestureIntent> rolled = new LinkedHashMap<>();
        List<String> closed = new ArrayList<>();
        for (Gesture gesture : List.of(Gesture.JOKE, Gesture.COMPLIMENT, Gesture.HUG, Gesture.FLIRT, Gesture.KISS)) {
            if (gesture.romantic && (!adult || related)) continue;
            GestureTable.Context context = new GestureTable.Context(p.social.persona(), p.social.traits(), view.mood(), score,
                    rapport.fatigue(now), rapport.repeats(gesture, now), courtship, adult);
            rolled.putAll(SocialTurn.roll(context, List.of(gesture), () -> ThreadLocalRandom.current().nextDouble()));
        }
        String held = snapshot.heldItem();
        boolean holds = held != null && held.contains(":") && !held.endsWith(":air") && !held.startsWith("unknown");
        if (holds) rolled.put(Gesture.GIFT, gift(p, rapport, held, profession(snapshot), !adult, now));
        if (!adult) closed.add(SocialPrompt.CHILD_ROMANCE);
        else if (related) closed.add(SocialPrompt.KIN_ROMANCE);
        else {
            UUID spouse = state.family.spouse(p.id);
            Romance.Suit suit = new Romance.Suit(adult, related, courtship, p.id, partner(state, player), player.equals(spouse) ? null : spouse,
                    score, p.social.gender(), p.social.traits(), Gender.ofPlayer(config.playerGender), holds ? held : "");
            if (!courtship.partners()) {
                String refusal = Romance.refuseDate(suit);
                rolled.put(Gesture.DATE, new GestureIntent(Gesture.DATE, refusal == null ? Gesture.Outcome.ACCEPTED : Gesture.Outcome.REFUSED,
                        refusal == null ? DATE_HEARTS : 0, refusal == null ? held : "", refusal == null ? "" : refusal));
            }
            if (courtship != Courtship.MARRIED) {
                String refusal = Romance.refusePropose(suit);
                rolled.put(Gesture.PROPOSE, new GestureIntent(Gesture.PROPOSE, refusal == null ? Gesture.Outcome.ACCEPTED : Gesture.Outcome.REFUSED,
                        refusal == null ? WEDDING_HEARTS : 0, refusal == null ? held : "", refusal == null ? "" : refusal));
            }
            if (courtship.partners()) rolled.put(Gesture.DIVORCE, new GestureIntent(Gesture.DIVORCE, Gesture.Outcome.ACCEPTED,
                    courtship == Courtship.MARRIED ? DIVORCE_HEARTS : BREAKUP_HEARTS, "", courtship.name()));
            if (courtship == Courtship.MARRIED && config.familyLife) {
                String refusal = SocialPrompt.childRefusal(score, p.social.has(VillagerTrait.INFERTILE), p.social.expectingTick >= 0,
                        rapport.lastChildMillis >= 0 && now - rapport.lastChildMillis < ChildGrowth.CHILD_COOLDOWN_MILLIS);
                rolled.put(Gesture.CHILD, new GestureIntent(Gesture.CHILD, refusal == null ? Gesture.Outcome.ACCEPTED : Gesture.Outcome.REFUSED,
                        refusal == null ? DATE_HEARTS : 0, "", refusal == null ? "" : refusal));
            }
        }
        List<String> facts = SocialPrompt.playerFacts(courtship, state.family, p.id, player, config);
        detached.socialTurn = new SocialTurn(rolled, closed, facts);
    }
    /** The villager the player courts or married, or null: the living spouse in the tree, else any courtship in the relations. */
    static UUID partner(ConversationStore.State state, UUID player) {
        UUID spouse = state.family.spouse(player);
        if (spouse != null) return spouse;
        for (ConversationStore.Profile q : state.profiles.values()) {
            ConversationStore.Relation r = q.relations.get(player.toString());
            if (r != null && r.rapport != null && r.rapport.courtship().partners() && !state.family.dead(q.id)) return q.id;
        }
        return null;
    }

    /** A gift weighed for this villager against what it was given lately ({@link GiftCatalog}). */
    static GestureIntent gift(ConversationStore.Profile p, Rapport rapport, String item, String profession, boolean child, long now) {
        GiftCatalog.Value value = GiftCatalog.weigh(item, new GiftCatalog.Receiver(p.social.persona(), p.social.traits(), profession, child), rapport.given(item, now));
        String note = !value.known() ? "" : value.offends() ? value.category() + ", тебе это претит" : value.category();
        return new GestureIntent(Gesture.GIFT, value.tier(), value.hearts(), item, note);
    }

    /** A gift thrown at the villager's feet (no conversation): weighed and applied at once; null when the villager is outside the
     * layer or the player unknown. The caller takes the item when the result {@link GestureIntent#takesItem()}. */
    public GestureIntent giftThrown(UUID mob, String type, String name, UUID player, String playerName, String item, String profession, boolean child, long now) {
        if (mob == null || player == null || item == null) return null;
        synchronized (store) {
            MobTalkConfig config = store.config();
            if (!applies(type, config)) return null;
            ConversationStore.Profile p = store.ensure(mob, type, name);
            if (p.social == null) return null;
            ConversationStore.Relation r = rapport(p, player);
            GestureIntent intent = gift(p, r.rapport, item, profession, child, now);
            apply(p, r, player, playerName, intent, config, now);
            p.revision++; // Like a blow or help: a reply written before the gift landed is stale.
            store.scheduleSave();
            return intent;
        }
    }

    /** After a delivered reply: the gesture's effects — relationship, fatigue and repeats, courtship, the emotional state, a memory line. */
    public void applyGesture(UUID mob, UUID player, String playerName, GestureIntent intent, long now) {
        if (mob == null || player == null || intent == null || !intent.present()) return;
        synchronized (store) {
            ConversationStore.Profile p = store.state.profiles.get(mob.toString());
            if (p == null) return;
            apply(p, rapport(p, player), player, playerName, intent, store.config(), now);
            store.scheduleSave();
        }
    }
    private ConversationStore.Relation rapport(ConversationStore.Profile p, UUID player) {
        ConversationStore.Relation r = store.relation(p, player);
        if (r.rapport == null) r.rapport = new Rapport();
        return r;
    }
    /** The effects of one gesture on one relation; under the store lock. */
    private void apply(ConversationStore.Profile p, ConversationStore.Relation r, UUID player, String playerName, GestureIntent intent, MobTalkConfig config, long now) {
        r.score = ConversationStore.clamp(r.score + intent.hearts(), p.hostile());
        r.rapport.record(intent.gesture(), now, intent.hearts() != 0);
        boolean accepted = intent.outcome() == Gesture.Outcome.ACCEPTED;
        if (intent.gesture() == Gesture.GIFT && intent.takesItem()) r.rapport.gave(intent.item(), now);
        FamilyTree tree = store.state.family;
        MobState.Event felt;
        switch (intent.gesture()) {
            case DATE -> { if (accepted) r.rapport.courtship(Courtship.DATING); felt = accepted ? MobState.Event.ROMANCE : null; }
            case PROPOSE -> {
                if (accepted) {
                    r.rapport.courtship(Courtship.MARRIED);
                    UUID former = partner(store.state, player);
                    if (former != null && !former.equals(p.id)) leave(former, player);
                    tree.marry(player, playerName, Gender.ofPlayer(config.playerGender), true, p.id, name(p), p.social == null ? null : p.social.gender(), false);
                }
                felt = accepted ? MobState.Event.ROMANCE : null;
            }
            case DIVORCE -> {
                r.rapport.courtship(Courtship.NONE);
                if (p.id.equals(tree.spouse(player))) tree.divorce(player);
                felt = MobState.Event.DIVORCE;
            }
            case CHILD -> {
                if (accepted && p.social != null) { r.rapport.lastChildMillis = now; p.social.expectingTick = 0; p.social.expectingWith = player.toString(); }
                felt = accepted ? MobState.Event.ROMANCE : null;
            }
            case GIFT -> felt = intent.outcome().warm() ? MobState.Event.GIFT_LIKED : intent.hearts() < 0 ? MobState.Event.GIFT_SPURNED : null;
            default -> felt = intent.outcome().warm() ? MobState.Event.GESTURE_WARM : MobState.Event.GESTURE_COLD;
        }
        if (felt != null) ConversationStore.feel(p, r, felt, now);
        ConversationStore.event(p, now, SocialPrompt.memoryLine(intent, playerName, r.score), SocialPrompt.importance(intent));
    }
    /** The player left this villager for another: the old courtship ends without a scene. */
    private void leave(UUID villager, UUID player) {
        ConversationStore.Profile q = store.state.profiles.get(villager.toString());
        ConversationStore.Relation r = q == null ? null : q.relations.get(player.toString());
        if (r != null && r.rapport != null) r.rapport.courtship(Courtship.NONE);
    }
    static String name(ConversationStore.Profile p) { return p.givenName == null || p.givenName.isBlank() ? p.name : p.givenName; }

    // ---- The family in the world: weddings between villagers, deaths and mourning. ----

    /** A villager the game sees in a village, for a wedding: profile-less ones get their sex and marks from the UUID. */
    public record Resident(UUID id, String name, boolean adult) { }
    /** Once in a while for a loaded village (spec §5.2): marries the pair {@link Romance#wedding} picks among these residents.
     * Returns the pair (with profiles made for both), or null when nobody married. */
    public UUID[] villageWedding(List<Resident> residents, UUID player, long now) {
        synchronized (store) {
            MobTalkConfig config = store.config();
            if (!config.socialLayer) return null;
            ConversationStore.State state = store.state;
            List<Romance.Single> singles = new ArrayList<>();
            Map<UUID, Resident> byId = new HashMap<>();
            for (Resident resident : residents) {
                byId.put(resident.id(), resident);
                ConversationStore.Profile p = state.profiles.get(resident.id().toString());
                SocialProfile social = p != null && p.social != null ? p.social : SocialProfile.of(resident.id(), !resident.adult());
                boolean courting = p != null && p.relations.values().stream().anyMatch(rel -> rel.rapport != null && rel.rapport.courtship().partners());
                int best = p == null ? 0 : p.relations.values().stream().mapToInt(rel -> rel.score).max().orElse(0);
                boolean free = !state.family.married(resident.id()) && !courting;
                singles.add(new Romance.Single(resident.id(), social.gender(), social.traits(), best, free, resident.adult()));
            }
            UUID[] pair = Romance.wedding(singles, state.family::related);
            if (pair == null) return null;
            ConversationStore.Profile a = store.ensure(pair[0], "minecraft:villager", byId.get(pair[0]).name()), b = store.ensure(pair[1], "minecraft:villager", byId.get(pair[1]).name());
            if (a.social == null || b.social == null) return null;
            state.family.marry(a.id, name(a), a.social.gender(), false, b.id, name(b), b.social.gender(), false);
            for (ConversationStore.Profile[] two : List.of(new ConversationStore.Profile[]{a, b}, new ConversationStore.Profile[]{b, a})) {
                ConversationStore.Profile self = two[0], other = two[1];
                if (player != null) ConversationStore.feel(self, store.relation(self, player), MobState.Event.ROMANCE, now);
                ConversationStore.event(self, now, "Ты вступил в брак: " + other.social.gender().spouse + " — " + name(other), 5);
                self.revision++;
            }
            state.relations.together(a.id, name(a), a.type, b.id, name(b), b.type, MobRelations.Kinship.NEIGHBOR, 30, 0, now);
            store.scheduleSave();
            return pair;
        }
    }

    /** One relative's loss, for the game to show: who grieves and the word for the dead. */
    public record Mourner(UUID id, String word) { }
    /** A villager died: the tree marks it, the spouse is widowed, every relative with a profile mourns — seen or not — and takes it out
     * on a player killer ({@code killer} null otherwise). Returns those who mourn. */
    public List<Mourner> died(UUID dead, String deadName, String deadType, UUID killer, String killerName, long now) {
        if (dead == null) return List.of();
        synchronized (store) {
            ConversationStore.State state = store.state;
            boolean inTree = state.family.known(dead);
            // Even a villager with no relatives is struck off: the village's standing counts the living only.
            if (!inTree) { if (state.profiles.containsKey(dead.toString())) { state.family.markDead(dead, deadName); store.scheduleSave(); } return List.of(); }
            Gender deadGender = deadGender(state, dead);
            List<Mourner> mourners = new ArrayList<>();
            for (Map.Entry<UUID, FamilyTree.Link> entry : state.family.died(dead).entrySet()) {
                ConversationStore.Profile q = state.profiles.get(entry.getKey().toString());
                if (q == null) continue;
                String word = switch (entry.getValue()) {
                    case SPOUSE -> deadGender.spouse; case PARENT -> deadGender.offspring; case CHILD -> deadGender.parent; case SIBLING -> deadGender.sibling;
                };
                state.relations.mourn(q.id, dead, deadName, deadType, true, now);
                ConversationStore.Relation r = killer == null ? null : store.relation(q, killer);
                if (r != null) {
                    r.score = ConversationStore.clamp(r.score + KIN_KILLED, q.hostile());
                    ConversationStore.event(q, now, "Игрок " + TextBounds.characters(killerName, 60) + " убил твоего родного (" + word + " " + deadName + "); отношение " + r.score, 5);
                } else ConversationStore.event(q, now, "Погиб твой родной: " + word + " " + deadName, 5);
                ConversationStore.Relation felt = r != null ? r : q.relations.values().stream().findFirst().orElse(null);
                if (felt != null) ConversationStore.feel(q, felt, MobState.Event.BEREAVED, now);
                else q.state.shift(MobState.Event.BEREAVED, ConversationStore.baseline(q, 0), now);
                if (entry.getValue() == FamilyTree.Link.SPOUSE) ConversationStore.event(q, now, "Ты овдовел: " + word + " " + deadName + " погиб", 5);
                q.revision++;
                mourners.add(new Mourner(q.id, word));
            }
            store.scheduleSave();
            return mourners;
        }
    }
    private static Gender deadGender(ConversationStore.State state, UUID dead) {
        ConversationStore.Profile p = state.profiles.get(dead.toString());
        if (p != null && p.social != null) return p.social.gender();
        FamilyTree.Node node = state.family.nodes.get(dead.toString());
        Gender g = node == null ? null : Gender.of(node.gender);
        return g == null ? Gender.MALE : g;
    }

    // ---- Children: born only to couples, growing through stages the game holds them in. ----

    /** A child on the way with the player: the villager, the player, the birth tick (0 while the game has not set it). */
    public record Expecting(UUID villager, UUID partner, long dueTick) { }
    public List<Expecting> expecting() {
        synchronized (store) {
            List<Expecting> list = new ArrayList<>();
            for (ConversationStore.Profile p : store.state.profiles.values())
                if (p.social != null && p.social.expectingTick >= 0 && !p.social.expectingWith.isBlank())
                    try { list.add(new Expecting(p.id, UUID.fromString(p.social.expectingWith), p.social.expectingTick)); } catch (IllegalArgumentException ignored) { }
            return list;
        }
    }
    /** The game set the day of birth (a game day after the agreement). */
    public void due(UUID villager, long tick) {
        synchronized (store) {
            ConversationStore.Profile p = store.state.profiles.get(villager.toString());
            if (p != null && p.social != null && p.social.expectingTick == 0) { p.social.expectingTick = Math.max(1, tick); store.scheduleSave(); }
        }
    }
    /** What the village rule needs to know of a married villager: its sex, its living spouse, how many children it has, fertility. */
    public record Household(UUID villager, Gender gender, UUID spouse, int children, boolean fertile) { }
    public Household household(UUID villager) {
        synchronized (store) {
            ConversationStore.State state = store.state;
            ConversationStore.Profile p = state.profiles.get(villager.toString());
            SocialProfile social = p != null && p.social != null ? p.social : SocialProfile.of(villager, false);
            UUID spouse = state.family.spouse(villager);
            ConversationStore.Profile q = spouse == null ? null : state.profiles.get(spouse.toString());
            boolean fertile = !social.has(VillagerTrait.INFERTILE) && (q == null || q.social == null || !q.social.has(VillagerTrait.INFERTILE));
            return new Household(villager, social.gender(), spouse, state.family.children(villager).size(), fertile);
        }
    }
    /** A child was born (the game spawned it) to {@code a} and {@code b} — villagers or the player — at {@code worldTick}: its profile
     * with sex, child character, inherited marks and temperament, its place in the tree, the news in its parents' memories, and the
     * starting {@link Hearts#OWN_CHILD} towards a player parent. Returns the newborn's sex. */
    public Gender born(UUID child, UUID a, UUID b, String playerName, long worldTick, long now) {
        synchronized (store) {
            ConversationStore.State state = store.state;
            MobTalkConfig config = store.config();
            ConversationStore.Profile pa = state.profiles.get(a.toString()), pb = state.profiles.get(b.toString());
            ChildGrowth.Newborn newborn = ChildGrowth.born(traits(pa), temperament(pa), traits(pb), temperament(pb), new Random());
            ConversationStore.Profile kid = store.ensure(child, "minecraft:villager", "Житель");
            if (kid.social == null) kid.social = SocialProfile.of(child, true);
            kid.social.gender = newborn.gender().name(); kid.social.persona = newborn.persona().name();
            kid.social.traits = new ArrayList<>(newborn.traits().stream().map(Enum::name).toList());
            kid.social.bornTick = worldTick; kid.social.stage = ChildGrowth.Stage.INFANT.name();
            kid.calm = newborn.calm(); kid.confidence = newborn.confidence(); kid.curiosity = newborn.curiosity(); kid.sociability = newborn.sociability();
            Gender ga = gender(state, pa, a, config), gb = gender(state, pb, b, config);
            boolean aMother = ga == Gender.FEMALE || gb == Gender.MALE;
            state.family.addChild(child, "", newborn.gender(), aMother ? a : b, aMother ? b : a);
            for (ConversationStore.Profile parent : new ConversationStore.Profile[]{pa, pb}) {
                if (parent == null) continue;
                if (parent.social != null && parent.social.expectingTick >= 0) { parent.social.expectingTick = -1; parent.social.expectingWith = ""; }
                parent.state.shift(MobState.Event.CHILD_BORN, ConversationStore.baseline(parent, 0), now);
                ConversationStore.event(parent, now, "У тебя родился ребёнок: " + newborn.gender().offspring, 5);
                parent.revision++;
            }
            for (UUID parent : List.of(a, b)) {
                // The player parent: no profile of its own, and the caller named it.
                if (playerName == null || playerName.isBlank() || state.profiles.containsKey(parent.toString())) continue;
                ConversationStore.Relation r = store.relation(kid, parent);
                r.score = Hearts.OWN_CHILD;
                state.family.node(parent, playerName, Gender.ofPlayer(config.playerGender), true);
            }
            ConversationStore.event(kid, now, "Ты родился в семье: " + String.join(", ", state.family.kin(child).stream()
                    .map(k -> k.word() + (k.name().isBlank() ? "" : " " + k.name())).toList()), 5);
            store.scheduleSave();
            return newborn.gender();
        }
    }
    private static Collection<VillagerTrait> traits(ConversationStore.Profile p) { return p == null || p.social == null ? List.of() : p.social.traits(); }
    private static int[] temperament(ConversationStore.Profile p) { return p == null ? null : new int[]{p.calm, p.confidence, p.curiosity, p.sociability}; }
    /** A parent's sex: its profile, else its node in the tree, else (a player) the config's. */
    private static Gender gender(ConversationStore.State state, ConversationStore.Profile p, UUID id, MobTalkConfig config) {
        if (p != null && p.social != null) return p.social.gender();
        FamilyTree.Node node = state.family.nodes.get(id.toString());
        Gender known = node == null ? null : Gender.of(node.gender);
        if (known != null) return known;
        return node != null && node.player ? Gender.ofPlayer(config.playerGender) : p != null ? SocialProfile.genderOf(id) : Gender.ofPlayer(config.playerGender);
    }
    /** For each loaded villager the tree tracks as a child: its stage at {@code worldTick} (saved in its profile). Grown ones are ADULT. */
    public Map<UUID, ChildGrowth.Stage> grow(Collection<UUID> loaded, long worldTick) {
        synchronized (store) {
            Map<UUID, ChildGrowth.Stage> stages = new LinkedHashMap<>();
            int days = store.config().childGrowthDays;
            boolean changed = false;
            for (UUID id : loaded) {
                ConversationStore.Profile p = store.state.profiles.get(id.toString());
                if (p == null || p.social == null || p.social.bornTick < 0) continue;
                ChildGrowth.Stage stage = ChildGrowth.stage(worldTick - p.social.bornTick, days);
                if (!stage.name().equals(p.social.stage)) { p.social.stage = stage.name(); p.revision++; changed = true; }
                stages.put(id, stage);
            }
            if (changed) store.scheduleSave();
            return stages;
        }
    }

    // ---- The village's view of the player (VillageStanding): relationships of its residents plus its rumours. ----

    /** The standing of the player in this village; {@link VillageStanding.View#NONE} for no village. */
    public VillageStanding.View standing(String villageId, UUID player, long now) { synchronized (store) { return standing(store.state, villageId, player, now); } }
    /** The standing in the known village around this point (same dimension, within the village radius), or NONE. */
    public VillageStanding.View standingAt(String dimension, int x, int z, UUID player, long now) {
        synchronized (store) {
            ConversationStore.Village village = store.villageNear(dimension, x, z);
            return village == null ? VillageStanding.View.NONE : standing(store.state, village.id(), player, now);
        }
    }
    static VillageStanding.View standing(ConversationStore.State state, String villageId, UUID player, long now) {
        if (villageId == null || villageId.isBlank() || player == null) return VillageStanding.View.NONE;
        List<Integer> scores = new ArrayList<>();
        int residents = 0;
        for (ConversationStore.Profile q : state.profiles.values()) {
            if (!villageId.equals(q.villageId) || !"minecraft:villager".equals(q.type) || state.family.dead(q.id)) continue;
            residents++;
            ConversationStore.Relation r = q.relations.get(player.toString());
            if (r != null) scores.add(r.score);
        }
        return VillageStanding.of(scores, residents, state.rumors.reputation(villageId, now));
    }

    /** The living spouse of a villager, for the game (village weddings, births); null for none. */
    public UUID spouse(UUID mob) { synchronized (store) { return store.state.family.spouse(mob); } }
    /** The relatives of a mob in words. */
    public List<FamilyTree.Kin> family(UUID mob) { synchronized (store) { return store.state.family.kin(mob); } }

    /** The occasion for the villager's words about a thrown gift whose outcome the game already applied. */
    public static String giftCue(GestureIntent intent) { return SocialPrompt.thrownGift(intent); }

    /** One line about the selected villager's social side, for /mobtalk state; "" for anyone else. */
    public String describe(UUID mob, UUID player) {
        synchronized (store) {
            ConversationStore.Profile p = mob == null ? null : store.state.profiles.get(mob.toString());
            if (p == null || p.social == null) return "";
            ConversationStore.Relation r = player == null ? null : p.relations.get(player.toString());
            long now = System.currentTimeMillis();
            StringBuilder line = new StringBuilder(p.social.gender().adult).append(", ").append(p.social.persona().word);
            for (VillagerTrait trait : p.social.traits()) line.append(", ").append(trait.name().toLowerCase(Locale.ROOT));
            ChildGrowth.Stage stage = ChildGrowth.Stage.of(p.social.stage);
            if (stage != null) line.append(", ").append(stage.word);
            if (r != null && r.rapport != null) {
                line.append("; усталость ").append(r.rapport.fatigue(now));
                if (r.rapport.courtship().partners()) line.append("; ").append(r.rapport.courtship().word);
            }
            VillageStanding.View standing = standing(store.state, p.villageId, player, now);
            if (standing.rank() != VillageStanding.Rank.STRANGER) line.append("; в деревне: ").append(standing.rank().word).append(" (").append(standing.points()).append(')');
            List<FamilyTree.Kin> kin = store.state.family.kin(mob);
            if (!kin.isEmpty()) line.append("; семья: ").append(String.join(", ", kin.stream().map(k -> k.word() + " " + (k.name().isBlank() ? "?" : k.name()) + (k.dead() ? " †" : "")).toList()));
            return line.toString();
        }
    }
}
