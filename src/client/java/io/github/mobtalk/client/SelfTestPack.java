package io.github.mobtalk.client;

import com.google.gson.JsonParser;
import io.github.mobtalk.audio.PcmWav;
import io.github.mobtalk.core.SelfTestSpeech;
import io.github.mobtalk.core.SpeechPreview;
import io.github.mobtalk.core.WhisperClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/** Самотест всей сборки ({@code -Only pack}): все моды загружены вместе, горячие клавиши не пересекаются, меню паузы ведёт в
 * общий хаб, из него открываются настройки каждого мода, блоки каждого мода ставятся в мир, жители в облике людей, и Whisper
 * один на всех (Mob Talk и Божественный блок ходят на один адрес). Без ИИ-чата и без платной озвучки; снимки в screenshots. */
final class SelfTestPack {
    private static final List<String> EXPECTED = List.of("mobtalk", "divineblock", "rusflora", "parrotcages", "viewerparrots",
            "ctov", "lithostitched", "waterframes", "watermedia", "fabric-api");
    private static final List<String> CONTENT = List.of("divineblock", "rusflora", "parrotcages");
    private final SelfTest test;
    private final int fx, fy, fz;
    private Runnable next = () -> { };

    SelfTestPack(SelfTest test, int stageX, int stageY, int stageZ) { this.test = test; this.fx = stageX + 60; this.fy = stageY; this.fz = stageZ; }

    private String name() { return test.owner.client.player.getName().getString(); }
    private String at(int dx, int dy, int dz) { return (fx + dx) + " " + (fy + dy) + " " + (fz + dz); }

    void run(Runnable then) {
        next = then;
        test.log("pack stages: mods, hotkeys, menus, content, Whisper");
        mods();
        keys();
        registries();
        content();
    }

    private void mods() {
        FabricLoader loader = FabricLoader.getInstance();
        Map<String, String> found = new TreeMap<>();
        loader.getAllMods().forEach(mod -> found.put(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString()));
        for (String id : EXPECTED) {
            if (found.containsKey(id)) test.log("mod " + id + " " + found.get(id));
            else if (id.equals("ctov") || id.equals("lithostitched") || id.startsWith("water")) test.log("mod " + id + " is not in this instance");
            else test.fail("mod " + id + " is missing");
        }
    }

    private void keys() {
        KeyBinding[] all = test.owner.client.options.allKeys;
        List<String> clashes = new ArrayList<>();
        for (int i = 0; i < all.length; i++)
            for (int j = i + 1; j < all.length; j++)
                if (!all[i].isUnbound() && all[i].equals(all[j]) && !(vanilla(all[i]) && vanilla(all[j])))
                    clashes.add(all[i].getTranslationKey() + " = " + all[j].getTranslationKey() + " (" + all[i].getBoundKeyLocalizedText().getString() + ")");
        for (KeyBinding key : all)
            if (!vanilla(key)) test.log("hotkey " + key.getCategory() + " / " + key.getTranslationKey() + " = " + (key.isUnbound() ? "unbound" : key.getBoundKeyLocalizedText().getString()));
        clashes.forEach(clash -> test.log("hotkey clash: " + clash));
        test.check(clashes.isEmpty(), "no mod hotkey shares a key with another binding (" + clashes.size() + " clashes)");
    }

    private static boolean vanilla(KeyBinding key) { return key.getCategory().startsWith("key.categories.") && !key.getCategory().contains("divineblock"); }

    private void registries() {
        for (String namespace : CONTENT) {
            long blocks = Registries.BLOCK.getIds().stream().filter(id -> id.getNamespace().equals(namespace)).count();
            long items = Registries.ITEM.getIds().stream().filter(id -> id.getNamespace().equals(namespace)).count();
            test.check(items > 0, namespace + " registered " + blocks + " blocks and " + items + " items");
        }
        long structures = test.server().submit(() -> test.server().getRegistryManager().get(RegistryKeys.STRUCTURE).getIds().stream()
                .filter(id -> id.getNamespace().equals("ctov")).count()).join();
        if (FabricLoader.getInstance().isModLoaded("ctov")) test.check(structures > 0, "CTOV structures are in the world's registry (" + structures + ")");
        long divineStructures = test.server().submit(() -> test.server().getRegistryManager().get(RegistryKeys.STRUCTURE).getIds().stream()
                .filter(id -> id.getNamespace().equals("divineblock")).count()).join();
        test.log("divineblock structures: " + divineStructures);
    }

