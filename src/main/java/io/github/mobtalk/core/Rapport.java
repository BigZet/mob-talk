package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.List;

/** One villager's social bookkeeping with one player, kept inside its relation: fatigue from gestures (spec §3.3), the last gesture
 * and how many times it came in a row, the gifts it was given lately (§3.4 saturation) and where the two stand romantically.
 * Real time throughout, like the rest of the world memory; every mutation runs under the store lock. */
public final class Rapport {
    /** Fatigue drops by one every four minutes of real time. */
    public static final long FATIGUE_STEP_MILLIS = 4 * 60_000L;
    /** The same gesture again within this time counts as a repeat in a row. */
    public static final long REPEAT_WINDOW_MILLIS = 10 * 60_000L;
    /** The gift memory: at most this many, the oldest forgotten after {@link #GIFT_FORGET_MILLIS}. */
    public static final int GIFT_QUEUE = 16;
    public static final long GIFT_FORGET_MILLIS = 20 * 60_000L;

    int fatigue; long fatigueAt;
    String lastGesture = ""; int repeats; long lastGestureAt = -1;
    /** Item ids of accepted gifts with when they were given, oldest first. */
    List<Given> gifts = new ArrayList<>();
    /** Where the forgetting of gifts counts from. */
    long forgetAt;
    /** {@link Courtship} by name. */
    String courtship = "";
    /** When the two last tried for a child (real millis), −1 for never. */
    long lastChildMillis = -1;
    /** Ревность и ночи вместе ({@link Jealousy}); null, пока не было ни того, ни другого. */
    Jealousy jealousy;
    /** Трек L: кольцо, принятое при помолвке (и на свадьбе), id предмета; свидетели, которых назвал игрок (UUID, "" — нет); когда
     * сыграна свадьба (реальные мс, −1 — не было). */
    String ring = "", witnessMan = "", witnessWoman = "";
    long weddingMillis = -1;
    public record Given(String item, long time) { }
    /** Ревность этой пары, создаётся при первом обращении. */
    public Jealousy jealousy() { if (jealousy == null) jealousy = new Jealousy(); return jealousy; }

    /** Fatigue now, after its lazy decay. */
    public int fatigue(long now) {
        if (fatigue <= 0) return 0;
        long steps = Math.max(0, now - fatigueAt) / (Tuning.get().gestureFatigueMinutes * 60_000L);
        return (int) Math.max(0, fatigue - steps);
    }
    /** How many times this gesture came right before (0 for a different one or after a pause). */
    public int repeats(Gesture gesture, long now) {
        if (gesture == null || !gesture.name().equals(lastGesture) || lastGestureAt < 0 || now - lastGestureAt > REPEAT_WINDOW_MILLIS) return 0;
        return repeats + 1;
    }
    /** A gesture: the repeat count follows it; fatigue +1 unless it is a gift (gifts never tire). */
    public void record(Gesture gesture, long now) { record(gesture, now, gesture != Gesture.GIFT); }
    /** {@code tires}: the gesture changed the relationship (spec §3.3: only those tire); a refused proposal does not. */
    public void record(Gesture gesture, long now, boolean tires) {
        int next = repeats(gesture, now);
        if (tires && gesture != Gesture.GIFT) {
            fatigue = fatigue(now) + 1;
            fatigueAt = now;
        }
        repeats = next; lastGesture = gesture.name(); lastGestureAt = now;
    }
    /** Accepted gifts still remembered at {@code now}, oldest first. */
    public List<Given> gifts(long now) {
        forget(now);
        return List.copyOf(gifts);
    }
    /** How many times this item is among the remembered gifts. */
    public int given(String item, long now) {
        forget(now);
        int count = 0;
        for (Given given : gifts) if (given.item().equals(item)) count++;
        return count;
    }
    public void gave(String item, long now) {
        forget(now);
        gifts.add(new Given(item, now));
        while (gifts.size() > GIFT_QUEUE) gifts.removeFirst();
    }
    /** One oldest gift forgotten per {@link #GIFT_FORGET_MILLIS} of real time, as MCA forgets one a game day. */
    private void forget(long now) {
        gifts.removeIf(given -> given == null || given.item() == null);
        if (gifts.isEmpty()) { forgetAt = now; return; }
        long every = Tuning.get().giftForgetMinutes * 60_000L, steps = Math.max(0, now - forgetAt) / every;
        for (long i = 0; i < steps && !gifts.isEmpty(); i++) gifts.removeFirst();
        forgetAt += steps * every;
    }
    public Courtship courtship() { return Courtship.of(courtship); }
    public void courtship(Courtship state) { courtship = state == null || state == Courtship.NONE ? "" : state.name(); }

    void sanitize() {
        fatigue = Math.max(0, Math.min(50, fatigue)); repeats = Math.max(0, Math.min(50, repeats));
        if (lastGesture == null) lastGesture = "";
        if (gifts == null) gifts = new ArrayList<>();
        gifts.removeIf(given -> given == null || given.item() == null || given.item().isBlank());
        while (gifts.size() > GIFT_QUEUE) gifts.removeFirst();
        if (courtship == null) courtship = "";
        if (jealousy != null) jealousy.sanitize();
        if (ring == null) ring = ""; if (witnessMan == null) witnessMan = ""; if (witnessWoman == null) witnessWoman = "";
    }
    Rapport copy() {
        Rapport c = new Rapport(); c.fatigue = fatigue; c.fatigueAt = fatigueAt; c.lastGesture = lastGesture; c.repeats = repeats;
        c.lastGestureAt = lastGestureAt; c.gifts = new ArrayList<>(gifts); c.forgetAt = forgetAt; c.courtship = courtship; c.lastChildMillis = lastChildMillis;
        c.jealousy = jealousy == null ? null : jealousy.copy();
        c.ring = ring; c.witnessMan = witnessMan; c.witnessWoman = witnessWoman; c.weddingMillis = weddingMillis;
        return c;
    }
}
