package io.github.divineblock.client;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.ApiKeys;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.game.GameHooks;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;

/** The god's settings (F9), the first page of the panel: temper, the ceiling of wrath, protection, Jev, the speech provider,
 * the voice and effects, churches and the keys. In single player it also shows how the god sees you. Keys are stored by
 * {@link ApiKeys}, never in the JSON. The other pages («Голос и микрофон», «Кары», «Боссы») are in the sidebar. */
public final class DivineSettingsScreen extends DivinePanel {
    private static final String[] TIERS = { "знамения", "малое", "среднее", "большое", "апокалипсис" };
    private String openRouterKey = "", veniceKey = "";
    private volatile String keyStatus = "";
    private String soul;

    /** Opened by F9, the pause menu and Mob Talk's hub (by reflection): keep this constructor. */
    public DivineSettingsScreen(Screen parent) { this(parent, ServerSettings.forScreen(DivineConfig.load(DivineBlockMod.configFile()))); }

    DivineSettingsScreen(Screen parent, DivineConfig config) { super(parent, config); }

    @Override protected int section() { return 0; }

    @Override protected void init() {
        if (soul == null) { soul = readSoul(); refreshKeyStatus(); }
        super.init();
    }

    @Override protected void content() {
        serverNote();
        heading("Характер бога");
        server(cycle("Нрав", "Как бог отвечает на молитвы и обиды.", Arrays.asList(DivineConfig.Personality.values()), config.personality, p -> p.label, v -> config.personality = v));
        server(cycle("Потолок кар", "Самая тяжёлая кара, до которой бог может дойти.", List.of(0, 1, 2, 3, 4), config.maxTier, v -> v + " — " + TIERS[v], v -> config.maxTier = v));
        server(cycle("Прихоть бога", "Насколько часто бог поступает по своей прихоти, а не по заслугам.", List.of(0, 1, 2, 3), config.whimWeight,
                v -> v == 0 ? "нет" : String.valueOf(v), v -> config.whimWeight = v));
        server(toggle("Может промолчать", "Бог иногда не отвечает на молитву.", !config.disabled.contains("ignore"),
                v -> { config.disabled.remove("ignore"); if (!v) config.disabled.add("ignore"); }));
        server(cycle("Пауза алтаря", "Сколько ждать между молитвами у алтаря.", List.of(0, 10, 20, 60), nearest(config.cooldownSeconds, 0, 10, 20, 60),
                v -> v + " с", v -> config.cooldownSeconds = v));
        server(toggle("Визеры и драконы", "Разрешить богу призывать визеров и драконов.", config.allowWither || config.allowDragon,
                v -> { config.allowWither = v; config.allowDragon = v; }));
        server(toggle("Беречь блоки", "Кары не ломают постройки игрока.", config.protectBlocks, v -> config.protectBlocks = v));

        heading("Речь и решения");
        server(cycle("Речь", "Кто пишет слова бога: OpenRouter или Venice с запасным OpenRouter.", Arrays.asList(DivineConfig.Provider.values()), config.provider,
                p -> p == DivineConfig.Provider.VENICE ? "Venice → OpenRouter" : "OpenRouter", v -> config.provider = v));
        server(toggle("Решатель Jev", "Решения бога принимает отдельная быстрая модель.", config.useJev, v -> config.useJev = v));
        server(cycle("Длина речи перед карой", "Короткая — одна-две фразы (до 25 слов), и рулетка кары сразу за ними; обычная — развёрнутая реплика. Милость и шутки не сокращаются.",
                Arrays.asList(DivineConfig.SpeechLength.values()), config.wrathSpeech, v -> v == DivineConfig.SpeechLength.SHORT ? "короткая" : "обычная",
                v -> config.wrathSpeech = v));
        server(toggle("Отладка", "Показывать числа: как бог считает твою душу.", config.debug, v -> { config.debug = v; rebuild(); }));

        heading("Голос и эффекты");
        // Off, plain, or ringing as in a temple (reverb and a quiet echo).
        cycle("Голос бога", "Озвучка ответов: выключена, без эха или с эхом храма. Эхо и микрофон — на странице «Голос и микрофон».",
                List.of(0, 1, 2), !config.voice ? 0 : config.voiceTemple ? 2 : 1, v -> new String[] { "выкл", "без эха", "с эхом" }[v],
                v -> { config.voice = v > 0; config.voiceTemple = v == 2; });
        List<String> voices = Arrays.stream(io.github.divineblock.core.GodVoice.VOICES).map(v -> v[0]).toList();
        cycle("Голос", "Чьим голосом говорит бог.", voices, voices.contains(config.voiceId) ? config.voiceId : voices.get(0), DivineSettingsScreen::voiceName, v -> config.voiceId = v);
        // The god's drawn effects: off leaves the vanilla particles of before.
        cycle("Эффекты", "Нарисованные эффекты бога; «выкл» оставляет обычные частицы.", List.of(0, 1, 2, 3), config.effects,
                v -> new String[] { "выкл", "низко", "средне", "высоко" }[v], v -> config.effects = v);
        toggle("Тряска камеры", "Камера дрожит от гнева бога.", config.cameraShake, v -> config.cameraShake = v);

        heading("Мир");
        // Churches in newly generated chunks and how rare they are (one per N x N chunks at most).
        server(toggle("Церкви в мире", "Церкви появляются в новых чанках.", config.churches, v -> config.churches = v));
        server(cycle("Редкость церквей", "Не больше одной церкви на столько чанков по каждой стороне.", List.of(32, 48, 64, 80, 100, 150, 256),
                nearest(config.churchSpacing, 32, 48, 64, 80, 100, 150, 256), v -> v + " чанков", v -> config.churchSpacing = v));
        // Which of the three churches new chunks get: all mixed, or one kind only to compare them.
        server(cycle("Вид церквей", "Какие церкви ставятся в новых чанках: все вперемешку или только один вид, чтобы сравнить.",
                List.of("", "STONE", "WOOD", "SLAVIC"), config.churchVariant,
                v -> switch (v) { case "STONE" -> "каменная"; case "WOOD" -> "деревянная"; case "SLAVIC" -> "Slavic Church"; default -> "все вперемешку"; },
                v -> config.churchVariant = v));

        heading("Ключи");
        if (ServerSettings.remote()) note("На сервере речь и решения бога идут через ключи хостера; твои ключи нужны только для голоса бога у тебя.", MUTED);
        keyRow("Ключ OpenRouter", ApiKeys.Provider.OPENROUTER, openRouterKey, v -> openRouterKey = v);
        keyRow("Ключ Venice", ApiKeys.Provider.VENICE, veniceKey, v -> veniceKey = v);
        info("Состояние ключей", "Ключи хранятся зашифрованными отдельно от настроек.", () -> keyStatus);

        if (!soul.isBlank()) {
            heading("Как бог видит тебя");
            for (String line : soul.split("\n")) {
                if (line.isBlank()) continue;
                boolean numbers = line.startsWith("•") || line.startsWith("Итог") || line.startsWith("Последний");
                if (numbers && !config.debug) continue;
                note(line, numbers ? DIM : 0xFFFF55);
            }
        }
    }

