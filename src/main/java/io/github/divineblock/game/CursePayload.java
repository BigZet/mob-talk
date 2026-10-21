package io.github.divineblock.game;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** The lingering curses the client must show itself ({@link Curses#FLAG_BLOOD}: the water runs red). */
public record CursePayload(int flags) implements CustomPayload {
    public static final Id<CursePayload> ID = new Id<>(Identifier.of("divineblock", "curses"));
    public static final PacketCodec<RegistryByteBuf, CursePayload> CODEC = PacketCodec.tuple(PacketCodecs.VAR_INT, CursePayload::flags, CursePayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
