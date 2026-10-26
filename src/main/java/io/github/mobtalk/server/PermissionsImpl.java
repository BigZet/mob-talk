package io.github.mobtalk.server;

import io.github.mobtalk.core.MobTalkConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Три уровня прав (раздел 6 плана): ADMIN — операторы сервера, список {@code admins} в {@code mobtalk-server.json} и хозяин одиночного
 * мира; STREAMER — {@code streamer} из того же файла; остальные PLAYER. Команды модов сборки фильтруются по корню и первой подкоманде
 * ({@link #required}): таблица по умолчанию {@link #DEFAULTS}, поверх — {@code commandLevels} серверных настроек. Ванильные команды
 * не трогаем: фильтр может только убрать команду мода, но не выдать то, что мод сам требует (Viewer Parrots ждёт оп-уровень 2). */
public final class PermissionsImpl implements Permissions {
    public static final String REFUSAL = "Эта команда доступна стримеру и администратору";
    public static final String ADMIN_ONLY = "Эта команда доступна только администратору";

    /** Корни команд модов сборки (у Viewer Parrots — {@code viewerparrot} и псевдоним {@code vp}, отчёт трека F). */
    public static final Set<String> MOD_ROOTS = Set.of("mobtalk", "divineblock", "viewerparrot", "viewerparrots", "vp", "rusflora", "parrotcages", "clip");

    /** Уровни по умолчанию: ключ — корень или «корень подкоманда». Неизвестная подкоманда берёт уровень корня, неизвестный корень
     * мода из {@link #MOD_ROOTS} — ADMIN. */
    public static final Map<String, Level> DEFAULTS;
    static {
        Map<String, Level> table = new LinkedHashMap<>();
        table.put("mobtalk", Level.STREAMER);
        for (String sub : new String[]{"admin", "web", "budget"}) table.put("mobtalk " + sub, Level.ADMIN);
        table.put("divineblock", Level.ADMIN);
        table.put("clip", Level.STREAMER); // Метки для монтажа; игрокам открывает commandLevels «clip»: «PLAYER».
        for (String sub : new String[]{"act", "boss", "unban", "ult", "clear", "lift", "cemetery", "streamer"}) table.put("divineblock " + sub, Level.ADMIN);
        for (String sub : new String[]{"list", "curses"}) table.put("divineblock " + sub, Level.STREAMER);
        // Viewer Parrots сам пускает только оп-уровень ≥ 2, так что стримеру без опа фильтр /vp не откроет (трек F): только ADMIN.
        for (String root : new String[]{"viewerparrot", "viewerparrots", "vp", "rusflora", "parrotcages"}) table.put(root, Level.ADMIN);
        // Тюрьма для попугаев-зрителей (Parrot Cages): сажает и продлевает срок стример, /vp uncage — администратор.
        for (String root : new String[]{"viewerparrot", "viewerparrots", "vp"}) table.put(root + " cage", Level.STREAMER);
        table.put("parrotcages jail", Level.STREAMER);
        DEFAULTS = java.util.Collections.unmodifiableMap(table);
    }

    private final Supplier<MobTalkConfig> config;
    private final Supplier<UUID> streamer;
    private final Supplier<Set<UUID>> admins;

    public PermissionsImpl() { this(ServerConfig::get, ServerConfig::streamer, ServerConfig::admins); }
    public PermissionsImpl(Supplier<MobTalkConfig> config, Supplier<UUID> streamer, Supplier<Set<UUID>> admins) {
        this.config = config; this.streamer = streamer; this.admins = admins;
    }

    @Override public Level level(ServerPlayerEntity player) {
        if (player == null) return Level.PLAYER;
        MinecraftServer server = player.getServer();
        boolean operator = server != null && (server.getPlayerManager().isOperator(player.getGameProfile()) || server.isHost(player.getGameProfile()));
        return level(player.getUuid(), operator);
    }

    /** Уровень по UUID и признаку оператора (хозяина одиночки) — без Minecraft, для тестов и команд. */
    public Level level(UUID player, boolean operator) {
        if (operator) return Level.ADMIN;
        if (player == null) return Level.PLAYER;
        Set<UUID> list = admins.get();
        if (list != null && list.contains(player)) return Level.ADMIN;
        return player.equals(streamer.get()) ? Level.STREAMER : Level.PLAYER;
    }

    /** Чей это корень: команда мода сборки или ванильная/чужая (null). */
    public static boolean modRoot(String root) { return root != null && MOD_ROOTS.contains(root.toLowerCase(Locale.ROOT)); }

    /** Какой уровень нужен для команды {@code root} с первой подкомандой {@code sub} (может быть null); null — команда не мода,
     * фильтр её не трогает. */
    public Level required(String root, String sub) {
        if (!modRoot(root)) return null;
        String base = root.toLowerCase(Locale.ROOT);
        Map<String, String> custom = config.get() == null ? Map.of() : config.get().commandLevels;
        if (sub != null && !sub.isBlank()) {
            String key = base + " " + sub.toLowerCase(Locale.ROOT);
            Level level = parse(custom.get(key));
            if (level == null) level = DEFAULTS.get(key);
            if (level != null) return level;
        }
        Level level = parse(custom.get(base));
        if (level == null) level = DEFAULTS.get(base);
        return level == null ? Level.ADMIN : level;
    }

    /** Может ли игрок этого уровня выполнить команду; ванильные — всегда. */
    public boolean allows(Level level, String root, String sub) {
        Level needed = required(root, sub);
        return needed == null || level != null && level.ordinal() >= needed.ordinal();
    }

    @Override public boolean mayRun(ServerCommandSource source, String commandRoot) { return mayRun(source, commandRoot, null); }

    /** Консоль сервера и командные блоки без игрока — как ADMIN, если у источника есть оп-уровень 2. */
    public boolean mayRun(ServerCommandSource source, String root, String sub) {
        if (!modRoot(root)) return true;
        ServerPlayerEntity player = source.getPlayer();
        Level level = player == null ? (source.hasPermissionLevel(2) ? Level.ADMIN : Level.PLAYER) : level(player);
        return allows(level, root, sub);
    }

    /** Текст отказа для игрока: «только администратору», если и стримеру нельзя. */
    public String refusal(String root, String sub) { return required(root, sub) == Level.ADMIN ? ADMIN_ONLY : REFUSAL; }

    static Level parse(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Level.valueOf(value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException unknown) { return null; }
    }
}
