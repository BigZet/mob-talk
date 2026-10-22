package io.github.divineblock.game;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** The server's own settings of the god ({@code DivineSettings.values}, JSON) for a player's F9: shown read-only unless
 * {@code editable} (the player is an operator). Sent on joining and after every change. */
public record SettingsPayload(String json, boolean editable) implements CustomPayload {
    public static final Id<SettingsPayload> ID = new Id<>(Identifier.of("divineblock", "settings"));
    public static final PacketCodec<RegistryByteBuf, SettingsPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.string(32_000), SettingsPayload::json, PacketCodecs.BOOL, SettingsPayload::editable, SettingsPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
