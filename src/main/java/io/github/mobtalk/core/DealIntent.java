package io.github.mobtalk.core;

/** The mob's bargaining move on one of its own deals (trades.json), never on a vanilla villager row — that stays {@link BargainIntent}.
 * OFFER sets {@code price} (the player's cost per exchange, in units of the deal's price item) for this player, permanently. */
public record DealIntent(Type type, String dealId, int price) {
    public enum Type { NONE, OFFER, DECLINE }
    public DealIntent {
        if (type == null) type = Type.NONE;
        dealId = dealId == null ? "" : dealId;
        if (type == Type.NONE) { dealId = ""; price = 0; }
    }
    public static DealIntent none() { return new DealIntent(Type.NONE, "", 0); }
}
