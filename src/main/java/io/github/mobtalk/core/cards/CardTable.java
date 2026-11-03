package io.github.mobtalk.core.cards;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.random.RandomGenerator;

/** One card table: a villager holding the bank against up to {@link Limits#seats()} players. The whole game runs here, on the server:
 * stakes, the deal, every decision of the villager, the trickster's sleeve and the settlement. The model never decides a move; the
 * game layer feeds in what players pressed and turns {@link #drainEvents()} into packets, speech and memory. Emeralds stay outside:
 * the server takes a stake from the player before {@link #bet} and pays {@link Settlement#returned()} back after a deal.
 *
 * <p>A deal goes BETTING (stakes go down) → PLAYERS (each hand in seat order: hit, stand, double, split, va-bank) → DEALER (the hidden
 * card turns, the villager draws by his {@link DealerStyle}) → SETTLED (stakes paid, «Ловлю!» may still be pressed) → BETTING. */
public final class CardTable {
    /** The villager's seat in events and views. */
    public static final int DEALER = -1;

    public enum Phase { BETTING, PLAYERS, DEALER, SETTLED }
    /** Why a press was refused; {@link #NONE} when it went through. */
    public enum Refusal { NONE, PHASE, SEAT, TURN, LIMIT, BANK, FUNDS, NOT_ALLOWED }

    /** Seats, the stake range per hand and whether the villager may cheat at all. */
    public record Limits(int seats, int minBet, int maxBet, boolean cheating) {
        public Limits {
            seats = Math.max(1, Math.min(4, seats));
            minBet = Math.max(1, minBet);
            maxBet = Math.max(minBet, maxBet);
        }
        public static Limits standard() { return new Limits(4, 1, 16, true); }
    }
    /** What one seat put in and got back in one deal. */
    public record Settlement(int seat, int staked, int returned) { public int net() { return returned - staked; } }
    /** The answer to a press: refused or not, and the emeralds it cost the player (double, split, va-bank). */
    public record Outcome(Refusal refusal, int paid) {
        public boolean ok() { return refusal == Refusal.NONE; }
        static Outcome refused(Refusal refusal) { return new Outcome(refusal, 0); }
    }
    /** The answer to «Ловлю!»: whether he was caught and what the catch paid on top of the settlement. */
    public record Accusation(Refusal refusal, boolean caught, int extra) { }

    static final class Seat {
        String name;
        int pendingBet;
        boolean split;
        final List<Hand> hands = new ArrayList<>();
        int staked() { int sum = pendingBet; for (Hand hand : hands) sum += hand.bet; return sum; }
    }

    private final Variant variant;
    private final DealerStyle style;
    private final RandomGenerator random;
    private final Shoe shoe;
    private final Limits limits;
    private final Seat[] seats;
    private final List<Card> dealer = new ArrayList<>();
    private final List<TableEvent> events = new ArrayList<>();
    private final List<Settlement> settlements = new ArrayList<>();
    private final boolean[] accused;
    private int purse, round, turnSeat = -1, turnHand, dealerStop;
    private boolean holeShown, cheated;
    private Phase phase = Phase.BETTING;

    public CardTable(Variant variant, DealerStyle style, RandomGenerator random, Shoe shoe, Limits limits, int purse) {
        this.variant = variant;
        this.style = style == null ? DealerStyle.CAUTIOUS : style;
        this.random = random;
        this.shoe = shoe == null ? new Shoe(variant, random) : shoe;
        this.limits = limits == null ? Limits.standard() : limits;
        this.seats = new Seat[this.limits.seats()];
        this.accused = new boolean[this.limits.seats()];
        this.purse = Math.max(0, purse);
    }

