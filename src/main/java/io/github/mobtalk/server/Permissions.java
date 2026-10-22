package io.github.mobtalk.server;

import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

/** Уровни игроков и право на команды модов; реализация — трек C ({@code admins}, {@code streamer}, таблица корней).
 * Заглушка: {@link OpsOnlyPermissions}. */
public interface Permissions {
    enum Level { PLAYER, STREAMER, ADMIN }

    Level level(ServerPlayerEntity player);
    /** {@code commandRoot} — корень команды мода: {@code mobtalk}, {@code divineblock}, {@code vp}... Ванильные команды не фильтруются. */
    boolean mayRun(ServerCommandSource source, String commandRoot);
}
