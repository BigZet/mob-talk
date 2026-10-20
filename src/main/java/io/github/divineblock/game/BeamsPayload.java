package io.github.divineblock.game;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** While the god speaks: beacon beams spinning out of the altar for {@code ticks}, in the colour of what is coming. */
public record BeamsPayload(BlockPos altar, int ticks, int color) implements CustomPayload {
    public static final Id<BeamsPayload> ID = new Id<>(Identifier.of("divineblock", "beams"));
    public static final PacketCodec<RegistryByteBuf, BeamsPayload> CODEC = PacketCodec.tuple(
            BlockPos.PACKET_CODEC, BeamsPayload::altar, PacketCodecs.VAR_INT, BeamsPayload::ticks, PacketCodecs.INTEGER, BeamsPayload::color, BeamsPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
