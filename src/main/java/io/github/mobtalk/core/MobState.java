package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** How a creature feels right now, on five axes of 0..100: mood (gloomy to joyful), calm (panic to serenity), interest in the
 * player (bored to fascinated), trust (wary to devoted) and energy (worn out to full of life). Each axis rests at a point set by
 * the temperament (and, for trust, by the relationship) and drifts back to it exponentially in real time: half of the deviation
 * goes in {@link #HALF_LIFE}; what a blow, a killing or a big favour did is forgotten slower ({@link #SLOW_HALF_LIFE}).
 *
 * <p>Three things move the rest point itself, so a feeling can last: the world around ({@link MoodFactor}: the rain, a raid,
 * hunger, a curse, a friend near) for as long as it lasts, and a long mood ({@link MoodSpell}: grief, depression, dread, joy…)
 * the creature falls into when one feeling holds too long or after a big event. Every push and every lasting factor is kept as
 * a reason, so the mob knows why it feels so and the player can read it. The drift is computed lazily when the state is read,
 * never by a tick; the long moods start and end in {@link #evolve}. Besides the axes the mob keeps one short thought, the last
 * thing it said to itself, which colours its next line. Pure Java, persisted inside the profile; every mutation runs under the
 * store lock. */
public final class MobState {
    /** Ordinary impressions: half of the deviation is gone in ten minutes (the default of {@link MobTalkConfig#stateHalfLifeMinutes}). */
    public static final long HALF_LIFE = 10 * 60_000L;
    /** A blow, a killing, a death threat, a big favour: half of it is still there after half an hour. */
    public static final long SLOW_HALF_LIFE = 30 * 60_000L;
    /** Below either, the mob makes no concessions and closes no deals (defaults; the live values are in {@link MobTalkConfig}). */
    public static final int WARY_CALM = 25, WARY_TRUST = 25;
    /** From this interest an acquainted mob turns to the player on its own when they come near. */
    public static final int KEEN_INTEREST = 75;
    /** From these two the mob haggles more generously. */
    public static final int GENEROUS_MOOD = 65, GENEROUS_TRUST = 65;
    /** A thought is one short phrase. */
    public static final int THOUGHT_LIMIT = 160;
    /** How many reasons a view names (the strongest), and how many pushes the mob remembers at all. */
    public static final int REASONS = 4, CAUSES = 8;
    /** At most this many players have a regard of their own; the one met longest ago is forgotten first. */
    static final int REGARDS = 64;
    /** How often being whole and unthreatened calms a mob down again. */
    static final long SAFE_COOLDOWN = 5 * 60_000L;
    /** Frights and irritations count within this window: three of either tip a mob into dread or bitterness. */
    static final long SPELL_WINDOW = 15 * 60_000L;
    /** A long mood that ended by itself does not come back at once. */
    static final long SPELL_PAUSE = 30 * 60_000L;
    private static final long MINUTE = 60_000L;
    /** The live half-lives from the settings. */
    static long halfLife() { return Tuning.get().stateHalfLifeMinutes * 60_000L; }
    static long slowHalfLife() { return Tuning.get().stateSlowHalfLifeMinutes * 60_000L; }

    public enum Axis { MOOD, CALM, INTEREST, TRUST, ENERGY }

    /** What happened and how far each axis moves (mood, calm, interest, trust, energy); {@code slow} events are forgotten slower;
     * {@code cause} is the reason the mob remembers (a noun phrase, null for the small change of a chat). */
    public enum Event {
        /** The player hit this mob. */
        HIT(-25, -35, 10, -35, -5, true, "удар"),
        /** It saw the player hit another creature. */
        WITNESSED_HIT(-10, -20, 5, -15, 0, false, "драка на глазах"),
        /** It saw the player kill another creature. */
        WITNESSED_KILL(-25, -35, 10, -30, -5, true, "убийство на глазах"),
        /** It saw the player at a misdeed without a blow: a stranger's chest, a village plot, a work block, a door or a bed. Suspicion
         * more than fear: trust and calm go down, interest up. */
        MISCHIEF(-5, -10, 10, -15, 0, false, "шарят по чужому"),
        INSULT(-15, -10, 0, -10, 0, false, "грубость"),
        THREAT(-10, -30, 10, -25, 0, true, "угроза"),
        APOLOGY(5, 10, 0, 10, 0, false, "извинение"),
        KIND(10, 5, 5, 5, 0, false, "доброе слово"),
        /** Real help seen by the game: healing, taming, feeding. */
        HELP(15, 10, 5, 20, 5, true, "помощь"),
        PURCHASE(8, 0, 5, 8, 0, false, "удачная торговля"),
        /** The player kept a promise: brought what the mob asked for. */
        FAVOR_DONE(20, 10, 10, 25, 0, true, "выполненная просьба"),
        /** The mob itself gave a price: it has invested in this player. */
        CONCESSION(0, 0, 5, 5, 0, false, null),
        /** The plain fact of a conversation. */
        TALK(3, 2, 12, 3, 0, false, null),
        /** Whole and unthreatened for a while. */
        SAFE(5, 15, 0, 0, 0, false, null),
        /** Social layer ({@link GestureTable}): a joke that landed, a hug returned — and one that did not. */
        GESTURE_WARM(6, 3, 5, 5, 0, false, "тёплый жест"), GESTURE_COLD(-5, -3, 0, -4, 0, false, "неловкий жест"),
        /** A gift taken with pleasure, or one that offended (allergy, a thing it hates). */
        GIFT_LIKED(10, 5, 5, 8, 0, false, "подарок"), GIFT_SPURNED(-6, -2, 0, -5, 0, false, "неприятный подарок"),
        /** Family events: a courtship or wedding, a divorce, a death in the family, a child born. */
        ROMANCE(15, 5, 10, 15, 5, true, "роман"), DIVORCE(-20, -15, 0, -30, -5, true, "развод"),
        BEREAVED(-35, -20, -10, 0, -10, true, "смерть родного"), CHILD_BORN(20, 10, 5, 15, 0, true, "рождение ребёнка"),
        /** Трек J: ревность к сопернику (доверие падает, интерес к игроку растёт) и ночь, проведённая рядом с игроком. */
        JEALOUSY(-10, -8, 8, -15, 0, false, "ревность"), NIGHT_TOGETHER(12, 12, 5, 20, 10, true, "ночь вместе"),
        /** The village around: a neighbour died, a wedding or a child in the village. */
        VILLAGE_DEATH(-12, -10, 0, 0, 0, true, "смерть соседа"), VILLAGE_WEDDING(8, 3, 0, 0, 3, false, "свадьба в деревне"),
        VILLAGE_BIRTH(6, 2, 0, 0, 0, false, "прибавление в деревне"),
        /** A night in its own bed, and a meal when it was hungry. */
        GOOD_SLEEP(5, 5, 0, 0, 30, false, "хороший сон"), FED(8, 0, 0, 0, 15, false, "сытная еда"),
        /** The divine block: a punishment over the village, a miracle seen. */
        DIVINE_WRATH(-12, -25, 0, 0, -5, true, "Божья кара"), DIVINE_MIRACLE(15, 15, 0, 0, 10, true, "Божье чудо");

        final int mood, calm, interest, trust, energy; final boolean slow; final String cause;
        Event(int mood, int calm, int interest, int trust, int energy, boolean slow, String cause) {
            this.mood = mood; this.calm = calm; this.interest = interest; this.trust = trust; this.energy = energy; this.slow = slow; this.cause = cause;
        }
        int delta(Axis axis) { return switch (axis) { case MOOD -> mood; case CALM -> calm; case INTEREST -> interest; case TRUST -> trust; case ENERGY -> energy; }; }
        /** How much good it does (cures a bad long mood) and how much harm (breaks a good one); interest is neither. */
        int warmth() { return Math.max(0, mood) + Math.max(0, calm) + Math.max(0, trust) + Math.max(0, energy); }
        int harm() { return Math.max(0, -mood) + Math.max(0, -calm) + Math.max(0, -trust) + Math.max(0, -energy); }
        int sign() { int sum = mood + calm + trust + energy; return sum > 0 ? 1 : sum < 0 ? -1 : 0; }
    }
    private static final Set<Event> SCARY = Set.of(Event.HIT, Event.THREAT, Event.WITNESSED_KILL, Event.DIVINE_WRATH);
    private static final Set<Event> IRRITATING = Set.of(Event.INSULT, Event.MISCHIEF, Event.GIFT_SPURNED, Event.GESTURE_COLD, Event.JEALOUSY, Event.WITNESSED_HIT, Event.HIT);
    private static final Set<Event> SOCIAL = Set.of(Event.TALK, Event.GESTURE_WARM, Event.GESTURE_COLD, Event.GIFT_LIKED, Event.KIND, Event.ROMANCE,
            Event.NIGHT_TOGETHER, Event.APOLOGY, Event.PURCHASE, Event.FAVOR_DONE, Event.HELP);

    /** One axis on disk: {@code value} below 0 means "at rest, wherever the rest point is now". */
    static final class Value {
        double value = -1; long halfLife = halfLife();
        Value copy() { Value c = new Value(); c.value = value; c.halfLife = halfLife; return c; }
    }
    /** A remembered push: what it was, how strong ({@code weight}, the sum of its deltas) and when; it fades with its half-life. */
    static final class Cause {
        String key = "", text = ""; double weight; long at, halfLife = HALF_LIFE; int sign;
        double weight(long now) { return weight * Math.pow(0.5, Math.max(0, now - at) / (double) Math.max(1, halfLife)); }
        Cause copy() { Cause c = new Cause(); c.key = key; c.text = text; c.weight = weight; c.at = at; c.halfLife = halfLife; c.sign = sign; return c; }
    }
    /** A lasting factor: active while {@code ended} is below 0, then fading out over the factor's linger time. */
    static final class Background {
        String factor = ""; long since, ended = -1;
        double fade(long now) {
            MoodFactor f = MoodFactor.of(factor);
            if (f == null) return 0;
            if (ended < 0) return 1;
            double linger = Math.max(1, f.lingerMinutes) * MINUTE;
            return Math.max(0, 1 - Math.max(0, now - ended) / linger);
        }
        Background copy() { Background c = new Background(); c.factor = factor; c.since = since; c.ended = ended; return c; }
    }
    /** The long mood on disk: its kind, when it began, when it may end at the earliest and at the latest, and the cure gathered. */
    static final class Spell {
        String kind = ""; long since, until, max; int cure;
        Spell copy() { Spell c = new Spell(); c.kind = kind; c.since = since; c.until = until; c.max = max; c.cure = cure; return c; }
    }

    /** Trust in one player and interest in them, as deviations from their rest (the rest follows that player's relationship):
     * each player has their own, so a blow from one never makes the mob wary of another. */
    static final class Regard {
        double trust, interest; long trustHalfLife = halfLife(), interestHalfLife = halfLife(), at;
        double deviation(Axis axis, long now) {
            double d = axis == Axis.TRUST ? trust : interest;
            long life = axis == Axis.TRUST ? trustHalfLife : interestHalfLife;
            return d * Math.pow(0.5, Math.max(0, now - at) / (double) Math.max(1, life));
        }
        Regard copy() { Regard c = new Regard(); c.trust = trust; c.interest = interest; c.trustHalfLife = trustHalfLife; c.interestHalfLife = interestHalfLife; c.at = at; return c; }
    }

    /** Mood, calm and energy are the mob's own; {@code trust} and {@code interest} here are only the fallback for a player it has no
     * regard of its own for yet (and what saves from before the per-player regards carried). */
    Value mood = new Value(), calm = new Value(), interest = new Value(), trust = new Value(), energy = new Value();
    Map<String, Regard> regards = new LinkedHashMap<>();
    long updatedAt, safeAt;
    String thought = "";
    List<Cause> causes = new ArrayList<>();
    Map<String, Background> backgrounds = new LinkedHashMap<>();
    Spell spell;
    /** When a feeling began to hold (0: it does not), and the last spell that ended by itself. */
    long lowMoodSince, lowCalmSince, sourSince, lowEnergySince, highMoodSince, socialAt, lastSpellAt;
    String lastSpell = "";
    List<Long> scares = new ArrayList<>(), irritations = new ArrayList<>();
    /** The body, as the world watch saw it: when food was last in the larder, the sleep (the night index it slept, the one it
     * was seen awake and how often), and whether it is short of a night's sleep. */
    long fedAt, asleepSince, sleptNight = Long.MIN_VALUE, awakeNight = Long.MIN_VALUE; int awakeCount; boolean asleep, sleepless;

    /** Where each axis rests: the temperament compressed so that no creature is born in panic, trust following the relationship;
     * {@code resilience} (from calm and confidence) and {@code sociability} decide how easily it falls into a long mood. */
    public record Baseline(int mood, int calm, int interest, int trust, int energy, int resilience, int sociability, String player) {
        public Baseline(int mood, int calm, int interest, int trust, int energy, int resilience, int sociability) { this(mood, calm, interest, trust, energy, resilience, sociability, null); }
        /** The same rest as seen by one player: trust and interest are read and moved in that player's own regard. */
        public Baseline toward(String player) { return new Baseline(mood, calm, interest, trust, energy, resilience, sociability, player); }
        public static Baseline of(int calm, int confidence, int curiosity, int sociability, int relationship) {
            int c = axis(calm), conf = axis(confidence), cur = axis(curiosity), soc = axis(sociability);
            int rel = Math.max(-100, Math.min(100, relationship));
            return new Baseline((int) Math.round(35 + (conf + soc) * 0.15), (int) Math.round(30 + c * 0.45),
                    (int) Math.round(25 + cur * 0.4), (int) Math.round(45 + rel * 0.35), (int) Math.round(55 + conf * 0.1), (c + conf) / 2, soc);
        }
        private static int axis(int value) { return value < 0 || value > 100 ? 50 : value; }
        /** The same rest with the mood point moved by a character ({@link Persona#moodRest}). */
        public Baseline moodShifted(int shift) { return shift == 0 ? this : new Baseline(Math.max(0, Math.min(100, mood + shift)), calm, interest, trust, energy, resilience, sociability, player); }
        int of(Axis axis) { return switch (axis) { case MOOD -> mood; case CALM -> calm; case INTEREST -> interest; case TRUST -> trust; case ENERGY -> energy; }; }
    }

    private Value value(Axis axis) { return switch (axis) { case MOOD -> mood; case CALM -> calm; case INTEREST -> interest; case TRUST -> trust; case ENERGY -> energy; }; }

    private static boolean personal(Axis axis, Baseline baseline) { return baseline.player() != null && (axis == Axis.TRUST || axis == Axis.INTEREST); }

    /** Where trust or interest in this player stands: their own regard, else the shared fallback. */
    private double personalValue(Axis axis, Baseline baseline, long now) {
        int rest = rest(baseline, axis, now);
        Regard g = regards.get(baseline.player());
        if (g == null) return current(value(axis), rest, now, updatedAt);
        return Math.max(0, Math.min(100, rest + g.deviation(axis, now)));
    }

    /** Moves trust or interest in one player; a first event starts their regard from where the shared fallback stood. */
    private void shiftPersonal(Axis axis, int delta, boolean slow, Baseline baseline, long now) {
        int rest = rest(baseline, axis, now);
        Regard g = regards.get(baseline.player());
        if (g == null) {
            g = new Regard();
            for (Axis a : List.of(Axis.TRUST, Axis.INTEREST)) {
                double legacy = current(value(a), rest(baseline, a, now), now, updatedAt) - rest(baseline, a, now);
                if (a == Axis.TRUST) g.trust = legacy; else g.interest = legacy;
            }
            g.at = now;
            regards.put(baseline.player(), g);
        } else {
            g.trust = g.deviation(Axis.TRUST, now); g.interest = g.deviation(Axis.INTEREST, now); g.at = now;
        }
        double was = rest + (axis == Axis.TRUST ? g.trust : g.interest), next = was + delta;
        if (slow) next = delta < 0 ? Math.min(next, rest + delta) : Math.max(next, rest + delta);
        double deviation = Math.max(0, Math.min(100, next)) - rest;
        long life = axis == Axis.TRUST ? g.trustHalfLife : g.interestHalfLife;
        if (Math.abs(deviation) < 1) { deviation = 0; life = halfLife(); }
        else if (slow) life = Math.max(life, slowHalfLife());
        if (axis == Axis.TRUST) { g.trust = deviation; g.trustHalfLife = life; } else { g.interest = deviation; g.interestHalfLife = life; }
        if (regards.size() > REGARDS) regards.entrySet().stream().min(java.util.Comparator.comparingLong(e -> e.getValue().at))
                .map(Map.Entry::getKey).ifPresent(regards::remove);
    }

    /** The long mood now, or null. */
    MoodSpell spell() { return spell == null ? null : MoodSpell.of(spell.kind); }

    /** Where an axis rests now: the temperament's point moved by the lasting factors and the long mood. */
    int rest(Baseline baseline, Axis axis, long now) {
        double shift = 0;
        double world = Tuning.get().moodWorldPercent / 100.0;
        for (Background b : backgrounds.values()) {
            MoodFactor f = MoodFactor.of(b.factor);
            if (f != null) shift += f.delta(axis) * world * b.fade(now);
        }
        MoodSpell kind = spell();
        if (kind != null) shift += kind.delta(axis);
        return (int) Math.round(Math.max(0, Math.min(100, baseline.of(axis) + shift)));
    }

    /** Where an axis stands now without touching it: the deviation halves every {@code halfLife}. */
    private static double current(Value v, int rest, long now, long updatedAt) {
        if (v.value < 0) return rest;
        long elapsed = Math.max(0, now - updatedAt);
        double deviation = (v.value - rest) * Math.pow(0.5, elapsed / (double) Math.max(1, v.halfLife));
        return Math.max(0, Math.min(100, rest + deviation));
    }

    /** Applies the drift up to {@code now}; an axis back within a point of its rest forgets its slow half-life. Faded factors and
     * forgotten pushes are dropped. */
    void settle(Baseline baseline, long now) {
        for (Axis axis : Axis.values()) {
            Value v = value(axis);
            if (v.value < 0) continue;
            int rest = rest(baseline, axis, now);
            v.value = current(v, rest, now, updatedAt);
            if (Math.abs(v.value - rest) < 1) { v.value = -1; v.halfLife = halfLife(); }
        }
        backgrounds.values().removeIf(b -> b.fade(now) <= 0);
        causes.removeIf(c -> c.weight(now) < 3);
        regards.values().removeIf(g -> Math.abs(g.deviation(Axis.TRUST, now)) < 1 && Math.abs(g.deviation(Axis.INTEREST, now)) < 1);
        updatedAt = now;
    }

    /** Pins every axis at rest to where it stands, so a moving rest point pulls it there with the half-life instead of a jump. */
    private void anchor(Baseline baseline, long now) {
        for (Axis axis : Axis.values()) {
            Value v = value(axis);
            if (v.value < 0) v.value = rest(baseline, axis, now);
        }
    }

    public void shift(Event event, Baseline baseline, long now) { shift(event, baseline, now, ""); }

    /** Moves the axes by what happened. Ordinary events add up; a slow (important) one lands at least its full weight from
     * the rest point, so a few kind words never cushion a blow and a gloomy mood never blunts a kept promise; it also makes
     * every axis it touched linger. Safety only brings calm back up to its rest, never above it. The event is remembered as a
     * reason ({@code detail}: who or what, e.g. the player's name), counts towards curing or breaking a long mood, and a big one
     * starts a long mood of its own (a death in the family: grief). */
    public void shift(Event event, Baseline baseline, long now, String detail) {
        settle(baseline, now);
        for (Axis axis : Axis.values()) {
            int delta = event.delta(axis);
            if (delta == 0) continue;
            if (personal(axis, baseline)) { shiftPersonal(axis, delta, event.slow, baseline, now); continue; }
            Value v = value(axis);
            int base = rest(baseline, axis, now);
            double was = v.value < 0 ? base : v.value, next = was + delta;
            if (event.slow) next = delta < 0 ? Math.min(next, base + delta) : Math.max(next, base + delta);
            if (event == Event.SAFE) next = Math.min(next, Math.max(was, base));
            v.value = Math.max(0, Math.min(100, next));
            if (event.slow) v.halfLife = Math.max(v.halfLife, slowHalfLife());
            if (Math.abs(v.value - base) < 1) { v.value = -1; v.halfLife = halfLife(); }
        }
        remember(event, detail, now);
        if (SCARY.contains(event)) window(scares, now).add(now);
        if (IRRITATING.contains(event)) window(irritations, now).add(now);
        if (SOCIAL.contains(event)) socialAt = now;
        MoodSpell kind = spell();
        if (kind != null) {
            int points = kind.sign < 0 ? (event == Event.SAFE && kind != MoodSpell.DREAD ? 0 : event.warmth()) : event.harm();
            if (kind == MoodSpell.EXHAUSTION) points = event == Event.GOOD_SLEEP ? kind.cure : 0;
            if (kind == MoodSpell.LONELY) points = SOCIAL.contains(event) ? kind.cure : 0;
            spell.cure += points;
        }
        MoodSpell starts = switch (event) {
            case BEREAVED -> MoodSpell.GRIEF; case ROMANCE, NIGHT_TOGETHER -> MoodSpell.LOVE; case CHILD_BORN -> MoodSpell.JOY;
            case DIVINE_MIRACLE -> MoodSpell.GRACE; default -> null;
        };
        if (starts != null) begin(starts, baseline, now);
        // An exhaustion ends the moment the mob slept; a lonely one the moment someone talks to it.
        if (kind != null && (kind == MoodSpell.EXHAUSTION || kind == MoodSpell.LONELY) && spell != null && kind.name().equals(spell.kind) && spell.cure >= kind.cure) end(baseline, now, false);
    }

    private void remember(Event event, String detail, long now) {
        if (event.cause == null) return;
        String who = detail == null ? "" : detail.strip();
        String text = who.isEmpty() ? event.cause : event.cause + " (" + TextBounds.characters(who, 40) + ")";
        String key = event.name() + "|" + who;
        Cause cause = causes.stream().filter(c -> c.key.equals(key)).findFirst().orElse(null);
        double weight = event.warmth() + event.harm();
        if (cause == null) { cause = new Cause(); cause.key = key; causes.add(cause); }
        else weight = Math.min(weight * 3, cause.weight(now) + weight);
        cause.text = text; cause.weight = weight; cause.at = now; cause.sign = event.sign();
        cause.halfLife = event.slow ? slowHalfLife() : halfLife();
        if (causes.size() > CAUSES) { causes.sort(Comparator.comparingDouble((Cause c) -> c.weight(now)).reversed()); causes.subList(CAUSES, causes.size()).clear(); }
    }

    private static List<Long> window(List<Long> times, long now) { times.removeIf(t -> t == null || now - t > SPELL_WINDOW || t > now); return times; }

    /** The world around right now: the factors that hold switch on, those gone start to fade. A sudden factor moves the axis
     * at once, a slow one lets it drift. Returns whether anything changed. */
    public boolean surroundings(Set<MoodFactor> active, Baseline baseline, long now) {
        settle(baseline, now);
        boolean changed = false, slow = false;
        for (MoodFactor f : active) {
            Background b = backgrounds.get(f.key());
            if (b != null && b.ended < 0) continue;
            if (!f.sudden) slow = true;
            changed = true;
        }
        for (Background b : backgrounds.values()) if (b.ended < 0 && active.stream().noneMatch(f -> f.key().equals(b.factor))) { changed = true; slow = true; }
        if (!changed) return false;
        if (slow) anchor(baseline, now);
        for (MoodFactor f : active) {
            Background b = backgrounds.get(f.key());
            if (b != null && b.ended < 0) continue;
            // A sudden factor jumps: an axis away from rest (or pinned there just now) is pushed along with its rest point.
            if (f.sudden) for (Axis axis : Axis.values()) {
                Value v = value(axis);
                if (f.delta(axis) != 0 && v.value >= 0) v.value = Math.max(0, Math.min(100, v.value + f.delta(axis) * Tuning.get().moodWorldPercent / 100.0));
            }
            if (b == null) { b = new Background(); b.factor = f.key(); b.since = now; backgrounds.put(f.key(), b); }
            b.ended = -1;
        }
        for (Background b : backgrounds.values()) if (b.ended < 0 && active.stream().noneMatch(f -> f.key().equals(b.factor))) b.ended = now;
        return true;
    }

    /** Whether a lasting factor holds now. */
    public boolean has(MoodFactor factor) { Background b = backgrounds.get(factor.key()); return b != null && b.ended < 0; }

    /** The long moods: ends the one that ran its course, then lets a feeling that held long enough tip the mob into one. Called
     * whenever the store looks at the mob (a conversation, the world watch). Returns whether a long mood began or ended. */
    public boolean evolve(Baseline baseline, long now) {
        settle(baseline, now);
        if (socialAt <= 0) socialAt = now;
        int percent = Tuning.get().moodSpellPercent;
        if (percent <= 0) { boolean had = spell != null; spell = null; return had; }
        boolean changed = false;
        MoodSpell kind = spell();
        if (spell != null && kind == null) { spell = null; changed = true; }
        if (kind != null && (now >= spell.max || now >= spell.until && spell.cure >= kind.cure)) {
            boolean grieving = kind == MoodSpell.GRIEF;
            end(baseline, now, true);
            changed = true;
            // A grief that never healed turns into a depression.
            if (grieving && view(baseline, now).mood() < 30) begin(MoodSpell.DEPRESSION, baseline, now);
        }
        View v = view(baseline, now);
        lowMoodSince = held(lowMoodSince, v.mood() < 25, now);
        lowCalmSince = held(lowCalmSince, v.calm() < 20, now);
        sourSince = held(sourSince, v.mood() < 35 && v.calm() < 45, now);
        lowEnergySince = held(lowEnergySince, v.energy() < 20, now);
        highMoodSince = held(highMoodSince, v.mood() >= 82, now);
        double scale = 100.0 / percent, fall = scale * (0.7 + 0.6 * baseline.resilience() / 100.0);
        MoodSpell next = null;
        if (since(lowMoodSince, now) >= 20 * fall) next = MoodSpell.DEPRESSION;
        else if (since(lowCalmSince, now) >= 5 * fall || window(scares, now).size() >= 3) next = MoodSpell.DREAD;
        else if (window(irritations, now).size() >= 3 && v.calm() >= 20 || since(sourSince, now) >= 12 * fall) next = MoodSpell.ANGER;
        else if (since(lowEnergySince, now) >= 8 * fall) next = MoodSpell.EXHAUSTION;
        else if (baseline.sociability() >= 55 && since(socialAt, now) >= 120 * fall) next = MoodSpell.LONELY;
        else if (since(highMoodSince, now) >= 6 * scale) next = MoodSpell.JOY;
        if (next != null && begin(next, baseline, now)) {
            changed = true;
            switch (next) {
                case DEPRESSION -> lowMoodSince = now; case DREAD -> { lowCalmSince = now; scares.clear(); }
                case ANGER -> { sourSince = now; irritations.clear(); } case EXHAUSTION -> lowEnergySince = now;
                case LONELY -> socialAt = now; case JOY -> highMoodSince = now; default -> { }
            }
        }
        return changed;
    }
    private static long held(long since, boolean holds, long now) { return holds ? since > 0 ? since : now : 0; }
    private static double since(long since, long now) { return since <= 0 ? -1 : (now - since) / (double) MINUTE; }

    /** Starts a long mood unless a stronger one holds or the same one just ended by itself; the same one again lasts longer. The
     * axes drift to the new rest point rather than jump. Returns whether it started (or was prolonged). */
    boolean begin(MoodSpell kind, Baseline baseline, long now) {
        if (kind == null || Tuning.get().moodSpellPercent <= 0) return false;
        MoodSpell current = spell();
        if (current != null && current != kind && !current.yieldsTo(kind)) return false;
        if (current == null && kind.name().equals(lastSpell) && now - lastSpellAt < SPELL_PAUSE && kind.sign < 0 && kind != MoodSpell.GRIEF) return false;
        double length = kind.sign < 0 ? 1.3 - 0.6 * baseline.resilience() / 100.0 : 1;
        if (current == kind) { spell.until = Math.max(spell.until, now + Math.round(kind.minMinutes * length * MINUTE)); spell.max = Math.max(spell.max, now + Math.round(kind.maxMinutes * length * MINUTE)); return true; }
        anchor(baseline, now);
        Spell s = new Spell();
        s.kind = kind.name(); s.since = now;
        s.until = now + Math.round(kind.minMinutes * length * MINUTE); s.max = now + Math.round(kind.maxMinutes * length * MINUTE);
        spell = s;
        return true;
    }

    /** Ends the long mood; the axes drift back from where they are. */
    void end(Baseline baseline, long now, boolean byItself) {
        if (spell == null) return;
        anchor(baseline, now);
        if (byItself) { lastSpell = spell.kind; lastSpellAt = now; }
        spell = null;
    }

    /** Whole and unthreatened: calms down, at most once per {@link #SAFE_COOLDOWN}. Returns whether anything changed. */
    public boolean safe(Baseline baseline, long now) {
        if (safeAt > 0 && now >= safeAt && now - safeAt < SAFE_COOLDOWN) return false;
        safeAt = now;
        shift(Event.SAFE, baseline, now);
        return true;
    }

    /** Keeps the model's thought as the mob's own; blank or wordless text keeps the previous one. */
    public void think(String text) {
        String clean = cleanThought(text);
        if (!clean.isEmpty()) thought = clean;
    }
    /** One phrase without wrapping quotes, at most {@link #THOUGHT_LIMIT} characters; "" when there are no words in it. */
    public static String cleanThought(String text) {
        if (text == null) return "";
        String clean = text.strip().replaceAll("^[\"«“'\\s]+", "").replaceAll("[\"»”'\\s]+$", "").strip();
        if (clean.codePoints().noneMatch(Character::isLetter)) return "";
        return TextBounds.characters(clean, THOUGHT_LIMIT);
    }

    /** A read-only picture at {@code now}, without moving anything. */
    public View view(Baseline baseline, long now) {
        int[] at = new int[Axis.values().length];
        for (Axis axis : Axis.values())
            at[axis.ordinal()] = (int) Math.round(personal(axis, baseline) ? personalValue(axis, baseline, now) : current(value(axis), rest(baseline, axis, now), now, updatedAt));
        List<Reason> reasons = new ArrayList<>();
        double world = Tuning.get().moodWorldPercent / 100.0;
        for (Background b : backgrounds.values()) {
            MoodFactor f = MoodFactor.of(b.factor);
            if (f == null) continue;
            double weight = (Math.abs(f.mood) + Math.abs(f.calm) + Math.abs(f.energy)) * world * b.fade(now);
            reasons.add(new Reason(f.text, f.sign(), (int) Math.round(weight), b.ended < 0));
        }
        for (Cause c : causes) reasons.add(new Reason(c.text, c.sign, (int) Math.round(c.weight(now)), false));
        reasons.removeIf(r -> r.weight() < 3);
        reasons.sort(Comparator.comparingInt(Reason::weight).reversed());
        MoodSpell kind = spell();
        return new View(at[Axis.MOOD.ordinal()], at[Axis.CALM.ordinal()], at[Axis.INTEREST.ordinal()], at[Axis.TRUST.ordinal()], at[Axis.ENERGY.ordinal()],
                kind, kind == null ? 0 : Math.max(0, now - spell.since), List.copyOf(reasons.subList(0, Math.min(REASONS, reasons.size()))), thought == null ? "" : thought);
    }

    void sanitize() {
        if (mood == null) mood = new Value(); if (calm == null) calm = new Value(); if (interest == null) interest = new Value(); if (trust == null) trust = new Value();
        if (energy == null) energy = new Value();
        for (Axis axis : Axis.values()) {
            Value v = value(axis);
            if (!(v.value >= 0 && v.value <= 100)) v.value = -1;
            if (v.halfLife < 1_000 || v.halfLife > 24 * 60 * 60_000L) v.halfLife = halfLife();
        }
        thought = cleanThought(thought);
        if (causes == null) causes = new ArrayList<>();
        causes.removeIf(c -> c == null || c.text == null || c.text.isBlank() || !(c.weight >= 0) || c.halfLife < 1_000);
        if (causes.size() > CAUSES) causes.subList(CAUSES, causes.size()).clear();
        if (backgrounds == null) backgrounds = new LinkedHashMap<>();
        backgrounds.entrySet().removeIf(e -> e.getValue() == null || MoodFactor.of(e.getValue().factor) == null);
        if (spell != null && (MoodSpell.of(spell.kind) == null || spell.max < spell.since)) spell = null;
        if (lastSpell == null) lastSpell = "";
        if (regards == null) regards = new LinkedHashMap<>();
        regards.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || !Double.isFinite(e.getValue().trust) || !Double.isFinite(e.getValue().interest));
        for (Regard g : regards.values()) {
            g.trust = Math.max(-100, Math.min(100, g.trust)); g.interest = Math.max(-100, Math.min(100, g.interest));
            if (g.trustHalfLife < 1_000 || g.trustHalfLife > 24 * 60 * 60_000L) g.trustHalfLife = halfLife();
            if (g.interestHalfLife < 1_000 || g.interestHalfLife > 24 * 60 * 60_000L) g.interestHalfLife = halfLife();
        }
        if (scares == null) scares = new ArrayList<>(); scares.removeIf(java.util.Objects::isNull);
        if (irritations == null) irritations = new ArrayList<>(); irritations.removeIf(java.util.Objects::isNull);
    }
    MobState copy() {
        MobState c = new MobState(); c.mood = mood.copy(); c.calm = calm.copy(); c.interest = interest.copy(); c.trust = trust.copy(); c.energy = energy.copy();
        c.updatedAt = updatedAt; c.safeAt = safeAt; c.thought = thought;
        for (Cause cause : causes) c.causes.add(cause.copy());
        backgrounds.forEach((k, b) -> c.backgrounds.put(k, b.copy()));
        c.spell = spell == null ? null : spell.copy();
        regards.forEach((k, g) -> c.regards.put(k, g.copy()));
        c.lowMoodSince = lowMoodSince; c.lowCalmSince = lowCalmSince; c.sourSince = sourSince; c.lowEnergySince = lowEnergySince; c.highMoodSince = highMoodSince;
        c.socialAt = socialAt; c.lastSpellAt = lastSpellAt; c.lastSpell = lastSpell;
        c.scares = new ArrayList<>(scares); c.irritations = new ArrayList<>(irritations);
        c.fedAt = fedAt; c.asleepSince = asleepSince; c.sleptNight = sleptNight; c.awakeNight = awakeNight; c.awakeCount = awakeCount; c.asleep = asleep; c.sleepless = sleepless;
        return c;
    }

    /** One word on one axis: the lang key for the HUD, the Russian word for the prompt, and the sign for the colour. */
    public record Descriptor(Axis axis, String key, String word, int sign) { }
    /** Why the mob feels so: a lasting factor (still holding: {@code lasting}) or a remembered push, with its present weight. */
    public record Reason(String text, int sign, int weight, boolean lasting) { }

    /** The state as numbers and words, detached from the store; {@code spell} is the long mood (null: none), {@code spellMillis}
     * how long it has held, {@code reasons} the strongest reasons first. */
    public record View(int mood, int calm, int interest, int trust, int energy, MoodSpell spell, long spellMillis, List<Reason> reasons, String thought) {
        public View {
            thought = thought == null ? "" : thought;
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
        /** Just the four old axes: rested energy, no long mood, no reasons. */
        public View(int mood, int calm, int interest, int trust, String thought) { this(mood, calm, interest, trust, 60, null, 0, List.of(), thought); }
        public static final View NEUTRAL = new View(50, 50, 45, 45, 60, null, 0, List.of(), "");

        /** Too anxious or too distrustful to haggle or close a deal; a dread closes every deal. */
        public boolean refusesDeals() { MobTalkConfig t = Tuning.get(); return calm < t.waryCalm || trust < t.waryTrust || spell != null && spell.deals == MoodSpell.Deals.REFUSES; }
        /** Interested enough to turn to the player on its own; a lonely or happy one does, a grieving, depressed or scared one never. */
        public boolean keen() { return spell != null && spell.keen != null ? spell.keen : interest >= Tuning.get().keenInterest; }
        /** Cheerful and trusting, or in a bright long mood: concessions come easier; never in a dark one. */
        public boolean generous() {
            if (spell != null && spell.deals == MoodSpell.Deals.HARDER) return false;
            MobTalkConfig t = Tuning.get();
            return spell != null && spell.deals == MoodSpell.Deals.GENEROUS && trust >= t.waryTrust || mood >= t.generousMood && trust >= t.generousTrust;
        }
        /** Bitter, grieving or depressed: holds the price and haggles grudgingly. */
        public boolean grudging() { return spell != null && spell.deals == MoodSpell.Deals.HARDER; }

        public int of(Axis axis) { return switch (axis) { case MOOD -> mood; case CALM -> calm; case INTEREST -> interest; case TRUST -> trust; case ENERGY -> energy; }; }

        /** The word for one axis; the neutral band of mood and calm has a word, that of interest, trust and energy is silence. */
        public Descriptor descriptor(Axis axis) {
            int v = of(axis);
            int level = v < 20 ? 0 : v < 40 ? 1 : v <= 60 ? 2 : v < 80 ? 3 : 4;
            int sign = level < 2 ? -1 : level > 2 ? 1 : 0;
            String[] keys = switch (axis) {
                case MOOD -> new String[]{"gloomy", "down", "even", "cheerful", "joyful"};
                case CALM -> new String[]{"panicked", "anxious", "calm", "composed", "serene"};
                case INTEREST -> new String[]{"bored", "indifferent", "", "curious", "fascinated"};
                case TRUST -> new String[]{"distrustful", "wary", "", "trusting", "devoted"};
                case ENERGY -> new String[]{"drained", "tired", "", "lively", "vigorous"};
            };
            String[] words = switch (axis) {
                case MOOD -> new String[]{"мрачен", "не в духе", "ровно", "в духе", "сияет"};
                case CALM -> new String[]{"в панике", "встревожен", "спокоен", "невозмутим", "безмятежен"};
                case INTEREST -> new String[]{"скучает", "безразличен", "", "заинтересован", "увлечён"};
                case TRUST -> new String[]{"не доверяет", "насторожен", "", "доверяет", "предан"};
                case ENERGY -> new String[]{"без сил", "устал", "", "бодр", "полон сил"};
            };
            return new Descriptor(axis, keys[level], words[level], sign);
        }

        /** Every axis that has a word, calm first. */
        public List<Descriptor> words() {
            List<Descriptor> list = new ArrayList<>();
            for (Axis axis : List.of(Axis.CALM, Axis.MOOD, Axis.ENERGY, Axis.TRUST, Axis.INTEREST)) {
                Descriptor d = descriptor(axis);
                if (!d.key().isEmpty()) list.add(d);
            }
            return list;
        }

        /** The one word for the HUD: the long mood when there is one, else the axis furthest from its middle, calm when nothing stands out. */
        public Descriptor marker() {
            if (spell != null) return new Descriptor(Axis.MOOD, spell.key, spell.word, spell.sign);
            Descriptor best = null; int strength = -1;
            for (Axis axis : List.of(Axis.CALM, Axis.MOOD, Axis.ENERGY, Axis.TRUST, Axis.INTEREST)) {
                Descriptor d = descriptor(axis);
                int far = Math.abs(of(axis) - 50);
                if (d.sign() != 0 && far > strength) { best = d; strength = far; }
            }
            return best != null ? best : descriptor(Axis.CALM);
        }

        /** The block the model reads: the state in words, second person, never numbers; the long mood and the reasons after it. */
        public String describe() {
            int m = mood, c = calm, i = interest, t = trust, e = energy;
            String moodText = m < 20 ? "мрачно на душе" : m < 40 ? "не в духе" : m <= 60 ? "ровное" : m < 80 ? "в духе" : "душа поёт";
            String calmText = c < 20 ? "паника: сбиваешься, дёргаешься, слова путаются" : c < 40 ? "встревожен, на взводе, ждёшь беды" : c <= 60 ? "спокоен"
                    : c < 80 ? "невозмутим" : "безмятежен, ничто не тревожит";
            String energyText = e < 20 ? "без сил, валишься с ног" : e < 40 ? "устал" : e <= 60 ? "обычные" : e < 80 ? "бодр" : "полон сил";
            String interestText = i < 20 ? "этот игрок тебе наскучил, отвечаешь нехотя и коротко" : i < 40 ? "этот игрок тебе безразличен" : i <= 60 ? "к этому игроку ни скуки, ни особого интереса"
                    : i < 80 ? "этот игрок тебе интересен, охотно поддерживаешь разговор" : "этот игрок тебя по-настоящему увлёк: сам тянешься к разговору, расспрашиваешь";
            String trustText = t < 20 ? "ему не веришь ни слову, ждёшь удара в спину" : t < 40 ? "настороже, ждёшь подвоха" : t <= 60 ? "ни доверия, ни недоверия"
                    : t < 80 ? "ему доверяешь" : "доверяешь как своему";
            StringBuilder text = new StringBuilder("Твоё состояние сейчас: настроение — ").append(moodText).append("; нервы — ").append(calmText)
                    .append("; силы — ").append(energyText).append("; интерес — ").append(interestText).append("; доверие — ").append(trustText).append('.');
            if (spell != null) text.append("\nДолгое состояние, оно держится давно и красит всё, что ты говоришь: ").append(spell.prompt);
            if (!reasons.isEmpty())
                text.append("\nОтчего тебе так (ты это знаешь сам; назови, если спросят, как ты, или к слову придётся): ")
                        .append(String.join("; ", reasons.stream().map(Reason::text).toList())).append('.');
            if (!thought.isEmpty()) text.append("\nТвоя последняя мысль про себя: «").append(thought).append("» — продолжай с неё, если она ещё к месту.");
            return text.toString();
        }

        /** What should be heard in every phrase, for the end of the prompt; "" when the state is ordinary. */
        public String voice() {
            if (spell != null) return spell.voice;
            if (calm < 20) return "паника: сбивчиво, коротко, озираешься";
            if (mood < 20) return "мрачен: скупо, без радости, ворчливо";
            if (energy < 20) return "без сил: зеваешь, коротко, мысли о сне";
            if (mood >= 82 && calm >= 50) return "сияешь: охотно, тепло, с шутками";
            if (calm < 35) return "встревожен: отрывисто, настороженно";
            if (mood < 35) return "не в духе: сухо, без охоты";
            return "";
        }

        /** How long a reply the state allows, percent of the usual. */
        public int lengthPercent() {
            if (spell != null) return spell.lengthPercent;
            if (calm < 20) return 50;
            if (mood < 20 || energy < 20) return 60;
            if (calm < 35 || mood < 35 || energy < 35) return 80;
            return 100;
        }
    }

    /** The view for a detached profile: its own state (a fresh one when none) against its temperament and relationship. */
    public static View viewOf(ConversationStore.Profile profile, long now) {
        if (profile == null) return View.NEUTRAL;
        MobState state = profile.state == null ? new MobState() : profile.state;
        return state.view(SocialLedger.baseline(profile, Baseline.of(profile.calm, profile.confidence, profile.curiosity, profile.sociability, profile.relationship)), now);
    }
}
