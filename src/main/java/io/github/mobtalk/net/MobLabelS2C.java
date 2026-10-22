package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.UUID;

/** Имя моба и строка под именем (беременность, ранг); пустое имя — подпись снять. Трек A добавил {@code mob} (UUID). */
public record MobLabelS2C(int mobId, UUID mob, String name, String line) implements CustomPayload {
    public static final Id<MobLabelS2C> ID = MobTalkNet.payloadId("mob_label");
    public static final PacketCodec<PacketByteBuf, MobLabelS2C> CODEC = MobTalkNet.codec(MobLabelS2C::write, MobLabelS2C::read);

    public MobLabelS2C { name = name == null ? "" : name; line = line == null ? "" : line; }

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(mobId); buf.writeUuid(mob); MobTalkNet.writeText(buf, name); MobTalkNet.writeText(buf, line);
    }
    private static MobLabelS2C read(PacketByteBuf buf) {
        return new MobLabelS2C(buf.readVarInt(), buf.readUuid(), MobTalkNet.readText(buf), MobTalkNet.readText(buf));
    }
    @Override public Id<MobLabelS2C> getId() { return ID; }
}
