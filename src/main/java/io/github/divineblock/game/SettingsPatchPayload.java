package io.github.divineblock.game;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** An operator changed the server's settings in his F9: the changed server fields as a JSON object. Players who are not
 * operators are refused on the server. */
public record SettingsPatchPayload(String json) implements CustomPayload {
    public static final Id<SettingsPatchPayload> ID = new Id<>(Identifier.of("divineblock", "settings_patch"));
    public static final PacketCodec<RegistryByteBuf, SettingsPatchPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.string(32_000), SettingsPatchPayload::json, SettingsPatchPayload::new);
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
