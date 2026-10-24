package io.github.mobtalk.server.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The admin page itself (track W2): served from the jar's assets with the security headers, every file it loads is there,
 * it only talks to known {@code /api/*} routes, pulls nothing from other hosts and carries no keys or local paths. */
class AdminWebSmokeTest {
    static final Path WEB = Path.of("src/main/resources/assets/mobtalk/web");
    /** First segments of the routes in docs/admin-web.md. */
    static final Set<String> ROUTES = Set.of("login", "logout", "session", "overview", "metrics", "latency", "history", "costs", "sources", "config",
            "players", "streamer", "whitelist", "waypoints", "mobs", "villages", "divine", "server", "logs", "diagnostics", "errors", "backups", "events", "live", "map", "map3d", "moments");
    /** Vendored files: checked for presence, not scanned. */
    static final Set<String> VENDORED = Set.of("uplot.min.js", "uplot.min.css");
    static final Pattern IMPORT = Pattern.compile("(?:import\\s[^'\"]*?from\\s*|import\\(\\s*`?)['\"`]([^'\"`$]+)['\"`]");
    static final Pattern API_CALL = Pattern.compile("(?:api\\.(?:get|post|patch|del|href)|new Stream)\\(\\s*[`'\"]([^`'\"]+)[`'\"]");

    @TempDir Path dir;
    private DevStubServer.Stub stub;
    private AdminHttpServer server;
    private HttpClient client;
    private String base;

    @BeforeEach void start() throws Exception {
        stub = DevStubServer.create(dir.resolve("data"), System::currentTimeMillis, new Random(1));
        Path store = dir.resolve("config").resolve("mobtalk-admin.p12");
        AdminTls.Loaded tls = AdminTls.loadOrCreate(store, "127.0.0.1");
        server = new AdminHttpServer(new AdminHttpServer.Options("127.0.0.1", 0, tls.context(), true),
                new AdminAuth(AdminAuth.hash("smoke"), System::currentTimeMillis), stub.api.router(), StaticFiles.classpath("assets/mobtalk/web/"), System.err::println);
        server.start();
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("admin", AdminTls.certificate(store));
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        client = HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(5)).build();
        base = "https://127.0.0.1:" + server.port() + "/";
    }

    @AfterEach void stop() { server.stop(); stub.api.close(); stub.history.close(); stub.metrics.close(); }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test void pageIsServedWithHeadersAndEveryFileItLoads() throws Exception {
        HttpResponse<byte[]> index = get("");
        assertEquals(200, index.statusCode());
        assertTrue(index.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        String csp = index.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(csp.contains("default-src 'self'") && csp.contains("frame-ancestors 'none'"), csp);
        assertEquals("DENY", index.headers().firstValue("X-Frame-Options").orElse(""));
        String html = new String(index.body(), StandardCharsets.UTF_8);
        assertTrue(html.contains("lang=\"ru\""));

        Set<String> needed = new LinkedHashSet<>();
        Matcher refs = Pattern.compile("(?:src|href)=\"([^\"#:]+)\"").matcher(html);
        while (refs.find()) needed.add(refs.group(1));
        needed.addAll(modules());
        for (String file : needed) {
            HttpResponse<byte[]> response = get(file);
            assertEquals(200, response.statusCode(), file);
            assertNotEquals("application/octet-stream", response.headers().firstValue("Content-Type").orElse(""), file);
        }
        assertEquals(404, get("nope.js").statusCode());
    }

    @Test void firstLoadStaysUnder200Kilobytes() throws Exception {
        long total = Files.size(WEB.resolve("index.html"));
        for (String f : List.of("app.css", "uplot.min.css", "uplot.min.js", "theme.js", "icon.svg")) total += Files.size(WEB.resolve(f));
        for (String module : staticImports("app.js")) total += Files.size(WEB.resolve(module));
        for (String module : staticImports("views/overview.js")) if (!module.startsWith("lib/") && !module.equals("app.js")) total += Files.size(WEB.resolve(module));
        total += Files.size(WEB.resolve("views/overview.js"));
        assertTrue(total <= 200 * 1024, "первая загрузка " + total + " байт");
    }

    @Test void talksOnlyToKnownApiRoutesAndNoOtherHosts() throws Exception {
        Set<String> used = new TreeSet<>();
        for (Path file : ownFiles()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String name = WEB.relativize(file).toString().replace('\\', '/');
            Matcher urls = Pattern.compile("\\b(?:https?:)?//[\\w.-]+\\.[a-z]{2,}[^\\s'\"`)]*").matcher(text);
            while (urls.find()) assertEquals("http://www.w3.org/2000/svg", urls.group(), "внешний адрес в " + name);
            if (!name.equals("lib/api.js")) assertFalse(text.contains("fetch("), "fetch мимо lib/api.js в " + name);
            if (!name.equals("lib/sse.js")) assertFalse(text.contains("new EventSource("), "EventSource мимо lib/sse.js в " + name);
            assertFalse(text.contains("innerHTML") || text.contains("insertAdjacentHTML") || text.contains("eval("), "разметка из строк в " + name);
            Matcher calls = API_CALL.matcher(text);
            while (calls.find()) {
                String path = calls.group(1);
                String root = path.split("[/?]")[0];
                assertTrue(ROUTES.contains(root), "неизвестный адрес /api/" + path + " в " + name);
                assertFalse(path.contains(".."), path);
                used.add(root);
            }
        }
        assertTrue(used.containsAll(Set.of("login", "session", "overview", "metrics", "history", "sources", "players", "events", "live", "logs", "diagnostics")), used.toString());
    }

    @Test void carriesNoSecretsOrLocalPaths() throws Exception {
        Pattern secret = Pattern.compile("sk-or-v1-|sk-[A-Za-z0-9]{24,}|Bearer\\s+[A-Za-z0-9]|[A-Z]:\\\\Users|/home/[a-z]|/Users/[a-z]");
        for (Path file : ownFiles()) assertFalse(secret.matcher(Files.readString(file, StandardCharsets.UTF_8)).find(), file.toString());
    }

    private static List<Path> ownFiles() throws Exception {
        try (Stream<Path> files = Files.walk(WEB)) {
            return files.filter(Files::isRegularFile).filter(f -> !VENDORED.contains(f.getFileName().toString()))
                    .filter(f -> f.toString().matches(".*\\.(js|css|html|svg)$")).toList();
        }
    }

    /** All modules app.js reaches, static and dynamic (views/<раздел>.js), relative to the web folder. */
    private static Set<String> modules() throws Exception {
        Set<String> all = new LinkedHashSet<>(staticImports("app.js"));
        try (Stream<Path> views = Files.list(WEB.resolve("views"))) {
            for (Path v : views.toList()) { String m = "views/" + v.getFileName(); all.add(m); all.addAll(staticImports(m)); }
        }
        return all;
    }

    private static Set<String> staticImports(String start) throws Exception {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            String module = queue.poll();
            if (!seen.add(module)) continue;
            Matcher m = IMPORT.matcher(Files.readString(WEB.resolve(module), StandardCharsets.UTF_8));
            Path dirOf = Path.of(module).getParent();
            while (m.find()) {
                String target = m.group(1);
                if (!target.startsWith(".")) continue;
                String resolved = (dirOf == null ? Path.of(target) : dirOf.resolve(target)).normalize().toString().replace('\\', '/');
                assertTrue(Files.isRegularFile(WEB.resolve(resolved)), module + " импортирует несуществующий " + target);
                queue.add(resolved);
            }
        }
        return seen;
    }
}
