package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** Игра в 21: открыть стол у игрока (docs/cards-21.md). {@code entityId} — житель-банкир, {@code seat} — место игрока (−1 — смотрит),
 * правила ({@code OCHKO}/{@code BLACKJACK}), ставка на руку от и до, мест за столом. Состояние приходит следом в {@link CardTableStateS2C}. */
public record CardTableOpenS2C(int entityId, String villager, int seat, String variant, int minBet, int maxBet, int seats) implements CustomPayload {
    public static final Id<CardTableOpenS2C> ID = MobTalkNet.payloadId("card_table_open");
    public static final PacketCodec<PacketByteBuf, CardTableOpenS2C> CODEC = MobTalkNet.codec(CardTableOpenS2C::write, CardTableOpenS2C::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(entityId); MobTalkNet.writeText(buf, villager); buf.writeVarInt(seat + 1); MobTalkNet.writeText(buf, variant);
        buf.writeVarInt(minBet); buf.writeVarInt(maxBet); buf.writeVarInt(seats);
    }
    private static CardTableOpenS2C read(PacketByteBuf buf) {
        return new CardTableOpenS2C(buf.readVarInt(), MobTalkNet.readText(buf), buf.readVarInt() - 1, MobTalkNet.readText(buf),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
    }
    @Override public Id<CardTableOpenS2C> getId() { return ID; }
}