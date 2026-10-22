package io.github.mobtalk.server;

import io.github.mobtalk.net.MobLabelS2C;
import io.github.mobtalk.net.MobSpeechS2C;
import io.github.mobtalk.net.MobStateS2C;
import io.github.mobtalk.net.NoticeS2C;
import io.github.mobtalk.net.ScreenDataS2C;
import io.github.mobtalk.net.SpeechClipS2C;
import io.github.mobtalk.net.TalkStatusS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/** Пакеты S2C игрокам этого сервера; игрок без Mob Talk на клиенте (ванильный) молча пропускается. Поток сервера. */
public final class ServerOutbox implements DialogueService.Outbox {
    private final MinecraftServer server;
    public ServerOutbox(MinecraftServer server) { this.server = server; }

    private void send(java.util.UUID player, CustomPayload payload) {
        ServerPlayerEntity entity = server.getPlayerManager().getPlayer(player);
        if (entity != null && ServerPlayNetworking.canSend(entity, payload.getId())) ServerPlayNetworking.send(entity, payload);
    }
    @Override public void status(java.util.UUID player, String status, boolean busy, String error) { send(player, new TalkStatusS2C(status, busy, error)); }
    @Override public void speech(java.util.UUID player, MobSpeechS2C packet) { send(player, packet); }
    @Override public void clip(java.util.UUID player, SpeechService.Clip clip) {
        for (SpeechClipS2C chunk : StubSpeech.chunks(clip)) send(player, chunk);
    }
    @Override public void chat(java.util.UUID player, String line) { send(player, new NoticeS2C(line, false)); }
    @Override public void screen(java.util.UUID player, String screen, String json) { send(player, new ScreenDataS2C(screen, json)); }
    @Override public void state(java.util.UUID player, MobStateS2C packet) { send(player, packet); }
    @Override public void label(java.util.UUID player, MobLabelS2C packet) { send(player, packet); }
}
