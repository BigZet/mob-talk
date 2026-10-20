package io.github.divineblock.client;

import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.VoiceEffects;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** The god's reverb and echo: a preset to start from, five knobs to tune it, and "Прослушать" to hear the result on a
 * sample line. Edits the settings screen's config; that screen saves it when closed. */
final class VoiceSettingsScreen extends Screen {
    private final Screen parent;
    private final DivineConfig config;
    private volatile String status = "";

    VoiceSettingsScreen(Screen parent, DivineConfig config) { super(Text.literal("Голос и микрофон")); this.parent = parent; this.config = config; }

    @Override protected void init() {
        // Two columns: the microphone and the voice on the left, the hall's knobs on the right.
        int column = 150, gap = 8, left = this.width / 2 - column - gap / 2, right = this.width / 2 + gap / 2, row = 23, top = 30;
        int y = top;
        java.util.List<String> microphones = new java.util.ArrayList<>();
        microphones.add("");
        microphones.addAll(MicrophoneRecorder.devices());
        if (!microphones.contains(config.microphoneName)) microphones.add(config.microphoneName);
        addDrawableChild(CyclingButtonWidget.<String>builder(name -> Text.literal(name.isEmpty() ? "системный" : shorten(name))).values(microphones)
                .initially(config.microphoneName).build(left, y, column, 20, Text.literal("Микрофон"), (b, name) -> config.microphoneName = name));
        y += row;
        addDrawableChild(ButtonWidget.builder(Text.literal("Проверить микрофон"), b -> {
            status = "Говори 4 секунды…";
            VoicePrayer.testMicrophone(config, line -> status = line);
        }).dimensions(left, y, column, 20).build());
        y += row + 8;
        VoiceEffects.Preset current = null;
        for (VoiceEffects.Preset preset : VoiceEffects.Preset.values()) if (preset.settings.equals(config.voiceEffects())) current = preset;
        addDrawableChild(CyclingButtonWidget.<VoiceEffects.Preset>builder(p -> Text.literal(p.label))
                .values(VoiceEffects.Preset.values()).initially(current == null ? VoiceEffects.Preset.CHAPEL : current)
                .build(left, y, column, 20, Text.literal("Пресет"), (b, preset) -> { config.voiceEffects(preset.settings); clearAndInit(); }));
        y += row;
        addDrawableChild(ButtonWidget.builder(Text.literal("Прослушать голос"), b -> {
            if (!config.voice) { status = "Голос бога выключен в настройках."; return; }
            status = "Звучит…";
            GodVoicePlayer.preview(client, config, line -> status = line);
        }).dimensions(left, y, column, 20).build());

        y = top;
        addDrawableChild(slider(right, y, column, "Зал", 0, 100, () -> config.voiceRoomSize, v -> config.voiceRoomSize = v, v -> v + "%"));
        y += row;
        addDrawableChild(slider(right, y, column, "Реверб", 0, 100, () -> config.voiceReverb, v -> config.voiceReverb = v, v -> v + "%"));
        y += row;
        addDrawableChild(slider(right, y, column, "Время эха", 80, 1000, () -> config.voiceEchoMs, v -> config.voiceEchoMs = v, v -> v + " мс"));
        y += row;
        addDrawableChild(slider(right, y, column, "Эхо", 0, 100, () -> config.voiceEchoLevel, v -> config.voiceEchoLevel = v, v -> v == 0 ? "выкл" : v + "%"));
        y += row;
        addDrawableChild(slider(right, y, column, "Повторы", 0, 100, () -> config.voiceEchoRepeats, v -> config.voiceEchoRepeats = v, v -> v + "%"));
        addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), b -> close()).dimensions(this.width / 2 - 100, this.height - 28, 200, 20).build());
    }

    private static String shorten(String name) { return name.length() > 18 ? name.substring(0, 17) + "…" : name; }

    private static SliderWidget slider(int x, int y, int width, String name, int min, int max, IntSupplier get, IntConsumer set, IntFunction<String> shown) {
        return new SliderWidget(x, y, width, 20, Text.empty(), (get.getAsInt() - min) / (double) (max - min)) {
            { updateMessage(); }
            private int current() { return (int) Math.round(min + value * (max - min)); }
            @Override protected void updateMessage() { setMessage(Text.literal(name + ": " + shown.apply(current()))); }
            @Override protected void applyValue() { set.accept(current()); }
        };
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title.copy().formatted(Formatting.GOLD), this.width / 2, 12, 0xFFFFFF);
        if (!status.isBlank()) context.drawCenteredTextWithShadow(textRenderer, Text.literal(status).formatted(Formatting.GRAY), this.width / 2, 30 + 23 * 5 + 10, 0xFFFFFF);
    }

    @Override public void close() { if (client != null) client.setScreen(parent); }
}
