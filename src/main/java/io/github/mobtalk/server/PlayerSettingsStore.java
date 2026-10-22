package io.github.mobtalk.server;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mobtalk.core.PlayerSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Личный слой настроек по UUID игрока (раздел 12.2 плана): в памяти и в {@code data/mobtalk/players.json} мира. Клиент шлёт
 * {@code PlayerSettingsC2S} при входе и при изменении; запись на диск идёт на своём потоке, не на потоке сервера. */
public final class PlayerSettingsStore {
    private PlayerSettingsStore() { }
    public static final String FILE = "players.json";
    private static final Map<UUID, PlayerSettings> players = new ConcurrentHashMap<>();
    private static volatile Path file;
    private static final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mobtalk-player-settings"); thread.setDaemon(true); return thread;
    });

    /** При старте мира: читает {@code <мир>/data/mobtalk/players.json}; повреждённый файл даёт пустое хранилище и предупреждение. */
    private static final Object lock = new Object();

    public static void open(Path worldDirectory) {
        players.clear();
        file = worldDirectory.resolve("data").resolve("mobtalk").resolve(FILE);
        if (!Files.exists(file)) return;
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            if (!parsed.isJsonObject()) return;
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                try { players.put(UUID.fromString(entry.getKey()), PlayerSettings.fromJson(entry.getValue().toString())); }
                catch (IllegalArgumentException skip) { /* не UUID — пропускаем */ }
            }
        } catch (IOException | RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger("mobtalk-server").warn("Не удалось прочитать {}: {}", FILE, failure.toString());
        }
    }

    /** При остановке мира: дописать и забыть. */
    public static void close() { flush(); players.clear(); file = null; }

    /** Настройки игрока; по умолчанию, если он ничего не присылал. */
    public static PlayerSettings of(UUID player) { return players.getOrDefault(player, PlayerSettings.defaults()); }
    /** Настройки, если игрок их присылал; пусто — нет (тогда имена и пол идут по общим настройкам, как в одиночке). */
    public static java.util.Optional<PlayerSettings> find(UUID player) { return player == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(players.get(player)); }
    public static void put(UUID player, PlayerSettings settings) {
        players.put(player, settings == null ? PlayerSettings.defaults() : settings);
        writer.execute(PlayerSettingsStore::save);
    }
    public static void remove(UUID player) { players.remove(player); writer.execute(PlayerSettingsStore::save); }
    public static int size() { return players.size(); }

    /** Дождаться записи на диск (остановка мира, тесты). */
    public static void flush() {
        try { writer.submit(PlayerSettingsStore::save).get(5, TimeUnit.SECONDS); }
        catch (Exception failure) { org.slf4j.LoggerFactory.getLogger("mobtalk-server").warn("Не удалось дописать {}: {}", FILE, failure.toString()); }
    }

    private static void save() { synchronized (lock) { write(); } }
    private static void write() {
        Path target = file;
        if (target == null) return;
        JsonObject tree = new JsonObject();
        for (Map.Entry<UUID, PlayerSettings> entry : players.entrySet())
            tree.add(entry.getKey().toString(), JsonParser.parseString(entry.getValue().toJson()));
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), FILE + ".", ".tmp");
            try {
                Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(tree));
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException failure) {
            org.slf4j.LoggerFactory.getLogger("mobtalk-server").warn("Не удалось записать {}: {}", FILE, failure.toString());
        }
    }
}
