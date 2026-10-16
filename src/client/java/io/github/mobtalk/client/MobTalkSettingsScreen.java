package io.github.mobtalk.client;

import io.github.mobtalk.core.MobTalkConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** A private draft remains local until the controller successfully saves it. */
final class MobTalkSettingsScreen extends Screen {
    interface Controller {
        void listMicrophones(Consumer<List<String>> callback);
        void save(MobTalkConfig draft, Consumer<String> completion);
        int talkKeyCode();
    }

    private final Screen parent;
    private final Controller controller;
    private final MobTalkConfig draft;
    private final String originalMicrophone;
    private final List<Label> labels = new ArrayList<>();
    private List<String> microphones = List.of();
    private CyclingButtonWidget<String> microphonePicker;
    private ButtonWidget refreshButton;
    private int tab;
    private int left;
    private int contentWidth;
    private boolean enumerated;
    private boolean listing;
    private boolean saving;
    private Text status = Text.empty();
    private boolean error;

    MobTalkSettingsScreen(Screen parent, MobTalkConfig initial, Controller controller) {
        super(Text.translatable("mobtalk.settings.title"));
        this.parent = parent;
        this.controller = controller;
        this.draft = initial.copy();
        this.originalMicrophone = initial.microphoneName == null ? "" : initial.microphoneName;
    }

    @Override protected void init() {
        labels.clear();
        contentWidth = Math.min(520, width - 32);
        left = (width - contentWidth) / 2;
        int tabWidth = (contentWidth - 8) / 3;
        String[] tabs = {"main", "ai", "whisper"};
        for (int i = 0; i < tabs.length; i++) {
            int selectedTab = i;
            ButtonWidget button = button("mobtalk.settings.tab." + tabs[i], left + i * (tabWidth + 4), 36,
                    tabWidth, ignored -> { tab = selectedTab; clearAndInit(); });
            button.active = i != tab && !saving;
        }
        switch (tab) {
            case 1 -> ai();
            case 2 -> whisper();
            default -> main();
        }
        int footerWidth = Math.min(200, (contentWidth - 8) / 2);
        button("mobtalk.settings.save", width / 2 - footerWidth - 4, height - 28, footerWidth, ignored -> save());
        button("gui.cancel", width / 2 + 4, height - 28, footerWidth, ignored -> close());
        if (saving) setControlsActive(false);
        if (!enumerated) refreshMicrophones();
    }

