package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Личные настройки игрока ({@code PlayerSettings} в JSON) при входе и при изменении. */
public record PlayerSettingsC2S(String json) implements CustomPayload {
    public static final Id<PlayerSettingsC2S> ID = MobTalkNet.payloadId("player_settings");
    public static final PacketCodec<PacketByteBuf, PlayerSettingsC2S> CODEC = MobTalkNet.codec(PlayerSettingsC2S::write, PlayerSettingsC2S::read);

    private void write(PacketByteBuf buf) {
        MobTalkNet.writeJson(buf, json);
    }
    private static PlayerSettingsC2S read(PacketByteBuf buf) {
        return new PlayerSettingsC2S(MobTalkNet.readJson(buf));
    }
    @Override public Id<PlayerSettingsC2S> getId() { return ID; }

}
