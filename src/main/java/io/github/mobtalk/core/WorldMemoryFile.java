package io.github.mobtalk.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** The world memory on disk: {@code data/mobtalk/conversations.json}. Reads with migration and sanitation, writes atomically
 * from one daemon thread. The store hands over a consistent copy of its state under its own lock; this class never reads the
 * live state. */
final class WorldMemoryFile {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final Supplier<ConversationStore.State> snapshot;
    private final ExecutorService writer;
    private boolean dirty, writing;
    private IOException saveFailure;

    /** What a read produced: the sanitized state (null when the file does not exist) and whether it was upgraded. */
    record Loaded(ConversationStore.State state, boolean migrated) { }

    static Path of(Path worldDirectory) { return worldDirectory.resolve("data/mobtalk/conversations.json"); }

    WorldMemoryFile(Path file, Supplier<ConversationStore.State> snapshot) {
        this.file = file; this.snapshot = snapshot;
        writer = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "mobtalk-world-memory"); thread.setDaemon(true); return thread; });
    }

    /** Reads and sanitizes the file; an unreadable or alien file fails loudly rather than being overwritten later. */
    static Loaded read(Path file) throws IOException {
        if (!Files.exists(file)) return new Loaded(null, false);
        try {
            String savedJson = Files.readString(file, StandardCharsets.UTF_8);
            ConversationStore.State state = JSON.fromJson(savedJson, ConversationStore.State.class);
            if (state == null || state.profiles == null || state.version > 2) throw new IllegalArgumentException();
            if (!JsonParser.parseString(savedJson).getAsJsonObject().has("bargains")) backup(file, "conversations.before-bargaining.backup.json");
            boolean migrated = state.version < 2;
            state.sanitize();
            for (var entry : state.profiles.entrySet()) {
                ConversationStore.Profile p = entry.getValue();
                if (p == null || p.type == null || p.personality == null || p.voiceId == null) throw new IllegalArgumentException();
                p.id = UUID.fromString(entry.getKey());
                p.sanitize();
                if (migrated) p.variantSlot = -1;
            }
            if (migrated) { backup(file, "conversations.v1.backup.json"); state.version = 2; }
            return new Loaded(state, migrated);
        } catch (IOException | RuntimeException failure) { throw new IOException("Не удалось прочитать память Mob Talk в папке мира."); }
    }
    /** A copy kept once beside the file before a format change rewrites it. */
    private static void backup(Path file, String name) throws IOException {
        Path backup = file.resolveSibling(name);
        if (!Files.exists(backup)) Files.copy(file, backup);
    }

    /** Queues a write; several changes in a row collapse into one. */
    synchronized void markDirty() { dirty = true; if (!writing) { writing = true; writer.execute(this::writeLoop); } }

    /** Blocking writer barrier for shutdown/tests, never the game thread. */
    void flush() throws IOException {
        Future<?> barrier;
        synchronized (this) { if (dirty && !writing) markDirty(); barrier = writer.submit(() -> { }); }
        try { barrier.get(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Сохранение памяти Mob Talk прервано."); }
        catch (ExecutionException failure) { throw new IOException("Не удалось сохранить память Mob Talk."); }
        synchronized (this) { if (saveFailure != null) throw saveFailure; }
    }
    void shutdown() { writer.shutdown(); }

    private void writeLoop() {
        while (true) {
            synchronized (this) {
                if (!dirty) { writing = false; return; }
                dirty = false;
            }
            try { write(snapshot.get()); synchronized (this) { saveFailure = null; } }
            catch (IOException failure) { synchronized (this) { saveFailure = failure; dirty = true; writing = false; } return; }
        }
    }
    private void write(ConversationStore.State state) throws IOException {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent()); temporary = Files.createTempFile(file.getParent(), "conversations-", ".tmp");
            Files.writeString(temporary, JSON.toJson(state), StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException failure) { throw new IOException("Не удалось сохранить память Mob Talk в папке мира."); }
        finally { if (temporary != null) Files.deleteIfExists(temporary); }
    }
}
