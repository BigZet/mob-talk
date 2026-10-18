package io.github.mobtalk.core;

/** The mob's running notes about one player: the model adds a few new facts per answer, the code keeps the tail. */
final class MemoryNotes {
    /** Most characters of newFacts one answer may add. */
    static final int FACTS_LIMIT = 300;
    private MemoryNotes() { }

    /** Appends new facts and drops the oldest lines once the memory outgrows its limit. */
    static String remember(String previous, String facts, int limit) {
        String old = previous == null ? "" : previous.strip(), fresh = facts == null ? "" : facts.strip();
        if (fresh.isEmpty()) return TextBounds.characters(old, limit);
        String combined = old.isEmpty() ? fresh : old + "\n" + fresh;
        if (combined.length() <= limit) return combined;
        String tail = combined.substring(combined.length() - limit);
        int boundary = tail.indexOf('\n');
        return boundary >= 0 && boundary < tail.length() - 1 ? tail.substring(boundary + 1) : tail;
    }
}
