package io.github.divineblock.client;

import io.github.divineblock.game.AltarBlock;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Client side: the prayer screen on the altar and the settings key (F9 by default). */
public final class DivineBlockClient implements ClientModInitializer {
    private static KeyBinding settingsKey;

    @Override public void onInitializeClient() {
        AltarBlock.openPrayer = pos -> MinecraftClient.getInstance().setScreen(new PrayerScreen(pos));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.divineblock.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.divineblock"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (settingsKey.wasPressed()) if (client.currentScreen == null) client.setScreen(new DivineSettingsScreen(null));
        });
    }
}
