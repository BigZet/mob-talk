package io.github.mobtalk.server.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** The admin page's web server: the page itself, login, and every {@code /api/*} route behind a session (docs/admin-web.md).
 * HTTPS on a server; plain HTTP only for the loopback stub and tests, where the cookie drops {@code Secure}. */
public final class AdminHttpServer {
    public static final String COOKIE = "mt_session";
    static final int MAX_BODY = 1 << 20;
    static final String CSP = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; connect-src 'self'; "
            + "frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    public record Options(String bind, int port, SSLContext tls, boolean secureCookie) { }

    private final AdminAuth auth;
    private final Router router;
    private final StaticFiles files;
    private final Consumer<String> log;
    private final HttpServer server;
    private final ExecutorService workers;
    private volatile String mountPrefix, mountCsp;
    private volatile Router.Handler mountHandler;

    public AdminHttpServer(Options options, AdminAuth auth, Router router, StaticFiles files, Consumer<String> log) throws IOException {
        this.auth = auth; this.router = router; this.files = files; this.log = log;
        InetSocketAddress address = new InetSocketAddress(options.bind(), options.port());
        if (options.tls() != null) {
            HttpsServer https = HttpsServer.create(address, 32);
            https.setHttpsConfigurator(new HttpsConfigurator(options.tls()));
            server = https;
        } else server = HttpServer.create(address, 32);
        AtomicInteger counter = new AtomicInteger();
        workers = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "mobtalk-admin-" + counter.incrementAndGet()); thread.setDaemon(true); return thread;
        });
        server.setExecutor(workers);
        boolean secure = options.secureCookie();
        server.createContext("/", exchange -> serve(exchange, secure));
    }

    /** Files under {@code prefix} (e.g. {@code /map3d/}) for a signed-in admin, with their own CSP and framing allowed from the admin page. */
    public void mount(String prefix, String csp, Router.Handler handler) { mountPrefix = prefix; mountCsp = csp; mountHandler = handler; }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    public void stop() { server.stop(0); workers.shutdownNow(); }

    private void serve(HttpExchange exchange, boolean secureCookie) {
        try (exchange) {
            Headers out = exchange.getResponseHeaders();
            out.set("Content-Security-Policy", CSP);
            out.set("X-Frame-Options", "DENY");
            out.set("X-Content-Type-Options", "nosniff");
            out.set("Referrer-Policy", "no-referrer");
            String path = exchange.getRequestURI().getRawPath();
            Response response;
            String prefix = mountPrefix;
            boolean mounted = prefix != null && (path.startsWith(prefix) || path.equals(prefix.substring(0, prefix.length() - 1)));
            if (mounted) {
                out.set("Content-Security-Policy", mountCsp);
                out.set("X-Frame-Options", "SAMEORIGIN");
            }
            try {
                if (mounted) response = mounted(exchange, prefix, path);
                else response = path.equals("/api") || path.startsWith("/api/") ? api(exchange, secureCookie) : page(exchange.getRequestMethod(), path);
            } catch (ApiException failure) {
                response = error(failure);
            } catch (Exception | LinkageError failure) {
                log.accept("админка: " + exchange.getRequestMethod() + " " + path + " упал: " + failure);
                response = error(new ApiException(500, "internal", "Внутренняя ошибка сервера: " + failure.getClass().getSimpleName()));
            }
            send(exchange, response);
        } catch (IOException gone) {
            // The page closed the connection; nothing to tell it.
        }
    }

    private Response page(String method, String path) throws IOException {
        if (!method.equals("GET") && !method.equals("HEAD")) throw new ApiException(405, "method_not_allowed", "Только GET.");
        String clean = StaticFiles.clean(java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8));
        byte[] body = clean == null ? null : files.read(clean);
        if (body == null) return Response.text(404, "text/plain; charset=utf-8", "Не найдено");
        return Response.bytes(200, StaticFiles.type(clean), body).header("Cache-Control", "no-cache");
    }

    private Response mounted(HttpExchange exchange, String prefix, String path) throws Exception {
        if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) throw new ApiException(405, "method_not_allowed", "Только GET.");
        if (auth.session(cookie(exchange.getRequestHeaders().getFirst("Cookie"))) == null)
            return Response.text(401, "text/plain; charset=utf-8", "Нужно войти в админку.");
        String rest = path.length() <= prefix.length() ? "" : java.net.URLDecoder.decode(path.substring(prefix.length()), java.nio.charset.StandardCharsets.UTF_8);
        return mountHandler.handle(new Request("GET", rest, Request.parseQuery(exchange.getRequestURI().getRawQuery()), Map.of(),
                exchange.getRequestHeaders(), new byte[0], exchange.getRemoteAddress().getAddress().getHostAddress(), null));
    }

    private Response api(HttpExchange exchange, boolean secureCookie) throws Exception {
        String method = exchange.getRequestMethod();
        Request request = new Request(method, exchange.getRequestURI().getRawPath().replaceAll("/+$", ""),
                Request.parseQuery(exchange.getRequestURI().getRawQuery()), Map.of(), exchange.getRequestHeaders(),
                body(exchange.getRequestBody()), exchange.getRemoteAddress().getAddress().getHostAddress(), null);
        if (!method.equals("GET") && !"1".equals(request.header("X-MobTalk")))
            throw new ApiException(403, "csrf", "Запрос без заголовка X-MobTalk отклонён.");
        String token = cookie(request.header("Cookie"));
        switch (request.path()) {
            case "/api/login" -> {
                if (!method.equals("POST")) throw new ApiException(405, "method_not_allowed", "Только POST.");
                AdminAuth.Session session = auth.login(request.ip(), Json.string(request.json(), "password"));
                return sessionJson(session).header("Set-Cookie", COOKIE + "=" + session.token() + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
                        + AdminAuth.SESSION_MILLIS / 1000 + (secureCookie ? "; Secure" : ""));
            }
            case "/api/logout" -> {
                auth.logout(token);
                return Response.json(Json.ok(null)).header("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0" + (secureCookie ? "; Secure" : ""));
            }
            default -> { }
        }
        AdminAuth.Session session = auth.session(token);
        if (session == null) throw new ApiException(401, "unauthorized", "Нужно войти.");
        if (request.path().equals("/api/session")) return sessionJson(session);
        if (session.level() != AdminAuth.Level.ADMIN) throw ApiException.forbidden("Это может только администратор.");
        Map<String, String> params = new HashMap<>();
        Router.Handler handler = router.find(request, params);
        if (handler == null) throw ApiException.notFound("Нет такого адреса API.");
        Response response = handler.handle(request.withParams(params).withSession(session));
        response.headers.putIfAbsent("Cache-Control", "no-store");
        return response;
    }

    private static Response sessionJson(AdminAuth.Session session) {
        return Response.json(Json.obj("user", session.user(), "level", session.level().name(), "expires", session.expires()));
    }

    static Response error(ApiException failure) {
        Response response = Response.json(failure.status, failure.retryAfter > 0
                ? Json.obj("error", failure.code, "message", failure.getMessage(), "retryAfter", failure.retryAfter)
                : Json.obj("error", failure.code, "message", failure.getMessage()));
        if (failure.retryAfter > 0) response.header("Retry-After", Integer.toString(failure.retryAfter));
        return response;
    }

    static String cookie(String header) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(COOKIE + "=")) return trimmed.substring(COOKIE.length() + 1);
        }
        return null;
    }

    private static byte[] body(InputStream in) throws IOException {
        byte[] body = in.readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) throw new ApiException(413, "too_large", "Тело запроса больше 1 МБ.");
        return body;
    }

    private static void send(HttpExchange exchange, Response response) throws IOException {
        Headers out = exchange.getResponseHeaders();
        out.set("Content-Type", response.contentType);
        response.headers.forEach(out::set);
        if (response.stream != null) {
            out.set("Cache-Control", "no-store");
            out.set("X-Accel-Buffering", "no");
            exchange.sendResponseHeaders(response.status, 0);
            try (OutputStream body = exchange.getResponseBody()) { response.stream.write(body); }
            catch (InterruptedException stopping) { Thread.currentThread().interrupt(); }
            return;
        }
        boolean head = exchange.getRequestMethod().equals("HEAD");
        exchange.sendResponseHeaders(response.status, head || response.body.length == 0 ? -1 : response.body.length);
        if (!head && response.body.length > 0) try (OutputStream body = exchange.getResponseBody()) { body.write(response.body); }
    }
}
