package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** «Слушаю», «думает…», отказ бюджета или ошибка: строка Mob Talk в HUD игрока. */
public record TalkStatusS2C(String status, boolean busy, String error) implements CustomPayload {
    public static final Id<TalkStatusS2C> ID = MobTalkNet.payloadId("talk_status");
    public static final PacketCodec<PacketByteBuf, TalkStatusS2C> CODEC = MobTalkNet.codec(TalkStatusS2C::write, TalkStatusS2C::read);

    private void write(PacketByteBuf buf) {
        MobTalkNet.writeText(buf, status); buf.writeBoolean(busy); MobTalkNet.writeText(buf, error);
    }
    private static TalkStatusS2C read(PacketByteBuf buf) {
        return new TalkStatusS2C(MobTalkNet.readText(buf), buf.readBoolean(), MobTalkNet.readText(buf));
    }
    @Override public Id<TalkStatusS2C> getId() { return ID; }

}
