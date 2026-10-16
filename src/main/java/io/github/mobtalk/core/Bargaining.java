package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.List;

/** Pure rules for a single-use concession on the first ingredient of an existing trade.
 * One OFFER may at most halve the price currently charged; all rounds together stop at 10% of the original quantity.
 * Rounding always favours the buyer. A villager's OFFER takes effect at once; PENDING only remains for quotes saved by 0.2.5. */
public final class Bargaining {
    public static final long QUOTE_TICKS = 6000, COOLDOWN_TICKS = 24000;
    public static final int MAX_ROUNDS = 3;
    /** At or below this relationship the villager refuses any concession. */
    public static final int HOSTILE_RELATIONSHIP = -40;
    public enum Status { PENDING, ACCEPTED, CONSUMED, DECLINED, EXPIRED }
    public record Offer(int index, String key, int baseCount, int vanillaCount, int maxStack, boolean available) { }
    public record Session(String key, int offerIndex, int vanillaCount, int price, int discount, int rounds,
                          long openedTick, long expiresTick, long cooldownUntil, Status status) { }
    public record Decision(Session session, String message, boolean changed) { }
    public record Availability(List<BargainIntent.Type> allowedTypes, String reason) {
        public Availability { allowedTypes = List.copyOf(allowedTypes); }
    }
    private Bargaining() { }

    /** Inspect the existing transition rules without changing or extending a stored negotiation. */
    public static Availability availability(Session previous, Offer offer, int relationship, long tick) {
        if (offer == null || offer.index < 0 || offer.key == null || offer.key.isBlank())
            return new Availability(List.of(BargainIntent.Type.NONE), "Выберите доступную строку торговли заново");
        Decision proposal = negotiate(previous, offer, relationship,
                new BargainIntent(BargainIntent.Type.OFFER, offer.index, Math.max(1, maximumDiscount(offer, relationship, currentPrice(previous, offer, tick)))), tick);
        Decision acceptance = negotiate(previous, offer, relationship, new BargainIntent(BargainIntent.Type.ACCEPT, offer.index, 0), tick);
        Decision refusal = negotiate(previous, offer, relationship, new BargainIntent(BargainIntent.Type.DECLINE, offer.index, 0), tick);
        boolean canOffer = reaches(proposal, Status.ACCEPTED), canAccept = reaches(acceptance, Status.ACCEPTED);
        List<BargainIntent.Type> allowed = new ArrayList<>(List.of(BargainIntent.Type.NONE));
        if (canOffer) allowed.add(BargainIntent.Type.OFFER);
        if (canAccept) allowed.add(BargainIntent.Type.ACCEPT);
        // The game may voice an unchanged refusal with no quote or price effect, including when the cap is zero.
        if (!refusal.changed || reaches(refusal, Status.DECLINED)) allowed.add(BargainIntent.Type.DECLINE);
        String reason = canOffer ? (canAccept ? "Можно продолжить торг, принять текущее предложение или отказаться"
                : active(previous) ? "Уступка уже действует в окне; можно изменить её в пределах allowedPrices или отказаться"
                : "Можно уступить по выбранной сделке (цена сразу появится в окне) или отказаться") : proposal.message;
        return new Availability(allowed, reason);
    }
    private static boolean reaches(Decision decision, Status status) {
        return decision.changed && decision.session != null && decision.session.status == status;
    }

