package io.github.divineblock.game;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** A tornado's base each tick, for the client to draw its funnel; {@code alive = false} lets the funnel dissolve. */
public record TornadoPayload(int id, double x, double y, double z, int stage, boolean alive) implements CustomPayload {
    public static final Id<TornadoPayload> ID = new Id<>(Identifier.of("divineblock", "tornado"));
    public static final PacketCodec<RegistryByteBuf, TornadoPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, TornadoPayload::id, PacketCodecs.DOUBLE, TornadoPayload::x, PacketCodecs.DOUBLE, TornadoPayload::y,
            PacketCodecs.DOUBLE, TornadoPayload::z, PacketCodecs.VAR_INT, TornadoPayload::stage, PacketCodecs.BOOL, TornadoPayload::alive,
            TornadoPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
