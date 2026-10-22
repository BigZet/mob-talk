package io.github.mobtalk.server.web;

import com.google.gson.JsonObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Exceptions grouped by type, place and top frames, with a count and the last stack. The first one of a group and then at most
 * one a minute go into the history, so a failure in a loop does not flood it. */
public final class ErrorLog {
    private static final int MAX_GROUPS = 200;
    private static final long HISTORY_EVERY = 60_000;

    private static final class Group {
        final String id, type, where;
        String message, stack, context;
        long count, first, last, written = Long.MIN_VALUE;
        Group(String id, String type, String where) { this.id = id; this.type = type; this.where = where; }
    }

    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final HistoryStore history;
    private final LongSupplier clock;
    private final AtomicLong recent = new AtomicLong();
    private final List<Consumer<JsonObject>> listeners = new CopyOnWriteArrayList<>();

    public ErrorLog(HistoryStore history, LongSupplier clock) { this.history = history; this.clock = clock; }

    public void listen(Consumer<JsonObject> listener) { listeners.add(listener); }

    public void record(Throwable failure, String where, String context) {
        long now = clock.getAsLong();
        String stack = stack(failure);
        String type = failure.getClass().getName(), message = Redact.text(String.valueOf(failure.getMessage()));
        String id = Integer.toHexString((type + "|" + where + "|" + topFrames(failure)).hashCode());
        Group group;
        boolean write;
        synchronized (groups) {
            group = groups.get(id);
            if (group == null) {
                if (groups.size() >= MAX_GROUPS) groups.remove(groups.keySet().iterator().next());
                group = new Group(id, type, where);
                group.first = now;
                groups.put(id, group);
            }
            group.count++; group.last = now; group.message = message; group.stack = stack; group.context = Redact.text(context);
            write = now - group.written >= HISTORY_EVERY;
            if (write) group.written = now;
        }
        recent.incrementAndGet();
        JsonObject json;
        synchronized (groups) { json = json(group); }
        if (write) history.append("error", null, null, failure.getClass().getSimpleName() + " в " + where + ": " + message,
                Json.obj("type", type, "message", message, "where", where, "stack", group.stack, "context", group.context, "repeat", group.count));
        for (Consumer<JsonObject> listener : listeners) {
            try { listener.accept(json); } catch (RuntimeException ignored) { /* Never fail while reporting a failure. */ }
        }
    }

    public JsonObject groups() {
        List<JsonObject> list = new ArrayList<>();
        synchronized (groups) { for (Group group : groups.values()) list.add(json(group)); }
        list.sort((a, b) -> Long.compare(b.get("last").getAsLong(), a.get("last").getAsLong()));
        return Json.obj("groups", list);
    }

    public int lastHour() {
        long since = clock.getAsLong() - 3_600_000;
        int count = 0;
        synchronized (groups) { for (Group group : groups.values()) if (group.last >= since) count += (int) Math.min(Integer.MAX_VALUE, group.count); }
        return count;
    }

    public void clear() { synchronized (groups) { groups.clear(); } }

    /** Errors since the last call, for the {@code errors} metric. */
    public long drain() { return recent.getAndSet(0); }

    private static JsonObject json(Group group) {
        return Json.obj("id", group.id, "type", group.type, "message", group.message, "where", group.where, "count", group.count,
                "first", group.first, "last", group.last, "stack", group.stack, "context", group.context);
    }

    private static String topFrames(Throwable failure) {
        StringBuilder frames = new StringBuilder();
        StackTraceElement[] trace = failure.getStackTrace();
        for (int i = 0; i < Math.min(3, trace.length); i++) frames.append(trace[i].getClassName()).append('.').append(trace[i].getMethodName()).append(';');
        return frames.toString();
    }

    private static String stack(Throwable failure) {
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        String text = out.toString();
        return Redact.text(text.length() > 8000 ? text.substring(0, 8000) + "\n…" : text);
    }
}
