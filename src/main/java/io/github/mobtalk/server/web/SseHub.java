package io.github.mobtalk.server.web;

import com.google.gson.JsonElement;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Server-sent events: everyone subscribed gets every published event; a slow page loses events past 500, never the server. */
public final class SseHub {
    private static final String CLOSE = "\u0000close";
    private static final int BACKLOG = 500;

    private final List<BlockingQueue<String>> subscribers = new CopyOnWriteArrayList<>();
    private final int maxSubscribers;
    private final long pingMillis;

    public SseHub(int maxSubscribers, long pingMillis) { this.maxSubscribers = maxSubscribers; this.pingMillis = pingMillis; }

    public static String frame(String event, JsonElement data) {
        return "event: " + event + "\ndata: " + Json.GSON.toJson(data) + "\n\n";
    }

    public void publish(String event, JsonElement data) {
        String frame = frame(event, data);
        for (BlockingQueue<String> queue : subscribers) queue.offer(frame);
    }

    public int subscribers() { return subscribers.size(); }

    /** A response that streams this hub; {@code first} is sent before anything published (hello, a snapshot). */
    public Response stream(Supplier<List<String>> first) {
        if (subscribers.size() >= maxSubscribers) throw ApiException.unavailable("Слишком много открытых вкладок админки.");
        return Response.events(out -> {
            BlockingQueue<String> queue = new LinkedBlockingQueue<>(BACKLOG);
            subscribers.add(queue);
            try {
                write(out, "retry: 3000\n\n");
                for (String frame : first.get()) write(out, frame);
                while (true) {
                    String frame = queue.poll(pingMillis, TimeUnit.MILLISECONDS);
                    if (CLOSE.equals(frame)) return;
                    write(out, frame == null ? ": ping\n\n" : frame);
                }
            } finally { subscribers.remove(queue); }
        });
    }

    /** Ends every stream, at server stop. */
    public void close() { for (BlockingQueue<String> queue : subscribers) { queue.clear(); queue.offer(CLOSE); } }

    private static void write(OutputStream out, String frame) throws IOException {
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
