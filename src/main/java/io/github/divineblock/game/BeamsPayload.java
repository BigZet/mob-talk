package io.github.divineblock.game;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** While the god speaks: beacon beams spinning out of the altar for {@code ticks}, in the colour of what is coming, the
 * music under the verdict ({@link #BLESSING}, {@link #PUNISHMENT} or {@link #NO_MUSIC}) and how deep the sky turns red
 * (0 for none, 1–4 for the wrath groups К2–К5). */
public record BeamsPayload(BlockPos altar, int ticks, int color, int music, int sky) implements CustomPayload {
    public static final int NO_MUSIC = 0, BLESSING = 1, PUNISHMENT = 2;
    public static final Id<BeamsPayload> ID = new Id<>(Identifier.of("divineblock", "beams"));
    public static final PacketCodec<RegistryByteBuf, BeamsPayload> CODEC = PacketCodec.tuple(
            BlockPos.PACKET_CODEC, BeamsPayload::altar, PacketCodecs.VAR_INT, BeamsPayload::ticks, PacketCodecs.INTEGER, BeamsPayload::color,
            PacketCodecs.VAR_INT, BeamsPayload::music, PacketCodecs.VAR_INT, BeamsPayload::sky, BeamsPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
