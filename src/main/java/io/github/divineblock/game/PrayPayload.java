package io.github.divineblock.game;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** A prayer from the client: which altar and the words. */
public record PrayPayload(BlockPos altar, String text) implements CustomPayload {
    public static final int MAX_TEXT = 600;
    public static final Id<PrayPayload> ID = new Id<>(Identifier.of("divineblock", "pray"));
    public static final PacketCodec<RegistryByteBuf, PrayPayload> CODEC = PacketCodec.tuple(
            BlockPos.PACKET_CODEC, PrayPayload::altar, PacketCodecs.string(MAX_TEXT * 4), PrayPayload::text, PrayPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
