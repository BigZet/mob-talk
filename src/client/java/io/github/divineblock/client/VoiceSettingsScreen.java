package io.github.divineblock.client;

import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.VoiceEffects;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/** «Голос и микрофон» of the panel: the microphone and its test, the god's reverb and echo — a preset to start from, five knobs
 * to tune it, and «Прослушать» to hear the result on a sample line — and the chants' volume. Edits the panel's config. */
final class VoiceSettingsScreen extends DivinePanel {
    private volatile String status = "";
    /** Listed once per page: a search or a preset rebuilds the rows. */
    private List<String> devices;

    VoiceSettingsScreen(Screen parent, DivineConfig config) { super(parent, config); }

    @Override protected int section() { return 1; }

    @Override protected void content() {
        heading("Микрофон");
        List<String> microphones = new ArrayList<>();
        microphones.add("");
        if (devices == null) devices = MicrophoneRecorder.devices();
        microphones.addAll(devices);
        if (!microphones.contains(config.microphoneName)) microphones.add(config.microphoneName);
        cycle("Микрофон", "Устройство записи для молитв голосом; «системный» — микрофон Windows по умолчанию.", microphones, config.microphoneName,
                name -> name.isEmpty() ? "системный" : name, name -> config.microphoneName = name);
        action("Проверка", "Запись 4 секунды и распознанный текст.", "Проверить микрофон", () -> {
            status = "Говори 4 секунды…";
            VoicePrayer.testMicrophone(config, line -> status = line);
        });
        info("Результат", "", () -> status.isBlank() ? "—" : status);

        heading("Голос бога");
        VoiceEffects.Preset current = null;
        for (VoiceEffects.Preset preset : VoiceEffects.Preset.values()) if (preset.settings.equals(config.voiceEffects())) current = preset;
        cycle("Пресет", "Готовый набор зала и эха; ручки ниже его подстраивают.", Arrays.asList(VoiceEffects.Preset.values()),
                current == null ? VoiceEffects.Preset.CHAPEL : current, p -> p.label, preset -> { config.voiceEffects(preset.settings); rebuild(); });
        action("Прослушать", "Пробная фраза бога с текущими настройками.", "Прослушать голос", () -> {
            if (!config.voice) { status = "Голос бога выключен в настройках."; return; }
            status = "Звучит…";
            GodVoicePlayer.preview(client, config, line -> status = line);
        });
        slider("Песнопения", "Громкость песнопений у алтаря.", 0, 200, () -> config.chantVolume, v -> config.chantVolume = v, v -> v == 0 ? "выкл" : v + "%");

        heading("Зал и эхо");
        slider("Зал", "Размер зала: чем больше, тем дольше звучит отражение.", 0, 100, () -> config.voiceRoomSize, v -> config.voiceRoomSize = v, v -> v + "%");
        slider("Реверб", "Доля отражённого звука.", 0, 100, () -> config.voiceReverb, v -> config.voiceReverb = v, v -> v + "%");
        slider("Время эха", "Задержка эха.", 80, 1000, () -> config.voiceEchoMs, v -> config.voiceEchoMs = v, v -> v + " мс");
        slider("Эхо", "Громкость эха.", 0, 100, () -> config.voiceEchoLevel, v -> config.voiceEchoLevel = v, v -> v == 0 ? "выкл" : v + "%");
        slider("Повторы", "Сколько раз эхо повторяется, затухая.", 0, 100, () -> config.voiceEchoRepeats, v -> config.voiceEchoRepeats = v, v -> v + "%");
    }

    private void slider(String name, String help, int min, int max, IntSupplier get, IntConsumer set, IntFunction<String> shown) {
        control(name, help, new SliderWidget(0, 0, 100, 20, Text.literal(name), (get.getAsInt() - min) / (double) (max - min)) {
            { updateMessage(); }
            private int current() { return (int) Math.round(min + value * (max - min)); }
            @Override protected void updateMessage() { setMessage(Text.literal(shown.apply(current()))); }
            @Override protected void applyValue() { set.accept(current()); }
        });
    }
}