    public Variant variant() { return variant; }
    public DealerStyle style() { return style; }
    public Limits limits() { return limits; }
    public Phase phase() { return phase; }
    public int purse() { return purse; }
    public int round() { return round; }
    /** Поправить банк после раздачи: часть ставки была не изумрудами (игра на интерес, услуга), и банк не должен от неё ни расти,
     * ни худеть. Ниже нуля банк не уходит. */
    public void adjust(int delta) { purse = Math.max(0, purse + delta); }
    public int turnSeat() { return turnSeat; }
    public int turnHand() { return turnHand; }
    /** Whether the villager used his sleeve this deal (only the server knows until someone presses «Ловлю!»). */
    public boolean cheated() { return cheated; }
    public List<Card> dealerCards() { return List.copyOf(dealer); }
    public List<Settlement> settlements() { return List.copyOf(settlements); }
    public String seatName(int seat) { return valid(seat) && seats[seat] != null ? seats[seat].name : null; }
    public boolean taken(int seat) { return valid(seat) && seats[seat] != null; }
    public List<Hand> hands(int seat) { return taken(seat) ? List.copyOf(seats[seat].hands) : List.of(); }
    /** Emeralds this seat has on the table right now (pending stake and stakes in play). */
    public int staked(int seat) { return taken(seat) ? seats[seat].staked() : 0; }
    public int occupied() { int n = 0; for (Seat seat : seats) if (seat != null) n++; return n; }
    /** The first free seat, or -1. */
    public int freeSeat() { for (int i = 0; i < seats.length; i++) if (seats[i] == null) return i; return -1; }

    /** Everything that happened since the last call, in order. */
    public List<TableEvent> drainEvents() {
        List<TableEvent> out = List.copyOf(events);
        events.clear();
        return out;
    }

    // ---------------------------------------------------------------- seats and stakes

    public Refusal sit(int seat, String name) {
        if (!valid(seat)) return Refusal.SEAT;
        if (seats[seat] != null) return Refusal.SEAT;
        Seat taken = new Seat();
        taken.name = name == null ? "" : name;
        seats[seat] = taken;
        return Refusal.NONE;
    }

    /** The player gets up. With {@code refund} (a disconnect, a crash, a cancelled deal) his stakes go back; without it (he chose to
     * leave mid-deal) stakes already dealt on are lost to the bank and only a pending stake comes back. Returns the emeralds to give
     * back to him. */
    public int leave(int seat, boolean refund) {
        if (!taken(seat)) return 0;
        Seat gone = seats[seat];
        int back = gone.pendingBet;
        if (phase != Phase.SETTLED)
            for (Hand hand : gone.hands) {
                if (refund) back += hand.bet;
                else purse += hand.bet;
            }
        seats[seat] = null;
        if (phase == Phase.PLAYERS && turnSeat == seat) advance();
        return back;
    }

    /** Puts {@code amount} more emeralds on this seat's stake for the next deal. */
    public Refusal bet(int seat, int amount) {
        if (phase != Phase.BETTING) return Refusal.PHASE;
        if (!taken(seat)) return Refusal.SEAT;
        int stake = seats[seat].pendingBet + amount;
        if (amount <= 0 || stake > limits.maxBet()) return Refusal.LIMIT;
        if (pendingExposure() - maxWinBefore(seats[seat].pendingBet) + maxWinBefore(stake) > purse) return Refusal.BANK;
        seats[seat].pendingBet = stake;
        events.add(new TableEvent(TableEvent.Kind.BET, seat, 0, null, amount));
        return Refusal.NONE;
    }

    /** Takes {@code amount} back off the pending stake; returns what came back. */
    public int unbet(int seat, int amount) {
        if (phase != Phase.BETTING || !taken(seat) || amount <= 0) return 0;
        int back = Math.min(amount, seats[seat].pendingBet);
        seats[seat].pendingBet -= back;
        if (back > 0) events.add(new TableEvent(TableEvent.Kind.BET, seat, 0, null, -back));
        return back;
    }

    // ---------------------------------------------------------------- the deal

