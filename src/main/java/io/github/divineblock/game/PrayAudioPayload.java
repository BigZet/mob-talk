package io.github.divineblock.game;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Молитва голосом для распознавания на сервере (путь {@code server}): алтарь и WAV 16 кГц моно не длиннее 15 с (480 КБ).
 * {@code test} — проверка микрофона из настроек: сервер только отвечает, что расслышал. Приёмник есть только на сервере с
 * распознаванием Mob Talk, поэтому {@code canSend} у клиента и значит «сервер умеет». */
public record PrayAudioPayload(BlockPos altar, byte[] wav, boolean test) implements CustomPayload {
    public static final int MAX_WAV = 44 + io.github.divineblock.core.PcmWav.MAX_PCM_BYTES;
    public static final Id<PrayAudioPayload> ID = new Id<>(Identifier.of("divineblock", "pray_audio"));
    public static final PacketCodec<RegistryByteBuf, PrayAudioPayload> CODEC = PacketCodec.tuple(
            BlockPos.PACKET_CODEC, PrayAudioPayload::altar, PacketCodecs.byteArray(MAX_WAV), PrayAudioPayload::wav,
            PacketCodecs.BOOL, PrayAudioPayload::test, PrayAudioPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
