package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.List;

/** How a creature feels right now, on four axes of 0..100: mood (gloomy to joyful), calm (panic to serenity), interest in the
 * player (bored to fascinated) and trust (wary to devoted). Each axis rests at a point set by the temperament (and, for trust,
 * by the relationship) and drifts back to it exponentially in real time: half of the deviation goes in {@link #HALF_LIFE};
 * what a blow, a killing or a big favour did is forgotten slower ({@link #SLOW_HALF_LIFE}). The drift is computed lazily
 * when the state is read, never by a tick. Besides the axes the mob keeps one short thought, the last thing it said to
 * itself, which colours its next line. Pure Java, persisted inside the profile; every mutation runs under the store lock. */
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
    /** How often being whole and unthreatened calms a mob down again. */
    static final long SAFE_COOLDOWN = 5 * 60_000L;
    /** The live half-lives from the settings. */
    static long halfLife() { return Tuning.get().stateHalfLifeMinutes * 60_000L; }
    static long slowHalfLife() { return Tuning.get().stateSlowHalfLifeMinutes * 60_000L; }

    public enum Axis { MOOD, CALM, INTEREST, TRUST }

    /** What happened and how far each axis moves (mood, calm, interest, trust); {@code slow} events are forgotten slower. */
    public enum Event {
        /** The player hit this mob. */
        HIT(-25, -35, 10, -35, true),
        /** It saw the player hit another creature. */
        WITNESSED_HIT(-10, -20, 5, -15, false),
        /** It saw the player kill another creature. */
        WITNESSED_KILL(-25, -35, 10, -30, true),
        /** It saw the player at a misdeed without a blow: a stranger's chest, a village plot, a work block, a door or a bed. Suspicion
         * more than fear: trust and calm go down, interest up. */
        MISCHIEF(-5, -10, 10, -15, false),
        INSULT(-15, -10, 0, -10, false),
        THREAT(-10, -30, 10, -25, true),
        APOLOGY(5, 10, 0, 10, false),
        KIND(10, 5, 5, 5, false),
        /** Real help seen by the game: healing, taming, feeding. */
        HELP(15, 10, 5, 20, true),
        PURCHASE(8, 0, 5, 8, false),
        /** The player kept a promise: brought what the mob asked for. */
        FAVOR_DONE(20, 10, 10, 25, true),
        /** The mob itself gave a price: it has invested in this player. */
        CONCESSION(0, 0, 5, 5, false),
        /** The plain fact of a conversation. */
        TALK(3, 2, 12, 3, false),
        /** Whole and unthreatened for a while. */
        SAFE(5, 15, 0, 0, false),
        /** Social layer ({@link GestureTable}): a joke that landed, a hug returned — and one that did not. */
        GESTURE_WARM(6, 3, 5, 5, false), GESTURE_COLD(-5, -3, 0, -4, false),
        /** A gift taken with pleasure, or one that offended (allergy, a thing it hates). */
        GIFT_LIKED(10, 5, 5, 8, false), GIFT_SPURNED(-6, -2, 0, -5, false),
        /** Family events: a courtship or wedding, a divorce, a death in the family, a child born. */
        ROMANCE(15, 5, 10, 15, true), DIVORCE(-20, -15, 0, -30, true), BEREAVED(-35, -20, -10, 0, true), CHILD_BORN(20, 10, 5, 15, true),
        /** Трек J: ревность к сопернику (доверие падает, интерес к игроку растёт) и ночь, проведённая рядом с игроком. */
        JEALOUSY(-10, -8, 8, -15, false), NIGHT_TOGETHER(12, 12, 5, 20, true);

        final int mood, calm, interest, trust; final boolean slow;
        Event(int mood, int calm, int interest, int trust, boolean slow) { this.mood = mood; this.calm = calm; this.interest = interest; this.trust = trust; this.slow = slow; }
        int delta(Axis axis) { return switch (axis) { case MOOD -> mood; case CALM -> calm; case INTEREST -> interest; case TRUST -> trust; }; }
    }

    /** One axis on disk: {@code value} below 0 means "at rest, wherever the baseline is now". */
    static final class Value {
        double value = -1; long halfLife = halfLife();
        Value copy() { Value c = new Value(); c.value = value; c.halfLife = halfLife; return c; }
    }
    Value mood = new Value(), calm = new Value(), interest = new Value(), trust = new Value();
    long updatedAt, safeAt;
    String thought = "";

    /** Where each axis rests: the temperament compressed so that no creature is born in panic, trust following the relationship. */
    public record Baseline(int mood, int calm, int interest, int trust) {
        public static Baseline of(int calm, int confidence, int curiosity, int sociability, int relationship) {
            int c = axis(calm), conf = axis(confidence), cur = axis(curiosity), soc = axis(sociability);
            int rel = Math.max(-100, Math.min(100, relationship));
            return new Baseline((int) Math.round(35 + (conf + soc) * 0.15), (int) Math.round(30 + c * 0.45),
                    (int) Math.round(25 + cur * 0.4), (int) Math.round(45 + rel * 0.35));
        }
        private static int axis(int value) { return value < 0 || value > 100 ? 50 : value; }
        /** The same rest with the mood point moved by a character ({@link Persona#moodRest}). */
        public Baseline moodShifted(int shift) { return shift == 0 ? this : new Baseline(Math.max(0, Math.min(100, mood + shift)), calm, interest, trust); }
        int of(Axis axis) { return switch (axis) { case MOOD -> mood; case CALM -> calm; case INTEREST -> interest; case TRUST -> trust; }; }
    }

    private Value value(Axis axis) { return switch (axis) { case MOOD -> mood; case CALM -> calm; case INTEREST -> interest; case TRUST -> trust; }; }

    /** Where an axis stands now without touching it: the deviation halves every {@code halfLife}. */
    private static double current(Value v, int baseline, long now, long updatedAt) {
        if (v.value < 0) return baseline;
        long elapsed = Math.max(0, now - updatedAt);
        double deviation = (v.value - baseline) * Math.pow(0.5, elapsed / (double) Math.max(1, v.halfLife));
        return Math.max(0, Math.min(100, baseline + deviation));
    }

    /** Applies the drift up to {@code now}; an axis back within a point of its rest forgets its slow half-life. */
    void settle(Baseline baseline, long now) {
        for (Axis axis : Axis.values()) {
            Value v = value(axis);
            if (v.value < 0) continue;
            v.value = current(v, baseline.of(axis), now, updatedAt);
            if (Math.abs(v.value - baseline.of(axis)) < 1) { v.value = -1; v.halfLife = halfLife(); }
        }
        updatedAt = now;
    }

    /** Moves the axes by what happened. Ordinary events add up; a slow (important) one lands at least its full weight from
     * the rest point, so a few kind words never cushion a blow and a gloomy mood never blunts a kept promise; it also makes
     * every axis it touched linger. Safety only brings calm back up to its rest, never above it. */
    public void shift(Event event, Baseline baseline, long now) {
        settle(baseline, now);
        for (Axis axis : Axis.values()) {
            int delta = event.delta(axis);
            if (delta == 0) continue;
            Value v = value(axis);
            int base = baseline.of(axis);
            double was = v.value < 0 ? base : v.value, next = was + delta;
            if (event.slow) next = delta < 0 ? Math.min(next, base + delta) : Math.max(next, base + delta);
            if (event == Event.SAFE) next = Math.min(next, Math.max(was, base));
            v.value = Math.max(0, Math.min(100, next));
            if (event.slow) v.halfLife = Math.max(v.halfLife, slowHalfLife());
            if (Math.abs(v.value - baseline.of(axis)) < 1) { v.value = -1; v.halfLife = halfLife(); }
        }
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
        return new View((int) Math.round(current(mood, baseline.mood(), now, updatedAt)), (int) Math.round(current(calm, baseline.calm(), now, updatedAt)),
                (int) Math.round(current(interest, baseline.interest(), now, updatedAt)), (int) Math.round(current(trust, baseline.trust(), now, updatedAt)),
                thought == null ? "" : thought);
    }

    void sanitize() {
        if (mood == null) mood = new Value(); if (calm == null) calm = new Value(); if (interest == null) interest = new Value(); if (trust == null) trust = new Value();
        for (Axis axis : Axis.values()) {
            Value v = value(axis);
            if (!(v.value >= 0 && v.value <= 100)) v.value = -1;
            if (v.halfLife < 1_000 || v.halfLife > 24 * 60 * 60_000L) v.halfLife = halfLife();
        }
        thought = cleanThought(thought);
    }
    MobState copy() {
        MobState c = new MobState(); c.mood = mood.copy(); c.calm = calm.copy(); c.interest = interest.copy(); c.trust = trust.copy();
        c.updatedAt = updatedAt; c.safeAt = safeAt; c.thought = thought; return c;
    }

    /** One word on one axis: the lang key for the HUD, the Russian word for the prompt, and the sign for the colour. */
    public record Descriptor(Axis axis, String key, String word, int sign) { }

    /** The state as numbers and words, detached from the store. */
    public record View(int mood, int calm, int interest, int trust, String thought) {
        public View { thought = thought == null ? "" : thought; }
        public static final View NEUTRAL = new View(50, 50, 45, 45, "");

        /** Too anxious or too distrustful to haggle or close a deal. */
        public boolean refusesDeals() { MobTalkConfig t = Tuning.get(); return calm < t.waryCalm || trust < t.waryTrust; }
        /** Interested enough to turn to the player on its own. */
        public boolean keen() { return interest >= Tuning.get().keenInterest; }
        /** Cheerful and trusting: concessions come easier. */
        public boolean generous() { MobTalkConfig t = Tuning.get(); return mood >= t.generousMood && trust >= t.generousTrust; }

        public int of(Axis axis) { return switch (axis) { case MOOD -> mood; case CALM -> calm; case INTEREST -> interest; case TRUST -> trust; }; }

        /** The word for one axis; the neutral band of mood and calm has a word, that of interest and trust is silence. */
        public Descriptor descriptor(Axis axis) {
            int v = of(axis);
            int level = v < 20 ? 0 : v < 40 ? 1 : v <= 60 ? 2 : v < 80 ? 3 : 4;
            int sign = level < 2 ? -1 : level > 2 ? 1 : 0;
            String[] keys = switch (axis) {
                case MOOD -> new String[]{"gloomy", "down", "even", "cheerful", "joyful"};
                case CALM -> new String[]{"panicked", "anxious", "calm", "composed", "serene"};
                case INTEREST -> new String[]{"bored", "indifferent", "", "curious", "fascinated"};
                case TRUST -> new String[]{"distrustful", "wary", "", "trusting", "devoted"};
            };
            String[] words = switch (axis) {
                case MOOD -> new String[]{"мрачен", "не в духе", "ровно", "в духе", "сияет"};
                case CALM -> new String[]{"в панике", "встревожен", "спокоен", "невозмутим", "безмятежен"};
                case INTEREST -> new String[]{"скучает", "безразличен", "", "заинтересован", "увлечён"};
                case TRUST -> new String[]{"не доверяет", "насторожен", "", "доверяет", "предан"};
            };
            return new Descriptor(axis, keys[level], words[level], sign);
        }

        /** Every axis that has a word, calm first. */
        public List<Descriptor> words() {
            List<Descriptor> list = new ArrayList<>();
            for (Axis axis : List.of(Axis.CALM, Axis.MOOD, Axis.TRUST, Axis.INTEREST)) {
                Descriptor d = descriptor(axis);
                if (!d.key().isEmpty()) list.add(d);
            }
            return list;
        }

        /** The one word for the HUD: the axis furthest from its middle, calm when nothing stands out. */
        public Descriptor marker() {
            Descriptor best = null; int strength = -1;
            for (Axis axis : List.of(Axis.CALM, Axis.MOOD, Axis.TRUST, Axis.INTEREST)) {
                Descriptor d = descriptor(axis);
                int far = Math.abs(of(axis) - 50);
                if (d.sign() != 0 && far > strength) { best = d; strength = far; }
            }
            return best != null ? best : descriptor(Axis.CALM);
        }

        /** The block the model reads: the state in words, second person, never numbers. */
        public String describe() {
            int m = mood, c = calm, i = interest, t = trust;
            String moodText = m < 20 ? "мрачно на душе" : m < 40 ? "не в духе" : m <= 60 ? "ровное" : m < 80 ? "в духе" : "душа поёт";
            String calmText = c < 20 ? "паника: сбиваешься, дёргаешься, слова путаются" : c < 40 ? "встревожен, на взводе, ждёшь беды" : c <= 60 ? "спокоен"
                    : c < 80 ? "невозмутим" : "безмятежен, ничто не тревожит";
            String interestText = i < 20 ? "этот игрок тебе наскучил, отвечаешь нехотя и коротко" : i < 40 ? "этот игрок тебе безразличен" : i <= 60 ? "к этому игроку ни скуки, ни особого интереса"
                    : i < 80 ? "этот игрок тебе интересен, охотно поддерживаешь разговор" : "этот игрок тебя по-настоящему увлёк: сам тянешься к разговору, расспрашиваешь";
            String trustText = t < 20 ? "ему не веришь ни слову, ждёшь удара в спину" : t < 40 ? "настороже, ждёшь подвоха" : t <= 60 ? "ни доверия, ни недоверия"
                    : t < 80 ? "ему доверяешь" : "доверяешь как своему";
            StringBuilder text = new StringBuilder("Твоё состояние сейчас: настроение — ").append(moodText).append("; нервы — ").append(calmText)
                    .append("; интерес — ").append(interestText).append("; доверие — ").append(trustText).append('.');
            if (!thought.isEmpty()) text.append("\nТвоя последняя мысль про себя: «").append(thought).append("» — продолжай с неё, если она ещё к месту.");
            return text.toString();
        }
    }

    /** The view for a detached profile: its own state (a fresh one when none) against its temperament and relationship. */
    public static View viewOf(ConversationStore.Profile profile, long now) {
        if (profile == null) return View.NEUTRAL;
        MobState state = profile.state == null ? new MobState() : profile.state;
        return state.view(SocialLedger.baseline(profile, Baseline.of(profile.calm, profile.confidence, profile.curiosity, profile.sociability, profile.relationship)), now);
    }
}
