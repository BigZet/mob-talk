package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Имя моба и строка под именем (беременность, ранг). */
public record MobLabelS2C(int mobId, String name, String line) implements CustomPayload {
    public static final Id<MobLabelS2C> ID = MobTalkNet.payloadId("mob_label");
    public static final PacketCodec<PacketByteBuf, MobLabelS2C> CODEC = MobTalkNet.codec(MobLabelS2C::write, MobLabelS2C::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(mobId); MobTalkNet.writeText(buf, name); MobTalkNet.writeText(buf, line);
    }
    private static MobLabelS2C read(PacketByteBuf buf) {
        return new MobLabelS2C(buf.readVarInt(), MobTalkNet.readText(buf), MobTalkNet.readText(buf));
    }
    @Override public Id<MobLabelS2C> getId() { return ID; }

}
