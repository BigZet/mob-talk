package io.github.mobtalk.core;

/** A gesture the model named in its answer together with the outcome the game decided before the request: what it does to the
 * relationship ({@code hearts}), the item that changes hands for a gift, ring or bouquet ({@code item}, "" otherwise), and a short
 * note for the prompt and the memory (the gift's category, why a proposal is refused; "" when none). The game takes the item and
 * applies the effects after the reply passed its checks. */
public record GestureIntent(Gesture gesture, Gesture.Outcome outcome, int hearts, String item, String note) {
    public GestureIntent {
        gesture = gesture == null ? Gesture.NONE : gesture;
        outcome = outcome == null ? Gesture.Outcome.FAIL : outcome;
        item = item == null ? "" : item;
        note = note == null ? "" : note;
    }
    public GestureIntent(Gesture gesture, Gesture.Outcome outcome, int hearts, String item) { this(gesture, outcome, hearts, item, ""); }
    private static final GestureIntent NONE = new GestureIntent(Gesture.NONE, Gesture.Outcome.FAIL, 0, "", "");
    public static GestureIntent none() { return NONE; }
    public boolean present() { return gesture != Gesture.NONE; }
    /** Whether an item leaves the player's hand: a gift that was taken (or a ring, a bouquet accepted). */
    public boolean takesItem() { return present() && !item.isBlank() && outcome.warm(); }
}
