package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Подарок из руки (клавиша G). */
public record GiftC2S(int mobId) implements CustomPayload {
    public static final Id<GiftC2S> ID = MobTalkNet.payloadId("gift");
    public static final PacketCodec<PacketByteBuf, GiftC2S> CODEC = MobTalkNet.codec(GiftC2S::write, GiftC2S::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(mobId);
    }
    private static GiftC2S read(PacketByteBuf buf) {
        return new GiftC2S(buf.readVarInt());
    }
    @Override public Id<GiftC2S> getId() { return ID; }

}
