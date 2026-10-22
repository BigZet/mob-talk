package io.github.mobtalk.server.web;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** A backup holds the world, leaves out the lock, and always turns saving back on; a second one waits its turn. */
class BackupTest {
    @TempDir Path dir;

    private Path world() throws Exception {
        Path world = dir.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("level.dat"), "уровень");
        Files.write(world.resolve("region").resolve("r.0.0.mca"), new byte[10_000]);
        Files.writeString(world.resolve("session.lock"), "☃");
        return world;
    }

    @Test void zipHoldsTheWorldAndSavingResumes() throws Exception {
        List<String> calls = new ArrayList<>();
        Backups backups = new Backups(world(), dir.resolve("backups"), new Backups.Saver() {
            @Override public void pauseAndFlush() { calls.add("save-off"); calls.add("save-all flush"); }
            @Override public void resume() { calls.add("save-on"); }
        }, () -> 1_792_000_000_000L, ZoneId.of("UTC"));
        JsonObject backup = backups.create();
        assertEquals(List.of("save-off", "save-all flush", "save-on"), calls);
        Path zip = dir.resolve("backups").resolve(backup.get("name").getAsString());
        assertTrue(backup.get("name").getAsString().matches("\\d{8}-\\d{6}\\.zip"));
        try (ZipFile file = new ZipFile(zip.toFile())) {
            assertNotNull(file.getEntry("world/level.dat"));
            assertEquals(10_000, file.getEntry("world/region/r.0.0.mca").getSize());
            assertNull(file.getEntry("world/session.lock"));
        }
        assertEquals(1, backups.list().getAsJsonArray("backups").size());
        try (var files = Files.list(dir.resolve("backups"))) { assertTrue(files.noneMatch(f -> f.toString().endsWith(".part"))); }
    }

    @Test void savingResumesEvenWhenTheCopyFails() throws Exception {
        List<String> calls = new ArrayList<>();
        Path world = world();
        Backups backups = new Backups(world, dir.resolve("backups"), new Backups.Saver() {
            @Override public void pauseAndFlush() throws Exception {
                calls.add("off");
                Files.createDirectories(dir.resolve("backups"));
                Files.createDirectory(dir.resolve("backups").resolve("19700101-000000.zip.part")); // the zip cannot be written over a folder
            }
            @Override public void resume() { calls.add("on"); }
        }, () -> 0L, ZoneId.of("UTC"));
        assertThrows(Exception.class, backups::create);
        assertEquals(List.of("off", "on"), calls, "сервер не остаётся без сохранений");
    }

    @Test void oneAtATime() throws Exception {
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        Backups backups = new Backups(world(), dir.resolve("backups"), new Backups.Saver() {
            @Override public void pauseAndFlush() throws Exception { inside.countDown(); release.await(5, TimeUnit.SECONDS); }
            @Override public void resume() { }
        }, System::currentTimeMillis, ZoneId.of("UTC"));
        Thread first = new Thread(() -> { try { backups.create(); } catch (Exception ignored) { } });
        first.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        ApiException busy = assertThrows(ApiException.class, backups::create);
        assertEquals(409, busy.status);
        release.countDown();
        first.join(5000);
    }
}
