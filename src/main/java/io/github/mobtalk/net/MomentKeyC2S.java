package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** Нажата клавиша «Отметить момент»: то же, что {@code /clip} без текста. */
public record MomentKeyC2S(String title) implements CustomPayload {
    public static final Id<MomentKeyC2S> ID = MobTalkNet.payloadId("moment_key");
    public static final PacketCodec<PacketByteBuf, MomentKeyC2S> CODEC = MobTalkNet.codec(MomentKeyC2S::write, MomentKeyC2S::read);

    private void write(PacketByteBuf buf) { MobTalkNet.writeText(buf, title); }
    private static MomentKeyC2S read(PacketByteBuf buf) { return new MomentKeyC2S(MobTalkNet.readText(buf)); }
    @Override public Id<MomentKeyC2S> getId() { return ID; }
}
