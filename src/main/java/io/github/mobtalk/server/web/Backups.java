package io.github.mobtalk.server.web;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A snapshot of the world into {@code backups/<time>.zip} while the server keeps running: saving is paused around the copy
 * ({@code save-off}, {@code save-all flush}, zip, {@code save-on}) and always resumed, whatever the zip did. */
public final class Backups {
    /** The server side of a backup; on a live server these run the three commands on the server thread and wait. */
    public interface Saver {
        void pauseAndFlush() throws Exception;
        void resume();
    }

    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path world, folder;
    private final Saver saver;
    private final LongSupplier clock;
    private final ZoneId zone;
    private final AtomicBoolean running = new AtomicBoolean();

    public Backups(Path world, Path folder, Saver saver, LongSupplier clock, ZoneId zone) {
        this.world = world; this.folder = folder; this.saver = saver; this.clock = clock; this.zone = zone;
    }

    public JsonObject create() throws Exception {
        if (!running.compareAndSet(false, true)) throw ApiException.conflict("Резервная копия уже создаётся.");
        try {
            if (!Files.isDirectory(world)) throw ApiException.unavailable("Папка мира не найдена.");
            Files.createDirectories(folder);
            long t = clock.getAsLong();
            String name = NAME.format(Instant.ofEpochMilli(t).atZone(zone)) + ".zip";
            Path target = folder.resolve(name), partial = folder.resolve(name + ".part");
            saver.pauseAndFlush();
            try {
                zip(world, partial);
            } finally { saver.resume(); }
            Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Json.obj("name", name, "size", Files.size(target), "t", t);
        } finally { running.set(false); }
    }

    public JsonObject list() throws IOException {
        List<JsonObject> backups = new ArrayList<>();
        if (Files.isDirectory(folder)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.zip")) {
                for (Path file : files)
                    backups.add(Json.obj("name", file.getFileName().toString(), "size", Files.size(file), "t", Files.getLastModifiedTime(file).toMillis()));
            }
        }
        backups.sort((a, b) -> Long.compare(b.get("t").getAsLong(), a.get("t").getAsLong()));
        return Json.obj("backups", backups);
    }

    /** The world folder as it is, without the lock file the running server holds. */
    static void zip(Path world, Path target) throws IOException {
        Path root = world.toAbsolutePath().normalize();
        String top = root.getFileName().toString();
        try (OutputStream file = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(file)) {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                    if (path.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE;
                    zip.putNextEntry(new ZipEntry(top + "/" + root.relativize(path).toString().replace('\\', '/')));
                    try { Files.copy(path, zip); }
                    catch (IOException locked) { /* A file the game holds open exclusively (rare on Windows): skip it, keep the rest. */ }
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path path, IOException failure) { return FileVisitResult.CONTINUE; }
            });
        }
    }
}
