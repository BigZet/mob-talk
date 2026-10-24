package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** OBS сохранил повтор для момента {@code id}: путь к файлу на компьютере игрока попадает в момент. */
public record MomentReplayC2S(String id, String path) implements CustomPayload {
    public static final Id<MomentReplayC2S> ID = MobTalkNet.payloadId("moment_replay");
    public static final PacketCodec<PacketByteBuf, MomentReplayC2S> CODEC = MobTalkNet.codec(MomentReplayC2S::write, MomentReplayC2S::read);

    private void write(PacketByteBuf buf) { MobTalkNet.writeText(buf, id); MobTalkNet.writeText(buf, path); }
    private static MomentReplayC2S read(PacketByteBuf buf) { return new MomentReplayC2S(MobTalkNet.readText(buf), MobTalkNet.readText(buf)); }
    @Override public Id<MomentReplayC2S> getId() { return ID; }
}
