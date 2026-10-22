package io.github.mobtalk.server.web;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Keys, tokens, passwords and coordinates reach neither a history record nor an API answer. */
class RedactTest {
    @TempDir Path dir;

    static final String OPENROUTER = "sk-or-v1-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final List<String> SECRETS = List.of(OPENROUTER, "sk-proj-AbCdEfGhIjKlMnOpQrStUv", "oauth:abcdefghij0123456789",
            "Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig", "hunter2-password");

    @Test void textLosesSecretsAndPlaces() {
        String line = "ключ " + OPENROUTER + ", заголовок Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig, password=hunter2-password, "
                + "токен twitch oauth:abcdefghij0123456789, игрок на x=120 y=64 z=-340, дом [1203, 70, -88], BlockPos{x=5, y=6, z=7}, координаты 100 64 200.";
        String clean = Redact.text(line);
        for (String secret : SECRETS) assertFalse(clean.contains(secret), secret + " в " + clean);
        for (String number : List.of("120", "-340", "1203", "-88", "100 64 200")) assertFalse(clean.contains(number), number + " в " + clean);
        assertTrue(clean.contains(Redact.HIDDEN));
        assertTrue(clean.contains(Redact.PLACE));
    }

    @Test void ordinaryTextStays() {
        for (String plain : List.of("BigZet → Тереза: «где тут кузнец?» — «За колодцем»", "Длина ответа: 45 → 60", "22.10.2026 19:35",
                "UUID 461baa90-bc98-4f86-b889-50f67d759b9a", "дай 3 алмаза"))
            assertEquals(plain, Redact.text(plain));
    }

    @Test void secretFieldsAreHiddenInTrees() {
        JsonObject tree = Json.obj("password", "x", "apiKey", "y", "pos", Json.obj("x", 1, "y", 2, "z", 3), "seed", 12345, "key", "maxReplyWords",
                "nested", List.of(Json.obj("token", "t", "text", "ok")));
        JsonObject clean = Redact.object(tree);
        assertEquals(Redact.HIDDEN, clean.get("password").getAsString());
        assertEquals(Redact.HIDDEN, clean.get("apiKey").getAsString());
        assertEquals(Redact.HIDDEN, clean.get("pos").getAsString());
        assertEquals(Redact.HIDDEN, clean.get("seed").getAsString());
        assertEquals("maxReplyWords", clean.get("key").getAsString(), "имя настройки — не секрет");
        assertEquals(Redact.HIDDEN, clean.getAsJsonArray("nested").get(0).getAsJsonObject().get("token").getAsString());
    }

    @Test void historyFilesAndApiAnswersHoldNoSecrets() throws Exception {
        DevStubServer.Stub stub = DevStubServer.create(dir.resolve("data"), System::currentTimeMillis, new Random(3));
        JsonObject player = Json.player(UUID.randomUUID(), "Игрок");
        String dirty = "мой ключ " + OPENROUTER + " и пароль: hunter2-password, стою на x=10 y=70 z=20";
        stub.history.append("dialogue", player, null, dirty, Json.obj("question", dirty, "answer", "ок", "prompt", dirty, "coords", "1 2 3"));
        AdminJournal.Run run = stub.journal.dialogueStarted(player, null, dirty);
        run.step("prompt").finish("ответ " + OPENROUTER, List.of(), 0.001, 1, 1, "m", "text", dirty, dirty);
        try { throw new IllegalStateException("ключ " + OPENROUTER); }
        catch (IllegalStateException failure) { stub.errors.record(failure, "тест", "x=1 y=2 z=3"); }
        stub.history.flush();
        try (var files = Files.walk(dir.resolve("data").resolve("history"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String text = Files.readString(file);
                for (String secret : SECRETS) assertFalse(text.contains(secret), file + ": " + secret);
                assertFalse(text.contains("x=10"), file.toString());
            }
        }
        String answers = Json.GSON.toJson(stub.history.search(HistoryStore.Query.all()).items()) + Json.GSON.toJson(stub.errors.groups())
                + Json.GSON.toJson(stub.journal.liveSnapshot())
                + Json.GSON.toJson(LogTail.tail(writeLog(dir.resolve("latest.log")), 50, List.of(), null));
        for (String secret : SECRETS) assertFalse(answers.contains(secret), secret);
        stub.history.close();
        stub.metrics.close();
    }

    private static Path writeLog(Path log) throws Exception {
        Files.writeString(log, "[19:30:01] [Server thread/INFO] (mobtalk) запрос с ключом " + OPENROUTER + " password=hunter2-password\n");
        return log;
    }

    @Test void journalSourceAnswersAreCleaned() {
        ConfigSources sources = new ConfigSources(new HistoryStore(dir.resolve("h"), System::currentTimeMillis, ZoneId.systemDefault(), 90, s -> { }));
        sources.add("leaky", request -> "{\"items\":[{\"text\":\"" + OPENROUTER + "\",\"player\":\"x\",\"pos\":\"1 2 3\"}]}");
        String answer = Json.GSON.toJson(sources.journal("leaky", 0, 10));
        assertFalse(answer.contains(OPENROUTER));
        assertFalse(answer.contains("1 2 3"));
    }
}
