package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Реплика моба всем в радиусе; {@code toYou} — адресована этому игроку; {@code clipId} 0 — без звука. */
public record MobSpeechS2C(int mobId, UUID mob, String speaker, String text, boolean toYou, int clipId, double range) implements CustomPayload {
    public static final Id<MobSpeechS2C> ID = MobTalkNet.payloadId("mob_speech");
    public static final PacketCodec<PacketByteBuf, MobSpeechS2C> CODEC = MobTalkNet.codec(MobSpeechS2C::write, MobSpeechS2C::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(mobId); buf.writeUuid(mob); MobTalkNet.writeText(buf, speaker); MobTalkNet.writeText(buf, text); buf.writeBoolean(toYou); buf.writeVarInt(clipId); buf.writeDouble(range);
    }
    private static MobSpeechS2C read(PacketByteBuf buf) {
        return new MobSpeechS2C(buf.readVarInt(), buf.readUuid(), MobTalkNet.readText(buf), MobTalkNet.readText(buf), buf.readBoolean(), buf.readVarInt(), buf.readDouble());
    }
    @Override public Id<MobSpeechS2C> getId() { return ID; }

}
