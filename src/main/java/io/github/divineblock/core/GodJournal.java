package io.github.divineblock.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/** What the god heard and did, newest last, for the server's web admin: prayers, verdicts, acts, bans. Kept in memory (the
 * last {@link #LIMIT} entries); the world's own memory stays in {@link GodLedger}. Never holds keys or coordinates: every text
 * passes {@link #redact}. Thread-safe: the game thread writes, the admin reads from its own threads. */
public final class GodJournal {
    /** {@code CONFESSION}: a confession at a priest — numbers and the place only, never the penitent's words. */
    public enum Kind { PRAYER, VERDICT, MIRACLE, BAN, CONFESSION;
        public String id() { return name().toLowerCase(java.util.Locale.ROOT); } }
    public record Entry(long t, Kind kind, String player, String text) { }

    public static final int LIMIT = 1000;
    private static final Pattern KEY = Pattern.compile("(?i)\\b(sk-[a-z0-9_-]{8,}|bearer\\s+\\S+|[a-z0-9_-]{32,})");
    private static final Pattern COORDINATES = Pattern.compile("-?\\d+(?:[.,]\\d+)?\\s*[,;/ ]\\s*-?\\d+(?:[.,]\\d+)?\\s*[,;/ ]\\s*-?\\d+(?:[.,]\\d+)?");
    private static final GodJournal SHARED = new GodJournal();
    private final Deque<Entry> entries = new ArrayDeque<>();

    /** The journal of this game (one server per JVM). */
    public static GodJournal shared() { return SHARED; }

    public synchronized void add(long millis, Kind kind, String player, String text) {
        entries.addLast(new Entry(millis, kind, player == null ? "" : player, redact(text)));
        while (entries.size() > LIMIT) entries.removeFirst();
    }
    public void add(Kind kind, String player, String text) { add(System.currentTimeMillis(), kind, player, text); }

    /** Entries after {@code since} (millis), oldest first, the newest {@code limit} of them. */
    public synchronized List<Entry> since(long since, int limit) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries) if (entry.t() > since) out.add(entry);
        int keep = Math.max(0, Math.min(limit, out.size()));
        return new ArrayList<>(out.subList(out.size() - keep, out.size()));
    }

    public synchronized void clear() { entries.clear(); }

    /** Keys and three numbers in a row (a position) are cut out; long lines are clipped. */
    public static String redact(String text) {
        if (text == null) return "";
        String out = KEY.matcher(text).replaceAll("•••");
        out = COORDINATES.matcher(out).replaceAll("…");
        return out.length() > 600 ? out.substring(0, 599) + "…" : out;
    }
}
