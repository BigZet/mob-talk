package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Аудио на распознавание сервером: {@code format} — {@code wav16k} или {@code mp3}, не больше 900 КБ. */
public record TalkAudioC2S(int mobId, String format, int seconds, byte[] audio) implements CustomPayload {
    public static final Id<TalkAudioC2S> ID = MobTalkNet.payloadId("talk_audio");
    public static final PacketCodec<PacketByteBuf, TalkAudioC2S> CODEC = MobTalkNet.codec(TalkAudioC2S::write, TalkAudioC2S::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(mobId); MobTalkNet.writeText(buf, format); buf.writeVarInt(seconds); MobTalkNet.writeBytes(buf, audio, MobTalkNet.MAX_AUDIO, "Аудио");
    }
    private static TalkAudioC2S read(PacketByteBuf buf) {
        return new TalkAudioC2S(buf.readVarInt(), MobTalkNet.readText(buf), buf.readVarInt(), MobTalkNet.readBytes(buf, MobTalkNet.MAX_AUDIO));
    }
    @Override public Id<TalkAudioC2S> getId() { return ID; }
    public TalkAudioC2S { Objects.requireNonNull(format); audio = audio == null ? new byte[0] : audio; }
    @Override public boolean equals(Object o) { return o instanceof TalkAudioC2S p && p.mobId == mobId && p.format.equals(format) && p.seconds == seconds && Arrays.equals(p.audio, audio); }
    @Override public int hashCode() { return Objects.hash(mobId, format, seconds, Arrays.hashCode(audio)); }
}
