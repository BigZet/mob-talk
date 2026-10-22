package io.github.mobtalk.server.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The 3D map: files only for a signed-in admin, its own CSP and framing, gzip tiles, and rendering only while the page looks. */
class BlueMapControlTest {
    @TempDir Path dir;
    private final AtomicLong clock = new AtomicLong(1_792_000_000_000L);
    private DevStubServer.Stub stub;
    private AdminAuth auth;
    private AdminHttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void start() throws Exception {
        stub = DevStubServer.create(dir, clock::get, new Random(4));
        auth = new AdminAuth(AdminAuth.hash("пароль-пароль"), clock::get);
        server = new AdminHttpServer(new AdminHttpServer.Options("127.0.0.1", 0, null, false), auth, stub.api.router(), path -> null, System.err::println);
        server.mount("/map3d/", BlueMapControl.CSP, stub.api.map3dFiles());
        server.start();
    }

    @AfterEach void stop() { server.stop(); stub.api.close(); stub.history.close(); stub.metrics.close(); }

    private HttpResponse<byte[]> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path));
        if (token != null) request.header("Cookie", AdminHttpServer.COOKIE + "=" + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test void filesNeedASessionAndMayBeFramedByTheAdmin() throws Exception {
        assertEquals(401, get("/map3d/", null).statusCode());
        String token = auth.open("admin", AdminAuth.Level.ADMIN).token();
        HttpResponse<byte[]> index = get("/map3d/", token);
        assertEquals(200, index.statusCode());
        assertTrue(new String(index.body(), StandardCharsets.UTF_8).contains("BlueMap"));
        assertEquals("SAMEORIGIN", index.headers().firstValue("X-Frame-Options").orElse(""));
        String csp = index.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(csp.contains("frame-ancestors 'self'") && csp.contains("worker-src 'self' blob:"), csp);
        HttpResponse<byte[]> admin = get("/api/session", token);
        assertEquals("DENY", admin.headers().firstValue("X-Frame-Options").orElse(""), "остальная админка не встраивается");
        assertFalse(admin.headers().firstValue("Content-Security-Policy").orElse("").contains("unsafe-eval"));
    }

    @Test void gzipTilesMissingTilesAndLivePlayers() throws Exception {
        String token = auth.open("admin", AdminAuth.Level.ADMIN).token();
        HttpResponse<byte[]> tile = get("/map3d/maps/world/tiles/0/x0/z0.prbm", token);
        assertEquals(200, tile.statusCode());
        assertEquals("gzip", tile.headers().firstValue("Content-Encoding").orElse(""));
        assertEquals(204, get("/map3d/maps/world/tiles/0/x5/z5.prbm", token).statusCode(), "неотрисованный тайл");
        assertEquals(404, get("/map3d/../../secret.txt", token).statusCode());
        JsonObject live = JsonParser.parseString(new String(get("/map3d/maps/world/live/players.json", token).body(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(2, live.getAsJsonArray("players").size());
        assertFalse(live.getAsJsonArray("players").get(0).getAsJsonObject().get("foreign").getAsBoolean());
    }

    @Test void rendersOnlyWhileSomeoneLooks() {
        BlueMapControl control = new BlueMapControl(dir.resolve("bluemap").resolve("web"), dir.resolve("latest.log"), stub.game, clock::get);
        control.sweep();
        assertEquals("bluemap stop", last(), "при старте админки рендер на паузе");
        assertFalse(control.status().get("rendering").getAsBoolean());
        assertTrue(control.keepalive().get("rendering").getAsBoolean());
        assertEquals("bluemap start", last());
        int commands = stub.game.commands.size();
        clock.addAndGet(60_000);
        control.keepalive();
        control.sweep();
        assertEquals(commands, stub.game.commands.size(), "смотрят — ни повторного start, ни stop");
        clock.addAndGet(BlueMapControl.IDLE + 1);
        control.sweep();
        assertEquals("bluemap stop", last(), "три минуты без keepalive — пауза");
        clock.addAndGet(20_000);
        JsonObject status = control.status();
        assertTrue(status.get("available").getAsBoolean());
        assertEquals(0.425, status.get("progress").getAsDouble(), 1e-9);
    }

    @Test void withoutBlueMap() {
        BlueMapControl absent = new BlueMapControl(dir.resolve("nothing"), dir.resolve("latest.log"), stub.game, clock::get);
        assertFalse(absent.status().get("available").getAsBoolean());
        assertEquals(503, assertThrows(ApiException.class, absent::keepalive).status);
        absent.sweep();
        assertFalse(stub.game.commands.contains("bluemap stop"));
    }

    @Test void waitsForBlueMapToLoad() throws Exception {
        Path log = dir.resolve("starting.log");
        java.nio.file.Files.writeString(log, "[19:30:01] [main/INFO] (FabricLoader) Loading 14 mods\n");
        BlueMapControl control = new BlueMapControl(dir.resolve("bluemap").resolve("web"), log, stub.game, clock::get);
        control.sweep();
        JsonObject early = control.keepalive();
        assertTrue(early.get("loading").getAsBoolean());
        assertFalse(stub.game.commands.stream().anyMatch(c -> c.startsWith("bluemap")), "до «BlueMap loaded!» команд нет");
        assertTrue(control.status().get("loading").getAsBoolean());
        java.nio.file.Files.writeString(log, "[19:30:09] [BlueMap-Plugin-Loading/INFO] (bluemap) BlueMap loaded!\n", java.nio.file.StandardOpenOption.APPEND);
        control.sweep();
        assertEquals("bluemap start", last(), "страницу уже смотрят — после загрузки сразу рендер");
        clock.addAndGet(BlueMapControl.IDLE + 1);
        control.sweep();
        assertEquals("bluemap stop", last());
    }

    private String last() { return stub.game.commands.get(stub.game.commands.size() - 1); }
}
