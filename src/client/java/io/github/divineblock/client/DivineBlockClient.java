package io.github.divineblock.client;

import io.github.divineblock.game.AltarBlock;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Client side: the prayer screen on the altar, praying aloud (hold R), the god's voice and the settings key (F9). */
public final class DivineBlockClient implements ClientModInitializer {
    private static KeyBinding settingsKey, prayKey;

    /** The pause menu already has Mob Talk's "Моды сборки" hub, which lists this mod too. */
    static boolean hasHub(net.minecraft.client.gui.screen.Screen screen) {
        return net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen).stream().anyMatch(b -> b.getMessage().getString().contains("Моды сборки"));
    }

    @Override public void onInitializeClient() {
        AltarBlock.openPrayer = pos -> MinecraftClient.getInstance().setScreen(new PrayerScreen(pos));
        SelfTest.register();
        BossSelfTest.register();
        FxTest.register();
        ChurchSelfTest.register();
        ZonesSelfTest.register();
        CruelTest.register();
        CemeteryTest.register();
        RouletteSelfTest.register();
        net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.INSTANCE.putBlocks(net.minecraft.client.render.RenderLayer.getCutout(),
                io.github.divineblock.cemetery.CemeteryBlocks.all().toArray(net.minecraft.block.Block[]::new));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.divineblock.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.divineblock"));
        prayKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.divineblock.pray", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.divineblock"));
        ServerSettings.register();
        GodVoicePlayer.register();
        AltarBeams.register();
        ChantPlayer.register();
        SkyTint.register();
        Flipped.register();
        TornadoRenderer.register();
        io.github.divineblock.client.boss.BossClient.register();
        io.github.divineblock.client.weapon.WeaponClient.register();
        io.github.divineblock.client.weapon.WeaponSelfTest.register();
        io.github.divineblock.client.fx.FxEngine.register();
        BloodWater.register();
        RouletteHud.register();
        // A button in the pause menu, one row above Mob Talk's in the same corner. A Mob Talk with the "Моды сборки" hub opens
        // DivineSettingsScreen(Screen) from there instead: checked a moment after the menu is built, once every mod added its buttons.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            if (!(screen instanceof net.minecraft.client.gui.screen.GameMenuScreen)) return;
            minecraft.execute(() -> {
                if (minecraft.currentScreen != screen || hasHub(screen)) return;
                net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen).add(net.minecraft.client.gui.widget.ButtonWidget
                        .builder(net.minecraft.text.Text.literal("Божественный блок"), button -> minecraft.setScreen(new DivineSettingsScreen(screen)))
                        .dimensions(width - 128, height - 50, 120, 20).build());
            });
        });
        // Whisper's model is loaded once when the game starts and stays loaded until it closes.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client -> WhisperHelper.warmUp(io.github.divineblock.DivineBlockMod.config()));
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> VoicePrayer.shutdown());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            VoicePrayer.tick(client, prayKey);
            while (settingsKey.wasPressed()) if (client.currentScreen == null) client.setScreen(new DivineSettingsScreen(null));
        });
    }
}
