package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Запрос данных для экрана: {@code info}, {@code family}, {@code village}. */
public record ScreenRequestC2S(String screen, int mobId) implements CustomPayload {
    public static final Id<ScreenRequestC2S> ID = MobTalkNet.payloadId("screen_request");
    public static final PacketCodec<PacketByteBuf, ScreenRequestC2S> CODEC = MobTalkNet.codec(ScreenRequestC2S::write, ScreenRequestC2S::read);

    private void write(PacketByteBuf buf) {
        MobTalkNet.writeText(buf, screen); buf.writeVarInt(mobId);
    }
    private static ScreenRequestC2S read(PacketByteBuf buf) {
        return new ScreenRequestC2S(MobTalkNet.readText(buf), buf.readVarInt());
    }
    @Override public Id<ScreenRequestC2S> getId() { return ID; }

}
