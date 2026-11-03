package io.github.mobtalk.core.cards;

/** A villager's own emeralds, the bank he plays with. It starts by his trade level, grows when players buy from him and by a little
 * each game day up to its start, and never goes below zero: a ruined villager gets up from the table. Pure numbers; the store keeps
 * one per villager. */
public final class Purse {
    /** What a novice (level 1) up to a master (level 5) starts with. */
    public static int start(int professionLevel) { return 8 + 4 * (Math.max(1, Math.min(5, professionLevel)) - 1); }

    private int balance;
    private long day;

    public Purse(int balance, long day) { this.balance = Math.max(0, balance); this.day = day; }

    public int balance() { return balance; }
    public long day() { return day; }

    /** Adds {@code perDay} for every game day since the last look, without passing {@code cap} (it never takes away what is above). */
    public void regenerate(long worldDay, int perDay, int cap) {
        if (worldDay <= day) return;
        long days = worldDay - day;
        day = worldDay;
        if (balance >= cap || perDay <= 0) return;
        balance = (int) Math.min(cap, balance + Math.min((long) cap, days * perDay));
    }

    /** Emeralds that came in (a sale, a won deal) or went out (negative: a lost deal); never below zero. */
    public void add(int emeralds) { balance = Math.max(0, balance + emeralds); }
}
