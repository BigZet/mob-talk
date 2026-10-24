package io.github.mobtalk.overlay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Веб-сервер оверлея в игре стримера (идея З3, docs/overlay.md): только {@code 127.0.0.1}, только чтение, без CORS. Страница —
 * {@code assets/mobtalk/overlay/} из jar, события — {@code /overlay/events} (SSE из {@link OverlayFeed}), снимок — {@code /overlay/state}.
 * Запрос с чужим {@code Host} (не 127.0.0.1 и не localhost) отвергается: посторонний сайт в браузере стримера не прочтёт данные
 * через подмену DNS. Потоки свои, игровой поток не трогается. */
public final class OverlayWeb {
    public static final Set<String> WIDGETS = Set.of("subtitles", "god", "mercy", "now");
    private static final String ASSETS = "/assets/mobtalk/overlay/";
    private static final String CSP = "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'";
    private final HttpServer server;
    private final ExecutorService workers;
    private final OverlayFeed feed;
    private final int port;

    private OverlayWeb(HttpServer server, ExecutorService workers, OverlayFeed feed) {
        this.server = server; this.workers = workers; this.feed = feed; this.port = server.getAddress().getPort();
    }

    /** {@code port} 0 — любой свободный (тесты). Занятый порт — {@link IOException}. */
    public static OverlayWeb start(int port, OverlayFeed feed) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        AtomicInteger count = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(20, runnable -> {
            Thread thread = new Thread(runnable, "mobtalk-overlay-" + count.incrementAndGet()); thread.setDaemon(true); return thread;
        });
        OverlayWeb web = new OverlayWeb(server, workers, feed);
        server.createContext("/", web::handle);
        server.setExecutor(workers);
        server.start();
        return web;
    }

    public int port() { return port; }
    public String address() { return "http://127.0.0.1:" + port + "/overlay/"; }

    public void stop() {
        feed.close();
        server.stop(0);
        workers.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Security-Policy", CSP);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            if (!localHost(exchange.getRequestHeaders().getFirst("Host"))) { plain(exchange, 403, "Только 127.0.0.1"); return; }
            if (!"GET".equals(exchange.getRequestMethod())) { plain(exchange, 405, "Только чтение"); return; }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/overlay")) { exchange.getResponseHeaders().set("Location", "/overlay/"); exchange.sendResponseHeaders(302, -1); return; }
            if (!path.startsWith("/overlay/")) { plain(exchange, 404, "Нет такой страницы"); return; }
            String rest = path.substring("/overlay/".length());
            switch (rest) {
                case "events" -> events(exchange);
                case "state" -> json(exchange, OverlayFeed.GSON.toJson(snapshot()));
                case "", "subtitles", "god", "mercy", "now" -> asset(exchange, "index.html");
                default -> { if (rest.matches("[a-z0-9-]+\\.(js|css)")) asset(exchange, rest); else plain(exchange, 404, "Нет такого файла"); }
            }
        } catch (IOException closed) { /* страница закрылась */ }
    }

    private com.google.gson.JsonObject snapshot() {
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        out.add("config", feed.config()); out.add("state", feed.state());
        return out;
    }

    /** {@code Host} без порта: 127.0.0.1, localhost или [::1]. */
    static boolean localHost(String host) {
        if (host == null) return false;
        String name = host.strip().toLowerCase(Locale.ROOT);
        if (name.startsWith("[::1]")) return name.equals("[::1]") || name.startsWith("[::1]:");
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(0, colon);
        return name.equals("127.0.0.1") || name.equals("localhost");
    }

    private void events(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) { feed.stream(out); }
    }
    private static void asset(HttpExchange exchange, String name) throws IOException {
        byte[] body;
        try (InputStream in = OverlayWeb.class.getResourceAsStream(ASSETS + name)) {
            if (in == null) { plain(exchange, 404, "Нет файла " + name); return; }
            body = in.readAllBytes();
        }
        String type = name.endsWith(".js") ? "text/javascript; charset=utf-8" : name.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8";
        send(exchange, 200, type, body);
    }
    private static void json(HttpExchange exchange, String body) throws IOException { send(exchange, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8)); }
    private static void plain(HttpExchange exchange, int code, String text) throws IOException { send(exchange, code, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8)); }
    private static void send(HttpExchange exchange, int code, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
        if (body.length > 0) try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
    }
}
