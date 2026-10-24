package io.github.mobtalk.overlay;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Что видит страница оверлея (идея З3, docs/overlay.md): настройки игрока ({@code config}), данные сервера ({@code state}) и
 * субтитры ({@code line}). Каждая открытая страница (источник OBS, вкладка браузера) получает их по SSE; новая сначала получает
 * последние {@code config} и {@code state}. Медленная страница теряет события сверх 200, игра не ждёт никогда. Без классов Minecraft. */
public final class OverlayFeed {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final String CLOSE = "\u0000close";
    private static final int BACKLOG = 200, MAX_PAGES = 16;
    private final List<BlockingQueue<String>> pages = new CopyOnWriteArrayList<>();
    private final long pingMillis;
    private volatile JsonObject config = new JsonObject(), state = new JsonObject();

    public OverlayFeed() { this(15_000); }
    OverlayFeed(long pingMillis) { this.pingMillis = pingMillis; }

    /** Личные настройки оверлея (виджеты, стиль, масштаб…), как их видит страница. */
    public void config(JsonObject value) { config = value == null ? new JsonObject() : value.deepCopy(); publish("config", config); }
    /** Данные сервера ({@code OverlayStateS2C}); пустой объект — данных нет. */
    public void state(JsonObject value) { state = value == null ? new JsonObject() : value.deepCopy(); publish("state", state); }
    /** Одна строка субтитров: {@code speaker}, {@code text}, {@code kind}, {@code heard}, {@code asker}, {@code millis}. */
    public void line(JsonObject line) { publish("line", line); }
    public JsonObject config() { return config.deepCopy(); }
    public JsonObject state() { return state.deepCopy(); }
    public int pages() { return pages.size(); }

    private void publish(String event, JsonObject data) {
        String frame = frame(event, data);
        for (BlockingQueue<String> queue : pages) queue.offer(frame);
    }
    static String frame(String event, JsonObject data) { return "event: " + event + "\ndata: " + GSON.toJson(data) + "\n\n"; }

    /** Поток SSE одной страницы, пока она не закроется или не вызван {@link #close}. Поток веб-сервера. */
    boolean stream(OutputStream out) throws IOException {
        if (pages.size() >= MAX_PAGES) return false;
        BlockingQueue<String> queue = new LinkedBlockingQueue<>(BACKLOG);
        pages.add(queue);
        try {
            write(out, "retry: 2000\n\n");
            write(out, frame("config", config));
            write(out, frame("state", state));
            while (true) {
                String frame;
                try { frame = queue.poll(pingMillis, TimeUnit.MILLISECONDS); }
                catch (InterruptedException stop) { Thread.currentThread().interrupt(); return true; }
                if (CLOSE.equals(frame)) return true;
                write(out, frame == null ? ": ping\n\n" : frame);
            }
        } finally { pages.remove(queue); }
    }
    /** Закрывает все потоки (выключили оверлей, вышли из игры). */
    public void close() { for (BlockingQueue<String> queue : pages) { queue.clear(); queue.offer(CLOSE); } }

    private static void write(OutputStream out, String frame) throws IOException {
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
