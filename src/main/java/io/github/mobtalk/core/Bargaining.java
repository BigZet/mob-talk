package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.List;

/** Pure rules for a small, single-use concession on the first ingredient of an existing trade. */
public final class Bargaining {
    public static final long QUOTE_TICKS = 6000, COOLDOWN_TICKS = 24000;
    public static final int MAX_ROUNDS = 3;
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
                new BargainIntent(BargainIntent.Type.OFFER, offer.index, Math.max(1, maximumDiscount(offer, relationship))), tick);
        Decision acceptance = negotiate(previous, offer, relationship, new BargainIntent(BargainIntent.Type.ACCEPT, offer.index, 0), tick);
        Decision refusal = negotiate(previous, offer, relationship, new BargainIntent(BargainIntent.Type.DECLINE, offer.index, 0), tick);
        boolean canOffer = reaches(proposal, Status.PENDING), canAccept = reaches(acceptance, Status.ACCEPTED);
        List<BargainIntent.Type> allowed = new ArrayList<>(List.of(BargainIntent.Type.NONE));
        if (canOffer) allowed.add(BargainIntent.Type.OFFER);
        if (canAccept) allowed.add(BargainIntent.Type.ACCEPT);
        // The game may voice an unchanged refusal with no quote or price effect, including when the cap is zero.
        if (!refusal.changed || reaches(refusal, Status.DECLINED)) allowed.add(BargainIntent.Type.DECLINE);
        String reason = canOffer ? (canAccept ? "Можно продолжить торг, принять текущее предложение или отказаться"
                : "Можно обсудить уступку по выбранной сделке или отказаться") : proposal.message;
        return new Availability(allowed, reason);
    }
    private static boolean reaches(Decision decision, Status status) {
        return decision.changed && decision.session != null && decision.session.status == status;
    }

    public static int maximumDiscount(Offer offer, int relationship) {
        if (!valid(offer) || relationship < 0) return 0;
        long originalFloor = (offer.baseCount * 9L + 9) / 10;
        int maximum = (int) Math.max(0, Math.min(3, Math.min(offer.vanillaCount / 10, offer.vanillaCount - originalFloor)));
        return relationship < 30 ? Math.min(1, maximum) : maximum;
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
        int maximum = maximumDiscount(offer, relationship);
        if (maximum == 0) {
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
            if (current == null || current.status != Status.PENDING) return unchanged(previous, "Нет предложения, которое можно принять");
            if (current.discount <= 0 || current.discount > maximum || current.price != offer.vanillaCount - current.discount)
                return new Decision(invalidate(current, Status.EXPIRED), "Условия предложения больше недействительны", true);
            return new Decision(invalidate(current, Status.ACCEPTED), "Договорились: " + current.price
                    + " вместо " + current.vanillaCount + " единиц первого предмета, на один обмен", true);
        }
        if (current != null && current.status == Status.ACCEPTED) return unchanged(previous, "Условия уже приняты: выполните один обмен в окне торговли");
        if (active(current) && current.rounds >= MAX_ROUNDS) return unchanged(previous, "Житель назвал последнюю цену; можно принять её или отказаться");
        if (intent.discount() <= 0 || intent.discount() > maximum) return unchanged(previous, "Предложенная уступка выходит за пределы этой сделки");
        boolean continuing = current != null && current.status == Status.PENDING;
        Session pending = new Session(offer.key, offer.index, offer.vanillaCount, offer.vanillaCount - intent.discount(), intent.discount(),
                continuing ? current.rounds + 1 : 1, continuing ? current.openedTick : tick,
                continuing ? current.expiresTick : tick + QUOTE_TICKS, continuing ? current.cooldownUntil : tick + COOLDOWN_TICKS, Status.PENDING);
        return new Decision(pending, "Предложение жителя: " + pending.price + " вместо " + pending.vanillaCount
                + "; подтвердите голосом, чтобы применить его к одному обмену", true);
    }
    public static int acceptedPrice(Session session, Offer offer, long tick) {
        if (session == null || session.status != Status.ACCEPTED || !valid(offer) || !sameTrade(session, offer)
                || tick < session.openedTick || tick >= session.expiresTick || session.vanillaCount != offer.vanillaCount
                || session.discount <= 0 || session.discount > maximumDiscount(offer, 100)
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
