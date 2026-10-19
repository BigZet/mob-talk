package io.github.mobtalk.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Misdeeds without a blow that a villager takes personally when it sees them: rummaging in a chest that is not the player's,
 * taking the harvest off a village plot, breaking a villager's work block, a door or a bed. Each kind carries a penalty smaller
 * than a blow (a hit costs a witness 10) and a cooldown so one chest opened ten times costs once. The player's own blocks —
 * placed by them in this session — are exempt. Pure rules; the game layer decides what was seen and by whom. */
public final class Mischief {
    private Mischief() { }

    /** How long one kind of misdeed is counted once, per player. */
    public static final long COOLDOWN_MILLIS = 20_000;

    public enum Kind {
        /** Opened a chest or a barrel in a village. */
        CONTAINER(-3, "открыл чужой сундук в деревне", "открыли чужой сундук в деревне"),
        /** Harvested (or trampled) a crop on a village plot. */
        CROP(-3, "собрал урожай с чужой грядки в деревне", "собрали урожай с чужой грядки в деревне"),
        /** Broke a villager's job block: lectern, composter, smithing table and the like. */
        WORKSTATION(-8, "сломал рабочий блок жителя", "сломали рабочий блок жителя"),
        DOOR(-5, "сломал дверь в деревне", "сломали дверь в деревне"),
        BED(-5, "сломал кровать в деревне", "сломали кровать в деревне");

        /** The relationship change for every witness. */
        public final int penalty;
        /** What the witness remembers, after «Видел, как игрок …», and the same said to the player («вы …»). */
        public final String seen, done;
        Kind(int penalty, String seen, String done) { this.penalty = penalty; this.seen = seen; this.done = done; }
    }

    /** The memory text for a misdeed, with the block named when known: «сломал рабочий блок жителя (стол картографа)». */
    public static String seen(Kind kind, String detail) { return withDetail(kind.seen, detail); }
    /** The same for the player's own chat line: «сломали рабочий блок жителя (стол картографа)». */
    public static String done(Kind kind, String detail) { return withDetail(kind.done, detail); }
    private static String withDetail(String text, String detail) {
        return detail == null || detail.isBlank() ? text : text + " (" + TextBounds.characters(detail, 60) + ")";
    }

    /** What this session knows: blocks the player placed (theirs, never a misdeed) and when each kind of misdeed last counted. */
    public static final class Ledger {
        private final Set<String> placed = new HashSet<>();
        private final Map<String, Long> last = new HashMap<>();

        /** The player put this block down: whatever happens to it later is their own business. */
        public void placed(String dimension, int x, int y, int z) {
            placed.add(key(dimension, x, y, z));
            while (placed.size() > 50_000) placed.remove(placed.iterator().next()); // A session of frantic building stays bounded.
        }
        /** Whether the player placed this block or a neighbour of it: a door's upper half and a bed's head are placed through
         * their other half. */
        public boolean ownBlock(String dimension, int x, int y, int z) {
            if (placed.contains(key(dimension, x, y, z))) return true;
            int[][] around = {{0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] d : around) if (placed.contains(key(dimension, x + d[0], y + d[1], z + d[2]))) return true;
            return false;
        }
        /** Counts this misdeed once per {@link #COOLDOWN_MILLIS} for the player and kind; returns whether it counts now. */
        public boolean count(UUID player, Kind kind, long nowMillis) {
            String key = player + ":" + kind.name();
            Long previous = last.get(key);
            // A clock that ran backwards counts as no time passed, like the store's own cooldowns.
            if (previous != null && (nowMillis < previous || nowMillis - previous < COOLDOWN_MILLIS)) return false;
            last.put(key, nowMillis);
            return true;
        }
        public void clear() { placed.clear(); last.clear(); }
        private static String key(String dimension, int x, int y, int z) { return dimension + "|" + x + "|" + y + "|" + z; }
    }
}
