package io.github.mobtalk.core;

/** This player's standing on one deal of one mob, persisted in the world memory. {@code price} is the negotiated cost
 * (0 = the catalog price); it only ever goes down and never resets. {@code rounds} counts OFFERs in the bargaining
 * session that began at {@code sessionStartMillis}. {@code boughtDay} is the world day of the last purchase. */
public record TradeState(int price, boolean unlocked, int rounds, long sessionStartMillis, int boughtToday, long boughtDay) {
    public static TradeState initial() { return new TradeState(0, false, 0, -1, 0, -1); }
}
