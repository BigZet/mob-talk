package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Что включено на сервере, лимиты, пути распознавания, уровень прав игрока, стример ({@code ServerFeatures} в JSON). */
public record ServerFeaturesS2C(String json) implements CustomPayload {
    public static final Id<ServerFeaturesS2C> ID = MobTalkNet.payloadId("server_features");
    public static final PacketCodec<PacketByteBuf, ServerFeaturesS2C> CODEC = MobTalkNet.codec(ServerFeaturesS2C::write, ServerFeaturesS2C::read);

    private void write(PacketByteBuf buf) {
        MobTalkNet.writeJson(buf, json);
    }
    private static ServerFeaturesS2C read(PacketByteBuf buf) {
        return new ServerFeaturesS2C(MobTalkNet.readJson(buf));
    }
    @Override public Id<ServerFeaturesS2C> getId() { return ID; }

}
