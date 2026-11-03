package io.github.mobtalk.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.mobtalk.MobTalkMod;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.PlayerIdentity;
import io.github.mobtalk.core.SettingsScope;
import io.github.mobtalk.net.MobTalkNet;
import io.github.mobtalk.net.ScreenDataS2C;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import java.time.Clock;
import java.util.UUID;

/** Трек C на сервере одной точкой: уровни прав и фильтр команд, учёт расходов, {@code /mobtalk admin}, имена и пол каждого игрока из его
 * личных настроек ({@link PlayerIdentity#resolver}) и снимок серверных настроек для F7 ({@code ScreenDataS2C("server-settings")}) при
 * входе и после каждой правки. {@code MobTalkMod.onInitialize} зовёт {@link #initialize()} одной строкой. */
public final class PlayerServices {
    private PlayerServices() { }
    /** Экран {@code ScreenDataS2C}, в котором клиент получает серверные настройки, свой уровень и расходы за сегодня. */
    public static final String SETTINGS_SCREEN = "server-settings";
    private static final Gson GSON = new GsonBuilder().create();

    private static PermissionsImpl permissions;
    private static PlayerBudgetImpl budget;
    private static volatile MinecraftServer current;
    private static volatile String rights = "";

    public static void initialize() {
        permissions = new PermissionsImpl();
        budget = new PlayerBudgetImpl(ServerConfig::get, PlayerServices::levelOf, Clock.systemDefaultZone());
        MobTalkMod.setPermissions(permissions);
        MobTalkMod.setBudget(budget);
        PlayerIdentity.resolver(player -> PlayerSettingsStore.find(player).map(settings -> settings.applyTo(ServerConfig.get())));
        AdminCommands.register();
        ClipCommands.register(); // Идея З4: метки для монтажа.
        MomentLink.register();
        RoleTags.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            current = server;
            rights = rights();
            budget.open(server.getSavePath(WorldSavePath.ROOT));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (current != server) return;
            current = null;
            budget.close();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            budget.remember(handler.getPlayer().getUuid(), handler.getPlayer().getGameProfile().getName());
            sendSettings(handler.getPlayer());
        });
        ServerConfig.listen(config -> changed());
    }

    /** Уровень по UUID: игрок онлайн — по нему (оператор, хозяин одиночки), иначе по списку операторов, {@code admins} и {@code streamer}. */
    static Permissions.Level levelOf(UUID player) {
        MinecraftServer server = current;
        if (server == null || player == null) return permissions.level(player, false);
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(player);
        if (online != null) return permissions.level(online);
        return permissions.level(player, server.getPlayerManager().isOperator(new GameProfile(player, "")));
    }

    /** Права, от которых зависит дерево команд: при их смене дерево уходит игрокам заново. */
    private static String rights() {
        return ServerConfig.streamer() + "|" + ServerConfig.admins() + "|" + ServerConfig.get().commandLevels;
    }

    private static void changed() {
        MinecraftServer server = current;
        if (server == null) return;
        String now = rights();
        boolean tree = !now.equals(rights);
        rights = now;
        server.execute(() -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (tree) server.getCommandManager().sendCommandTree(player);
                sendSettings(player);
            }
        });
    }

    /** Серверные настройки (без личных), уровень игрока, стример и его расходы за сегодня — для F7. */
    public static void sendSettings(ServerPlayerEntity player) {
        if (player == null || !ServerPlayNetworking.canSend(player, ScreenDataS2C.ID)) return;
        ServerPlayNetworking.send(player, new ScreenDataS2C(SETTINGS_SCREEN, settingsJson(player)));
    }

    static String settingsJson(ServerPlayerEntity player) {
        MobTalkConfig config = SettingsScope.serverOnly(ServerConfig.get());
        JsonObject root = new JsonObject();
        root.add("config", GSON.toJsonTree(config));
        if (root.toString().length() > MobTalkNet.MAX_JSON / 2) { // Огромные описания видов не нужны экрану настроек.
            config.speciesOverrides.clear();
            root.add("config", GSON.toJsonTree(config));
        }
        root.addProperty("level", MobTalkMod.permissions().level(player).name());
        root.addProperty("dedicated", ServerConfig.persistent());
        UUID streamer = ServerConfig.streamer();
        root.addProperty("streamer", streamer == null || player.getServer() == null ? "" : AdminCommands.name(player.getServer(), streamer));
        root.addProperty("spentToday", MobTalkMod.budget().spentToday(player.getUuid()));
        return root.toString();
    }
}
