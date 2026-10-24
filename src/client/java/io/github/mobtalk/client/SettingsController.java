package io.github.mobtalk.client;

import io.github.mobtalk.core.ActionIntent;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.OpenRouterToken;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** What the F7 menu may ask of the running client: microphones, saving the config, the API keys, the selected mob's card. */
final class SettingsController implements MobTalkSettingsScreen.Controller {
    private final MobTalkClient owner;
    SettingsController(MobTalkClient owner) { this.owner = owner; }

    @Override public void listMicrophones(Consumer<List<String>> callback) {
        owner.maintenance.execute(() -> {
            List<String> names;
            try { names = MicrophoneRecorder.devices(); }
            catch (RuntimeException unavailable) { names = List.of(); }
            List<String> result = names;
            owner.client.execute(() -> callback.accept(result));
        });
    }

    /** After a save the server gets the player's personal layer again (names, sex), as at joining. */
    @Override public void save(MobTalkConfig draft, Consumer<String> completion) {
        owner.saveConfig(draft, failure -> {
            if (failure == null) owner.client.execute(() -> ClientNet.sendSettings(owner));
            completion.accept(failure);
        });
    }

    @Override public int talkKeyCode() {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(owner.talkKey);
        return key.getCategory() == InputUtil.Type.KEYSYM ? key.getCode() : GLFW.GLFW_KEY_V;
    }
    @Override public CompletableFuture<OpenRouterToken.Status> keyStatus(OpenRouterToken.Provider provider) {
        return CompletableFuture.supplyAsync(() -> OpenRouterToken.status(owner.configDirectory, provider), owner.maintenance);
    }
    @Override public CompletableFuture<OpenRouterToken.Status> saveKey(OpenRouterToken.Provider provider, String key) {
        return keyChange(provider, () -> OpenRouterToken.store(owner.configDirectory, provider, key));
    }
    @Override public CompletableFuture<OpenRouterToken.Status> removeKey(OpenRouterToken.Provider provider) {
        return keyChange(provider, () -> OpenRouterToken.remove(owner.configDirectory, provider));
    }
    @Override public CompletableFuture<MobCard> selectedCard() { return owner.selectedCard(); }
    @Override public CompletableFuture<MobSnapshot> selectedSnapshot() { return owner.selectedSnapshot(); }
    @Override public CompletableFuture<String> requestMobAction(ActionIntent action) { return owner.requestMobAction(action); }
    @Override public void stopSelectedAction() { owner.stopSelectedAction(); }
    @Override public void stopAllFollowers() { owner.stopAllFollowers(); }
    @Override public CompletableFuture<String> editSelectedCard(String name, String personality, String voiceId, String memory) {
        return owner.editSelectedCard(name, personality, voiceId, memory);
    }
    @Override public CompletableFuture<String> clearSelectedHistory() { return owner.clearSelectedHistory(); }
    @Override public CompletableFuture<String> resetSelectedPersonality() { return owner.resetSelectedPersonality(); }

    @Override public CompletableFuture<List<String>> villageStatus() { return owner.villageStatus(); }
    @Override public void openInfo(net.minecraft.client.gui.screen.Screen parent) { owner.openInfo(parent); }
    @Override public List<String> keyHints() {
        return List.of(hint(owner.talkKey, "mobtalk.settings.keys.talk"), hint(owner.settingsKey, "mobtalk.settings.keys.settings"),
                hint(owner.tradeKey, "mobtalk.settings.keys.trade"), hint(owner.giftKey, "mobtalk.settings.keys.gift"),
                hint(MomentClient.key(), "mobtalk.settings.keys.moment"),
                net.minecraft.text.Text.translatable("mobtalk.settings.keys.commands").getString());
    }
    private static String hint(net.minecraft.client.option.KeyBinding key, String what) {
        return key == null ? "" : key.getBoundKeyLocalizedText().getString() + " — " + net.minecraft.text.Text.translatable(what).getString();
    }

    private interface KeyChange { void run() throws IOException; }

    /** Opening settings already dropped the engine, so the next conversation reads the new key. */
    private CompletableFuture<OpenRouterToken.Status> keyChange(OpenRouterToken.Provider provider, KeyChange change) {
        return CompletableFuture.supplyAsync(() -> {
            try { change.run(); }
            catch (IOException failure) { throw new CompletionException(failure); }
            return OpenRouterToken.status(owner.configDirectory, provider);
        }, owner.maintenance);
    }
}
