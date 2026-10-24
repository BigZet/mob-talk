package io.github.mobtalk.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** Данные оверлея для OBS (идея З3, docs/overlay.md): сервер шлёт их только тем, у кого оверлей включён и есть право по роли.
 * {@code json} — блоки {@code god}, {@code verdict}, {@code souls}, {@code active}, {@code events}, {@code lines}; неизвестный блок
 * клиент пропускает. Пустой объект {@code {}} — данных больше нет (выключили, нет права). */
public record OverlayStateS2C(String json) implements CustomPayload {
    public static final Id<OverlayStateS2C> ID = MobTalkNet.payloadId("overlay_state");
    public static final PacketCodec<PacketByteBuf, OverlayStateS2C> CODEC = MobTalkNet.codec(OverlayStateS2C::write, OverlayStateS2C::read);

    private void write(PacketByteBuf buf) { MobTalkNet.writeJson(buf, json); }
    private static OverlayStateS2C read(PacketByteBuf buf) { return new OverlayStateS2C(MobTalkNet.readJson(buf)); }
    @Override public Id<OverlayStateS2C> getId() { return ID; }
}