    private void main() {
        label("mobtalk.settings.microphone", left, 66, contentWidth);
        List<String> options = microphoneOptions();
        microphonePicker = addDrawableChild(CyclingButtonWidget.<String>builder(this::microphoneLabel)
                .values(options).initially(draft.microphoneName == null ? "" : draft.microphoneName).omitKeyText()
                .build(left, 77, contentWidth, 20, Text.translatable("mobtalk.settings.microphone"), (button, value) -> {
                    draft.microphoneName = value;
                    updateMicrophoneTooltip();
                }));
        updateMicrophoneTooltip();
        int half = (contentWidth - 6) / 2;
        refreshButton = button("mobtalk.settings.refresh", left, 101, half, ignored -> refreshMicrophones());
        refreshButton.active = !listing && !saving;
        ButtonWidget test = button("mobtalk.settings.test", left + half + 6, 101, half,
                ignored -> client.setScreen(new MicrophoneTestScreen(this, draft.copy(), controller.talkKeyCode())));
        test.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.test.tooltip")));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(draft.enabled)
                .build(left, 125, contentWidth, 20, Text.translatable("mobtalk.settings.enabled"),
                        (button, value) -> draft.enabled = value));
        slider("mobtalk.settings.recording", left, 149, half, 1, 15, draft.maxRecordingSeconds,
                value -> draft.maxRecordingSeconds = value);
        slider("mobtalk.settings.reply", left + half + 6, 149, half, 5, 45, draft.maxReplyWords,
                value -> draft.maxReplyWords = value);
        // Distance retains a configured fractional value until the user moves this slider.
        addDrawableChild(new IntegerSlider(left, 173, contentWidth, "mobtalk.settings.distance", 2, 64,
                draft.maxDistance, value -> draft.maxDistance = value));
    }

    private void ai() {
        field("mobtalk.settings.llm", left, 66, contentWidth, draft.llmModel, 160, value -> draft.llmModel = value);
        field("mobtalk.settings.voice", left, 105, contentWidth, draft.defaultVoice, 160, value -> draft.defaultVoice = value);
        int half = (contentWidth - 6) / 2;
        slider("mobtalk.settings.history", left, 149, half, 1, 6, draft.historyTurns, value -> draft.historyTurns = value);
        slider("mobtalk.settings.memory", left + half + 6, 149, half, 200, 1200, draft.maxMemoryChars,
                value -> draft.maxMemoryChars = value);
        slider("mobtalk.settings.timeout", left, 173, contentWidth, 5, 90, draft.requestTimeoutSeconds,
                value -> draft.requestTimeoutSeconds = value);
    }

    private void whisper() {
        int half = (contentWidth - 6) / 2;
        field("mobtalk.settings.whisper.model", left, 66, half, draft.whisperModel, 160, value -> draft.whisperModel = value);
        field("mobtalk.settings.whisper.language", left + half + 6, 66, half, draft.whisperLanguage, 24,
                value -> draft.whisperLanguage = value);
        List<String> devices = new ArrayList<>(List.of("auto", "cpu", "cuda"));
        if (draft.whisperDevice != null && !devices.contains(draft.whisperDevice)) devices.add(draft.whisperDevice);
        addDrawableChild(CyclingButtonWidget.<String>builder(Text::literal).values(devices)
                .initially(draft.whisperDevice == null ? "auto" : draft.whisperDevice)
                .build(left, 116, half, 20, Text.translatable("mobtalk.settings.whisper.device"),
                        (button, value) -> draft.whisperDevice = value));
        addDrawableChild(CyclingButtonWidget.onOffBuilder(draft.whisperAutoStart)
                .build(left + half + 6, 116, half, 20, Text.translatable("mobtalk.settings.whisper.autostart"),
                        (button, value) -> draft.whisperAutoStart = value));
        TextFieldWidget url = field("mobtalk.settings.whisper.url", left, 149, contentWidth, draft.whisperUrl, 128,
                value -> draft.whisperUrl = value);
        url.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.whisper.url.tooltip")));
    }

    private TextFieldWidget field(String key, int x, int y, int fieldWidth, String initial, int maximum,
                                  Consumer<String> changed) {
        label(key, x, y, fieldWidth);
        TextFieldWidget field = addDrawableChild(new TextFieldWidget(textRenderer, x, y + 11, fieldWidth, 20,
                Text.translatable(key)));
        // Retain even unusually long pre-existing values when this field is not edited.
        field.setMaxLength(Math.max(maximum, initial == null ? 0 : initial.length()));
        field.setText(initial == null ? "" : initial);
        field.setChangedListener(changed);
        return field;
    }

    private void label(String key, int x, int y, int labelWidth) {
        labels.add(new Label(Text.translatable(key), x, y, labelWidth));
    }

    private ButtonWidget button(String key, int x, int y, int buttonWidth, ButtonWidget.PressAction action) {
        return addDrawableChild(ButtonWidget.builder(Text.translatable(key), action).dimensions(x, y, buttonWidth, 20).build());
    }

    private void slider(String key, int x, int y, int sliderWidth, int min, int max, int current, IntConsumer changed) {
        addDrawableChild(new IntegerSlider(x, y, sliderWidth, key, min, max, current, changed));
    }

    private List<String> microphoneOptions() {
        List<String> values = new ArrayList<>();
        values.add("");
        for (String name : microphones) if (name != null && !name.isBlank() && !values.contains(name)) values.add(name);
        if (!originalMicrophone.isBlank() && !values.contains(originalMicrophone)) values.add(originalMicrophone);
        if (draft.microphoneName != null && !draft.microphoneName.isBlank() && !values.contains(draft.microphoneName))
            values.add(draft.microphoneName);
        return values;
    }

    private Text microphoneLabel(String name) {
        Text full = name.isBlank() ? Text.translatable("mobtalk.settings.microphone.default")
                : !microphones.contains(name) && enumerated && !listing
                    ? Text.translatable("mobtalk.settings.microphone.missing", name) : Text.literal(name);
        String value = full.getString();
        if (textRenderer.getWidth(value) <= contentWidth - 24) return full;
        return Text.literal(textRenderer.trimToWidth(value, contentWidth - 36) + "…");
    }

    private void updateMicrophoneTooltip() {
        String name = draft.microphoneName == null ? "" : draft.microphoneName;
        Text text = name.isBlank() ? Text.translatable("mobtalk.settings.microphone.default")
                : !microphones.contains(name) && enumerated && !listing
                    ? Text.translatable("mobtalk.settings.microphone.missing", name) : Text.literal(name);
        microphonePicker.setTooltip(Tooltip.of(text));
    }

    private void refreshMicrophones() {
        if (listing || saving) return;
        enumerated = true;
        listing = true;
        status = Text.translatable("mobtalk.settings.listing");
        error = false;
        if (refreshButton != null) refreshButton.active = false;
        try {
            controller.listMicrophones(names -> client.execute(() -> {
                microphones = names == null ? List.of() : List.copyOf(names);
                listing = false;
                status = names == null ? Text.translatable("mobtalk.settings.list.failed")
                        : names.isEmpty() ? Text.translatable("mobtalk.settings.list.empty") : Text.empty();
                error = names == null || names.isEmpty();
                if (client.currentScreen == this && !saving) clearAndInit();
            }));
        } catch (RuntimeException unavailable) {
            listing = false;
            status = Text.translatable("mobtalk.settings.list.failed");
            error = true;
            if (refreshButton != null) refreshButton.active = true;
        }
    }

    private void save() {
        if (saving) return;
        saving = true;
        error = false;
        status = Text.translatable("mobtalk.settings.saving");
        setControlsActive(false);
        try {
            controller.save(draft.copy(), failure -> client.execute(() -> {
                saving = false;
                if (failure == null) {
                    if (client.currentScreen == this) client.setScreen(parent);
                    return;
                }
                status = Text.literal(cleanMessage(failure));
                error = true;
                if (client.currentScreen == this) clearAndInit();
            }));
        } catch (RuntimeException unavailable) {
            saving = false;
            error = true;
            status = Text.translatable("mobtalk.settings.save.failed");
            clearAndInit();
        }
    }

    private void setControlsActive(boolean active) {
        for (var child : children()) if (child instanceof ClickableWidget widget) widget.active = active;
    }

    @Override public void close() {
        if (!saving) client.setScreen(parent);
    }

    @Override public boolean shouldCloseOnEsc() { return !saving; }
    @Override public boolean shouldPause() { return true; }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFF);
        for (Label label : labels) context.drawTextWithShadow(textRenderer,
                textRenderer.trimToWidth(label.text.getString(), label.width), label.x, label.y, 0xD5D5D5);
        if (!status.getString().isEmpty()) {
            String value = status.getString();
            context.drawCenteredTextWithShadow(textRenderer,
                    textRenderer.trimToWidth(value, contentWidth), width / 2, height - 42, error ? 0xFF9999 : 0xBBBBBB);
            if (mouseY >= height - 44 && mouseY < height - 30 && textRenderer.getWidth(value) > contentWidth)
                context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(status, Math.min(300, width - 24)), mouseX, mouseY);
        }
    }

    static String cleanMessage(String message) {
        String clean = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ').strip();
        return clean.length() > 500 ? clean.substring(0, 500) : clean;
    }

    private record Label(Text text, int x, int y, int width) { }

    private static final class IntegerSlider extends SliderWidget {
        private final String key;
        private final int min;
        private final int max;
        private final IntConsumer changed;
        private double displayed;

        IntegerSlider(int x, int y, int width, String key, int min, int max, double current, IntConsumer changed) {
            super(x, y, width, 20, Text.empty(), Math.max(0, Math.min(1, (current - min) / (max - min))));
            this.key = key;
            this.min = min;
            this.max = max;
            this.changed = changed;
            this.displayed = current;
            updateMessage();
        }

        @Override protected void updateMessage() {
            String amount = displayed == Math.rint(displayed) ? Integer.toString((int) displayed)
                    : String.format(java.util.Locale.ROOT, "%.1f", displayed);
            setMessage(Text.translatable(key, amount));
        }

        @Override protected void applyValue() {
            int amount = min + (int) Math.round(value * (max - min));
            displayed = amount;
            changed.accept(amount);
        }
    }
}
