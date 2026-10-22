package io.github.mobtalk.server;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.SettingsScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Серверный слой настроек (раздел 12.2 плана): неизменяемый снимок {@link MobTalkConfig} только с серверными полями
 * ({@link SettingsScope}), стример и админы. На выделенном сервере читается из {@code config/mobtalk-server.json}; если файла нет,
 * серверные поля берутся из {@code config/mobtalk.json} одиночки и файл создаётся. В одиночке встроенный сервер файла не ведёт:
 * клиент отдаёт ему серверные поля своего {@code mobtalk.json} через {@link #adopt}. Снимок копируется при каждой записи, поэтому
 * тот, кто взял {@link #get()}, видит прежние значения, пока не возьмёт снова; слушатели зовутся после каждой записи. */
public final class ServerConfig {
    private ServerConfig() { }
    public static final String FILE = "mobtalk-server.json";
    private static final String SINGLEPLAYER_FILE = "mobtalk.json";
    private static final String KEY_STREAMER = "streamer", KEY_ADMINS = "admins";

    private static volatile MobTalkConfig snapshot = SettingsScope.serverOnly(new MobTalkConfig());
    private static volatile UUID streamer;
    private static volatile Set<UUID> admins = Set.of();
    private static Path file;
    private static final List<Consumer<MobTalkConfig>> listeners = new CopyOnWriteArrayList<>();

    /** Выделенный сервер: читает или создаёт {@code configDir/mobtalk-server.json}. Диск; звать при старте, до мира. */
    public static synchronized void init(Path configDir) throws IOException {
        file = configDir.resolve(FILE);
        MobTalkConfig loaded;
        UUID loadedStreamer = null;
        Set<UUID> loadedAdmins = Set.of();
        if (Files.exists(file)) {
            loaded = MobTalkConfig.load(file);
            JsonObject extra = extras(file);
            loadedStreamer = uuid(extra.get(KEY_STREAMER));
            loadedAdmins = uuids(extra.get(KEY_ADMINS));
        } else {
            Path single = configDir.resolve(SINGLEPLAYER_FILE);
            loaded = Files.exists(single) ? MobTalkConfig.load(single) : new MobTalkConfig();
        }
        snapshot = SettingsScope.serverOnly(loaded);
        streamer = loadedStreamer;
        admins = Collections.unmodifiableSet(new LinkedHashSet<>(loadedAdmins));
        if (!Files.exists(file)) save();
        notifyListeners();
    }

    /** Одиночка: встроенный сервер живёт по серверным полям настроек клиента, без своего файла. */
    public static synchronized void adopt(MobTalkConfig clientConfig) {
        file = null;
        snapshot = SettingsScope.serverOnly(clientConfig == null ? new MobTalkConfig() : clientConfig);
        notifyListeners();
    }

    /** Неизменяемый снимок: правки в нём никуда не попадают, менять через {@link #update}. */
    public static MobTalkConfig get() { return snapshot; }
    /** Идёт ли запись в файл (выделенный сервер) или снимок живёт в памяти (одиночка). */
    public static boolean persistent() { return file != null; }

    /** Применить правку к копии, отбросить личные поля, сохранить JSON (на выделенном сервере) и позвать слушателей. */
    public static synchronized void update(UnaryOperator<MobTalkConfig> change) {
        MobTalkConfig draft = change.apply(snapshot.copy());
        snapshot = SettingsScope.serverOnly(draft == null ? snapshot : draft);
        saveQuietly();
        notifyListeners();
    }

    /** Слушатель получает новый снимок после каждой записи; поток — тот, кто писал. */
    public static void listen(Consumer<MobTalkConfig> listener) { listeners.add(listener); }

    public static UUID streamer() { return streamer; }
    public static Set<UUID> admins() { return admins; }
    public static synchronized void setStreamer(UUID player) { streamer = player; saveQuietly(); notifyListeners(); }
    public static synchronized void setAdmins(Set<UUID> players) {
        admins = Collections.unmodifiableSet(new LinkedHashSet<>(players == null ? Set.of() : players));
        saveQuietly(); notifyListeners();
    }

    private static void notifyListeners() {
        MobTalkConfig current = snapshot;
        for (Consumer<MobTalkConfig> listener : listeners) {
            try { listener.accept(current); }
            catch (RuntimeException failure) { org.slf4j.LoggerFactory.getLogger("mobtalk-server").warn("Слушатель настроек упал: {}", failure.toString()); }
        }
    }

    private static void saveQuietly() {
        try { save(); }
        catch (IOException failure) { org.slf4j.LoggerFactory.getLogger("mobtalk-server").warn("Не удалось записать {}: {}", FILE, failure.toString()); }
    }

    /** Файл: серверные поля как в {@code mobtalk.json}, плюс {@code streamer} и {@code admins}. Личные поля не пишутся. */
    private static void save() throws IOException {
        Path target = file;
        if (target == null) return;
        JsonObject tree = new GsonBuilder().create().toJsonTree(snapshot).getAsJsonObject();
        for (String personal : SettingsScope.personalFields()) tree.remove(personal);
        tree.addProperty(KEY_STREAMER, streamer == null ? "" : streamer.toString());
        JsonArray list = new JsonArray();
        for (UUID admin : admins) list.add(admin.toString());
        tree.add(KEY_ADMINS, list);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(tree);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), FILE + ".", ".tmp");
        try {
            Files.writeString(temporary, json);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static JsonObject extras(Path path) {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (IOException | RuntimeException failure) { return new JsonObject(); }
    }
    private static UUID uuid(JsonElement element) {
        try { return element == null || !element.isJsonPrimitive() || element.getAsString().isBlank() ? null : UUID.fromString(element.getAsString()); }
        catch (IllegalArgumentException invalid) { return null; }
    }
    private static Set<UUID> uuids(JsonElement element) {
        Set<UUID> result = new LinkedHashSet<>();
        if (element != null && element.isJsonArray()) for (JsonElement item : element.getAsJsonArray()) { UUID id = uuid(item); if (id != null) result.add(id); }
        return result;
    }

    /** Для тестов: забыть файл, слушателей и вернуть значения по умолчанию. */
    static synchronized void resetForTests() {
        file = null; streamer = null; admins = Set.of(); listeners.clear();
        snapshot = SettingsScope.serverOnly(new MobTalkConfig());
    }
}
