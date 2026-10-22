package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Уведомление игроку: в чат или всплывашкой. */
public record NoticeS2C(String text, boolean toast) implements CustomPayload {
    public static final Id<NoticeS2C> ID = MobTalkNet.payloadId("notice");
    public static final PacketCodec<PacketByteBuf, NoticeS2C> CODEC = MobTalkNet.codec(NoticeS2C::write, NoticeS2C::read);

    private void write(PacketByteBuf buf) {
        MobTalkNet.writeText(buf, text); buf.writeBoolean(toast);
    }
    private static NoticeS2C read(PacketByteBuf buf) {
        return new NoticeS2C(MobTalkNet.readText(buf), buf.readBoolean());
    }
    @Override public Id<NoticeS2C> getId() { return ID; }

}
