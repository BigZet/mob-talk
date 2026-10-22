package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Игрок отпустил цель, сменил мир, умер: сервер сбрасывает разговор. */
public record TalkCancelC2S(String reason) implements CustomPayload {
    public static final Id<TalkCancelC2S> ID = MobTalkNet.payloadId("talk_cancel");
    public static final PacketCodec<PacketByteBuf, TalkCancelC2S> CODEC = MobTalkNet.codec(TalkCancelC2S::write, TalkCancelC2S::read);

    private void write(PacketByteBuf buf) {
        MobTalkNet.writeText(buf, reason);
    }
    private static TalkCancelC2S read(PacketByteBuf buf) {
        return new TalkCancelC2S(MobTalkNet.readText(buf));
    }
    @Override public Id<TalkCancelC2S> getId() { return ID; }

}
