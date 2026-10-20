package io.github.divineblock.game;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** The client began voicing line {@code id}, which lasts {@code seconds} (0 when it stays silent: voice off or failed). */
public record VoiceStartPayload(int id, float seconds) implements CustomPayload {
    public static final Id<VoiceStartPayload> ID = new Id<>(Identifier.of("divineblock", "voice_start"));
    public static final PacketCodec<RegistryByteBuf, VoiceStartPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, VoiceStartPayload::id, PacketCodecs.FLOAT, VoiceStartPayload::seconds, VoiceStartPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
