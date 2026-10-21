package io.github.divineblock.game;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** One of the god's drawn effects ({@link Fx}): its kind, where, a second point (a target or a direction), a colour, a size
 * and a length in ticks. The client draws it with its own particles; nothing here touches the world. */
public record FxPayload(int kind, double x, double y, double z, double tx, double ty, double tz, int color, float size, int ticks)
        implements CustomPayload {
    public static final Id<FxPayload> ID = new Id<>(Identifier.of("divineblock", "fx"));
    public static final PacketCodec<RegistryByteBuf, FxPayload> CODEC = PacketCodec.of((payload, buf) -> {
        buf.writeVarInt(payload.kind);
        buf.writeDouble(payload.x); buf.writeDouble(payload.y); buf.writeDouble(payload.z);
        buf.writeDouble(payload.tx); buf.writeDouble(payload.ty); buf.writeDouble(payload.tz);
        buf.writeInt(payload.color); buf.writeFloat(payload.size); buf.writeVarInt(payload.ticks);
    }, buf -> new FxPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
            buf.readDouble(), buf.readInt(), buf.readFloat(), buf.readVarInt()));
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
