package io.github.mobtalk.server.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The admin API over real HTTPS with a certificate made by keytool: login, the brake, every route on the stub's fixtures, SSE. */
class AdminApiTest {
    @TempDir Path dir;
    private DevStubServer.Stub stub;
    private AdminHttpServer server;
    private HttpClient client;
    private String base, cookie;

    static final List<String> GETS = List.of("overview", "metrics", "metrics/now", "latency?kind=llm", "history", "history/stats?kind=dialogue",
            "history/export?format=jsonl", "costs", "sources", "sources/mobtalk/values", "sources/divineblock/actions", "config/history", "players",
            "players/" + DevStubServer.BIGZET, "players/positions", "players/tracks", "map/info", "map/markers", "players/" + DevStubServer.BIGZET + "/position", "waypoints", "mobs", "mobs/fb57b8eb-d3e2-4f1e-8da3-2101caf9f459", "villages", "divine/souls", "divine/active",
            "server", "server/commands", "logs", "logs/export", "logs/export?filter=mobtalk&level=WARN", "diagnostics", "errors", "backups");

    @BeforeEach void start() throws Exception {
        stub = DevStubServer.create(dir.resolve("data"), System::currentTimeMillis, new Random(1));
        stub.seed();
        Path store = dir.resolve("config").resolve("mobtalk-admin.p12");
        AdminTls.Loaded tls = AdminTls.loadOrCreate(store, "127.0.0.1");
        server = new AdminHttpServer(new AdminHttpServer.Options("127.0.0.1", 0, tls.context(), true),
                new AdminAuth(AdminAuth.hash("correct horse"), System::currentTimeMillis), stub.api.router(), StaticFiles.classpath("admin-stub/"), System.err::println);
        server.start();
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("admin", AdminTls.certificate(store));
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        client = HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(5)).build();
        base = "https://127.0.0.1:" + server.port();
    }

    @AfterEach void stop() { server.stop(); stub.api.close(); stub.history.close(); stub.metrics.close(); }

    private HttpResponse<String> send(String method, String path, String body, boolean csrf) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) request.header("Cookie", cookie);
        if (csrf) request.header("X-MobTalk", "1");
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> login(String password) throws Exception {
        HttpResponse<String> response = send("POST", "/api/login", "{\"password\":\"" + password + "\"}", true);
        response.headers().firstValue("Set-Cookie").ifPresent(set -> cookie = set.split(";")[0]);
        return response;
    }

    private static JsonObject json(HttpResponse<String> response) { return JsonParser.parseString(response.body()).getAsJsonObject(); }

    @Test void everyApiNeedsASession() throws Exception {
        for (String path : GETS) assertEquals(401, send("GET", "/api/" + path, null, false).statusCode(), path);
        assertEquals(401, send("GET", "/api/session", null, false).statusCode());
        assertEquals(401, send("PATCH", "/api/sources/mobtalk/values", "{\"values\":{}}", true).statusCode());
        assertEquals("unauthorized", json(send("GET", "/api/players", null, false)).get("error").getAsString());
    }

    @Test void wrongPasswordThenTheBrake() throws Exception {
        for (int i = 0; i < 5; i++) assertEquals(401, login("wrong").statusCode());
        HttpResponse<String> sixth = login("correct horse");
        assertEquals(429, sixth.statusCode(), "шестая попытка за минуту");
        assertTrue(sixth.headers().firstValue("Retry-After").isPresent());
        assertTrue(json(sixth).get("retryAfter").getAsInt() > 0);
    }

    @Test void loginSetsAStrictCookie() throws Exception {
        HttpResponse<String> ok = login("correct horse");
        assertEquals(200, ok.statusCode());
        String set = ok.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(set.contains("HttpOnly") && set.contains("Secure") && set.contains("SameSite=Strict"), set);
        assertEquals("ADMIN", json(send("GET", "/api/session", null, false)).get("level").getAsString());
        assertEquals(200, send("POST", "/api/logout", null, true).statusCode());
        assertEquals(401, send("GET", "/api/session", null, false).statusCode());
    }

    @Test void everyRouteAnswersOnFixtures() throws Exception {
        login("correct horse");
        for (String path : GETS) {
            HttpResponse<String> response = send("GET", "/api/" + path, null, false);
            assertEquals(200, response.statusCode(), path + ": " + response.body());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""), path);
            assertEquals("DENY", response.headers().firstValue("X-Frame-Options").orElse(""), path);
            assertTrue(response.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'self'"), path);
        }
        assertTrue(json(send("GET", "/api/players", null, false)).getAsJsonArray("players").size() >= 3);
        JsonObject costs = json(send("GET", "/api/costs?group=player", null, false));
        assertTrue(costs.get("totalUsd").getAsDouble() > 0);
        assertTrue(json(send("GET", "/api/history?kind=verdict&limit=5", null, false)).getAsJsonArray("items").size() > 0, "вердикты бога в журнале");
        JsonObject metrics = json(send("GET", "/api/metrics?series=tps,mspt&points=60", null, false));
        assertEquals(metrics.getAsJsonArray("t").size(), metrics.getAsJsonObject("series").getAsJsonArray("tps").size());
        assertEquals(400, send("GET", "/api/metrics?series=nope", null, false).statusCode());
        assertEquals(404, send("GET", "/api/players/" + java.util.UUID.randomUUID(), null, false).statusCode());
        assertEquals(404, send("GET", "/api/nothing", null, false).statusCode());
        assertEquals(404, send("GET", "/missing.js", null, false).statusCode());
        assertEquals(200, send("GET", "/players.json", null, false).statusCode(), "статика из папки страницы");
    }

    @Test void writesNeedTheCsrfHeader() throws Exception {
        login("correct horse");
        HttpResponse<String> response = send("PATCH", "/api/sources/mobtalk/values", "{\"values\":{\"maxReplyWords\":50}}", false);
        assertEquals(403, response.statusCode());
        assertEquals("csrf", json(response).get("error").getAsString());
    }

    @Test void patchRejectsUnknownAndOutOfRangeAndReachesTheEventStream() throws Exception {
        login("correct horse");
        HttpRequest stream = HttpRequest.newBuilder(URI.create(base + "/api/events")).header("Cookie", cookie).build();
        HttpResponse<InputStream> events = client.send(stream, HttpResponse.BodyHandlers.ofInputStream());
        BufferedReader reader = new BufferedReader(new InputStreamReader(events.body(), StandardCharsets.UTF_8));
        assertEquals("retry: 3000", reader.readLine());
        HttpResponse<String> patch = send("PATCH", "/api/sources/mobtalk/values",
                "{\"values\":{\"maxReplyWords\":61,\"maxDistance\":999,\"whisperBeamSize\":5,\"noSuchKey\":1}}", true);
        assertEquals(200, patch.statusCode());
        JsonObject answer = json(patch);
        assertEquals(List.of("maxReplyWords"), answer.getAsJsonArray("applied").asList().stream().map(e -> e.getAsString()).toList());
        JsonObject errors = answer.getAsJsonObject("errors");
        assertTrue(errors.has("maxDistance") && errors.has("whisperBeamSize") && errors.has("noSuchKey"), errors.toString());
        assertEquals(61, json(send("GET", "/api/sources/mobtalk/values", null, false)).getAsJsonObject("values").get("maxReplyWords").getAsInt());
        CompletableFuture<Boolean> sawConfig = CompletableFuture.supplyAsync(() -> {
            try {
                for (String line; (line = reader.readLine()) != null; ) if (line.equals("event: config")) return true;
            } catch (IOException closed) { return false; }
            return false;
        });
        assertTrue(sawConfig.get(10, TimeUnit.SECONDS), "событие config в SSE после PATCH");
        events.body().close();
        JsonObject change = json(send("GET", "/api/config/history?source=mobtalk&limit=1", null, false)).getAsJsonArray("items").get(0).getAsJsonObject();
        assertEquals("maxReplyWords", change.getAsJsonObject("data").get("key").getAsString());
        HttpResponse<String> rollback = send("POST", "/api/config/rollback/" + change.get("id").getAsString(), null, true);
        assertEquals(200, rollback.statusCode(), rollback.body());
        assertEquals(60, json(send("GET", "/api/sources/mobtalk/values", null, false)).getAsJsonObject("values").get("maxReplyWords").getAsInt());
        assertEquals(409, send("POST", "/api/config/rollback/" + change.get("id").getAsString(), null, true).statusCode(), "значение с тех пор меняли");
    }

    @Test void playerAndMobActions() throws Exception {
        login("correct horse");
        String test2 = "/api/players/" + DevStubServer.TEST2;
        assertEquals(200, send("POST", test2 + "/ban", "{\"reason\":\"тест\"}", true).statusCode());
        assertTrue(json(send("GET", test2, null, false)).get("banned").getAsBoolean());
        assertEquals(409, send("POST", test2 + "/kick", "{}", true).statusCode(), "кик офлайн-игрока");
        assertEquals(200, send("POST", "/api/streamer", "{\"uuid\":\"" + DevStubServer.TEST2 + "\"}", true).statusCode());
        assertEquals(200, send("POST", "/api/mobs/fb57b8eb-d3e2-4f1e-8da3-2101caf9f459/card", "{\"name\":\"Тереза Ивановна\"}", true).statusCode());
        assertEquals("Тереза Ивановна", json(send("GET", "/api/mobs/fb57b8eb-d3e2-4f1e-8da3-2101caf9f459", null, false)).getAsJsonObject("card").get("name").getAsString());
        assertEquals(200, send("POST", "/api/villages/v1/forgive/" + DevStubServer.TEST2, null, true).statusCode());
    }

    @Test void positionsTeleportAndWaypoints() throws Exception {
        login("correct horse");
        String bigzet = "/api/players/" + DevStubServer.BIGZET;
        assertEquals(2, json(send("GET", "/api/players/positions", null, false)).getAsJsonArray("players").size(), "только онлайн");
        HttpResponse<String> added = send("POST", "/api/waypoints", "{\"name\":\"Дом\",\"world\":\"minecraft:overworld\",\"x\":100,\"y\":70,\"z\":-50}", true);
        assertEquals(200, added.statusCode(), added.body());
        String id = json(added).getAsJsonObject("waypoint").get("id").getAsString();
        assertEquals(200, send("POST", bigzet + "/teleport", "{\"waypoint\":\"" + id + "\"}", true).statusCode());
        JsonObject at = json(send("GET", bigzet + "/position", null, false));
        assertEquals(100, at.get("x").getAsDouble(), 1e-9);
        assertTrue(at.has("respawn"));
        assertEquals(200, send("POST", bigzet + "/teleport", "{\"toPlayer\":\"" + DevStubServer.TEST2 + "\"}", true).statusCode());
        assertEquals(400, send("POST", bigzet + "/teleport", "{\"to\":\"moon\"}", true).statusCode());
        assertEquals(409, send("POST", "/api/players/51a7640e-4899-4753-b8d2-90b0c715341b/teleport", "{\"to\":\"spawn\"}", true).statusCode(), "офлайн");
        String history = send("GET", "/api/history?kind=command&limit=10", null, false).body();
        assertTrue(history.contains("к точке «Дом»"), history);
        assertFalse(history.contains("100.0") || history.contains("-50.0"), "координаты не в истории");
        assertEquals(200, send("DELETE", "/api/waypoints/" + id, null, true).statusCode());
        assertEquals(404, send("DELETE", "/api/waypoints/" + id, null, true).statusCode());
    }

    @Test void mapTilesTracksAndMarkers() throws Exception {
        stub.seedMap(64);
        login("correct horse");
        String tile = "/api/map/tiles/minecraft%3Aoverworld/0/0/0.png";
        HttpResponse<byte[]> png = client.send(HttpRequest.newBuilder(URI.create(base + tile)).header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, png.statusCode());
        assertEquals("image/png", png.headers().firstValue("Content-Type").orElse(""));
        assertEquals("max-age=60", png.headers().firstValue("Cache-Control").orElse(""));
        String etag = png.headers().firstValue("ETag").orElseThrow();
        assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png.body())));
        HttpResponse<byte[]> same = client.send(HttpRequest.newBuilder(URI.create(base + tile)).header("Cookie", cookie).header("If-None-Match", etag).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(304, same.statusCode());
        assertEquals(204, send("GET", "/api/map/tiles/minecraft%3Aoverworld/0/90/90.png", null, false).statusCode(), "неисследованный тайл");
        assertEquals(400, send("GET", "/api/map/tiles/minecraft%3Aoverworld/9/0/0.png", null, false).statusCode());
        assertEquals(404, send("GET", "/api/map/tiles/minecraft%3Aoverworld/0/0/0.jpg", null, false).statusCode());
        JsonObject info = json(send("GET", "/api/map/info?world=minecraft:overworld", null, false));
        assertEquals(-256, info.get("minX").getAsInt(), "границы — по целым тайлам");
        assertEquals(0, info.getAsJsonObject("spawn").get("x").getAsInt());
        assertTrue(info.getAsJsonArray("worlds").toString().contains("the_nether"));
        send("POST", "/api/waypoints", "{\"name\":\"Шахта\",\"world\":\"minecraft:overworld\",\"x\":10,\"y\":20,\"z\":30}", true);
        String markers = send("GET", "/api/map/markers?world=minecraft:overworld", null, false).body();
        assertTrue(markers.contains("\"kind\":\"village\"") && markers.contains("Шахта"), markers);
        JsonObject tracks = json(send("GET", "/api/players/tracks?from=0&points=100", null, false));
        assertEquals(2, tracks.getAsJsonArray("tracks").size());
        assertTrue(tracks.getAsJsonArray("tracks").get(0).getAsJsonObject().getAsJsonArray("points").size() <= 101);
        String history = send("GET", "/api/history?limit=500", null, false).body();
        assertFalse(history.contains("\"x\":"), "координаты путей не в истории");
    }

    @Test void plainHttpIsRefusedOnTheHttpsPort() {
        HttpClient plain = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        assertThrows(IOException.class, () -> plain.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/session"))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString()));
    }

    @Test void untrustedClientsSeeTheSelfSignedCertificate() {
        HttpClient strict = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        assertThrows(SSLHandshakeException.class, () -> strict.send(HttpRequest.newBuilder(URI.create(base + "/api/session")).build(), HttpResponse.BodyHandlers.ofString()));
    }

    @Test void noPasswordNoAdmin() {
        assertThrows(IllegalStateException.class, () -> new AdminAuth("", System::currentTimeMillis));
        assertThrows(IllegalStateException.class, () -> new AdminAuth(null, System::currentTimeMillis));
    }

    @Test void passwordHashVerifies() {
        String hash = AdminAuth.hash("пароль-123");
        assertTrue(AdminAuth.verify("пароль-123", hash));
        assertFalse(AdminAuth.verify("пароль-124", hash));
        assertFalse(hash.contains("пароль"));
        assertEquals(16, AdminAuth.generatePassword().length());
    }

    @Test void adminWebGeneratesAPasswordOnceAndKeepsOnlyTheHash() throws Exception {
        Path game = dir.resolve("game");
        List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
        Files.createDirectories(game.resolve("world"));
        Files.createDirectories(game.resolve("config"));
        Files.writeString(game.resolve("config").resolve("mobtalk-admin.json"), "{\"port\":0}");
        AdminWeb web = AdminWeb.start(game, new AdminWeb.Hooks(stub.game, new Backups.Saver() {
            @Override public void pauseAndFlush() { }
            @Override public void resume() { }
        }, game.resolve("world"), stub.config::get, stub.config::updateAndGet, MobTalkAdminSource::personal, JsonObject::new), log::add);
        assertNotNull(web, String.join("\n", log));
        try {
            String announced = log.stream().filter(line -> line.contains("создан пароль")).findFirst().orElseThrow();
            String password = announced.replaceAll(".*создан пароль (\\S+).*", "$1");
            String saved = Files.readString(game.resolve("config").resolve("mobtalk-admin.json"));
            assertFalse(saved.contains(password), "пароль не хранится открытым");
            assertTrue(saved.contains("pbkdf2$"));
            assertTrue(Files.isRegularFile(game.resolve("config").resolve("mobtalk-admin.p12")));
        } finally { web.stop(); }
    }
}
