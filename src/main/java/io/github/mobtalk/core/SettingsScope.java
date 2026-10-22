package io.github.mobtalk.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Два слоя настроек после переноса на сервер (раздел 12.2 плана): личные поля {@link MobTalkConfig} живут у игрока
 * ({@link PlayerSettings}), остальные — на сервере ({@code ServerConfig}). Личные — всё, что касается микрофона, локального Whisper,
 * HUD и подписей, обращений к игроку, его пола и того, как ему рисовать жителей; серверные — модель, озвучка, бюджет, поведение мобов. */
public final class SettingsScope {
    private SettingsScope() { }

    /** Личные поля по имени; префиксы покрывают и будущие поля треков B и C ({@code micGain}, {@code voiceVolume}, {@code sttPath},
     * {@code hud*}, {@code labels*}, {@code keys*}). */
    private static final List<String> PERSONAL_EXACT = List.of("microphoneName", "showStateHud", "showBargainHud", "showNameTags",
            "playerGender", "humanVillagers", "voiceVolume", "sttPath", "micGain", "micDevice");
    private static final List<String> PERSONAL_PREFIXES = List.of("whisper", "mic", "hud", "playerNames", "labels", "keys");

    /** Поле хранится у игрока, а не на сервере. */
    public static boolean personal(String field) {
        if (PERSONAL_EXACT.contains(field)) return true;
        for (String prefix : PERSONAL_PREFIXES)
            if (field.startsWith(prefix) && field.length() > prefix.length() && Character.isUpperCase(field.charAt(prefix.length()))) return true;
        return false;
    }

    private static final List<String> FIELDS;
    static {
        List<String> names = new ArrayList<>();
        for (Field field : MobTalkConfig.class.getFields())
            if (!Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getModifiers())) names.add(field.getName());
        FIELDS = Collections.unmodifiableList(names);
    }
    /** Все публичные поля настроек, в порядке объявления. */
    public static List<String> fields() { return FIELDS; }
    public static List<String> personalFields() { return FIELDS.stream().filter(SettingsScope::personal).toList(); }
    public static List<String> serverFields() { return FIELDS.stream().filter(f -> !personal(f)).toList(); }

    /** Копия, в которой личные поля сброшены к значениям по умолчанию: то, что хранит и рассылает сервер. */
    public static MobTalkConfig serverOnly(MobTalkConfig config) { return merge(config, new MobTalkConfig()); }

    /** Копия серверного снимка с личными полями игрока: по такому снимку живут клиентские экраны и локальный Whisper. */
    public static MobTalkConfig merge(MobTalkConfig server, MobTalkConfig personal) {
        MobTalkConfig result = server.copy();
        for (String name : personalFields()) copyField(name, personal, result);
        return result;
    }

    /** Копия личного снимка, в которую перенесены серверные поля: клиент одиночки отдаёт встроенному серверу свои серверные настройки. */
    public static MobTalkConfig withServerFields(MobTalkConfig personal, MobTalkConfig server) {
        MobTalkConfig result = personal.copy();
        for (String name : serverFields()) copyField(name, server, result);
        return result;
    }

    private static void copyField(String name, MobTalkConfig from, MobTalkConfig to) {
        try {
            Field field = MobTalkConfig.class.getField(name);
            Object value = field.get(from);
            if (value instanceof java.util.Map<?, ?> map) value = new java.util.LinkedHashMap<>(map);
            field.set(to, value);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(name, failure); }
    }
}
