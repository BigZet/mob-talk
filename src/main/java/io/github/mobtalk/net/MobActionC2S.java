package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** STAY, следуй, GUARD, стоп из экрана: {@code ActionIntent} в JSON. */
public record MobActionC2S(int mobId, String intentJson) implements CustomPayload {
    public static final Id<MobActionC2S> ID = MobTalkNet.payloadId("mob_action");
    public static final PacketCodec<PacketByteBuf, MobActionC2S> CODEC = MobTalkNet.codec(MobActionC2S::write, MobActionC2S::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(mobId); MobTalkNet.writeJson(buf, intentJson);
    }
    private static MobActionC2S read(PacketByteBuf buf) {
        return new MobActionC2S(buf.readVarInt(), MobTalkNet.readJson(buf));
    }
    @Override public Id<MobActionC2S> getId() { return ID; }

}
