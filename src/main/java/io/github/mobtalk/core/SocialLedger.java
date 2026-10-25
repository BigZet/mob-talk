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
     * character's one-time shift of the temperament, and a name no neighbour carries ({@code others}: every profile of the world).
     * Returns whether anything changed. */
    static boolean adopt(ConversationStore.Profile p, MobTalkConfig config, Collection<ConversationStore.Profile> others) {
        if (!applies(p, config)) return false;
        boolean changed = false;
        if (p.social == null) { p.social = SocialProfile.of(p.id, false); temper(p); changed = true; }
        // A villager the layer knows has a name before anyone talks to it: weddings and rumours name people, not «Крестьянин».
        // A name once given stays (memories, the family tree and rumours carry it); a new one is one the neighbours do not wear.
        if (p.givenName == null || p.givenName.isBlank()) { p.givenName = VillagerNames.pick(p.id, p.social.gender(), VillagerNames.wear(others, p.id, p.villageId)); changed = true; }
        // The voice comes once from the villagers' own pool by sex and age; a voice the player typed in stays theirs. Later the
        // voice changes only when it does not match the sex, so a catalog voice the player picked in the menu is kept too.
        Gender gender = p.social.gender();
        boolean first = p.social.age() == null;
        if (first) { p.social.age = VillagerVoices.age(p.id, p.social).name(); changed = true; }
        if (p.voiceId == null) p.voiceId = "";
        SpeciesCatalog.Voice current = SpeciesCatalog.voice(p.voiceId);
        boolean custom = !p.voiceId.isBlank() && !SpeciesCatalog.known(p.voiceId);
        boolean recast = first ? !custom && !VillagerVoices.fits(p.voiceId, gender, p.social.age())
                : p.voiceId.isBlank() || (current.sex() != null && current.sex() != gender);
        if (recast) {
            String fitting = VillagerVoices.pick(gender, p.social.age(), p.id, others == null ? List.of() : others);
            if (fitting.isBlank()) fitting = SpeciesCatalog.voiceFor(p.type, gender, p.id).id();
            if (!fitting.isBlank() && !fitting.equals(p.voiceId)) { p.voiceId = fitting; changed = true; }
        }
        return changed;
    }
    /** Old worlds: the biography writer renamed villagers without knowing their sex («Валентина» with a man's body and voice). The sex
     * stays (the body, the voice and the clients' human looks follow the UUID); the name and the life story, written for the other sex,
     * go when the world is opened: the villager gets a fitting name no neighbour wears and its story is written anew at the next talk. One in the family tree
     * keeps its name: the tree, the wedding and the children carry it. */
    static boolean renameToSex(ConversationStore.Profile p, FamilyTree family, Collection<ConversationStore.Profile> others) {
        if (p.social == null || family != null && family.known(p.id)) return false;
        Gender named = VillagerNames.sexOf(p.givenName);
        if (named == null || named == p.social.gender()) return false;
        p.givenName = VillagerNames.pick(p.id, p.social.gender(), VillagerNames.wear(others, p.id, p.villageId));
        p.backstory = "";
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
                        MobSnapshot snapshot, MobState.View view, MobTalkConfig config, long now, ChildLedger children) {
        if (!applies(p, config) || p.social == null) return;
        detached.family = state.family.kin(p.id).stream().filter(kin -> !kin.player()).toList();
        UUID player = snapshot.playerId();
        if (player == null) return;
        detached.standing = standing(state, p.villageId, player, now);
        detached.divine = SocialPrompt.divine(state, p.villageId, player, now);
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
                    score, p.social.gender(), p.social.traits(), PlayerIdentity.gender(config, player), holds ? held : "");
            // Сервер: житель — пара только одного игрока. С соперниками (П3) до помолвки сердце открыто: букет и кольцо решают они.
            boolean rivals = RivalryLedger.on(config);
            String taken = rivals ? RivalryLedger.closedBy(state, p, player) : takenBy(state, p, player);
            if (!courtship.partners()) {
                String refusal = taken != null ? taken : Romance.refuseDate(suit);
                if (refusal == null && rivals) refusal = RivalryLedger.dateRefusal(state, p, player, score, config, now);
                rolled.put(Gesture.DATE, new GestureIntent(Gesture.DATE, refusal == null ? Gesture.Outcome.ACCEPTED : Gesture.Outcome.REFUSED,
                        refusal == null ? DATE_HEARTS : 0, refusal == null ? held : "", refusal == null ? "" : refusal));
            }
            if (courtship != Courtship.MARRIED && courtship != Courtship.ENGAGED) {
                String refusal = taken != null ? taken : Romance.refusePropose(suit);
                int cost = 0;
                if (refusal == null && rivals) {
                    RivalryLedger.Verdict verdict = RivalryLedger.proposeVerdict(state, p, r, player, config, now);
                    if (verdict != null) { refusal = verdict.refusal(); cost = verdict.hearts(); }
                }
                rolled.put(Gesture.PROPOSE, new GestureIntent(Gesture.PROPOSE, refusal == null ? Gesture.Outcome.ACCEPTED : Gesture.Outcome.REFUSED,
                        refusal == null ? WEDDING_HEARTS + Romance.ringHearts(held) : cost, refusal == null ? held : "", refusal == null ? "" : refusal));
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
        boolean mercantile = Mercantile.active(p) && adult && !related;
        int wealth = Mercantile.wealth(snapshot);
        // Подружки уже рассказали, жмот он или щедрый: незнакомка судит о кошельке и по сплетне.
        Mercantile.Gossip heard = mercantile && !courtship.partners() ? Mercantile.heard(state, p, player, config, now) : null;
        Mercantile.Purse purse = Mercantile.rumoured(Mercantile.purse(wealth, config), heard);
        if (mercantile) Mercantile.gestures(rolled, purse); // Голодранцу — от ворот поворот, богатому — улыбки.
        List<String> facts = SocialPrompt.playerFacts(courtship, state.family, p.id, player, config);
        if (mercantile) {
            facts.addAll(Mercantile.facts(rapport, purse, wealth, Mercantile.neglected(rapport, config, now), courtship));
            if (heard != null) facts.add(Mercantile.gossipFact(heard));
            facts.addAll(Mercantile.dinnerFacts(rapport, now)); // Ужин-«тарелочница».
        }
        facts.addAll(JealousyLedger.facts(p, rapport, PlayerIdentity.configOf(player, config), now)); // Трек J: ревность и ночь вместе.
        facts.addAll(WeddingLedger.facts(state, p, r, player, snapshot, config, now)); // Трек L: помолвка, свидетели, свадьба.
        facts.addAll(ChildLedger.facts(state, p, r, player, config, children)); // Трек N: особый вечер, беременность.
        facts.addAll(RivalryLedger.facts(state, p, r, player, config, now)); // П3: соперники за это сердце.
        detached.socialTurn = new SocialTurn(rolled, closed, facts);
    }
    /** Why this villager will not court {@code player}: another player already courts, is engaged to or married it; null when free.
     * A villager is the partner of one player only. */
    static String takenBy(ConversationStore.State state, ConversationStore.Profile p, UUID player) {
        UUID spouse = state.family.spouse(p.id);
        UUID other = spouse != null && !spouse.equals(player) && state.players.containsKey(spouse.toString()) ? spouse : null;
        if (other == null) for (Map.Entry<String, ConversationStore.Relation> entry : p.relations.entrySet()) {
            ConversationStore.Relation r = entry.getValue();
            if (!entry.getKey().equals(player.toString()) && r != null && r.rapport != null && r.rapport.courtship().partners()) {
                try { other = UUID.fromString(entry.getKey()); } catch (IllegalArgumentException bad) { continue; }
                break;
            }
        }
        if (other == null) return null;
        String name = state.players.getOrDefault(other.toString(), "");
        return "сердце уже занято: у него пара — другой игрок" + (name.isBlank() ? "" : " (" + name + ")") + ", и это не ты";
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
        GestureIntent intent = new GestureIntent(Gesture.GIFT, value.tier(), value.hearts(), item, note);
        return Mercantile.active(p) && !child ? Mercantile.gift(intent, rapport, item, rapport.given(item, now)) : intent;
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

    /** How warmly a creature outside the villagers' layer takes a gift from the hand: its own food is the best present, anything the
     * catalogue values counts for half (a cow cares little for emeralds), the rest is declined; a hated or hostile creature wants nothing. */
    static final int FOOD_FIRST = 8;
    public GestureIntent giftFromHand(UUID mob, String type, String name, UUID player, String playerName, String item, String profession, boolean child,
                                      boolean favouriteFood, long now) {
        if (mob == null || player == null || item == null) return null;
        synchronized (store) {
            MobTalkConfig config = store.config();
            if (applies(type, config)) {
                ConversationStore.Profile p = store.ensure(mob, type, name);
                if (p.social == null) return null;
                ConversationStore.Relation r = rapport(p, player);
                GestureIntent intent = gift(p, r.rapport, item, profession, child, now);
                apply(p, r, player, playerName, intent, config, now);
                p.revision++; store.scheduleSave();
                return intent;
            }
            ConversationStore.Profile p = store.ensure(mob, type, name);
            ConversationStore.Relation r = rapport(p, player);
            int times = r.rapport.given(item, now);
            GestureIntent intent;
            if (p.hostile() || r.score <= CombatRules.HATRED) intent = new GestureIntent(Gesture.GIFT, Gesture.Outcome.REFUSED, 0, item, "от этого игрока тебе ничего не надо");
            else if (favouriteFood) {
                int first = config.foodGiftHearts, hearts = Math.max(0, first - 2 * times);
                intent = new GestureIntent(Gesture.GIFT, hearts >= first && first > 0 ? Gesture.Outcome.BEST : hearts >= 4 ? Gesture.Outcome.BETTER : hearts > 0 ? Gesture.Outcome.GOOD : Gesture.Outcome.ALREADY,
                        hearts, item, "твоя любимая еда");
            } else if (GiftCatalog.known(item)) {
                GiftCatalog.Value value = GiftCatalog.weigh(item, new GiftCatalog.Receiver(Persona.FRIENDLY, Set.of(), "", child), times);
                int hearts = value.hearts() / 2;
                intent = new GestureIntent(Gesture.GIFT, value.tier() == Gesture.Outcome.REFUSED ? Gesture.Outcome.REFUSED : hearts <= 0 ? Gesture.Outcome.ALREADY : Gesture.Outcome.GOOD,
                        Math.max(0, hearts), item, value.category());
            } else intent = new GestureIntent(Gesture.GIFT, Gesture.Outcome.REFUSED, 0, item, "тебе это не нужно");
            r.score = ConversationStore.clamp(r.score + intent.hearts(), p.hostile());
            r.rapport.record(Gesture.GIFT, now, intent.hearts() != 0);
            if (intent.takesItem()) r.rapport.gave(item, now);
            ConversationStore.feel(p, r, intent.takesItem() ? MobState.Event.GIFT_LIKED : MobState.Event.GIFT_SPURNED, now);
            ConversationStore.event(p, now, "Игрок " + TextBounds.characters(playerName, 60) + (intent.takesItem() ? " подарил тебе " : " предлагал тебе ") + item
                    + (intent.note().isBlank() ? "" : " (" + intent.note() + ")") + (intent.takesItem() ? "; отношение " + r.score : ", ты не взял"), 4);
            p.revision++; store.scheduleSave();
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
        if (intent.gesture() == Gesture.GIFT && intent.takesItem()) {
            r.rapport.gave(intent.item(), now);
            if (Mercantile.active(p)) {
                Mercantile.paid(r.rapport, intent.item(), config, now); // Аппетит растёт, остывание с нуля.
                if (Mercantile.price(intent.item()) >= Mercantile.BRAG_PRICE)
                    Mercantile.gossip(store.state, p, player, playerName, Mercantile.Talk.GENEROUS, "подарил " + Mercantile.thing(intent.item()), now);
            }
        }
        FamilyTree tree = store.state.family;
        MobState.Event felt;
        switch (intent.gesture()) {
            case DATE -> {
                if (accepted) { r.rapport.courtship(Courtship.DATING); r.rapport.datingAt = now; r.rapport.anniversaries = 0; }
                felt = accepted ? MobState.Event.ROMANCE : null;
            }
            case PROPOSE -> {
                if (accepted) {
                    UUID former = partner(store.state, player);
                    if (former != null && !former.equals(p.id)) leave(former, player);
                    r.rapport.ring = intent.item() == null ? "" : intent.item();
                    // Трек L: кольцо — помолвка, брак будет у колокола; без свадебной службы — сразу брак, как раньше.
                    if (config.weddingCeremony) r.rapport.courtship(Courtship.ENGAGED);
                    else {
                        r.rapport.courtship(Courtship.MARRIED);
                        tree.marry(player, playerName, PlayerIdentity.gender(config, player), true, p.id, name(p), p.social == null ? null : p.social.gender(), false);
                    }
                }
                felt = accepted ? MobState.Event.ROMANCE : null;
            }
            case DIVORCE -> {
                r.rapport.courtship(Courtship.NONE);
                r.rapport.ring = ""; r.rapport.witnessMan = ""; r.rapport.witnessWoman = ""; // Кольцо игра вернула игроку до этого.
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
        store.rivalry().after(store.state, p, r, player, playerName, intent, config, now); // П3: ухажёры, отбитая пара, выбор.
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
    public UUID[] villageWedding(List<Resident> residents, UUID player, long now) { return villageWedding(residents, player, now, true); }
    /** {@code marry} false: only picks the pair (and gives both a profile) for a wedding held as a scene at the bell (трек L); the
     * marriage itself is then {@link WeddingLedger#villageWed}. */
    public UUID[] villageWedding(List<Resident> residents, UUID player, long now, boolean marry) {
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
            if (!marry) { store.scheduleSave(); return pair; }
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
                if (parent.social != null && parent.social.expectingTick >= 0) parent.social.clearExpecting();
                parent.state.shift(MobState.Event.CHILD_BORN, ConversationStore.baseline(parent, 0), now);
                ConversationStore.event(parent, now, "У тебя родился ребёнок: " + newborn.gender().offspring, 5);
                parent.revision++;
            }
            for (UUID parent : List.of(a, b)) {
                // The player parent: no profile of its own, and the caller named it.
                if (playerName == null || playerName.isBlank() || state.profiles.containsKey(parent.toString())) continue;
                ConversationStore.Relation r = store.relation(kid, parent);
                r.score = Hearts.OWN_CHILD;
                state.family.node(parent, playerName, PlayerIdentity.gender(config, parent), true);
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
        return node != null && node.player ? PlayerIdentity.gender(config, id) : p != null ? SocialProfile.genderOf(id) : PlayerIdentity.gender(config, id);
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
        List<Integer> scores = new ArrayList<>(), earthly = new ArrayList<>();
        int residents = 0;
        for (ConversationStore.Profile q : state.profiles.values()) {
            if (!villageId.equals(q.villageId) || !"minecraft:villager".equals(q.type) || state.family.dead(q.id)) continue;
            residents++;
            ConversationStore.Relation r = q.relations.get(player.toString());
            if (r != null) { scores.add(r.score); earthly.add(r.score - r.divine); }
        }
        VillageStanding.View view = VillageStanding.of(scores, residents, state.rumors.reputation(villageId, player, now));
        // Б1: the bell assembly exiles; the god's wrath alone does not (unless the setting allows it) — it leaves the player a suspect.
        if (state.divine != null && state.divine.exiled(villageId, player)) return view.as(VillageStanding.Rank.EXILE);
        if (view.rank() == VillageStanding.Rank.EXILE && !Tuning.get().divineMayExile
                && VillageStanding.of(earthly, residents, state.rumors.reputation(villageId, player, now, false)).rank() != VillageStanding.Rank.EXILE)
            return view.as(VillageStanding.Rank.SUSPECT);
        return view;
    }

    /** The living spouse of a villager, for the game (village weddings, births); null for none. */
    public UUID spouse(UUID mob) { synchronized (store) { return store.state.family.spouse(mob); } }
    /** The relatives of a mob in words. */
    public List<FamilyTree.Kin> family(UUID mob) { synchronized (store) { return store.state.family.kin(mob); } }

    /** The occasion for the villager's words about a thrown gift whose outcome the game already applied. */
    public static String giftCue(GestureIntent intent) { return SocialPrompt.thrownGift(intent); }
    /** The same cue for a gift handed over (not picked up from the ground). */
    public static String handGiftCue(GestureIntent intent) { return SocialPrompt.thrownGift(intent).replace("ты его подобрал", "ты взял его из рук игрока").replace("ты его не взял", "ты не взял его из рук игрока"); }

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
                String jealous = r.rapport.jealousy == null ? "" : r.rapport.jealousy.describe(now);
                if (!jealous.isEmpty()) line.append("; ").append(jealous);
            }
            VillageStanding.View standing = standing(store.state, p.villageId, player, now);
            if (standing.rank() != VillageStanding.Rank.STRANGER) line.append("; в деревне: ").append(standing.rank().word).append(" (").append(standing.points()).append(')');
            List<FamilyTree.Kin> kin = store.state.family.kin(mob);
            if (!kin.isEmpty()) line.append("; семья: ").append(String.join(", ", kin.stream().map(k -> k.word() + " " + (k.name().isBlank() ? "?" : k.name()) + (k.dead() ? " †" : "")).toList()));
            return line.toString();
        }
    }
}