    /** Deals a new hand to every seat with a stake of at least {@link Limits#minBet()}. */
    public Refusal deal() {
        if (phase != Phase.BETTING) return Refusal.PHASE;
        boolean any = false;
        for (Seat seat : seats) if (seat != null && seat.pendingBet >= limits.minBet()) any = true;
        if (!any) return Refusal.LIMIT;
        round++;
        cheated = false;
        Arrays.fill(accused, false);
        settlements.clear();
        dealer.clear();
        holeShown = false;
        if (shoe.wantsShuffle()) {
            shoe.shuffle();
            events.add(TableEvent.of(TableEvent.Kind.SHUFFLE, DEALER, 0));
        }
        for (Seat seat : seats) {
            if (seat == null) continue;
            seat.hands.clear();
            seat.split = false;
            if (seat.pendingBet >= limits.minBet()) seat.hands.add(new Hand(seat.pendingBet));
            seat.pendingBet = seat.pendingBet >= limits.minBet() ? 0 : seat.pendingBet;
        }
        dealerStop = variant == Variant.OCHKO ? style.stopAt(random) : 17;
        dealAround();
        if (variant == Variant.BLACKJACK) {
            dealDealer(true);
            dealAround();
        }
        dealDealer(false);
        phase = Phase.PLAYERS;
        for (int s = 0; s < seats.length; s++) {
            if (seats[s] == null) continue;
            for (int h = 0; h < seats[s].hands.size(); h++) {
                Hand hand = seats[s].hands.get(h);
                if (hand.natural(variant)) {
                    hand.stood = true;
                    events.add(TableEvent.of(TableEvent.Kind.NATURAL, s, h));
                }
            }
        }
        if (variant == Variant.BLACKJACK && variant.natural(dealer, false)) {
            dealerTurn();
            return Refusal.NONE;
        }
        turnSeat = -1;
        advance();
        return Refusal.NONE;
    }

    /** The emeralds a press would cost this player beyond his stake: a double or a split costs the stake again, va-bank whatever the
     * bank still covers. 0 when the press costs nothing or is not possible. */
    public int cost(int seat, Action action) {
        Hand hand = current(seat);
        if (hand == null) return 0;
        return switch (action) {
            case DOUBLE, SPLIT -> hand.bet;
            case VA_BANK -> Math.max(0, purse - exposureExcept(hand) - hand.bet);
            default -> 0;
        };
    }

    /** One press on this seat's turn. {@code wallet} is how many emeralds the player could still add (the server counts them in his
     * inventory); a double, a split or va-bank needs them, and {@link Outcome#paid()} says how many to take. */
    public Outcome act(int seat, Action action, int wallet) {
        if (phase != Phase.PLAYERS) return Outcome.refused(Refusal.PHASE);
        if (!taken(seat)) return Outcome.refused(Refusal.SEAT);
        if (seat != turnSeat) return Outcome.refused(Refusal.TURN);
        Seat at = seats[seat];
        Hand hand = at.hands.get(turnHand);
        int paid = 0;
        switch (action) {
            case HIT -> {
                if (hand.doubled) return Outcome.refused(Refusal.NOT_ALLOWED);
                draw(hand, seat, turnHand);
            }
            case STAND -> {
                hand.stood = true;
                events.add(TableEvent.of(TableEvent.Kind.STAND, seat, turnHand));
            }
            case DOUBLE -> {
                if (variant != Variant.BLACKJACK || hand.cards.size() != 2 || hand.doubled) return Outcome.refused(Refusal.NOT_ALLOWED);
                if (wallet < hand.bet) return Outcome.refused(Refusal.FUNDS);
                if (exposureExcept(hand) + hand.bet * 2 > purse) return Outcome.refused(Refusal.BANK);
                paid = hand.bet;
                hand.bet *= 2;
                hand.doubled = true;
                events.add(new TableEvent(TableEvent.Kind.DOUBLE, seat, turnHand, null, paid));
                draw(hand, seat, turnHand);
                hand.stood = true;
            }
            case SPLIT -> {
                if (variant != Variant.BLACKJACK || at.split || hand.cards.size() != 2
                        || variant.value(hand.cards.get(0).rank()) != variant.value(hand.cards.get(1).rank()))
                    return Outcome.refused(Refusal.NOT_ALLOWED);
                if (wallet < hand.bet) return Outcome.refused(Refusal.FUNDS);
                if (exposure() + hand.bet > purse) return Outcome.refused(Refusal.BANK);
                paid = hand.bet;
                at.split = true;
                Hand second = new Hand(hand.bet);
                second.fromSplit = true;
                hand.fromSplit = true;
                second.cards.add(hand.cards.remove(1));
                at.hands.add(turnHand + 1, second);
                events.add(new TableEvent(TableEvent.Kind.SPLIT, seat, turnHand, null, paid));
                boolean aces = hand.cards.get(0).rank() == Rank.ACE;
                draw(hand, seat, turnHand);
                draw(second, seat, turnHand + 1);
                if (aces) { hand.stood = true; second.stood = true; }
            }
            case VA_BANK -> {
                if (variant != Variant.OCHKO || hand.cards.size() != 1 || hand.vaBank) return Outcome.refused(Refusal.NOT_ALLOWED);
                int raise = Math.min(wallet, cost(seat, Action.VA_BANK));
                if (raise <= 0) return Outcome.refused(wallet <= 0 ? Refusal.FUNDS : Refusal.BANK);
                paid = raise;
                hand.bet += raise;
                hand.vaBank = true;
                events.add(new TableEvent(TableEvent.Kind.VA_BANK, seat, turnHand, null, raise));
            }
        }
        if (hand.done(variant)) advance();
        return new Outcome(Refusal.NONE, paid);
    }

