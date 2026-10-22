package io.github.mobtalk.server.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Everything that happened, append-only: {@code <root>/<kind>/<yyyyMMdd>.jsonl}, one record per line. The day files are the time
 * index, the kind folders the kind index; a player or mob filter checks the raw line before parsing it. Appending never touches the
 * disk on the caller's thread: one writer thread does, and a search first waits for it. */
public final class HistoryStore {
    public record Query(Set<String> kinds, String player, String mob, String text, Long from, Long to, String cursor, int limit) {
        public static Query all() { return new Query(Set.of(), null, null, null, null, null, null, 50); }
        public Query withCursor(String next) { return new Query(kinds, player, mob, text, from, to, next, limit); }
        public Query withLimit(int max) { return new Query(kinds, player, mob, text, from, to, cursor, max); }
    }
    public record Page(List<JsonObject> items, String next) { }

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern KIND = Pattern.compile("[a-z][a-z_]{0,31}");
    private static final Set<String> HEAVY = Set.of("trace", "prompt", "raw");

    private final Path root;
    private final LongSupplier clock;
    private final ZoneId zone;
    private volatile int retentionDays;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "mobtalk-history"); thread.setDaemon(true); return thread;
    });
    private final AtomicLong sequence = new AtomicLong();
    private final List<Consumer<JsonObject>> listeners = new CopyOnWriteArrayList<>();
    private final Consumer<String> log;

    public HistoryStore(Path root, LongSupplier clock, ZoneId zone, int retentionDays, Consumer<String> log) {
        this.root = root; this.clock = clock; this.zone = zone; this.retentionDays = Math.max(1, retentionDays); this.log = log;
    }

    public void retentionDays(int days) { retentionDays = Math.max(1, days); }
    public ZoneId zone() { return zone; }
    /** Called with every new record, already cleaned and without heavy fields (for the event stream). */
    public void listen(Consumer<JsonObject> listener) { listeners.add(listener); }

    /** Writes a record and returns it. Every string is cleaned of keys and coordinates first. */
    public JsonObject append(String kind, JsonObject player, JsonObject mob, String text, JsonObject data) {
        return append(clock.getAsLong(), kind, player, mob, text, data);
    }

    public JsonObject append(long t, String kind, JsonObject player, JsonObject mob, String text, JsonObject data) {
        if (!KIND.matcher(kind).matches()) throw new IllegalArgumentException("вид истории: " + kind);
        String day = DAY.format(Instant.ofEpochMilli(t).atZone(zone));
        JsonObject record = new JsonObject();
        record.addProperty("id", kind + "." + day + "." + Long.toString(t, 36) + "-" + Long.toString(sequence.incrementAndGet(), 36));
        record.addProperty("t", t);
        record.addProperty("kind", kind);
        record.add("player", player == null ? null : Redact.tree(player));
        record.add("mob", mob == null ? null : Redact.tree(mob));
        record.addProperty("text", Redact.text(text == null ? "" : text));
        record.add("data", data == null ? new JsonObject() : Redact.tree(data));
        String line = Json.GSON.toJson(record) + "\n";
        Path file = root.resolve(kind).resolve(day + ".jsonl");
        writer.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException failure) { log.accept("история: не записалась " + file.getFileName() + ": " + failure.getMessage()); }
        });
        JsonObject light = light(record);
        for (Consumer<JsonObject> listener : listeners) {
            try { listener.accept(light); } catch (RuntimeException ignored) { /* A listener must not lose the record. */ }
        }
        return record;
    }

    /** Waits until everything appended so far is on disk; never on the game thread. */
    public void flush() {
        try { writer.submit(() -> { }).get(10, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException stuck) { log.accept("история: запись не успела: " + stuck); }
    }

    public void close() { flush(); writer.shutdown(); }

    /** The record without the trace, the prompt and the raw answer: what lists and the event stream carry. */
    public static JsonObject light(JsonObject record) {
        JsonObject copy = record.deepCopy();
        JsonElement data = copy.get("data");
        if (data != null && data.isJsonObject()) for (String heavy : HEAVY) data.getAsJsonObject().remove(heavy);
        return copy;
    }

    public Page search(Query query) {
        int limit = Math.max(1, Math.min(500, query.limit()));
        List<JsonObject> items = new ArrayList<>();
        String[] after = parseCursor(query.cursor());
        boolean[] more = {false};
        scan(query, record -> {
            if (after != null && !older(record, after)) return true;
            if (items.size() == limit) { more[0] = true; return false; }
            items.add(light(record));
            return true;
        });
        String next = more[0] && !items.isEmpty() ? cursor(items.get(items.size() - 1)) : null;
        return new Page(items, next);
    }

    /** The whole record, or null. The id names its kind and day, so only that one file is read. */
    public JsonObject get(String id) {
        String[] parts = id == null ? new String[0] : id.split("\\.");
        if (parts.length != 3 || !KIND.matcher(parts[0]).matches() || !parts[1].matches("\\d{8}")) return null;
        flush();
        Path file = root.resolve(parts[0]).resolve(parts[1] + ".jsonl");
        if (!Files.isRegularFile(file)) return null;
        String needle = "\"id\":\"" + id + "\"";
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
                if (line.contains(needle)) { JsonObject record = parse(line); if (record != null) return record; }
        } catch (IOException failure) { log.accept("история: не прочиталась " + file.getFileName() + ": " + failure.getMessage()); }
        return null;
    }

    /** Every match, newest first, until the visitor says stop. */
    public void scan(Query query, Predicate<JsonObject> visitor) {
        flush();
        long now = clock.getAsLong();
        long to = query.to() == null ? now : query.to();
        long from = query.from() == null ? now - (long) retentionDays * 86_400_000L : query.from();
        if (from > to) return;
        Set<String> kinds = query.kinds() == null || query.kinds().isEmpty() ? kinds() : query.kinds();
        String player = lower(query.player()), mob = lower(query.mob()), text = lower(query.text());
        LocalDate first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate();
        for (LocalDate day = Instant.ofEpochMilli(to).atZone(zone).toLocalDate(); !day.isBefore(first); day = day.minusDays(1)) {
            List<JsonObject> records = new ArrayList<>();
            for (String kind : kinds) {
                if (!KIND.matcher(kind).matches()) continue;
                Path file = root.resolve(kind).resolve(DAY.format(day) + ".jsonl");
                if (!Files.isRegularFile(file)) continue;
                try {
                    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        String raw = line.toLowerCase(Locale.ROOT);
                        if ((player != null && !raw.contains(player)) || (mob != null && !raw.contains(mob)) || (text != null && !raw.contains(text))) continue;
                        JsonObject record = parse(line);
                        if (record == null) continue;
                        long t = record.get("t").getAsLong();
                        if (t < from || t > to) continue;
                        if (player != null && !names(record.get("player"), player)) continue;
                        if (mob != null && !names(record.get("mob"), mob)) continue;
                        if (text != null && !mentions(record, text)) continue;
                        records.add(record);
                    }
                } catch (IOException failure) { log.accept("история: не прочиталась " + file + ": " + failure.getMessage()); }
            }
            records.sort(NEWEST_FIRST);
            for (JsonObject record : records) if (!visitor.test(record)) return;
        }
    }

    /** Deletes day files older than the retention; returns how many. */
    public int prune() {
        flush();
        LocalDate oldest = Instant.ofEpochMilli(clock.getAsLong()).atZone(zone).toLocalDate().minusDays(retentionDays);
        int removed = 0;
        for (String kind : kinds()) {
            try (DirectoryStream<Path> days = Files.newDirectoryStream(root.resolve(kind), "*.jsonl")) {
                for (Path file : days) {
                    String name = file.getFileName().toString();
                    try {
                        if (LocalDate.parse(name.substring(0, 8), DAY).isBefore(oldest)) { Files.deleteIfExists(file); removed++; }
                    } catch (RuntimeException notADay) { /* Someone else's file; leave it. */ }
                }
            } catch (IOException failure) { log.accept("история: не удалось почистить " + kind + ": " + failure.getMessage()); }
        }
        return removed;
    }

    /** CSV ({@code t,kind,player,mob,text}) or JSONL of the matches, newest first, at most {@code max}. */
    public byte[] export(Query query, String format, int max) {
        StringBuilder out = new StringBuilder();
        boolean csv = !"jsonl".equals(format);
        if (csv) out.append("t,kind,player,mob,text\n");
        DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        int[] count = {0};
        scan(query.withCursor(null), record -> {
            if (count[0]++ >= max) return false;
            if (!csv) { out.append(Json.GSON.toJson(record)).append('\n'); return true; }
            out.append(time.format(Instant.ofEpochMilli(record.get("t").getAsLong()).atZone(zone))).append(',')
                    .append(record.get("kind").getAsString()).append(',')
                    .append(csvCell(nameOf(record.get("player")))).append(',')
                    .append(csvCell(nameOf(record.get("mob")))).append(',')
                    .append(csvCell(Json.string(record, "text"))).append('\n');
            return true;
        });
        return (csv ? "﻿" + out : out.toString()).getBytes(StandardCharsets.UTF_8);
    }

    /** Counts per hour or day, one series per value of {@code by} (a record field or {@code data.<field>}), or one {@code count}. */
    public JsonObject stats(Query query, String bucket, String by) {
        boolean hourly = "hour".equals(bucket);
        TreeSet<Long> buckets = new TreeSet<>();
        Map<String, Map<Long, Integer>> series = new LinkedHashMap<>();
        scan(query.withCursor(null), record -> {
            LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochMilli(record.get("t").getAsLong()), zone);
            long start = (hourly ? at.truncatedTo(ChronoUnit.HOURS) : at.truncatedTo(ChronoUnit.DAYS)).atZone(zone).toEpochSecond();
            buckets.add(start);
            String key = by == null || by.isBlank() ? "count" : valueOf(record, by);
            series.computeIfAbsent(key == null ? "—" : key, k -> new LinkedHashMap<>()).merge(start, 1, Integer::sum);
            return true;
        });
        long now = clock.getAsLong();
        if (query.from() != null) fill(buckets, query.from(), query.to() == null ? now : query.to(), hourly);
        List<Long> times = new ArrayList<>(buckets);
        JsonObject out = Json.obj("bucket", hourly ? "hour" : "day", "t", times);
        JsonObject values = new JsonObject();
        series.forEach((key, counts) -> values.add(key, Json.array(times.stream().map(t -> counts.getOrDefault(t, 0)).toList())));
        out.add("series", values);
        return out;
    }

    /** Kinds that have a folder. */
    public Set<String> kinds() {
        Set<String> kinds = new LinkedHashSet<>();
        if (!Files.isDirectory(root)) return kinds;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path dir : dirs) if (KIND.matcher(dir.getFileName().toString()).matches()) kinds.add(dir.getFileName().toString());
        } catch (IOException failure) { log.accept("история: не читается папка: " + failure.getMessage()); }
        return kinds;
    }

    private void fill(TreeSet<Long> buckets, long from, long to, boolean hourly) {
        ChronoUnit unit = hourly ? ChronoUnit.HOURS : ChronoUnit.DAYS;
        LocalDateTime at = LocalDateTime.ofInstant(Instant.ofEpochMilli(from), zone).truncatedTo(unit);
        LocalDateTime end = LocalDateTime.ofInstant(Instant.ofEpochMilli(to), zone);
        for (int guard = 0; !at.isAfter(end) && guard < 5000; guard++, at = at.plus(1, unit)) buckets.add(at.atZone(zone).toEpochSecond());
    }

    static String valueOf(JsonObject record, String path) {
        JsonElement element = record;
        for (String part : path.split("\\.")) {
            if (element == null || !element.isJsonObject()) return null;
            element = element.getAsJsonObject().get(part);
        }
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonObject()) return nameOf(element);
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    private static final Comparator<JsonObject> NEWEST_FIRST = Comparator
            .comparingLong((JsonObject r) -> r.get("t").getAsLong()).thenComparing(r -> r.get("id").getAsString()).reversed();

    private static boolean older(JsonObject record, String[] cursor) {
        long t = record.get("t").getAsLong(), ct = Long.parseLong(cursor[0]);
        return t < ct || (t == ct && record.get("id").getAsString().compareTo(cursor[1]) < 0);
    }

    private static String cursor(JsonObject record) { return record.get("t").getAsLong() + "~" + record.get("id").getAsString(); }

    private static String[] parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        String[] parts = cursor.split("~", 2);
        if (parts.length != 2 || !parts[0].matches("\\d{1,15}")) throw ApiException.badRequest("Неверный курсор.");
        return parts;
    }

    private static boolean names(JsonElement ref, String wanted) {
        if (ref == null || !ref.isJsonObject()) return false;
        String uuid = Json.string(ref.getAsJsonObject(), "uuid"), name = Json.string(ref.getAsJsonObject(), "name");
        return wanted.equalsIgnoreCase(uuid == null ? "" : uuid) || wanted.equalsIgnoreCase(name == null ? "" : name);
    }

    private static boolean mentions(JsonObject record, String text) {
        String line = Json.string(record, "text");
        if (line != null && line.toLowerCase(Locale.ROOT).contains(text)) return true;
        JsonElement data = record.get("data");
        return data != null && strings(data, text);
    }

    private static boolean strings(JsonElement element, String text) {
        if (element.isJsonPrimitive()) return element.getAsJsonPrimitive().isString() && element.getAsString().toLowerCase(Locale.ROOT).contains(text);
        if (element.isJsonArray()) { for (JsonElement item : element.getAsJsonArray()) if (strings(item, text)) return true; return false; }
        if (element.isJsonObject()) { for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) if (strings(e.getValue(), text)) return true; }
        return false;
    }

    static String nameOf(JsonElement ref) {
        if (ref == null || !ref.isJsonObject()) return "";
        String name = Json.string(ref.getAsJsonObject(), "name");
        return name == null ? "" : name;
    }

    private static String csvCell(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String lower(String value) { return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT); }

    private JsonObject parse(String line) {
        if (line.isBlank()) return null;
        try {
            JsonElement element = JsonParser.parseString(line);
            if (!element.isJsonObject()) return null;
            JsonObject record = element.getAsJsonObject();
            return record.has("t") && record.has("id") && record.has("kind") ? record : null;
        } catch (JsonParseException | IllegalStateException broken) { return null; }
    }
}
