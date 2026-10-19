package io.github.mobtalk.core;

import java.util.List;
import java.util.Map;

/** How the haggling is going, in one word for the HUD: «склоняется», «не уверен», «против». Worked out without the model, from
 * the mob's last move (an offer, an acceptance, a refusal), how far it went towards the floor, whether the player's own number
 * was within reach, and how the mob feels about the player right now (trust, mood, relationship). Deterministic and cheap enough
 * to recompute every second while the state drifts. Pure Java. */
public final class BargainMeter {
    private BargainMeter() { }

    /** From this score the mob leans towards the deal; at or below its negative the mob is against it. */
    public static final int THRESHOLD = 25;

    public enum Verdict {
        LEANING("leaning", "склоняется", 1), UNSURE("unsure", "не уверен", 0), AGAINST("against", "против", -1);
        /** The lang key suffix ({@code mobtalk.bargain.<key>}), the Russian word, and the sign for the colour. */
        public final String key, word; public final int sign;
        Verdict(String key, String word, int sign) { this.key = key; this.word = word; this.sign = sign; }
        public static Verdict of(int score) { return score >= THRESHOLD ? LEANING : score <= -THRESHOLD ? AGAINST : UNSURE; }
    }

    /** The mob's last move in the haggle. */
    public enum Kind { NONE, OFFER, ACCEPT, DECLINE }

    /** What the mob just did: {@code price} is the price it named (OFFER) or -1; {@code currentPrice} what the row charged before and
     * {@code minimumPrice} the lowest it could go (-1 when unknown); {@code asked} the number the player named, or -1. */
    public record Move(Kind kind, int price, int currentPrice, int minimumPrice, int asked) {
        public Move { if (kind == null) kind = Kind.NONE; }
        public static final Move NONE = new Move(Kind.NONE, -1, -1, -1, -1);
        /** How far towards the floor the offer went, 0..1; 0 when nothing was offered or the range is unknown. */
        double generosity() {
            if (kind != Kind.OFFER || price < 0 || currentPrice <= minimumPrice || minimumPrice < 0) return 0;
            return Math.max(0, Math.min(1, (currentPrice - price) / (double) (currentPrice - minimumPrice)));
        }
        /** The player's number is below what the mob may ever accept. */
        boolean askedTooLittle() { return asked > 0 && minimumPrice > 0 && asked < minimumPrice; }
        /** The mob met (or beat) the player's number. */
        boolean metTheAsk() { return asked > 0 && kind == Kind.OFFER && price > 0 && price <= asked; }
        boolean askedWithinReach() { return asked > 0 && minimumPrice > 0 && currentPrice > 0 && asked >= minimumPrice && asked < currentPrice; }
    }

    public record Reading(Verdict verdict, int score) { }

    /** The verdict for {@code move} given how the mob feels ({@code state}) and stands ({@code relationship}) towards the player. */
    public static Reading read(Move move, MobState.View state, int relationship) {
        if (move == null) move = Move.NONE;
        if (state == null) state = MobState.View.NEUTRAL;
        // Too shaken, too distrustful, or hating the player: the mob is not dealing, whatever it just said.
        if (state.refusesDeals() || relationship <= CombatRules.HATRED) return new Reading(Verdict.AGAINST, -100);
        double score = switch (move.kind()) { case OFFER, ACCEPT -> 35; case DECLINE -> -35; case NONE -> 0; };
        score += 15 * move.generosity();
        if (move.askedTooLittle()) score -= 20;
        else if (move.metTheAsk()) score += 15;
        else if (move.askedWithinReach()) score += 5;
        score += (state.trust() - 50) * 0.5;
        score += (state.mood() - 50) * 0.2;
        score += Math.max(-100, Math.min(100, relationship)) * 0.25;
        if (state.generous()) score += 10;
        int rounded = (int) Math.round(Math.max(-100, Math.min(100, score)));
        return new Reading(Verdict.of(rounded), rounded);
    }

    /** The move behind a delivered reply, read off the intents and the snapshot the model saw: the villager window row, a spoken
     * promise, or one of the mob's own deals. {@code playerText} gives the number the player asked for. Null when the reply did
     * not touch a price at all. */
    public static Move move(BargainIntent bargain, DealIntent deal, MobSnapshot snapshot, String playerText) {
        int asked = asked(playerText);
        TradingView view = snapshot == null ? null : TradingView.of(snapshot);
        if (deal != null && deal.type() != DealIntent.Type.NONE) {
            Map<?,?> own = view == null ? null : view.ownDeal(deal.dealId());
            int current = own != null && own.get("currentPrice") instanceof Number n ? n.intValue() : -1;
            int minimum = own != null && own.get("minPrice") instanceof Number n ? n.intValue() : -1;
            return new Move(deal.type() == DealIntent.Type.OFFER ? Kind.OFFER : Kind.DECLINE, deal.type() == DealIntent.Type.OFFER ? deal.price() : -1, current, minimum, asked);
        }
        if (bargain == null || bargain.type() == BargainIntent.Type.NONE || view == null) return null;
        Kind kind = switch (bargain.type()) { case OFFER -> Kind.OFFER; case ACCEPT -> Kind.ACCEPT; case DECLINE -> Kind.DECLINE; case NONE -> Kind.NONE; };
        Map<?,?> context = view.bargaining();
        if (view.windowOpen() && context != null) {
            int current = context.get("vanillaCount") instanceof Number n ? n.intValue() : -1;
            int minimum = context.get("minimumPrice") instanceof Number n ? n.intValue() : -1;
            if (context.get("state") instanceof Map<?,?> state && "ACCEPTED".equals(state.get("status")) && state.get("price") instanceof Number held) current = held.intValue();
            return new Move(kind, kind == Kind.OFFER && current > 0 ? current - bargain.discount() : -1, current, minimum, asked);
        }
        Map<?,?> row = SpokenOffer.row(view.spoken(), bargain.offerIndex());
        int current = row != null && row.get("currentPrice") instanceof Number n ? n.intValue() : -1;
        int minimum = row != null && row.get("minimumPrice") instanceof Number n ? n.intValue() : -1;
        return new Move(kind, kind == Kind.OFFER && current > 0 ? current - bargain.discount() : -1, current, minimum, asked);
    }

    /** The last number the player said (digits or Russian words), or -1; stack-sized numbers only. */
    static int asked(String playerText) {
        if (playerText == null || playerText.isBlank()) return -1;
        List<Integer> numbers = AnswerValidator.spokenNumbers(playerText);
        for (int i = numbers.size() - 1; i >= 0; i--) if (numbers.get(i) >= 1 && numbers.get(i) <= 999) return numbers.get(i);
        return -1;
    }
}
