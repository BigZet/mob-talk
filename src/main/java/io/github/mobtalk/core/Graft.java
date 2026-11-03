package io.github.mobtalk.core;

import java.util.*;

/** What the head of the administration does with the money (track «чиновники», stages 3–4, docs/officials.md): a satire. A corrupt
 * head builds a dacha outside the village, one piece at a time — a fence, a cottage, a banya, a fountain and at last a golden toilet
 * under glass; a game day in office and every envelope he takes push it on. Heaven sees it: once a day a corrupt head who took
 * envelopes may be smitten (a lightning into the dacha, toads in the office, the golden toilet turned to dung). The safe in the
 * cottage keeps the «чёрная бухгалтерия» a player may steal; handed to an honest official it brings the revizor from the capital
 * next day, and the head is removed. The code decides; nothing here talks to a model. Pure Java; the estates live in
 * {@link Officialdom}, under the store lock. */
public final class Graft {
    private Graft() { }

    /** The dacha's pieces, in the order they are built. */
    public enum Stage { NONE, FENCE, COTTAGE, BANYA, FOUNTAIN, TOILET }
    /** The stage {@code points} reach at {@code perStage} points a stage. */
    public static Stage stage(int points, int perStage) {
        int n = Math.max(0, points) / Math.max(1, perStage);
        return Stage.values()[Math.min(Stage.values().length - 1, n)];
    }
    /** Points an envelope adds (a game day in office adds one). */
    public static final int ENVELOPE_POINTS = 2;
    /** Envelopes before the cottage's safe gets the black ledger. */
    public static final int LEDGER_ENVELOPES = 3;

    /** One head's dacha: where it stands (the plot's front-left corner, the way its front faces), how far it has grown, what the
     * game has built, whether heaven turned the toilet to dung, whether it was confiscated, and the day heaven last struck. */
    public static final class Estate {
        String head = "", headName = "", village = "";
        int x, y, z, facing;
        int points, built;
        long lastDay = -1, smiteDay = -1;
        boolean dung, confiscated, ledger;
        Estate copy() {
            Estate e = new Estate(); e.head = head; e.headName = headName; e.village = village; e.x = x; e.y = y; e.z = z; e.facing = facing;
            e.points = points; e.built = built; e.lastDay = lastDay; e.smiteDay = smiteDay; e.dung = dung; e.confiscated = confiscated; e.ledger = ledger;
            return e;
        }
        public UUID head() { try { return UUID.fromString(head); } catch (RuntimeException ex) { return null; } }
        public String headName() { return headName; }
        public String village() { return village; }
        public int x() { return x; } public int y() { return y; } public int z() { return z; }
        /** The horizontal direction the dacha's front (the gate) faces, 0..3 as Minecraft's horizontal ids (south, west, north, east). */
        public int facing() { return facing; }
        public int points() { return points; }
        /** How many stages the world already has. */
        public int built() { return built; }
        public boolean dung() { return dung; }
        public boolean confiscated() { return confiscated; }
        public boolean ledger() { return ledger; }
        public void place(int x, int y, int z, int facing) { this.x = x; this.y = y; this.z = z; this.facing = facing; }
        public void built(int built) { this.built = Math.max(this.built, built); }
        public void dung(boolean dung) { this.dung = dung; }
        public void ledger(boolean ledger) { this.ledger = ledger; }
        public void confiscated(boolean confiscated) { this.confiscated = confiscated; }
        public void points(int points) { this.points = Math.max(0, points); }
        public void smitten(long day) { smiteDay = day; }
        public boolean placed() { return y != 0 || x != 0 || z != 0; }
        public long smiteDay() { return smiteDay; }
    }

    /** A game day passed in office: one point a day (at most three days are caught up at once, a long absence is no windfall). */
    public static void days(Estate estate, long day) {
        if (estate == null || estate.confiscated) return;
        if (estate.lastDay < 0) { estate.lastDay = day; return; }
        if (day <= estate.lastDay) return;
        estate.points += (int) Math.min(3, day - estate.lastDay);
        estate.lastDay = day;
    }
    public static void envelope(Estate estate) { if (estate != null && !estate.confiscated) estate.points += ENVELOPE_POINTS; }

    /** How heaven strikes a corrupt head. */
    public enum Smite { NONE, LIGHTNING, TOADS, DUNG }
    /** Once a game day: with {@code percent} chance, for a head who has taken at least one envelope. The golden toilet turns to dung
     * once it stands; otherwise a lightning into the dacha (if there is one) or toads in the office. {@code roll} and {@code pick}
     * are 0..1. */
    public static Smite smite(Estate estate, int taken, long day, int percent, double roll, double pick) {
        if (estate == null || estate.confiscated || taken <= 0 || estate.smiteDay >= day || roll * 100 >= percent) return Smite.NONE;
        if (estate.built >= Stage.TOILET.ordinal() && !estate.dung && pick < 0.4) return Smite.DUNG;
        if (estate.built >= Stage.COTTAGE.ordinal() && pick < 0.7) return Smite.LIGHTNING;
        return Smite.TOADS;
    }
    /** The giver's small mark: the chance a taken envelope gets the player «noticed from above» (a pigeon). */
    public static final double GIVER_MARK = 0.15;

    /** What the black ledger buys when handed to someone. */
    public enum Ledger {
        /** An honest official: «передадим куда следует», the revizor comes next day. */
        REVIZOR,
        /** The corrupt head himself: he buys it back. */
        BLACKMAIL,
        /** Any other villager: the village talks. */
        RUMOR,
        /** The revizor is already on his way: «уже знаем». */
        ALREADY
    }
    public static Ledger ledger(boolean official, boolean honest, boolean isHead, boolean revizorComing) {
        if (isHead && !honest) return Ledger.BLACKMAIL;
        if (official && honest) return revizorComing ? Ledger.ALREADY : Ledger.REVIZOR;
        return Ledger.RUMOR;
    }
    /** What the head pays for his ledger back. */
    public static int hushMoney(int taken) { return Math.min(64, 16 + 4 * Math.max(0, taken)); }

    /** The rumour of a new piece of the dacha, for the village. */
    public static String rumor(Stage stage, String head) {
        String who = head == null || head.isBlank() ? "главы администрации" : "главы администрации " + head;
        return switch (stage) {
            case NONE -> "";
            case FENCE -> "за деревней у " + who + " появился участок с высоким забором и табличкой «Частная собственность»";
            case COTTAGE -> "у " + who + " за деревней вырос кирпичный коттедж — и откуда только деньги";
            case BANYA -> "у " + who + " на даче теперь своя баня, дым идёт каждый вечер";
            case FOUNTAIN -> "у " + who + " на даче поставили фонтан, как в столице";
            case TOILET -> "у " + who + " на даче золотой унитаз под стеклом, люди ходят смотреть";
        };
    }
}