    /** «Ловлю!»: once per seat per deal, after the villager has played. Caught, the accuser's deal counts as won outright; for nothing,
     * the table changes nothing (the game layer takes it out of the relationship). */
    public Accusation accuse(int seat) {
        if (phase != Phase.SETTLED) return new Accusation(Refusal.PHASE, false, 0);
        if (!taken(seat) || seats[seat].hands.isEmpty()) return new Accusation(Refusal.SEAT, false, 0);
        if (accused[seat]) return new Accusation(Refusal.NOT_ALLOWED, false, 0);
        accused[seat] = true;
        if (!cheated) {
            events.add(TableEvent.of(TableEvent.Kind.FALSE_ACCUSE, seat, 0));
            return new Accusation(Refusal.NONE, false, 0);
        }
        int extra = 0;
        for (int i = 0; i < settlements.size(); i++) {
            Settlement settled = settlements.get(i);
            if (settled.seat() != seat) continue;
            extra = Math.max(0, Math.min(purse, settled.staked() * 2 - settled.returned()));
            purse -= extra;
            settlements.set(i, new Settlement(seat, settled.staked(), settled.returned() + extra));
        }
        events.add(new TableEvent(TableEvent.Kind.CAUGHT, seat, 0, null, extra));
        return new Accusation(Refusal.NONE, true, extra);
    }

    /** From a settled deal back to stakes; seats keep their places. */
    public Refusal nextRound() {
        if (phase != Phase.SETTLED) return Refusal.PHASE;
        for (Seat seat : seats) if (seat != null) { seat.hands.clear(); seat.split = false; }
        dealer.clear();
        holeShown = false;
        turnSeat = -1;
        phase = Phase.BETTING;
        return Refusal.NONE;
    }

    /** Calls the deal off (the villager was hit, a raid, the server stopping): every stake on the table goes back untouched.
     * Returns what each seat gets back; the bank does not change. */
    public List<Settlement> cancel() {
        List<Settlement> back = new ArrayList<>();
        if (phase != Phase.SETTLED) {
            for (int s = 0; s < seats.length; s++) {
                if (seats[s] == null) continue;
                int staked = seats[s].staked();
                if (staked > 0) back.add(new Settlement(s, staked, staked));
                seats[s].pendingBet = 0;
                seats[s].hands.clear();
                seats[s].split = false;
            }
            if (phase != Phase.BETTING || !back.isEmpty()) events.add(TableEvent.of(TableEvent.Kind.CANCEL, DEALER, 0));
        }
        dealer.clear();
        holeShown = false;
        turnSeat = -1;
        phase = Phase.BETTING;
        return back;
    }

    // ---------------------------------------------------------------- inside the deal

    private void dealAround() {
        for (int s = 0; s < seats.length; s++) {
            if (seats[s] == null) continue;
            for (int h = 0; h < seats[s].hands.size(); h++) {
                Hand hand = seats[s].hands.get(h);
                Card card = shoe.draw();
                hand.cards.add(card);
                events.add(new TableEvent(TableEvent.Kind.DEAL, s, h, card, 0));
            }
        }
    }

    private void dealDealer(boolean faceUp) {
        Card card = shoe.draw();
        dealer.add(card);
        events.add(new TableEvent(TableEvent.Kind.DEAL, DEALER, 0, faceUp ? card : null, 0));
    }

