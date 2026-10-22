package io.github.mobtalk.client.settings;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mobtalk.core.MobTalkConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;

/** Что сервер рассказал о своих настройках (трек C): серверный слой {@link MobTalkConfig}, уровень этого игрока, стример и расходы
 * за сегодня. Приходит в {@code ScreenDataS2C("server-settings")} при входе и после каждой правки; F7 на выделенном сервере рисует
 * серверные поля по нему — серыми для игрока и стримера, правимыми для администратора. */
public final class ServerSettingsView {
    private ServerSettingsView() { }
    public static final String SCREEN = "server-settings";

    public record Snapshot(MobTalkConfig config, String level, boolean dedicated, String streamer, String spentToday) {
        public boolean admin() { return "ADMIN".equals(level); }
    }
    private static volatile Snapshot current;

    /** Последний снимок или null, пока сервер ничего не прислал (одиночка до ответа, сервер без Mob Talk). */
    public static Snapshot get() { return current; }
    /** Новый вход в мир: прежний снимок не годится. */
    public static void reset() { current = null; }

    public static void accept(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            MobTalkConfig config = new Gson().fromJson(root.get("config"), MobTalkConfig.class);
            if (config == null) return;
            current = new Snapshot(config, text(root, "level", "PLAYER"), root.has("dedicated") && root.get("dedicated").getAsBoolean(),
                    text(root, "streamer", ""), text(root, "spentToday", ""));
        } catch (RuntimeException malformed) { /* старый или чужой сервер: F7 остаётся как в одиночке */ }
    }
    private static String text(JsonObject root, String field, String fallback) {
        JsonElement value = root.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    /** Команда серверу мимо клиентского {@code /mobtalk}: у клиента и сервера один корень, и клиентский перехватил бы её. Только
     * для команд без подписанных аргументов ({@code /mobtalk admin …}). */
    public static boolean sendToServer(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() == null || command == null || command.isBlank()) return false;
        String line = command.strip().startsWith("/") ? command.strip().substring(1) : command.strip();
        if (line.length() > 256) return false;
        client.getNetworkHandler().sendPacket(new CommandExecutionC2SPacket(line));
        return true;
    }
}
