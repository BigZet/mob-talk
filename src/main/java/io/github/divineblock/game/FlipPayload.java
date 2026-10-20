package io.github.divineblock.game;

import java.util.List;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** These entities are drawn upside down for {@code ticks} (the god's jest). */
public record FlipPayload(List<Integer> entities, int ticks) implements CustomPayload {
    public static final Id<FlipPayload> ID = new Id<>(Identifier.of("divineblock", "flip"));
    public static final PacketCodec<RegistryByteBuf, FlipPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), FlipPayload::entities, PacketCodecs.VAR_INT, FlipPayload::ticks, FlipPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
