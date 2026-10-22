package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Состояние моба для HUD и подписи: четыре оси, слово, отношение, метка и сердечки. */
public record MobStateS2C(int mobId, int calm, int trust, int interest, int mood, String word, int relationship, String label, int hearts) implements CustomPayload {
    public static final Id<MobStateS2C> ID = MobTalkNet.payloadId("mob_state");
    public static final PacketCodec<PacketByteBuf, MobStateS2C> CODEC = MobTalkNet.codec(MobStateS2C::write, MobStateS2C::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(mobId); buf.writeVarInt(calm); buf.writeVarInt(trust); buf.writeVarInt(interest); buf.writeVarInt(mood); MobTalkNet.writeText(buf, word); buf.writeVarInt(relationship); MobTalkNet.writeText(buf, label); buf.writeVarInt(hearts);
    }
    private static MobStateS2C read(PacketByteBuf buf) {
        return new MobStateS2C(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), MobTalkNet.readText(buf), buf.readVarInt(), MobTalkNet.readText(buf), buf.readVarInt());
    }
    @Override public Id<MobStateS2C> getId() { return ID; }

}
