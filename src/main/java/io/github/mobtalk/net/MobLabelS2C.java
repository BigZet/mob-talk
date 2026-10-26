package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.UUID;

/** Имя моба и строка под именем (беременность, ранг); пустое имя — подпись снять. Трек A добавил {@code mob} (UUID); {@code kin} — кем
 * моб приходится этому игроку («жена», «сын»), "" — не родня. */
public record MobLabelS2C(int mobId, UUID mob, String name, String line, String kin) implements CustomPayload {
    public MobLabelS2C(int mobId, UUID mob, String name, String line) { this(mobId, mob, name, line, ""); }
    public static final Id<MobLabelS2C> ID = MobTalkNet.payloadId("mob_label");
    public static final PacketCodec<PacketByteBuf, MobLabelS2C> CODEC = MobTalkNet.codec(MobLabelS2C::write, MobLabelS2C::read);

    public MobLabelS2C { name = name == null ? "" : name; line = line == null ? "" : line; kin = kin == null ? "" : kin; }

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(mobId); buf.writeUuid(mob); MobTalkNet.writeText(buf, name); MobTalkNet.writeText(buf, line); MobTalkNet.writeText(buf, kin);
    }
    private static MobLabelS2C read(PacketByteBuf buf) {
        return new MobLabelS2C(buf.readVarInt(), buf.readUuid(), MobTalkNet.readText(buf), MobTalkNet.readText(buf), MobTalkNet.readText(buf));
    }
    @Override public Id<MobLabelS2C> getId() { return ID; }
}
