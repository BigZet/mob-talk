package io.github.mobtalk.client;

import io.github.mobtalk.game.OfficialContent;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.tooltip.TooltipComponent;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.util.DefaultSkinHelper;

/** The holder's face in the passport's tooltip (track «чиновники», stage 2): drawn from the skin of a player online on this server,
 * else the skin the client has cached for the profile, else the default skin of that UUID. Framed like a photo 3×4. */
final class PassportFace implements TooltipComponent {
    static final int SIZE = 24, PAD = 2;
    private final OfficialContent.Face face;

    private PassportFace(OfficialContent.Face face) { this.face = face; }

    static void register() {
        TooltipComponentCallback.EVENT.register(data -> data instanceof OfficialContent.Face face ? new PassportFace(face) : null);
    }

    @Override public int getHeight() { return SIZE + PAD * 2 + 2; }
    @Override public int getWidth(TextRenderer textRenderer) { return SIZE + PAD * 2; }

    @Override public void drawItems(TextRenderer textRenderer, int x, int y, DrawContext context) {
        context.fill(x, y, x + SIZE + PAD * 2, y + SIZE + PAD * 2, 0xFFE8E2D0); // the photo's white border
        PlayerSkinDrawer.draw(context, skin(), x + PAD, y + PAD, SIZE);
    }

    private SkinTextures skin() {
        MinecraftClient client = MinecraftClient.getInstance();
        PlayerListEntry entry = client.getNetworkHandler() == null ? null : client.getNetworkHandler().getPlayerListEntry(face.id());
        if (entry != null) return entry.getSkinTextures();
        try { return client.getSkinProvider().getSkinTextures(new GameProfile(face.id(), face.name())); }
        catch (RuntimeException e) { return DefaultSkinHelper.getSkinTextures(face.id()); }
    }
}
