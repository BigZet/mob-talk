package io.github.divineblock.game;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** A line the god just said, for the client to voice. */
public record GodLinePayload(String line) implements CustomPayload {
    public static final Id<GodLinePayload> ID = new Id<>(Identifier.of("divineblock", "god_line"));
    public static final PacketCodec<RegistryByteBuf, GodLinePayload> CODEC = PacketCodec.tuple(PacketCodecs.string(2400), GodLinePayload::line, GodLinePayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
