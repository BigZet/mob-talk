package io.github.mobtalk.server;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.Set;

/** Пока трек C не сделал уровни по {@code admins} и {@code streamer}: операторы сервера — ADMIN, остальные — PLAYER;
 * команды модов из {@link #MOD_ROOTS} — только ADMIN (консоль сервера — тоже ADMIN), ванильные не фильтруются. */
public final class OpsOnlyPermissions implements Permissions {
    /** Корни команд модов сборки; неизвестный корень мода тоже считается модовым, если он в этом списке. */
    public static final Set<String> MOD_ROOTS = Set.of("mobtalk", "divineblock", "vp", "viewerparrots", "rusflora", "parrotcages");

    @Override public Level level(ServerPlayerEntity player) {
        if (player == null) return Level.PLAYER;
        return player.getServer() != null && player.getServer().getPlayerManager().isOperator(player.getGameProfile()) ? Level.ADMIN : Level.PLAYER;
    }

    @Override public boolean mayRun(ServerCommandSource source, String commandRoot) {
        if (!MOD_ROOTS.contains(commandRoot)) return true;
        ServerPlayerEntity player = source.getPlayer();
        return player == null ? source.hasPermissionLevel(2) : level(player) == Level.ADMIN;
    }
}
