package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Данные для экранов info, family, village, deal. */
public record ScreenDataS2C(String screen, String json) implements CustomPayload {
    public static final Id<ScreenDataS2C> ID = MobTalkNet.payloadId("screen_data");
    public static final PacketCodec<PacketByteBuf, ScreenDataS2C> CODEC = MobTalkNet.codec(ScreenDataS2C::write, ScreenDataS2C::read);

    private void write(PacketByteBuf buf) {
        MobTalkNet.writeText(buf, screen); MobTalkNet.writeJson(buf, json);
    }
    private static ScreenDataS2C read(PacketByteBuf buf) {
        return new ScreenDataS2C(MobTalkNet.readText(buf), MobTalkNet.readJson(buf));
    }
    @Override public Id<ScreenDataS2C> getId() { return ID; }

}