    /** A masked key field and «Сохранить»: an empty field removes the stored key. */
    private void keyRow(String label, ApiKeys.Provider provider, String value, java.util.function.Consumer<String> changed) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, 0, 0, 100, 20, Text.literal(label));
        field.setMaxLength(512);
        field.setText(value);
        field.setPlaceholder(Text.literal("вставьте ключ").withColor(DIM));
        field.setRenderTextProvider((text, start) -> net.minecraft.text.OrderedText.styledForwardsVisitedString("•".repeat(text.length()), net.minecraft.text.Style.EMPTY));
        field.setChangedListener(changed);
        ButtonWidget store = ButtonWidget.builder(Text.literal("Сохранить"), b -> { storeKey(provider, field.getText()); field.setText(""); }).size(60, 20).build();
        control(label, "Вставьте ключ и нажмите «Сохранить»; пустое поле удаляет сохранённый ключ. Ключ не показывается.", field, store);
    }

    /** Encrypting with DPAPI runs PowerShell: off the render thread. */
    private void storeKey(ApiKeys.Provider provider, String text) {
        String key = text.strip();
        if (provider == ApiKeys.Provider.OPENROUTER) openRouterKey = ""; else veniceKey = "";
        keyStatus = "Сохранение…";
        CompletableFuture.runAsync(() -> {
            try {
                if (key.isEmpty()) ApiKeys.remove(DivineBlockMod.configDirectory(), provider);
                else ApiKeys.store(DivineBlockMod.configDirectory(), provider, key);
                keyStatus = provider.label + (key.isEmpty() ? ": ключ удалён." : ": ключ сохранён.");
            } catch (IOException failure) { keyStatus = failure.getMessage(); }
            refreshKeyStatus();
        });
    }

    private void refreshKeyStatus() {
        CompletableFuture.runAsync(() -> {
            ApiKeys.Status open = ApiKeys.status(DivineBlockMod.configDirectory(), ApiKeys.Provider.OPENROUTER);
            ApiKeys.Status venice = ApiKeys.status(DivineBlockMod.configDirectory(), ApiKeys.Provider.VENICE);
            String line = "OpenRouter: " + describe(open) + "; Venice: " + describe(venice);
            keyStatus = keyStatus.isBlank() || keyStatus.startsWith("OpenRouter: ") || keyStatus.startsWith("Сохранение") ? line : keyStatus + "  " + line;
        });
    }
    private static String describe(ApiKeys.Status status) {
        return switch (status.source()) {
            case ENVIRONMENT -> "из переменной среды " + status.hint(); case FILE -> "файл " + status.hint();
            case ENCRYPTED -> "сохранён"; case NONE -> "нет";
        };
    }

    /** How the god sees the player, read on the integrated server's own thread. */
    private String readSoul() {
        MinecraftClient client = MinecraftClient.getInstance();
        IntegratedServer server = client.getServer();
        if (server == null || client.player == null) return "";
        var player = client.player.getUuid();
        try { return server.submit(() -> GameHooks.describe(server, player)).get(1, TimeUnit.SECONDS); }
        catch (Exception unavailable) { return ""; }
    }

    /** Saves the config and returns to the screen F9 was opened from. */
    @Override public void close() {
        save();
        if (client != null) client.setScreen(exit);
    }

    private static String voiceName(String id) {
        for (String[] voice : io.github.divineblock.core.GodVoice.VOICES) if (voice[0].equals(id)) return voice[1];
        return id;
    }

    private static int nearest(int value, int... options) {
        int best = options[0];
        for (int option : options) if (Math.abs(option - value) < Math.abs(best - value)) best = option;
        return best;
    }
}
