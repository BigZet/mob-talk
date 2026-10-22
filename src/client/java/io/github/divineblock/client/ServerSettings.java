package io.github.divineblock.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.DivineSettings;
import io.github.divineblock.game.SettingsPatchPayload;
import io.github.divineblock.game.SettingsPayload;
import java.io.IOException;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

/** F9 on a dedicated server: the server's own settings of the god come from it ({@link SettingsPayload}) and are shown
 * read-only unless the player is an operator; his personal settings (voice, microphone, effects) stay in his own file. In
 * single player nothing changes: the integrated server reads the same file F9 writes. */
final class ServerSettings {
    private static volatile String values;
    private static volatile boolean editable;

    private ServerSettings() { }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(SettingsPayload.ID, (payload, context) -> {
            if (context.client().isIntegratedServerRunning()) return;
            values = payload.json();
            editable = payload.editable();
            DivineBlockMod.serverValues(payload.json());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            if (values == null) return;
            values = null; editable = false;
            DivineBlockMod.serverValues(null);
        }));
    }

    /** Playing on a dedicated server that sent its settings. */
    static boolean remote() { return values != null && !MinecraftClient.getInstance().isIntegratedServerRunning(); }
    /** The server's fields can't be changed here: on a server, by a player who is not an operator. */
    static boolean locked() { return remote() && !editable; }

    /** The player's own file with the server's settings laid over it: what F9 shows on a server. */
    static DivineConfig forScreen(DivineConfig own) { return remote() ? DivineSettings.overlay(own, values) : own; }

    /** Saves F9 on a server: the personal fields go to the player's own file, changed server fields to the server (when he
     * is an operator; the server checks it again). */
    static void save(DivineConfig edited) throws IOException {
        DivineConfig own = DivineConfig.load(DivineBlockMod.configFile());
        DivineSettings.withPersonal(own, edited).save(DivineBlockMod.configFile());
        if (editable && values != null) {
            JsonObject before = JsonParser.parseString(values).getAsJsonObject(), now = DivineSettings.values(edited), changed = new JsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : now.entrySet())
                if (!entry.getValue().equals(before.get(entry.getKey()))) changed.add(entry.getKey(), entry.getValue());
            if (!changed.isEmpty() && ClientPlayNetworking.canSend(SettingsPatchPayload.ID)) ClientPlayNetworking.send(new SettingsPatchPayload(changed.toString()));
        }
        DivineBlockMod.reloadConfig();
    }
}
