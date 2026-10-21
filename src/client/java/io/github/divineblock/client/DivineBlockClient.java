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

    @Override public void onInitializeClient() {
        AltarBlock.openPrayer = pos -> MinecraftClient.getInstance().setScreen(new PrayerScreen(pos));
        SelfTest.register();
        BossSelfTest.register();
        FxTest.register();
        ChurchSelfTest.register();
        CruelTest.register();
        CemeteryTest.register();
        net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap.INSTANCE.putBlocks(net.minecraft.client.render.RenderLayer.getCutout(),
                io.github.divineblock.cemetery.CemeteryBlocks.all().toArray(net.minecraft.block.Block[]::new));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.divineblock.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9, "key.categories.divineblock"));
        prayKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.divineblock.pray", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.divineblock"));
        GodVoicePlayer.register();
        AltarBeams.register();
        ChantPlayer.register();
        SkyTint.register();
        Flipped.register();
        TornadoRenderer.register();
        io.github.divineblock.client.boss.BossClient.register();
        io.github.divineblock.client.fx.FxEngine.register();
        BloodWater.register();
        // A button in the pause menu, as Mob Talk has, one row above its "Mob Talk" button in the same corner.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((minecraft, screen, width, height) -> {
            if (screen instanceof net.minecraft.client.gui.screen.GameMenuScreen)
                net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen).add(net.minecraft.client.gui.widget.ButtonWidget
                        .builder(net.minecraft.text.Text.literal("Божественный блок"), button -> minecraft.setScreen(new DivineSettingsScreen(screen)))
                        .dimensions(width - 128, height - 50, 120, 20).build());
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> VoicePrayer.shutdown());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            VoicePrayer.tick(client, prayKey);
            while (settingsKey.wasPressed()) if (client.currentScreen == null) client.setScreen(new DivineSettingsScreen(null));
        });
    }
}
