package io.github.divineblock.client;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.ApiKeys;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.game.GameHooks;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** The god's settings (F9): temper, the ceiling of wrath, protection, Jev, the speech provider and the keys. In single player
 * it also shows how the god sees you. Keys are stored by {@link ApiKeys}, never in the JSON. */
public final class DivineSettingsScreen extends Screen {
    private final Screen parent;
    private final DivineConfig config = DivineConfig.load(DivineBlockMod.configFile());
    private TextFieldWidget openRouterKey, veniceKey;
    private volatile String keyStatus = "";
    private String soul = "";

    public DivineSettingsScreen(Screen parent) { super(Text.literal("Божественный блок")); this.parent = parent; }

    @Override protected void init() {
        // Eight rows of settings and the keys: on a small window (GUI height under 280) the rows close up so the keys stay above the bottom buttons.
        int column = 150, gap = 8, left = this.width / 2 - column - gap / 2, right = this.width / 2 + gap / 2, y = 28, row = row();
        addDrawableChild(CyclingButtonWidget.<DivineConfig.Personality>builder(p -> Text.literal(p.label)).values(DivineConfig.Personality.values())
                .initially(config.personality).build(left, y, column, 20, Text.literal("Нрав"), (b, v) -> config.personality = v));
        addDrawableChild(CyclingButtonWidget.<Integer>builder(v -> Text.literal(v + " — " + new String[] { "знамения", "малое", "среднее", "большое", "апокалипсис" }[v]))
                .values(List.of(0, 1, 2, 3, 4)).initially(config.maxTier).build(right, y, column, 20, Text.literal("Потолок"), (b, v) -> config.maxTier = v));
        y += row;
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.allowWither || config.allowDragon).build(left, y, column, 20, Text.literal("Визеры и драконы"),
                (b, v) -> { config.allowWither = v; config.allowDragon = v; }));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.protectBlocks).build(right, y, column, 20, Text.literal("Беречь блоки"), (b, v) -> config.protectBlocks = v));
        y += row;
        addDrawableChild(CyclingButtonWidget.<Integer>builder(v -> Text.literal(v == 0 ? "нет" : String.valueOf(v))).values(List.of(0, 1, 2, 3))
                .initially(config.whimWeight).build(left, y, column, 20, Text.literal("Прихоть бога"), (b, v) -> config.whimWeight = v));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(!config.disabled.contains("ignore")).build(right, y, column, 20, Text.literal("Может промолчать"),
                (b, v) -> { config.disabled.remove("ignore"); if (!v) config.disabled.add("ignore"); }));
        y += row;
        addDrawableChild(CyclingButtonWidget.<Integer>builder(v -> Text.literal(v + " с")).values(List.of(0, 10, 20, 60))
                .initially(nearest(config.cooldownSeconds, 0, 10, 20, 60)).build(left, y, column, 20, Text.literal("Пауза алтаря"), (b, v) -> config.cooldownSeconds = v));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.useJev).build(right, y, column, 20, Text.literal("Решатель Jev"), (b, v) -> config.useJev = v));
        y += row;
        addDrawableChild(CyclingButtonWidget.<DivineConfig.Provider>builder(p -> Text.literal(p == DivineConfig.Provider.VENICE ? "Venice → OpenRouter" : "OpenRouter"))
                .values(DivineConfig.Provider.values()).initially(config.provider).build(left, y, column, 20, Text.literal("Речь"), (b, v) -> config.provider = v));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.debug).build(right, y, column, 20, Text.literal("Отладка"), (b, v) -> config.debug = v));
        y += row;
        // Off, plain, or ringing as in a temple (reverb and a quiet echo).
        addDrawableChild(CyclingButtonWidget.<Integer>builder(v -> Text.literal(new String[] { "выкл", "без эха", "с эхом" }[v])).values(List.of(0, 1, 2))
                .initially(!config.voice ? 0 : config.voiceTemple ? 2 : 1).build(left, y, column, 20, Text.literal("Голос бога"),
                        (b, v) -> { config.voice = v > 0; config.voiceTemple = v == 2; }));
        java.util.List<String> voices = java.util.Arrays.stream(io.github.divineblock.core.GodVoice.VOICES).map(v -> v[0]).toList();
        addDrawableChild(CyclingButtonWidget.<String>builder(id -> Text.literal(voiceName(id))).values(voices)
                .initially(voices.contains(config.voiceId) ? config.voiceId : voices.get(0)).build(right, y, column, 20, Text.literal("Голос"), (b, v) -> config.voiceId = v));
        y += row;
        // The god's drawn effects: off leaves the vanilla particles of before.
        addDrawableChild(CyclingButtonWidget.<Integer>builder(v -> Text.literal(new String[] { "выкл", "низко", "средне", "высоко" }[v])).values(List.of(0, 1, 2, 3))
                .initially(config.effects).build(left, y, column, 20, Text.literal("Эффекты"), (b, v) -> config.effects = v));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.cameraShake).build(right, y, column, 20, Text.literal("Тряска камеры"), (b, v) -> config.cameraShake = v));
        y += row;
        // Churches in newly generated chunks and how rare they are (one per N x N chunks at most).
        addDrawableChild(CyclingButtonWidget.onOffBuilder(config.churches).build(left, y, column, 20, Text.literal("Церкви в мире"), (b, v) -> config.churches = v));
        addDrawableChild(CyclingButtonWidget.<Integer>builder(v -> Text.literal(v + " чанков")).values(List.of(32, 48, 64, 80, 100, 150, 256))
                .initially(nearest(config.churchSpacing, 32, 48, 64, 80, 100, 150, 256)).build(right, y, column, 20, Text.literal("Редкость"), (b, v) -> config.churchSpacing = v));
        y += row + keysGap();

        // Both keys on one row: a field and its save button in each column.
        openRouterKey = keyField(left, y, column - 44, "Ключ OpenRouter");
        addDrawableChild(ButtonWidget.builder(Text.literal("Сохр."), b -> storeKey(ApiKeys.Provider.OPENROUTER, openRouterKey)).dimensions(left + column - 40, y, 40, 20).build());
        veniceKey = keyField(right, y, column - 44, "Ключ Venice");
        addDrawableChild(ButtonWidget.builder(Text.literal("Сохр."), b -> storeKey(ApiKeys.Provider.VENICE, veniceKey)).dimensions(right + column - 40, y, 40, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Голос и микрофон…"), b -> client.setScreen(new VoiceSettingsScreen(this, config)))
                .dimensions(this.width / 2 - 154, this.height - 28, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Кары…"), b -> client.setScreen(new WrathSettingsScreen(this, config)))
                .dimensions(this.width / 2 - 50, this.height - 28, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), b -> close()).dimensions(this.width / 2 + 54, this.height - 28, 100, 20).build());
        refreshKeyStatus();
        soul = readSoul();
    }

    private int row() { return this.height < 280 ? 20 : 22; }
    private int keysGap() { return this.height < 280 ? 2 : 6; }

    private TextFieldWidget keyField(int x, int y, int width, String placeholder) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, width, 20, Text.literal(placeholder));
        field.setMaxLength(512);
        field.setPlaceholder(Text.literal(placeholder).formatted(Formatting.DARK_GRAY));
        field.setRenderTextProvider((text, start) -> net.minecraft.text.OrderedText.styledForwardsVisitedString("•".repeat(text.length()), net.minecraft.text.Style.EMPTY));
        addDrawableChild(field);
        return field;
    }

    /** Encrypting with DPAPI runs PowerShell: off the render thread. */
    private void storeKey(ApiKeys.Provider provider, TextFieldWidget field) {
        String key = field.getText().strip();
        field.setText("");
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
            keyStatus = keyStatus.isBlank() || keyStatus.startsWith("OpenRouter: ") ? line : keyStatus + "  " + line;
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

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title.copy().formatted(Formatting.GOLD), this.width / 2, 12, 0xFFFFFF);
        int y = 28 + row() * 8 + keysGap() + 22 + 2;
        if (y <= this.height - 38) context.drawTextWithShadow(textRenderer, Text.literal(keyStatus).formatted(Formatting.GRAY), this.width / 2 - 154, y, 0xFFFFFF);
        y += 12;
        String[] lines = soul.split("\n");
        int shown = 0;
        for (String line : lines) {
            if (line.isBlank()) continue;
            boolean numbers = line.startsWith("•") || line.startsWith("Итог") || line.startsWith("Последний");
            if (numbers && !config.debug) continue;
            if (y > this.height - 38 || shown++ > 12) break;
            context.drawTextWithShadow(textRenderer, Text.literal(line).formatted(numbers ? Formatting.DARK_GRAY : Formatting.YELLOW), this.width / 2 - 154, y, 0xFFFFFF);
            y += 10;
        }
    }

    @Override public void close() {
        try { config.save(DivineBlockMod.configFile()); DivineBlockMod.reloadConfig(); io.github.divineblock.client.fx.FxQuality.reload(); }
        catch (IOException failure) { DivineBlockMod.LOG.warn("Божественный блок: настройки не сохранены", failure); }
        if (client != null) client.setScreen(parent);
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
