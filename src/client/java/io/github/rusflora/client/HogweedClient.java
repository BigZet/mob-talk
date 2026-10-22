package io.github.rusflora.client;

import io.github.rusflora.hogweed.Hogweed;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** The hogweed's leaves, umbels and the dry umbel are drawn with see-through gaps; a key (unbound by default, the pack's mod
 * hub is the usual way in) opens the settings; the self-test starts here when asked for. */
final class HogweedClient {
    private HogweedClient() { }

    static void register() {
        BlockRenderLayerMap.INSTANCE.putBlock(Hogweed.BLOCK, RenderLayer.getCutout());
        BlockRenderLayerMap.INSTANCE.putBlock(Hogweed.DRY_UMBEL, RenderLayer.getCutout());
        KeyBinding settings = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.rusflora.hogweed_settings", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, "key.categories.rusflora"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (settings.wasPressed()) if (client.currentScreen == null) client.setScreen(new HogweedSettingsScreen(null));
        });
        HogweedSelfTest.register();
    }
}
