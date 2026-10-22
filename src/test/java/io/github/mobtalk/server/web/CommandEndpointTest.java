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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The console: only an ADMIN session runs commands, the answer comes back, the history keeps it. Plain HTTP on loopback here. */
class CommandEndpointTest {
    @TempDir Path dir;
    private DevStubServer.Stub stub;
    private AdminAuth auth;
    private AdminHttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void start() throws Exception {
        stub = DevStubServer.create(dir, System::currentTimeMillis, new Random(2));
        auth = new AdminAuth(AdminAuth.hash("пароль-пароль"), System::currentTimeMillis);
        server = new AdminHttpServer(new AdminHttpServer.Options("127.0.0.1", 0, null, false), auth, stub.api.router(), path -> null, System.err::println);
        server.start();
    }

    @AfterEach void stop() { server.stop(); stub.history.close(); stub.metrics.close(); }

    private HttpResponse<String> command(AdminAuth.Session session, String command) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/server/command"))
                .header("Cookie", AdminHttpServer.COOKIE + "=" + session.token()).header("X-MobTalk", "1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"" + command + "\"}")).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test void onlyAdminRunsCommands() throws Exception {
        for (AdminAuth.Level level : new AdminAuth.Level[]{AdminAuth.Level.STREAMER, AdminAuth.Level.PLAYER}) {
            HttpResponse<String> refused = command(auth.open("кто-то", level), "say привет");
            assertEquals(403, refused.statusCode(), level.name());
        }
        assertTrue(stub.game.commands.isEmpty(), "команда не дошла до сервера");
    }

    @Test void adminGetsTheResultAndTheHistoryKeepsIt() throws Exception {
        HttpResponse<String> ok = command(auth.open("admin", AdminAuth.Level.ADMIN), "/say привет");
        assertEquals(200, ok.statusCode(), ok.body());
        JsonObject answer = JsonParser.parseString(ok.body()).getAsJsonObject();
        assertEquals(1, answer.get("result").getAsInt());
        assertTrue(answer.getAsJsonArray("output").get(0).getAsString().contains("say привет"));
        assertEquals("say привет", stub.game.commands.get(0), "ведущий слэш снят");
        JsonObject record = stub.history.search(new HistoryStore.Query(Set.of("command"), null, null, null, null, null, null, 5)).items().get(0);
        assertEquals("say привет", record.getAsJsonObject("data").get("command").getAsString());
        assertEquals(400, command(auth.open("admin", AdminAuth.Level.ADMIN), "  ").statusCode());
    }
}
