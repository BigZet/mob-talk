package io.github.divineblock.game;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** A piece of the god's voice synthesised by the server ({@link ServerGodVoice}): 24 kHz mono PCM, in order. {@code stream}
 * names the line on this server; {@code ack} is the id the praying player's client answers with {@link VoiceStartPayload}
 * (0 for everyone else who hears it); {@code seq} counts the pieces from 0, {@code last} closes the line. */
public record GodAudioPayload(int stream, int ack, int seq, boolean last, byte[] pcm) implements CustomPayload {
    public static final Id<GodAudioPayload> ID = new Id<>(Identifier.of("divineblock", "god_audio"));
    /** Half a second of speech per piece, well under the packet limit. */
    public static final int CHUNK = io.github.divineblock.core.GodVoice.SAMPLE_RATE;
    public static final PacketCodec<RegistryByteBuf, GodAudioPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, GodAudioPayload::stream, PacketCodecs.VAR_INT, GodAudioPayload::ack, PacketCodecs.VAR_INT, GodAudioPayload::seq,
            PacketCodecs.BOOL, GodAudioPayload::last, PacketCodecs.byteArray(CHUNK * 2), GodAudioPayload::pcm, GodAudioPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
