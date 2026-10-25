package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** OBS на компьютере игрока: эфир ({@code stream}) или запись ({@code record}) идёт {@code elapsedMillis} или остановлен. Шлётся при
 * смене состояния, при входе и раз в минуту — сервер по нему держит таймкоды моментов. {@code everything} — игроку нужны главы
 * и повторы всех моментов сервера, а не только своих (F7). */
public record RecStateC2S(String output, boolean active, long elapsedMillis, boolean everything) implements CustomPayload {
    public static final Id<RecStateC2S> ID = MobTalkNet.payloadId("rec_state");
    public static final PacketCodec<PacketByteBuf, RecStateC2S> CODEC = MobTalkNet.codec(RecStateC2S::write, RecStateC2S::read);

    private void write(PacketByteBuf buf) {
        MobTalkNet.writeText(buf, output); buf.writeBoolean(active); buf.writeVarLong(Math.max(0, elapsedMillis)); buf.writeBoolean(everything);
    }
    private static RecStateC2S read(PacketByteBuf buf) {
        return new RecStateC2S(MobTalkNet.readText(buf), buf.readBoolean(), buf.readVarLong(), buf.readBoolean());
    }
    @Override public Id<RecStateC2S> getId() { return ID; }
}
