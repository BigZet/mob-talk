package io.github.mobtalk.core;

import java.util.*;

/** «Исход заранее» (docs/mca-social-spec.md §7.2): for one reply, the outcome of every gesture the player could make right now,
 * decided by the game before the request. The prompt reads them as facts, the schema offers only these gestures, the validator
 * turns the one the model named into its {@link GestureIntent}. The rolls hold until the next reply. {@code closed} explains in
 * words what is out of the question (romance with a child or a relative). */
public final class SocialTurn {
    private final Map<Gesture, GestureIntent> rolled;
    private final List<String> closed, facts;

    public SocialTurn(Map<Gesture, GestureIntent> rolled, List<String> closed) { this(rolled, closed, List.of()); }
    /** {@code facts}: what the villager and this player are to each other (a couple, a parent and child), for the prompt. */
    public SocialTurn(Map<Gesture, GestureIntent> rolled, List<String> closed, List<String> facts) {
        this.rolled = Collections.unmodifiableMap(new LinkedHashMap<>(rolled == null ? Map.of() : rolled));
        this.closed = closed == null ? List.of() : List.copyOf(closed);
        this.facts = facts == null ? List.of() : List.copyOf(facts);
    }
    public List<String> facts() { return facts; }
    public Map<Gesture, GestureIntent> rolled() { return rolled; }
    public List<String> closed() { return closed; }
    /** The intent for the gesture the model named; none for NONE or a gesture that was not on offer. */
    public GestureIntent intent(Gesture gesture) { GestureIntent intent = gesture == null ? null : rolled.get(gesture); return intent == null ? GestureIntent.none() : intent; }
    /** The enum of the schema's gesture field: NONE and every gesture on offer. */
    public List<String> schemaValues() {
        List<String> values = new ArrayList<>(); values.add(Gesture.NONE.name());
        for (Gesture gesture : rolled.keySet()) values.add(gesture.name());
        return values;
    }

    /** Draws the outcome of each ordinary gesture in {@code offered} with {@code dice} (0..1 per draw). */
    public static Map<Gesture, GestureIntent> roll(GestureTable.Context context, Collection<Gesture> offered, java.util.function.DoubleSupplier dice) {
        Map<Gesture, GestureIntent> map = new LinkedHashMap<>();
        for (Gesture gesture : offered) {
            List<GestureTable.Option> options = GestureTable.options(gesture, context);
            GestureTable.Option chosen = GestureTable.roll(options, dice.getAsDouble());
            if (chosen != null) map.put(gesture, new GestureIntent(gesture, chosen.outcome(), chosen.hearts(), ""));
        }
        return map;
    }
}
