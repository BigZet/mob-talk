package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** Игра в 21: нажатие за столом жителя {@code entityId}. {@code action}: bet, unbet, deal, hit, stand, double, split, vabank, catch,
 * sit, watch, leave, say; {@code amount} — изумруды ставки; {@code text} — сказанное («Сказать…»). Проверяет всё сервер. */
public record CardTableActionC2S(int entityId, String action, int amount, String text) implements CustomPayload {
    public static final Id<CardTableActionC2S> ID = MobTalkNet.payloadId("card_table_action");
    public static final PacketCodec<PacketByteBuf, CardTableActionC2S> CODEC = MobTalkNet.codec(CardTableActionC2S::write, CardTableActionC2S::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(entityId); MobTalkNet.writeText(buf, action); buf.writeVarInt(amount); MobTalkNet.writeText(buf, text);
    }
    private static CardTableActionC2S read(PacketByteBuf buf) {
        return new CardTableActionC2S(buf.readVarInt(), MobTalkNet.readText(buf), buf.readVarInt(), MobTalkNet.readText(buf));
    }
    @Override public Id<CardTableActionC2S> getId() { return ID; }
}