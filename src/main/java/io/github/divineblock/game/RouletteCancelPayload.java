package io.github.divineblock.game;

import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** The spin {@code id} stops before its end: {@code pardon} — the sinner repented, the reels jam and turn into doves
 * («Помилован»); otherwise he left, died or went to another world and the machine simply goes away. */
public record RouletteCancelPayload(int id, boolean pardon, long at) implements CustomPayload {
    public static final Id<RouletteCancelPayload> ID = new Id<>(Identifier.of("divineblock", "roulette_cancel"));
    public static final PacketCodec<net.minecraft.network.RegistryByteBuf, RouletteCancelPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, RouletteCancelPayload::id, PacketCodecs.BOOL, RouletteCancelPayload::pardon, PacketCodecs.VAR_LONG, RouletteCancelPayload::at,
            RouletteCancelPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
