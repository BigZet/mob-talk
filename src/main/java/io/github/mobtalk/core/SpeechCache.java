package io.github.mobtalk.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Озвучка, уже оплаченная один раз: (голос, модель, текст) → PCM. В памяти LRU на {@code capacity} клипов, на диске — папка
 * мира {@code data/mobtalk/speech-cache} (переживает перезапуск, держит до {@code capacity * 5} файлов). Смена голоса или модели —
 * другой ключ. Дисковые операции блокирующие: только с рабочего потока озвучки. */
public final class SpeechCache {
    private final int capacity;
    private final Path directory;
    private final Map<String, byte[]> memory;

    public SpeechCache(int capacity, Path directory) {
        this.capacity = Math.max(1, capacity);
        this.directory = directory;
        this.memory = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) { return size() > SpeechCache.this.capacity; }
        };
    }

    public Optional<byte[]> get(String voiceId, String model, String text) {
        String key = key(voiceId, model, text);
        synchronized (memory) {
            byte[] hit = memory.get(key);
            if (hit != null) return Optional.of(hit);
        }
        if (directory == null) return Optional.empty();
        Path file = directory.resolve(key + ".pcm");
        try {
            if (!Files.isRegularFile(file)) return Optional.empty();
            byte[] pcm = Files.readAllBytes(file);
            if (pcm.length < 2) return Optional.empty();
            synchronized (memory) { memory.put(key, pcm); }
            return Optional.of(pcm);
        } catch (IOException unreadable) { return Optional.empty(); }
    }

    public void put(String voiceId, String model, String text, byte[] pcm) {
        if (pcm == null || pcm.length < 2) return;
        String key = key(voiceId, model, text);
        synchronized (memory) { memory.put(key, pcm); }
        if (directory == null) return;
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(key + ".pcm");
            Path temporary = Files.createTempFile(directory, key, ".tmp");
            try {
                Files.write(temporary, pcm);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temporary); }
            trimDisk();
        } catch (IOException ignored) { /* The clip still serves from memory; the disk is only a bonus. */ }
    }

    public int size() { synchronized (memory) { return memory.size(); } }

    private void trimDisk() throws IOException {
        int keep = capacity * 5;
        try (var files = Files.list(directory)) {
            var all = files.filter(f -> f.getFileName().toString().endsWith(".pcm")).toList();
            if (all.size() <= keep) return;
            all.stream().sorted(java.util.Comparator.comparingLong(SpeechCache::modified)).limit(all.size() - keep).forEach(f -> {
                try { Files.deleteIfExists(f); } catch (IOException ignored) { }
            });
        }
    }

    private static long modified(Path file) {
        try { return Files.getLastModifiedTime(file).toMillis(); } catch (IOException gone) { return 0; }
    }

    static String key(String voiceId, String model, String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String joined = (voiceId == null ? "" : voiceId) + "\n" + (model == null ? "" : model) + "\n" + (text == null ? "" : text.strip());
            return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8))).substring(0, 40);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
