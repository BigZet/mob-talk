package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Реплика игрока текстом (локальный helper или чат); {@code picture} — JPEG не больше 200 КБ или пусто. */
public record TalkTextC2S(int mobId, String text, boolean fromVoice, byte[] picture) implements CustomPayload {
    public static final Id<TalkTextC2S> ID = MobTalkNet.payloadId("talk_text");
    public static final PacketCodec<PacketByteBuf, TalkTextC2S> CODEC = MobTalkNet.codec(TalkTextC2S::write, TalkTextC2S::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(mobId); MobTalkNet.writeText(buf, text); buf.writeBoolean(fromVoice); MobTalkNet.writeBytes(buf, picture, MobTalkNet.MAX_PICTURE, "Снимок");
    }
    private static TalkTextC2S read(PacketByteBuf buf) {
        return new TalkTextC2S(buf.readVarInt(), MobTalkNet.readText(buf), buf.readBoolean(), MobTalkNet.readBytes(buf, MobTalkNet.MAX_PICTURE));
    }
    @Override public Id<TalkTextC2S> getId() { return ID; }
    public TalkTextC2S { Objects.requireNonNull(text); picture = picture == null ? new byte[0] : picture; }
    @Override public boolean equals(Object o) { return o instanceof TalkTextC2S p && p.mobId == mobId && p.text.equals(text) && p.fromVoice == fromVoice && Arrays.equals(p.picture, picture); }
    @Override public int hashCode() { return Objects.hash(mobId, text, fromVoice, Arrays.hashCode(picture)); }
}
