package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Звук реплики чанками не больше 512 КБ; клиент собирает {@code count} штук и играет от моба. */
public record SpeechClipS2C(int clipId, int index, int count, String format, byte[] chunk) implements CustomPayload {
    public static final Id<SpeechClipS2C> ID = MobTalkNet.payloadId("speech_clip");
    public static final PacketCodec<PacketByteBuf, SpeechClipS2C> CODEC = MobTalkNet.codec(SpeechClipS2C::write, SpeechClipS2C::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(clipId); buf.writeVarInt(index); buf.writeVarInt(count); MobTalkNet.writeText(buf, format); MobTalkNet.writeBytes(buf, chunk, MobTalkNet.MAX_CHUNK, "Чанк звука");
    }
    private static SpeechClipS2C read(PacketByteBuf buf) {
        return new SpeechClipS2C(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), MobTalkNet.readText(buf), MobTalkNet.readBytes(buf, MobTalkNet.MAX_CHUNK));
    }
    @Override public Id<SpeechClipS2C> getId() { return ID; }
    public SpeechClipS2C { Objects.requireNonNull(format); chunk = chunk == null ? new byte[0] : chunk; }
    @Override public boolean equals(Object o) { return o instanceof SpeechClipS2C p && p.clipId == clipId && p.index == index && p.count == count && p.format.equals(format) && Arrays.equals(p.chunk, chunk); }
    @Override public int hashCode() { return Objects.hash(clipId, index, count, format, Arrays.hashCode(chunk)); }
}
