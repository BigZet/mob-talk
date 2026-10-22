package io.github.mobtalk.server;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.SettingsScope;
import io.github.mobtalk.core.Tunables;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Что умеет {@code /mobtalk admin} (бриф C, шаг 4), без Minecraft: каждая правка идёт через {@link ServerConfig}, поэтому её видят
 * слушатели — мост, рассылка возможностей игрокам и админка. Ники в UUID переводит слой команд ({@link AdminCommands}). Ответ —
 * строка для чата. */
public final class AdminActions {
    private AdminActions() { }

    // ---- Стример и админы. ----

    public static String streamer(UUID player, String name) {
        if (player == null) return "Игрок не найден";
        UUID before = ServerConfig.streamer();
        ServerConfig.setStreamer(player);
        return player.equals(before) ? name + " уже стример" : "Стример теперь " + name;
    }
    public static String clearStreamer() {
        if (ServerConfig.streamer() == null) return "Стример не назначен";
        ServerConfig.setStreamer(null);
        return "Стример снят";
    }
    public static String showStreamer(Function<UUID, String> names) {
        UUID streamer = ServerConfig.streamer();
        return streamer == null ? "Стример не назначен" : "Стример: " + names.apply(streamer);
    }

    public static String addAdmin(UUID player, String name) {
        if (player == null) return "Игрок не найден";
        Set<UUID> admins = new LinkedHashSet<>(ServerConfig.admins());
        if (!admins.add(player)) return name + " уже администратор";
        ServerConfig.setAdmins(admins);
        return name + " теперь администратор";
    }
    public static String removeAdmin(UUID player, String name) {
        if (player == null) return "Игрок не найден";
        Set<UUID> admins = new LinkedHashSet<>(ServerConfig.admins());
        if (!admins.remove(player)) return name + " не в списке администраторов (операторы сервера — администраторы всегда)";
        ServerConfig.setAdmins(admins);
        return name + " больше не администратор";
    }
    public static String listAdmins(Function<UUID, String> names) {
        Set<UUID> admins = ServerConfig.admins();
        if (admins.isEmpty()) return "Администраторы: только операторы сервера";
        return "Администраторы: операторы сервера и " + String.join(", ", admins.stream().map(names).toList());
    }

    // ---- Бюджет. ----

    /** {@code who} — UUID строкой или {@code all}; {@code kind} — вид работы по-русски или по-английски. */
    public static String budgetSet(PlayerBudget budget, String who, String whoName, String kind, int perMinute, double dailyUsd) {
        if (!(budget instanceof PlayerBudgetImpl impl)) return "Учёт расходов не подключён";
        PlayerBudget.Kind parsed = PlayerBudgetImpl.kind(kind);
        if (parsed == null) return "Нет такого вида работы: " + kind + " (диалог, распознавание, озвучка, очевидцы, болтовня)";
        if (perMinute < 0 || dailyUsd < 0 || !Double.isFinite(dailyUsd)) return "Лимиты не бывают отрицательными (0 — без ограничения)";
        if (perMinute == 0 && dailyUsd == 0) {
            boolean removed = impl.clearLimit(who, parsed);
            return removed ? "Правило снято: " + whoName + ", " + PlayerBudgetImpl.kindName(parsed) : "Без ограничения: " + whoName + ", " + PlayerBudgetImpl.kindName(parsed);
        }
        impl.setLimit(who, parsed, perMinute, dailyUsd);
        return "Лимит: " + whoName + ", " + PlayerBudgetImpl.kindName(parsed) + " — " + (perMinute > 0 ? perMinute + " в минуту" : "без лимита в минуту")
                + ", " + (dailyUsd > 0 ? PlayerBudgetImpl.money(dailyUsd) + " в день" : "без лимита в день");
    }

    // ---- Настройки сервера. ----

    public static String model(String id) {
        String model = id == null ? "" : id.strip();
        if (model.isEmpty() || model.contains(" ")) return "Нужен идентификатор модели, например openai/gpt-6-luna";
        ServerConfig.update(config -> { if (config.usesVenice()) config.veniceModel = model; else config.llmModel = model; return config; });
        return "Модель диалога: " + model;
    }

    public static String feature(String name, boolean on) {
        Field field = field(name);
        if (field == null || field.getType() != boolean.class) return "Нет такой функции: " + name;
        if (SettingsScope.personal(field.getName())) return name + " — личная настройка игрока, её меняют в F7";
        return set(field.getName(), on ? "true" : "false");
    }

    /** Одно серверное поле строкой: числа зажимаются в границы таблицы {@link Tunables}, личные поля отвергаются. */
    public static String set(String name, String value) {
        Field field = field(name);
        if (field == null) return "Нет такой настройки: " + name;
        if (SettingsScope.personal(field.getName())) return field.getName() + " — личная настройка игрока, её меняют в F7";
        Class<?> type = field.getType();
        if (type != boolean.class && type != int.class && type != double.class && type != String.class)
            return field.getName() + " — составная настройка, её меняют в админке";
        String text = value == null ? "" : value.strip();
        Object parsed;
        try { parsed = parse(type, text); }
        catch (IllegalArgumentException bad) { return "Не понял значение «" + text + "» для " + field.getName() + ": " + bad.getMessage(); }
        Object chosen = parsed;
        String[] applied = new String[1];
        ServerConfig.update(config -> {
            try {
                Tunables.Tunable tunable = tunable(field.getName());
                if (tunable != null) tunable.set(config, ((Number) chosen).doubleValue());
                else field.set(config, chosen);
                applied[0] = String.valueOf(field.get(config));
            } catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
            return config;
        });
        return field.getName() + " = " + applied[0];
    }

    public static String get(String name) {
        Field field = field(name);
        if (field == null) return "Нет такой настройки: " + name;
        if (SettingsScope.personal(field.getName())) return field.getName() + " — личная настройка игрока, на сервере её нет";
        try { return field.getName() + " = " + field.get(ServerConfig.get()); }
        catch (IllegalAccessException impossible) { return field.getName() + ": нет доступа"; }
    }

    /** Имена серверных полей, которые можно задать командой {@code set} (для подсказок). */
    public static java.util.List<String> settable() {
        return SettingsScope.serverFields().stream().filter(name -> {
            Field field = field(name);
            Class<?> type = field == null ? null : field.getType();
            return type == boolean.class || type == int.class || type == double.class || type == String.class;
        }).toList();
    }

    static Field field(String name) {
        if (name == null || name.isBlank()) return null;
        for (String known : SettingsScope.fields()) if (known.equalsIgnoreCase(name.strip())) {
            try { return MobTalkConfig.class.getField(known); } catch (NoSuchFieldException impossible) { return null; }
        }
        return null;
    }
    private static Tunables.Tunable tunable(String field) {
        for (Tunables.Tunable tunable : Tunables.ALL) if (tunable.field().equals(field)) return tunable;
        return null;
    }
    private static Object parse(Class<?> type, String text) {
        if (type == String.class) return text;
        if (type == boolean.class) {
            return switch (text.toLowerCase(Locale.ROOT)) {
                case "on", "true", "да", "вкл", "1" -> true;
                case "off", "false", "нет", "выкл", "0" -> false;
                default -> throw new IllegalArgumentException("нужно on или off");
            };
        }
        try {
            double number = Double.parseDouble(text.replace(',', '.'));
            if (!Double.isFinite(number)) throw new IllegalArgumentException("нужно число");
            return type == int.class ? (Object) (int) Math.round(number) : (Object) number;
        } catch (NumberFormatException bad) { throw new IllegalArgumentException("нужно число"); }
    }
}
