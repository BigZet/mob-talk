package io.github.divineblock.game;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** A line the god says, for the client to voice. A non-zero id asks the client to answer with {@link VoiceStartPayload}
 * once the voice starts, so the act can wait until the god has spoken. */
public record GodLinePayload(int id, String line) implements CustomPayload {
    public static final Id<GodLinePayload> ID = new Id<>(Identifier.of("divineblock", "god_line"));
    public static final PacketCodec<RegistryByteBuf, GodLinePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, GodLinePayload::id, PacketCodecs.string(2400), GodLinePayload::line, GodLinePayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
