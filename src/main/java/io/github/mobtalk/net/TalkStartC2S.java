package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Игрок зажал V на мобе: сервер помечает внимание моба и отвечает {@link TalkStatusS2C}. */
public record TalkStartC2S(int mobId) implements CustomPayload {
    public static final Id<TalkStartC2S> ID = MobTalkNet.payloadId("talk_start");
    public static final PacketCodec<PacketByteBuf, TalkStartC2S> CODEC = MobTalkNet.codec(TalkStartC2S::write, TalkStartC2S::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(mobId);
    }
    private static TalkStartC2S read(PacketByteBuf buf) {
        return new TalkStartC2S(buf.readVarInt());
    }
    @Override public Id<TalkStartC2S> getId() { return ID; }

}