    private void draw(Hand hand, int seat, int index) {
        Card card = shoe.draw();
        hand.cards.add(card);
        events.add(new TableEvent(TableEvent.Kind.DEAL, seat, index, card, 0));
        Variant.Score score = hand.score(variant);
        if (score.bust()) events.add(TableEvent.of(TableEvent.Kind.BUST, seat, index));
        else if (variant.golden(hand.cards)) {
            hand.stood = true;
            events.add(TableEvent.of(TableEvent.Kind.NATURAL, seat, index));
        } else if (score.total() == 21) hand.stood = true;
    }

    /** The next hand that still has a decision, in seat order; when none is left the villager plays. */
    private void advance() {
        for (int s = 0; s < seats.length; s++) {
            if (seats[s] == null) continue;
            for (int h = 0; h < seats[s].hands.size(); h++) {
                if (!seats[s].hands.get(h).done(variant)) { turnSeat = s; turnHand = h; return; }
            }
        }
        turnSeat = -1;
        turnHand = 0;
        dealerTurn();
    }

    private void dealerTurn() {
        phase = Phase.DEALER;
        turnSeat = -1;
        holeShown = true;
        Card hole = dealer.get(dealer.size() - 1);
        events.add(new TableEvent(TableEvent.Kind.REVEAL, DEALER, 0, hole, 0));
        while (dealerDraws()) {
            List<Card> next = new ArrayList<>(dealer);
            next.add(shoe.peek());
            if (limits.cheating() && !cheated && style.cheatChance > 0 && variant.score(next).bust()
                    && random.nextDouble() < style.cheatChance) {
                Card sleeve = bestSleeveCard();
                if (sleeve != null) {
                    dealer.add(sleeve);
                    cheated = true;
                    events.add(new TableEvent(TableEvent.Kind.CHEAT, DEALER, 0, sleeve, 0));
                    continue;
                }
            }
            dealDealer(true);
        }
        if (variant.score(dealer).bust()) events.add(TableEvent.of(TableEvent.Kind.BUST, DEALER, 0));
        else if (variant.natural(dealer, false)) events.add(TableEvent.of(TableEvent.Kind.NATURAL, DEALER, 0));
        settle();
    }

    /** The card from the shoe that takes the villager closest to 21 without going over; pulled out of the shoe. */
    private Card bestSleeveCard() {
        int best = -1;
        for (Card candidate : shoe.left()) {
            List<Card> with = new ArrayList<>(dealer);
            with.add(candidate);
            Variant.Score score = variant.score(with);
            if (!score.bust()) best = Math.max(best, score.total());
        }
        if (best < 0) return null;
        int target = best;
        return shoe.take(candidate -> {
            List<Card> with = new ArrayList<>(dealer);
            with.add(candidate);
            return variant.score(with).total() == target;
        });
    }

    /** Whether the villager takes another card. Blackjack: to 16, stands on every 17. Ochko: until his stop for this deal, without
     * reading the players' open cards (a villager who did would win one deal in seven more: CardBalanceTest). Nobody draws when every
     * player has already gone bust. */
    boolean dealerDraws() {
        Variant.Score score = variant.score(dealer);
        if (score.bust() || score.total() >= 21 || variant.golden(dealer)) return false;
        List<Hand> live = new ArrayList<>();
        for (Seat seat : seats) if (seat != null) for (Hand hand : seat.hands) if (!hand.bust(variant)) live.add(hand);
        if (live.isEmpty()) return false;
        if (variant == Variant.BLACKJACK) {
            boolean allNatural = true;
            for (Hand hand : live) allNatural &= hand.natural(variant);
            return !allNatural && score.total() < 17;
        }
        if (dealer.size() < 2 && score.total() <= 11) return true;
        return score.total() < dealerStop;
    }

    private void settle() {
        Variant.Score mine = variant.score(dealer);
        boolean dealerNatural = variant.natural(dealer, false);
        for (int s = 0; s < seats.length; s++) {
            if (seats[s] == null || seats[s].hands.isEmpty()) continue;
            int staked = 0, returned = 0;
            for (int h = 0; h < seats[s].hands.size(); h++) {
                Hand hand = seats[s].hands.get(h);
                int back = payout(hand, mine, dealerNatural);
                staked += hand.bet;
                returned += back;
                events.add(new TableEvent(TableEvent.Kind.SETTLE, s, h, null, back));
            }
            purse += staked - returned;
            settlements.add(new Settlement(s, staked, returned));
        }
        purse = Math.max(0, purse);
        phase = Phase.SETTLED;
    }

