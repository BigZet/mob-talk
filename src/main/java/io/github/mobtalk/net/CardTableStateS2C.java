package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** Игра в 21: что видно за столом этому игроку. {@code version} растёт с каждым изменением (старое клиент отбрасывает); {@code json} —
 * открытые карты и ставки ({@code CardTable.View}), события с прошлой версии для анимаций, его место, изумруды, таймеры, реплика
 * жителя; {@code closed} не пусто — стол закрыт, это причина. */
public record CardTableStateS2C(int entityId, int version, String json, String closed) implements CustomPayload {
    public static final Id<CardTableStateS2C> ID = MobTalkNet.payloadId("card_table_state");
    public static final PacketCodec<PacketByteBuf, CardTableStateS2C> CODEC = MobTalkNet.codec(CardTableStateS2C::write, CardTableStateS2C::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(entityId); buf.writeVarInt(version); MobTalkNet.writeJson(buf, json); MobTalkNet.writeText(buf, closed);
    }
    private static CardTableStateS2C read(PacketByteBuf buf) {
        return new CardTableStateS2C(buf.readVarInt(), buf.readVarInt(), MobTalkNet.readJson(buf), MobTalkNet.readText(buf));
    }
    @Override public Id<CardTableStateS2C> getId() { return ID; }
}