    // ---- Content: a row of each mod's blocks on a grass strip, villagers and a parrot beside them.
    private void content() {
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.command("effect give " + name() + " minecraft:slow_falling 30 1 true");
        test.command("tp " + name() + " " + at(0, 1, 9) + " 180 15");
        test.await("pause: the showcase chunks load", 5, () -> false, () -> {
            test.command("fill " + at(-14, -1, -6) + " " + at(14, -1, 10) + " minecraft:grass_block");
            test.command("fill " + at(-14, 0, -6) + " " + at(14, 8, 10) + " minecraft:air");
            // A row per mod, a step apart, seen from above: every block of the pack in one picture.
            int z = -5;
            for (String namespace : CONTENT) {
                List<Identifier> ids = Registries.BLOCK.getIds().stream().filter(id -> id.getNamespace().equals(namespace))
                        .filter(id -> !id.getPath().contains("fire") && !id.getPath().contains("portal")).sorted().limit(12).toList();
                test.log(namespace + " showcase: " + ids);
                int x = -11;
                for (Identifier id : ids) { test.command("setblock " + at(x, 0, z) + " " + id); x += 2; }
                z += 3;
            }
            test.command("summon minecraft:villager " + at(-4, 0, 5) + " {NoAI:1b,PersistenceRequired:1b,Rotation:[0f,0f],VillagerData:{profession:\"minecraft:farmer\",type:\"minecraft:plains\",level:2}}");
            test.command("summon minecraft:villager " + at(0, 0, 5) + " {NoAI:1b,PersistenceRequired:1b,Rotation:[0f,0f],VillagerData:{profession:\"minecraft:librarian\",type:\"minecraft:taiga\",level:2}}");
            test.command("summon minecraft:parrot " + at(4, 0, 5) + " {NoAI:1b,PersistenceRequired:1b,Variant:1}");
            test.command("tp " + name() + " " + at(0, 5, 12) + " 180 32");
            test.await("pause: the showcase settles", 10, () -> false, () -> shot("mobtalk-pack-content.png", () -> {
                // Villagers as people, close up: faces, hair and clothes are easy to judge here.
                test.command("tp " + name() + " " + at(-2, 0, 8) + " 180 5");
                test.await("pause: close to the villagers", 3, () -> false, () -> shot("mobtalk-pack-villagers.png", this::menus));
            }));
        });
    }

    // ---- Menus: the pause menu shows one hub; the hub opens each mod's own screen.
    private void menus() {
        var client = test.owner.client;
        client.setScreen(new GameMenuScreen(true));
        test.await("pause: pause menu", 2, () -> false, () -> {
            Screen menu = client.currentScreen;
            List<String> labels = labels(menu);
            test.log("pause menu buttons: " + labels);
            test.check(labels.contains("Моды сборки"), "the pause menu has the pack hub button");
            test.check(!labels.contains("Божественный блок"), "Divine Block does not add a second corner button");
            shot("mobtalk-pack-pause.png", () -> {
                press(menu, "Моды сборки");
                test.await("hub open", 5, () -> client.currentScreen instanceof ModHubScreen, () -> {
                    Screen hub = client.currentScreen;
                    test.log("hub buttons: " + labels(hub));
                    shot("mobtalk-pack-hub.png", () -> openFromHub(hub, "Mob Talk", "MobTalkSettingsScreen", "mobtalk-pack-f7.png",
                            () -> openFromHub(hub, "Божественный блок", "DivineSettingsScreen", "mobtalk-pack-f9.png",
                                    () -> openFromHub(hub, "Попугаи", "", "mobtalk-pack-parrots.png", this::whisper))));
                });
            });
        });
    }

