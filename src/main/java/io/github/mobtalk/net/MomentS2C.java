package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** Моменты для монтажа: только что случился яркий момент (или поставлена ручная метка). Клиент с OBS ставит главу в записи и сохраняет
 * повтор; {@code manual} и {@code mine} — метку поставил сам игрок, ему короткое подтверждение над хотбаром. */
public record MomentS2C(String id, String title, int weight, String type, boolean manual, boolean mine) implements CustomPayload {
    public static final Id<MomentS2C> ID = MobTalkNet.payloadId("moment");
    public static final PacketCodec<PacketByteBuf, MomentS2C> CODEC = MobTalkNet.codec(MomentS2C::write, MomentS2C::read);

    private void write(PacketByteBuf buf) {
        MobTalkNet.writeText(buf, id); MobTalkNet.writeText(buf, title); buf.writeVarInt(weight); MobTalkNet.writeText(buf, type);
        buf.writeBoolean(manual); buf.writeBoolean(mine);
    }
    private static MomentS2C read(PacketByteBuf buf) {
        return new MomentS2C(MobTalkNet.readText(buf), MobTalkNet.readText(buf), buf.readVarInt(), MobTalkNet.readText(buf), buf.readBoolean(), buf.readBoolean());
    }
    @Override public Id<MomentS2C> getId() { return ID; }
}
