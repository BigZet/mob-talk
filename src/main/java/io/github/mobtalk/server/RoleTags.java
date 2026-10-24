package io.github.mobtalk.server;

import io.github.mobtalk.MobTalkMod;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

/** Роли игроков для других модов без общего кода: теги {@link #STREAMER} и {@link #ADMIN} (Parrot Cages пускает по ним стримера и
 * администратора к клеткам попугаев-зрителей). Стример — назначенный в настройках сервера, администратор — уровень ADMIN
 * ({@link Permissions}: оператор, хозяин одиночки, список admins). Сверка раз в две секунды для игроков в сети. */
public final class RoleTags {
    public static final String STREAMER = "mobtalk.role.streamer", ADMIN = "mobtalk.role.admin";
    private static final int PERIOD = 40;

    private RoleTags() { }

    static void register() { ServerTickEvents.END_SERVER_TICK.register(RoleTags::tick); }

    private static void tick(MinecraftServer server) {
        if (server.getTicks() % PERIOD != 0) return;
        UUID streamer = ServerConfig.streamer();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            set(player, STREAMER, player.getUuid().equals(streamer));
            set(player, ADMIN, MobTalkMod.permissions().level(player) == Permissions.Level.ADMIN);
        }
    }

    private static void set(ServerPlayerEntity player, String tag, boolean on) {
        if (on) player.addCommandTag(tag); else player.removeCommandTag(tag);
    }
}