    /** Presses the hub button starting with {@code label}; expects a screen whose class name contains {@code screenClass}
     * (empty: any screen), screenshots it and returns to the game. */
    private void openFromHub(Screen hub, String label, String screenClass, String file, Runnable then) {
        var client = test.owner.client;
        if (!labels(hub).stream().anyMatch(text -> text.startsWith(label))) { test.log("hub has no " + label + " button"); then.run(); return; }
        client.setScreen(hub);
        test.await("pause: hub again", 1, () -> false, () -> {
            press(client.currentScreen, label);
            test.await(label + " screen open", 6, () -> client.currentScreen != null && !(client.currentScreen instanceof ModHubScreen)
                    && (screenClass.isEmpty() || client.currentScreen.getClass().getSimpleName().contains(screenClass)), () -> {
                test.check(client.currentScreen != null, label + " opened " + (client.currentScreen == null ? "nothing" : client.currentScreen.getClass().getSimpleName()));
                shot(file, () -> { client.setScreen(null); test.await("pause: back in game", 1, () -> false, then); });
            });
        });
    }

    private static List<String> labels(Screen screen) {
        return screen == null ? List.of() : Screens.getButtons(screen).stream().map(widget -> widget.getMessage().getString()).toList();
    }

    private void press(Screen screen, String label) {
        for (ClickableWidget widget : Screens.getButtons(screen))
            if (widget.getMessage().getString().startsWith(label) && widget instanceof PressableWidget button) { button.onPress(); return; }
        test.fail("no button " + label + " on " + screen.getClass().getSimpleName());
    }

    // ---- One Whisper for the pack: Mob Talk loads it, Divine Block points at the same address, a phrase is recognised.
    private void whisper() {
        String mobTalkUrl = test.owner.config().whisperUrl;
        Path divine = FabricLoader.getInstance().getConfigDir().resolve("divineblock.json");
        try {
            if (Files.isRegularFile(divine)) {
                String divineUrl = JsonParser.parseString(Files.readString(divine)).getAsJsonObject().get("whisperUrl").getAsString();
                test.check(mobTalkUrl.equals(divineUrl), "Divine Block uses the same Whisper as Mob Talk (" + divineUrl + ")");
            } else test.log("no config/divineblock.json yet: Divine Block uses its default " + mobTalkUrl);
        } catch (Exception unreadable) { test.fail("config/divineblock.json unreadable: " + unreadable.getMessage()); }
        CompletableFuture<Void> warm = test.owner.warmWhisper();
        test.await("Whisper loaded", 600, warm::isDone, () -> {
            if (warm.isCompletedExceptionally()) { test.fail("Whisper did not load: " + warm.exceptionNow().getMessage()); next.run(); return; }
            test.log("Whisper ready on " + mobTalkUrl);
            var config = test.owner.config().copy();
            config.ttsProvider = "openrouter";
            String phrase = "Как тебя зовут и что ты здесь делаешь?";
            CompletableFuture<String> heard = CompletableFuture.supplyAsync(() -> {
                try {
                    byte[] pcm24 = SelfTestSpeech.fixture(SelfTest.fixtures(), config.defaultVoice, phrase,
                            () -> SpeechPreview.synthesize(config, test.owner.configDirectory, config.defaultVoice, phrase));
                    int samples = pcm24.length / 2 * 2 / 3;
                    byte[] pcm16 = new byte[samples * 2];
                    for (int i = 0; i < samples; i++) { int j = (int) ((long) i * 3 / 2) * 2; pcm16[i * 2] = pcm24[j]; pcm16[i * 2 + 1] = pcm24[j + 1]; }
                    try (WhisperClient whisper = new WhisperClient(test.owner.config())) { return whisper.transcribe(PcmWav.encode(pcm16), "Разговор с жителем"); }
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            test.await("the shared Whisper recognised a phrase", 120, heard::isDone, () -> {
                if (heard.isCompletedExceptionally()) test.fail("Whisper transcription: " + heard.exceptionNow().getMessage());
                else { test.log("Whisper heard: " + heard.join()); test.check(heard.join().toLowerCase().contains("зовут"), "the shared Whisper recognised the phrase"); }
                next.run();
            });
        });
    }

    private void shot(String file, Runnable then) {
        test.await("pause: before the screenshot", 1, () -> false, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }
}
