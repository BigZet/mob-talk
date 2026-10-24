package io.github.divineblock.game;

import java.util.UUID;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** A spin of the punishment roulette to draw ({@link RouletteService}): whose punishment, its name and one reason, the symbol
 * the reels stop on, how heavy it is, the seed of the strips, the mode, the tease, the server tick it started at and whether
 * it spares the sinner by chance (the third reel slips onto a dove). The
 * client only draws it; the act falls on the server's own ticks. */
public record RouletteSpinPayload(int id, UUID sinner, String name, String miracle, String title, String reason, int symbol, int severity,
                                  long seed, int mode, boolean tease, long start, boolean mercy) implements CustomPayload {
    public static final Id<RouletteSpinPayload> ID = new Id<>(Identifier.of("divineblock", "roulette_spin"));
    public static final PacketCodec<RegistryByteBuf, RouletteSpinPayload> CODEC = PacketCodec.of(RouletteSpinPayload::write, RouletteSpinPayload::read);

    private void write(RegistryByteBuf buf) {
        buf.writeVarInt(id); buf.writeUuid(sinner); buf.writeString(name, 64); buf.writeString(miracle, 64); buf.writeString(title, 120);
        buf.writeString(reason, 240); buf.writeVarInt(symbol); buf.writeVarInt(severity); buf.writeLong(seed); buf.writeVarInt(mode);
        buf.writeBoolean(tease); buf.writeLong(start); buf.writeBoolean(mercy);
    }
    private static RouletteSpinPayload read(RegistryByteBuf buf) {
        return new RouletteSpinPayload(buf.readVarInt(), buf.readUuid(), buf.readString(64), buf.readString(64), buf.readString(120),
                buf.readString(240), buf.readVarInt(), buf.readVarInt(), buf.readLong(), buf.readVarInt(), buf.readBoolean(), buf.readLong(),
                buf.readBoolean());
    }

    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
