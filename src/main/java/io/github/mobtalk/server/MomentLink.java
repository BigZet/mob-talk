package io.github.mobtalk.server;

import com.google.gson.JsonObject;
import io.github.mobtalk.net.MomentKeyC2S;
import io.github.mobtalk.net.MomentReplayC2S;
import io.github.mobtalk.net.MomentS2C;
import io.github.mobtalk.net.RecStateC2S;
import io.github.mobtalk.server.web.AdminWeb;
import io.github.mobtalk.server.web.Json;
import io.github.mobtalk.server.web.Moments;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Пакеты моментов для монтажа (идея З4) между {@link Moments} и клиентами: клавиша метки, состояние OBS игрока (сессия записи
 * и её таймкоды), файл повтора, а обратно — живой момент тем, кого он касается, и тем, кто пишет всё. Поток сервера. */
public final class MomentLink {
    private MomentLink() { }
    /** Игроки, у которых OBS сейчас пишет или в эфире, и те, кто просил главы всех моментов сервера (F7). */
    private static final Set<UUID> recording = ConcurrentHashMap.newKeySet(), everything = ConcurrentHashMap.newKeySet();

    static void register() {
        ServerPlayNetworking.registerGlobalReceiver(MomentKeyC2S.ID, (payload, context) -> ClipCommands.markFromKey(context.player()));
        ServerPlayNetworking.registerGlobalReceiver(RecStateC2S.ID, (payload, context) -> recState(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(MomentReplayC2S.ID, (payload, context) -> {
            Moments moments = moments();
            if (moments != null) moments.replayFile(payload.id(), context.player().getUuidAsString(), payload.path());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            recording.remove(handler.getPlayer().getUuid());
            everything.remove(handler.getPlayer().getUuid());
        });
    }

    /** Админка поднялась: живые моменты уходят игрокам, сессии при входе открываются для стримера. */
    static void attach(Moments moments, MinecraftServer server) {
        moments.streamer(uuid -> { UUID streamer = ServerConfig.streamer(); return streamer != null && streamer.toString().equals(uuid); });
        moments.onLive(live -> server.execute(() -> send(server, live)));
    }

    private static Moments moments() { AdminWeb web = AdminLink.web(); return web == null ? null : web.moments(); }

    private static void recState(ServerPlayerEntity player, RecStateC2S state) {
        if (state.active()) recording.add(player.getUuid()); else recording.remove(player.getUuid());
        if (state.everything()) everything.add(player.getUuid()); else everything.remove(player.getUuid());
        Moments moments = moments();
        if (moments == null) return;
        moments.obs(Json.player(player.getUuid(), player.getGameProfile().getName()), state.output(), state.active(), state.elapsedMillis());
    }

    private static void send(MinecraftServer server, Moments.Live live) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID id = player.getUuid();
            boolean mine = live.players().contains(id.toString());
            boolean wanted = mine || everything.contains(id) || live.players().isEmpty() && recording.contains(id);
            if (!wanted || !ServerPlayNetworking.canSend(player, MomentS2C.ID)) continue;
            ServerPlayNetworking.send(player, new MomentS2C(live.id(), live.title(), live.weight(), live.type(), live.manual(), mine));
        }
    }

    /** A manual mark: a player without the mod gets the confirmation over the hotbar from here, a modded one from {@link MomentS2C}. */
    static void confirm(ServerPlayerEntity player, JsonObject record) {
        if (ServerPlayNetworking.canSend(player, MomentS2C.ID)) return;
        player.sendMessage(Text.literal(ClipCommands.PREFIX + "Отмечено: " + Json.string(record, "text")), true);
    }
}
