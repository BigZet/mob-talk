package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** Самотест сервера с клиентами (трек G): строка в обе стороны — сервер-дирижёр шлёт клиенту реплику сценария, клиент отвечает тем,
 * что увидел. Приёмники вешаются только при {@code -Dmobtalk.selftest.play}; в обычной игре пакет не ходит. */
public record SelfTestLine(String line) implements CustomPayload {
    public static final Id<SelfTestLine> ID = MobTalkNet.payloadId("selftest");
    public static final PacketCodec<PacketByteBuf, SelfTestLine> CODEC = MobTalkNet.codec(SelfTestLine::write, SelfTestLine::read);

    private void write(PacketByteBuf buf) { MobTalkNet.writeText(buf, line); }
    private static SelfTestLine read(PacketByteBuf buf) { return new SelfTestLine(MobTalkNet.readText(buf)); }
    @Override public Id<SelfTestLine> getId() { return ID; }
}