    /** What comes back to a hand: 0 lost, the stake on a push, double on a win, two and a half times on a blackjack. */
    int payout(Hand hand, Variant.Score dealerScore, boolean dealerNatural) {
        if (hand.bust(variant)) return 0;
        boolean natural = hand.natural(variant);
        if (natural && dealerNatural) return hand.bet;
        if (natural) return variant == Variant.BLACKJACK ? hand.bet + hand.bet * 3 / 2 : hand.bet * 2;
        if (dealerNatural) return 0;
        if (dealerScore.bust()) return hand.bet * 2;
        int theirs = hand.score(variant).total();
        if (theirs > dealerScore.total()) return hand.bet * 2;
        return theirs == dealerScore.total() ? hand.bet : 0;
    }

    // ---------------------------------------------------------------- exposure

    private int maxWinBefore(int stake) { return variant == Variant.BLACKJACK ? (stake * 3 + 1) / 2 : stake; }

    private int pendingExposure() {
        int sum = 0;
        for (Seat seat : seats) if (seat != null) sum += maxWinBefore(seat.pendingBet);
        return sum;
    }

    private int exposure() {
        int sum = 0;
        for (Seat seat : seats) if (seat != null) for (Hand hand : seat.hands) if (!hand.bust(variant)) sum += hand.maxWin(variant);
        return sum;
    }

    private int exposureExcept(Hand except) { return exposure() - (except.bust(variant) ? 0 : except.maxWin(variant)); }

    private Hand current(int seat) {
        if (phase != Phase.PLAYERS || seat != turnSeat || !taken(seat)) return null;
        return seats[seat].hands.get(turnHand);
    }

    private boolean valid(int seat) { return seat >= 0 && seat < seats.length; }

    // ---------------------------------------------------------------- what players see

    /** The table as every player and onlooker may see it: open cards, stakes, totals; the villager's hidden card as -1 and his total
     * counted from the open cards only. Card codes keep the packet small ({@link Card#of(int)}). */
    public View view() {
        List<Integer> dealerCodes = new ArrayList<>();
        List<Card> open = new ArrayList<>();
        for (int i = 0; i < dealer.size(); i++) {
            boolean hidden = !holeShown && i == dealer.size() - 1 && (variant == Variant.OCHKO || i == 1);
            dealerCodes.add(hidden ? -1 : dealer.get(i).code());
            if (!hidden) open.add(dealer.get(i));
        }
        List<SeatView> seatViews = new ArrayList<>();
        for (Seat seat : seats) {
            if (seat == null) { seatViews.add(null); continue; }
            List<HandView> hands = new ArrayList<>();
            for (Hand hand : seat.hands) {
                List<Integer> codes = new ArrayList<>();
                for (Card card : hand.cards) codes.add(card.code());
                Variant.Score score = hand.score(variant);
                hands.add(new HandView(codes, hand.bet, score.total(), score.soft(), score.bust(), hand.natural(variant),
                        hand.stood, hand.doubled, hand.vaBank));
            }
            seatViews.add(new SeatView(seat.name, seat.pendingBet, hands));
        }
        Variant.Score dealerScore = variant.score(open);
        return new View(variant.name(), phase.name(), round, purse, style.name(), dealerCodes, dealerScore.total(),
                holeShown && variant.natural(dealer, false), seatViews, turnSeat, turnHand, limits.minBet(), limits.maxBet(),
                shoe.remaining());
    }

    public record View(String variant, String phase, int round, int purse, String style, List<Integer> dealer, int dealerTotal,
                       boolean dealerNatural, List<SeatView> seats, int turnSeat, int turnHand, int minBet, int maxBet, int shoe) { }
    public record SeatView(String name, int pendingBet, List<HandView> hands) { }
    public record HandView(List<Integer> cards, int bet, int total, boolean soft, boolean bust, boolean natural, boolean stood,
                           boolean doubled, boolean vaBank) { }
}