    /** The lowest price any negotiation may reach: 90% off the original quantity, rounded down, never below one item. */
    public static int floorPrice(Offer offer) { return Math.max(1, offer.baseCount / 10); }
    /** The price the window charges now: an applied concession, otherwise the vanilla price. */
    public static int currentPrice(Session session, Offer offer, long tick) {
        return session != null && session.status == Status.ACCEPTED && valid(offer) && sameTrade(session, offer)
                && session.vanillaCount == offer.vanillaCount && tick < session.expiresTick ? session.price : offer.vanillaCount;
    }
    /** Largest discount from vanillaCount that the next OFFER may set when starting from the vanilla price. */
    public static int maximumDiscount(Offer offer, int relationship) { return maximumDiscount(offer, relationship, offer == null ? 0 : offer.vanillaCount); }
    /** One step may at most halve the current price (half rounded down), and never go below floorPrice. */
    public static int maximumDiscount(Offer offer, int relationship, int currentPrice) {
        if (!valid(offer) || relationship <= HOSTILE_RELATIONSHIP) return 0;
        int lowest = Math.max(floorPrice(offer), Math.min(currentPrice, offer.vanillaCount) / 2);
        return Math.max(0, offer.vanillaCount - lowest);
    }
    /** Whether an already agreed discount still fits the overall limit for this trade and relationship. */
    public static boolean permitted(Offer offer, int relationship, int discount) {
        return valid(offer) && relationship > HOSTILE_RELATIONSHIP && discount >= 1 && offer.vanillaCount - discount >= floorPrice(offer);
    }
    public static Decision negotiate(Session previous, Offer offer, int relationship, BargainIntent intent, long tick) {
        if (intent == null || intent.type() == BargainIntent.Type.NONE) return unchanged(previous, "");
        if (!valid(offer) || intent.offerIndex() != offer.index) return unchanged(previous, "Выберите доступную строку торговли заново");
        if (previous != null && tick < previous.openedTick) return unchanged(previous, "Сохранённый срок переговоров ещё не наступил");
        Session current = previous;
        if (active(current) && tick >= current.expiresTick) {
            if (tick < current.cooldownUntil) return new Decision(invalidate(current, Status.EXPIRED), "Срок предложения истёк", true);
            current = null;
        }
        if (current != null && !active(current) && tick < current.cooldownUntil)
            return unchanged(previous, "Этот житель уже закончил торг; новые переговоры будут доступны позднее");
        if (active(current) && (!sameTrade(current, offer) || current.vanillaCount != offer.vanillaCount))
            return new Decision(invalidate(current, Status.EXPIRED), "Условия сделки изменились; прежняя уступка снята", true);
        if (intent.type() == BargainIntent.Type.DECLINE && active(current))
            return new Decision(invalidate(current, Status.DECLINED), "Переговоры закончены; обычная торговля остаётся доступна", true);
        int maximum = maximumDiscount(offer, relationship, currentPrice(current, offer, tick));
        if (maximumDiscount(offer, relationship) == 0) {
            if (active(current)) return new Decision(invalidate(current, Status.EXPIRED), "Уступка больше недоступна", true);
            return unchanged(previous, "В этой сделке нет доступной уступки с учётом цены и отношения");
        }
        if (intent.type() == BargainIntent.Type.DECLINE) {
            Session declined = active(current) ? invalidate(current, Status.DECLINED)
                    : new Session(offer.key, offer.index, offer.vanillaCount, offer.vanillaCount, 0, 0,
                            tick, tick + QUOTE_TICKS, tick + COOLDOWN_TICKS, Status.DECLINED);
            return new Decision(declined, "Переговоры закончены; обычная торговля остаётся доступна", true);
        }
        if (intent.type() == BargainIntent.Type.ACCEPT) {
            if (current != null && current.status == Status.ACCEPTED) return unchanged(previous, "Цена уже действует в окне торговли");
            if (current == null || current.status != Status.PENDING) return unchanged(previous, "Нет предложения, которое можно принять");
            if (!permitted(offer, relationship, current.discount) || current.price != offer.vanillaCount - current.discount)
                return new Decision(invalidate(current, Status.EXPIRED), "Условия предложения больше недействительны", true);
            return new Decision(invalidate(current, Status.ACCEPTED), "Договорились: " + current.price
                    + " вместо " + current.vanillaCount + " единиц первого предмета, на один обмен", true);
        }
        if (active(current) && current.rounds >= MAX_ROUNDS)
            return unchanged(previous, current.status == Status.ACCEPTED ? "Житель уже назвал последнюю цену, она действует в окне торговли"
                    : "Житель назвал последнюю цену; можно принять её или отказаться");
        if (intent.discount() <= 0 || intent.discount() > maximum) return unchanged(previous, "Предложенная уступка выходит за пределы: за раз цена падает не больше чем вдвое, всего не ниже "
                + floorPrice(offer));
        boolean continuing = active(current);
        Session applied = new Session(offer.key, offer.index, offer.vanillaCount, offer.vanillaCount - intent.discount(), intent.discount(),
                continuing ? current.rounds + 1 : 1, continuing ? current.openedTick : tick,
                continuing ? current.expiresTick : tick + QUOTE_TICKS, continuing ? current.cooldownUntil : tick + COOLDOWN_TICKS, Status.ACCEPTED);
        return new Decision(applied, "Цена в окне торговли: " + applied.price + " вместо " + applied.vanillaCount
                + " единиц первого предмета, на один обмен", true);
    }
    public static int acceptedPrice(Session session, Offer offer, long tick) {
        if (session == null || session.status != Status.ACCEPTED || !valid(offer) || !sameTrade(session, offer)
                || tick < session.openedTick || tick >= session.expiresTick || session.vanillaCount != offer.vanillaCount
                || !permitted(offer, 100, session.discount)
                || session.price != offer.vanillaCount - session.discount || session.price < 1) return -1;
        return session.price;
    }
    public static Session consumed(Session session) { return invalidate(session, Status.CONSUMED); }
    public static Session invalidate(Session session, Status status) {
        if (session == null) return null;
        return new Session(session.key, session.offerIndex, session.vanillaCount, session.price, session.discount,
                session.rounds, session.openedTick, session.expiresTick, session.cooldownUntil, status);
    }
    private static boolean valid(Offer offer) {
        return offer != null && offer.index >= 0 && offer.key != null && !offer.key.isBlank() && offer.available
                && offer.baseCount > 0 && offer.vanillaCount > 0 && offer.vanillaCount <= offer.maxStack;
    }
    private static boolean active(Session session) { return session != null && (session.status == Status.PENDING || session.status == Status.ACCEPTED); }
    private static boolean sameTrade(Session session, Offer offer) { return session.offerIndex == offer.index && offer.key.equals(session.key); }
    private static Decision unchanged(Session previous, String message) { return new Decision(previous, message, false); }
}
