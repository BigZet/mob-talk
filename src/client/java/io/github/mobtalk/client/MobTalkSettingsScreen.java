package io.github.mobtalk.client;

import com.google.gson.GsonBuilder;
import io.github.mobtalk.core.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.*;
import net.minecraft.text.Text;

/** Five compact native sections; preference/profile edits remain private until Save. */
final class MobTalkSettingsScreen extends Screen {
    interface Controller {
        void listMicrophones(Consumer<List<String>> callback);
        void save(MobTalkConfig draft, Consumer<String> completion);
        int talkKeyCode();
        default CompletableFuture<MobCard> selectedCard() { return CompletableFuture.completedFuture(null); }
        default CompletableFuture<MobSnapshot> selectedSnapshot() { return CompletableFuture.completedFuture(null); }
        default CompletableFuture<String> requestMobAction(ActionIntent action) { return CompletableFuture.completedFuture("Сначала выберите моба."); }
        default void stopSelectedAction() { }
        default void stopAllFollowers() { }
        default CompletableFuture<String> editSelectedCard(String name, String personality, String voiceId, String memory) { return CompletableFuture.completedFuture("Сначала выберите моба."); }
        default CompletableFuture<String> clearSelectedHistory() { return CompletableFuture.completedFuture("Сначала выберите моба."); }
        default CompletableFuture<String> resetSelectedPersonality() { return CompletableFuture.completedFuture("Сначала выберите моба."); }
    }
    static final List<String> WHISPER_MODELS = List.of("small", "medium", "large-v2", "large-v3", "large-v3-turbo");
    private final Screen parent;
    private final Controller controller;
    private final String originalMicrophone;
    private MobTalkConfig draft;
    private final List<Label> labels = new ArrayList<>();
    private final int[] pages = new int[5];
    private List<String> microphones = List.of();
    private boolean enumerated, listing, saving, openingChild, disposed;
    private int tab, left, contentWidth;
    private String species = "minecraft:cow", search = "";
    private int slot;
    private CyclingButtonWidget<String> speciesPicker;
    private TextFieldWidget searchField;
    private boolean noSpeciesMatches, refreshOnTick;
    private CyclingButtonWidget<String> microphonePicker;
    private ButtonWidget refreshButton;
    private Text status = Text.empty();
    private boolean error;
    private VoicePreviewService voices;
    private MobCard card;
    private MobSnapshot snapshot;
    private boolean loadingCard, cardLoaded, dirtyCard, clearHistory, resetPersonality;
    private String cardName = "", cardPersonality = "", cardVoice = "", cardMemory = "";
    private ActionType requestedAction = ActionType.FOLLOW;
    private String requestedTarget = "player";

    MobTalkSettingsScreen(Screen parent, MobTalkConfig initial, Controller controller) {
        super(Text.translatable("mobtalk.settings.title")); this.parent = parent; this.controller = controller;
        draft = initial.copy(); originalMicrophone = initial.microphoneName == null ? "" : initial.microphoneName;
    }

    @Override protected void init() {
        labels.clear(); speciesPicker = null; microphonePicker = null; refreshButton = null;
        contentWidth = Math.min(600, width - 24); left = (width - contentWidth) / 2;
        int tabWidth = (contentWidth - 16) / 5;
        String[] names = {"general", "mic", "characters", "voices", "mob"};
        for (int i = 0; i < names.length; i++) {
            int chosen = i;
            ButtonWidget button = button("mobtalk.settings.tab." + names[i], left + i * (tabWidth + 4), 32, tabWidth,
                    ignored -> { stopPreview(); tab = chosen; status = Text.empty(); error = false; clearAndInit(); });
            button.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.tab." + names[i] + ".full")));
            button.active = i != tab && !saving;
        }
        switch (tab) { case 1 -> microphone(); case 2 -> characters(); case 3 -> voicePage(); case 4 -> selectedMob(); default -> general(); }
        if ((tab == 2 || tab == 3) && noSpeciesMatches) for (var child : children())
            if (child instanceof ClickableWidget widget && widget.getY() >= 86 && widget.getY() <= 165) widget.active = false;
        paging();
        int footer = Math.min(160, (contentWidth - 12) / 3), footerLeft = (width - footer * 3 - 12) / 2;
        button("mobtalk.settings.save", footerLeft, height - 28, footer, ignored -> save());
        button("gui.cancel", footerLeft + footer + 6, height - 28, footer, ignored -> close());
        button("mobtalk.settings.reset", footerLeft + 2 * (footer + 6), height - 28, footer, ignored -> {
            draft = new MobTalkConfig(); dirtyCard = false; clearHistory = false; resetPersonality = false;
            if (card != null) { cardName = card.name(); cardPersonality = card.personality(); cardVoice = card.voiceId(); cardMemory = card.memory(); }
            clearAndInit(); status = Text.translatable("mobtalk.settings.reset.draft"); error = false;
        }).setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.reset.tooltip")));
        if (saving) controls(false);
        if (!enumerated) refreshMicrophones();
    }

    private void paging() {
        int total = pageCount(), y = height - 72;
        ButtonWidget previous = button("mobtalk.settings.page.previous", left, y, 64, ignored -> { pages[tab]--; clearAndInit(); });
        previous.active = pages[tab] > 0 && !saving;
        ButtonWidget next = button("mobtalk.settings.page.next", left + contentWidth - 64, y, 64, ignored -> { pages[tab]++; clearAndInit(); });
        next.active = pages[tab] + 1 < total && !saving;
    }
    private int pageCount() { return switch (tab) { case 1, 3 -> 3; case 2 -> 2; case 4 -> 5; default -> 3; }; }

    private void general() {
        int page = pages[0], half = half();
        if (page == 0) {
            toggle("mobtalk.settings.enabled", left, 62, contentWidth, draft.enabled, value -> draft.enabled = value);
            field("mobtalk.settings.llm", left, 88, contentWidth, draft.llmModel, 160, value -> draft.llmModel = value);
            button("mobtalk.settings.commonPrompt", left, 132, contentWidth, ignored -> edit("mobtalk.settings.commonPrompt", draft.commonPrompt, 8000,
                    value -> draft.commonPrompt = value, ""));
        } else if (page == 1) {
            slider("mobtalk.settings.recording", left, 62, contentWidth, 1, 15, draft.maxRecordingSeconds, value -> draft.maxRecordingSeconds = value);
            slider("mobtalk.settings.reply", left, 86, contentWidth, 5, 120, draft.maxReplyWords, value -> draft.maxReplyWords = value);
            addDrawableChild(new IntegerSlider(left, 110, contentWidth, "mobtalk.settings.distance", 2, 64, draft.maxDistance, value -> draft.maxDistance = value));
            slider("mobtalk.settings.timeout", left, 134, contentWidth, 5, 90, draft.requestTimeoutSeconds, value -> draft.requestTimeoutSeconds = value);
        } else {
            slider("mobtalk.settings.history", left, 62, contentWidth, 1, 64, draft.historyTurns, value -> draft.historyTurns = value);
            slider("mobtalk.settings.memory", left, 86, contentWidth, 200, 4000, draft.maxMemoryChars, value -> draft.maxMemoryChars = value);
            button("mobtalk.settings.connection", left, 116, contentWidth, ignored -> operation(voiceService().connection(draft.copy()), false))
                    .setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.connection.tooltip")));
        }
    }

    private void microphone() {
        int page = pages[1], half = half();
        if (page == 0) {
            label(Text.translatable("mobtalk.settings.microphone"), left, 62, contentWidth);
            List<String> options = new ArrayList<>(List.of(""));
            for (String name : microphones) if (name != null && !name.isBlank() && !options.contains(name)) options.add(name);
            if (!originalMicrophone.isBlank() && !options.contains(originalMicrophone)) options.add(originalMicrophone);
            String current = draft.microphoneName == null ? "" : draft.microphoneName;
            if (!options.contains(current)) options.add(current);
            microphonePicker = addDrawableChild(CyclingButtonWidget.<String>builder(this::microphoneName).values(options).initially(current).omitKeyText()
                    .build(left, 73, contentWidth, 20, Text.translatable("mobtalk.settings.microphone"), (button, value) -> { draft.microphoneName = value; microphoneTooltip(); }));
            microphoneTooltip();
            refreshButton = button("mobtalk.settings.refresh", left, 97, half, ignored -> refreshMicrophones()); refreshButton.active = !listing && !saving;
            button("mobtalk.settings.test", left + half + 6, 97, half, ignored -> open(new MicrophoneTestScreen(this, draft.copy(), controller.talkKeyCode(),
                    value -> { draft.whisperModel = value; status = Text.translatable("mobtalk.settings.model.chosen", value); })))
                    .setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.test.tooltip")));
            cycling("mobtalk.settings.whisper.model", left, 125, contentWidth, modelOptions(), draft.whisperModel, value -> draft.whisperModel = value);
        } else if (page == 1) {
            cycling("mobtalk.settings.whisper.device", left, 62, half, retain(List.of("auto", "cpu", "cuda"), draft.whisperDevice), draft.whisperDevice, value -> draft.whisperDevice = value);
            toggle("mobtalk.settings.whisper.autostart", left + half + 6, 62, half, draft.whisperAutoStart, value -> draft.whisperAutoStart = value);
            field("mobtalk.settings.whisper.language", left, 88, contentWidth, draft.whisperLanguage, 24, value -> draft.whisperLanguage = value);
            field("mobtalk.settings.whisper.url", left, 127, contentWidth, draft.whisperUrl, 128, value -> draft.whisperUrl = value)
                    .setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.whisper.url.tooltip")));
        } else {
            slider("mobtalk.settings.whisper.beam", left, 62, contentWidth, 1, 10, draft.whisperBeamSize, value -> draft.whisperBeamSize = value);
            slider("mobtalk.settings.whisper.vad", left, 86, contentWidth, 150, 1500, draft.whisperVadSilenceMs, value -> draft.whisperVadSilenceMs = value);
            slider("mobtalk.settings.whisper.cpu", left, 110, contentWidth, 1, 16, draft.whisperCpuThreads, value -> draft.whisperCpuThreads = value);
            button("mobtalk.settings.whisper.hotwords", left, 134, contentWidth, ignored -> edit("mobtalk.settings.whisper.hotwords", draft.whisperHotwords, 2000,
                    value -> draft.whisperHotwords = value, new MobTalkConfig().whisperHotwords));
        }
    }
    private List<String> modelOptions() { return retain(WHISPER_MODELS, draft.whisperModel); }

    private void speciesHeader() {
        TextFieldWidget filter = addDrawableChild(new TextFieldWidget(textRenderer, left, 62, contentWidth, 20, Text.translatable("mobtalk.settings.search")));
        searchField = filter;
        filter.setMaxLength(100); filter.setText(search); filter.setPlaceholder(Text.translatable("mobtalk.settings.search"));
        filter.setChangedListener(value -> {
            search = value; String previous = species; boolean hadNone = noSpeciesMatches; rebuildSpeciesPicker();
            if (!previous.equals(species) || hadNone != noSpeciesMatches) { int cursor = filter.getCursor(); clearAndInit(); setInitialFocus(searchField); searchField.setCursor(cursor, false); }
        });
        rebuildSpeciesPicker();
        addDrawableChild(CyclingButtonWidget.<Integer>builder(value -> Text.translatable("mobtalk.settings.archetype", value + 1,
                SpeciesCatalog.effective(species, draft).variants().get(value).name())).values(0, 1, 2).initially(slot).omitKeyText()
                .build(left, 110, contentWidth, 20, Text.translatable("mobtalk.settings.archetype.label"), (button, value) -> { slot = value; clearAndInit(); }));
    }
    private void rebuildSpeciesPicker() {
        if (speciesPicker != null) remove(speciesPicker);
        String needle = search.strip().toLowerCase(Locale.ROOT);
        List<String> types = SpeciesCatalog.all().stream().filter(item -> item.id().contains(needle) || item.name().toLowerCase(Locale.ROOT).contains(needle)).map(SpeciesCatalog.Species::id).toList();
        noSpeciesMatches = types.isEmpty();
        if (types.isEmpty()) types = List.of(species);
        if (!types.contains(species)) species = types.getFirst();
        speciesPicker = addDrawableChild(CyclingButtonWidget.<String>builder(type -> clipped(SpeciesCatalog.get(type).name() + " · " + type, contentWidth - 24))
                .values(types).initially(species).omitKeyText().build(left, 86, contentWidth, 20, Text.translatable("mobtalk.settings.species"),
                        (button, value) -> { species = value; clearAndInit(); }));
        speciesPicker.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.species.tooltip", SpeciesCatalog.get(species).name(), species)));
        if (noSpeciesMatches) { speciesPicker.setMessage(Text.translatable("mobtalk.settings.search.empty")); speciesPicker.active = false; }
    }
    private String overrideKey(String type) { for (String key : draft.speciesOverrides.keySet()) if ((key.contains(":") ? key : "minecraft:" + key).equals(type)) return key; return type; }
    private SpeciesCatalog.Override override(String type) { return draft.speciesOverrides.computeIfAbsent(overrideKey(type), ignored -> new SpeciesCatalog.Override()); }

    private void characters() {
        speciesHeader(); int half = half(); String type = species; int index = slot;
        SpeciesCatalog.Species effective = SpeciesCatalog.effective(type, draft), bundled = SpeciesCatalog.get(type);
        if (pages[2] == 0) {
            button("mobtalk.settings.species.edit", left, 138, half, ignored -> edit("mobtalk.settings.species.edit", effective.prompt(), 8000,
                    value -> override(type).prompt = value, bundled.prompt()));
            button("mobtalk.settings.archetype.edit", left + half + 6, 138, half, ignored -> edit("mobtalk.settings.archetype.edit", effective.variants().get(index).prompt(), 4000,
                    value -> override(type).archetypePrompts[index] = value, bundled.variants().get(index).prompt()));
        } else {
            button("mobtalk.settings.species.read", left, 138, half, ignored -> read("mobtalk.settings.species.read",
                    effective.name() + "\n" + type + "\n\n" + effective.prompt() + "\n\nИнтересы: " + effective.interests() + "\nСтрахи: " + effective.fears()
                    + "\n\n" + effective.variants().get(index).name() + "\n" + effective.variants().get(index).prompt()));
            button("mobtalk.settings.species.reset", left + half + 6, 138, half, ignored -> { draft.speciesOverrides.remove(overrideKey(type)); clearAndInit(); });
        }
    }

    private void voicePage() {
        speciesHeader(); String type = species; int index = slot;
        SpeciesCatalog.Voice voice = SpeciesCatalog.effective(type, draft).variants().get(index).voice();
        if (pages[3] == 0) {
            TextFieldWidget voiceId = field("mobtalk.settings.voice.id", left, 134, contentWidth, voice.id(), 100,
                    value -> override(type).voiceIds[index] = value);
            voiceId.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.voice.id.tooltip")));
        } else if (pages[3] == 1) {
            int third = (contentWidth - 12) / 3;
            ButtonWidget preview = button("mobtalk.settings.voice.preview", left, 138, third, ignored -> operation(voiceService().preview(voice), false));
            preview.active = voice.previewUrl() != null && !voice.previewUrl().isBlank();
            preview.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.voice.preview.tooltip")));
            ButtonWidget test = button("mobtalk.settings.voice.test", left + third + 6, 138, third,
                    ignored -> operation(voiceService().test(draft.copy(), voice.id(), Text.translatable("mobtalk.settings.voice.phrase").getString()), false));
            test.active = voice.id() != null && !voice.id().isBlank(); test.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.voice.test.tooltip")));
            button("mobtalk.settings.voice.stop", left + 2 * (third + 6), 138, third, ignored -> stopPreview());
        } else {
            int half = half();
            button("mobtalk.settings.voice.metadata", left, 138, half, ignored -> read("mobtalk.settings.voice.metadata",
                    voice.name() + "\nID: " + voice.id() + "\nЯзык: " + voice.language() + "\n\n" + voice.description()
                    + "\n\nПревью: " + (voice.previewUrl().isBlank() ? "нет" : voice.previewUrl())
                    + "\n\nПревью библиотеки не подтверждает генерацию OpenRouter v4 Turbo."));
            button("mobtalk.settings.voice.status", left + half + 6, 138, half, ignored -> operation(voiceService().status(voice.id()), false));
        }
    }

    private VoicePreviewService voiceService() { if (voices == null) voices = new VoicePreviewService(client.runDirectory.toPath().resolve("config")); return voices; }
    private void stopPreview() { if (voices != null) voices.stop(); }

    private void selectedMob() {
        if (!cardLoaded && !loadingCard) loadCard();
        if (card == null) {
            label(Text.translatable(loadingCard ? "mobtalk.settings.mob.loading" : "mobtalk.settings.mob.empty"), left, 67, contentWidth);
            button("mobtalk.settings.mob.refresh", left, 110, contentWidth, ignored -> { cardLoaded = false; loadCard(); });
            button("mobtalk.settings.mob.stopAll", left, 138, contentWidth, ignored -> controller.stopAllFollowers());
            return;
        }
        label(Text.literal(cardName + " · " + SpeciesCatalog.get(card.type()).name()), left, 62, contentWidth);
        label(Text.translatable("mobtalk.settings.mob.summary", card.relationship(), card.turns().size(), card.action().type().name()), left, 76, contentWidth);
        int half = half(), page = pages[4];
        if (page == 0) {
            field("mobtalk.settings.mob.name", left, 96, contentWidth, cardName, 120, value -> { cardName = value; dirtyCard = true; });
            button("mobtalk.settings.mob.personality", left, 138, half, ignored -> edit("mobtalk.settings.mob.personality", cardPersonality, 4000,
                    value -> { cardPersonality = value; dirtyCard = true; }, card.personality()));
            button("mobtalk.settings.mob.memory", left + half + 6, 138, half, ignored -> edit("mobtalk.settings.mob.memory", cardMemory, 4000,
                    value -> { cardMemory = value; dirtyCard = true; }, ""));
        } else if (page == 1) {
            List<String> ids = new ArrayList<>();
            for (SpeciesCatalog.Archetype variant : SpeciesCatalog.effective(card.type(), draft).variants()) if (!ids.contains(variant.voice().id())) ids.add(variant.voice().id());
            if (!ids.contains(cardVoice)) ids.add(cardVoice);
            addDrawableChild(CyclingButtonWidget.<String>builder(id -> id.isBlank() ? Text.translatable("mobtalk.settings.voice.unassigned")
                            : clipped(SpeciesCatalog.voice(id).name() + " · " + id, contentWidth - 24)).values(ids).initially(cardVoice).omitKeyText()
                    .build(left, 96, contentWidth, 20, Text.translatable("mobtalk.settings.voice.id"), (button, value) -> { cardVoice = value; dirtyCard = true; }));
            button("mobtalk.settings.mob.clearHistory", left, 120, half, ignored -> { clearHistory = true; status = Text.translatable("mobtalk.settings.mob.clearPending"); });
            button("mobtalk.settings.mob.refresh", left + half + 6, 120, half, ignored -> { dirtyCard = false; clearHistory = false; resetPersonality = false; cardLoaded = false; loadCard(); });
            button("mobtalk.settings.mob.history", left, 144, contentWidth, ignored -> read("mobtalk.settings.mob.history", historyText()));
        } else if (page == 2) {
            List<ActionType> actions = snapshot == null ? List.of(ActionType.FOLLOW, ActionType.FLEE, ActionType.ATTACK) : snapshot.availableActions().stream()
                    .filter(action -> action != ActionType.NONE && action != ActionType.STOP).toList();
            if (actions.isEmpty()) actions = List.of(ActionType.NONE);
            if (!actions.contains(requestedAction)) requestedAction = actions.getFirst();
            addDrawableChild(CyclingButtonWidget.<ActionType>builder(action -> Text.translatable("mobtalk.action." + action.name().toLowerCase(Locale.ROOT)))
                    .values(actions).initially(requestedAction).omitKeyText().build(left, 96, half, 20, Text.translatable("mobtalk.settings.mob.action"), (button, value) -> { requestedAction = value; clearAndInit(); }));
            ButtonWidget request = button("mobtalk.settings.mob.request", left + half + 6, 96, half,
                    ignored -> operation(controller.requestMobAction(new ActionIntent(requestedAction, requestedTarget)), true)); request.active = requestedAction != ActionType.NONE;
            request.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.mob.request.tooltip")));
            List<String> targets = new ArrayList<>(List.of("player"));
            if ((requestedAction == ActionType.ATTACK || requestedAction == ActionType.FLEE) && snapshot != null)
                for (ObservedEntity entity : snapshot.observedEntities())
                    if (entity.visible() || "minecraft:warden".equals(snapshot.type()) && Boolean.TRUE.equals(entity.details().get("audible"))) targets.add(entity.key());
            if (!targets.contains(requestedTarget)) requestedTarget = "player";
            addDrawableChild(CyclingButtonWidget.<String>builder(target -> {
                if (target.equals("player")) return Text.translatable("mobtalk.settings.mob.target.player");
                return snapshot == null ? Text.literal(target) : snapshot.observedEntities().stream().filter(entity -> target.equals(entity.key()))
                        .findFirst().map(entity -> clipped(entity.name() + " · " + String.format(Locale.ROOT, "%.1f", entity.distance()) + " м", contentWidth - 24)).orElse(Text.literal(target));
            }).values(targets).initially(requestedTarget).omitKeyText().build(left, 120, contentWidth, 20,
                    Text.translatable("mobtalk.settings.mob.target"), (button, value) -> requestedTarget = value));
            button("mobtalk.settings.mob.stop", left, 144, half, ignored -> { controller.stopSelectedAction(); cardLoaded = false; loadCard(); });
            button("mobtalk.settings.mob.stopAll", left + half + 6, 144, half, ignored -> controller.stopAllFollowers());
        } else if (page == 3) {
            button("mobtalk.settings.mob.context", left, 96, contentWidth, ignored -> showContext());
            button("mobtalk.settings.mob.events", left, 120, half, ignored -> read("mobtalk.settings.mob.events", String.join("\n\n", card.events())));
            button("mobtalk.settings.mob.history", left + half + 6, 120, half, ignored -> read("mobtalk.settings.mob.history", historyText()));
            button("mobtalk.settings.mob.refresh", left, 144, contentWidth, ignored -> { cardLoaded = false; loadCard(); });
        } else {
            button("mobtalk.settings.mob.traits", left, 96, contentWidth, ignored -> read("mobtalk.settings.mob.traits",
                    Text.translatable("mobtalk.settings.mob.temperament", card.calm(), card.confidence(), card.curiosity(), card.sociability()).getString()
                    + "\n\n" + card.personality() + "\n\n"
                    + Text.translatable("mobtalk.settings.mob.preferences", card.preference(), card.fear()).getString()));
            button("mobtalk.settings.mob.resetPersonality", left, 120, contentWidth, ignored -> {
                resetPersonality = true; status = Text.translatable("mobtalk.settings.mob.resetPending");
            }).setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.mob.resetPersonality.tooltip")));
            button("mobtalk.settings.mob.clearMemory", left, 144, contentWidth, ignored -> {
                cardMemory = ""; dirtyCard = true; status = Text.translatable("mobtalk.settings.mob.memoryPending");
            });
        }
    }

    private String historyText() { StringBuilder text = new StringBuilder(); for (ConversationStore.Turn turn : card.turns()) text.append("Игрок: ").append(turn.user()).append("\nМоб: ").append(turn.assistant()).append("\n\n"); return text.toString(); }
    private void showContext() {
        controller.selectedSnapshot().whenComplete((context, failure) -> client.execute(() -> {
            if (disposed || client.currentScreen != this) return;
            if (failure != null || context == null) { status = Text.translatable("mobtalk.settings.mob.empty"); return; }
            snapshot = context; read("mobtalk.settings.mob.context", new GsonBuilder().setPrettyPrinting().create().toJson(context));
        }));
    }
    private void loadCard() {
        if (loadingCard) return; loadingCard = true;
        controller.selectedSnapshot().whenComplete((value, failure) -> client.execute(() -> { if (!disposed && failure == null) { snapshot = value; if (client.currentScreen == this && tab == 4) refreshOnTick = true; } }));
        controller.selectedCard().whenComplete((value, failure) -> client.execute(() -> {
            if (disposed) return; loadingCard = false; cardLoaded = true; card = value;
            if (value != null && !dirtyCard) { cardName = value.name(); cardPersonality = value.personality(); cardVoice = value.voiceId(); cardMemory = value.memory(); }
            if (failure != null) { status = Text.translatable("mobtalk.settings.mob.loadFailed"); error = true; }
            if (client.currentScreen == this && tab == 4) refreshOnTick = true;
        }));
    }

    private void operation(CompletableFuture<String> future, boolean refresh) {
        status = Text.translatable("mobtalk.settings.working"); error = false;
        future.whenComplete((message, failure) -> client.execute(() -> {
            if (disposed) return; status = failure != null ? Text.translatable("mobtalk.settings.operation.failed") : Text.literal(cleanMessage(message)); error = failure != null;
            if (refresh) { cardLoaded = false; loadCard(); }
        }));
    }

    private void save() {
        if (saving) return; saving = true; status = Text.translatable("mobtalk.settings.saving"); error = false; controls(false); stopPreview();
        try { controller.save(draft.copy(), failure -> client.execute(() -> {
            if (failure != null) { saveFailed(failure); return; }
            CompletableFuture<String> edits = dirtyCard && card != null ? controller.editSelectedCard(cardName, cardPersonality, cardVoice, cardMemory) : CompletableFuture.completedFuture(null);
            edits.thenCompose(message -> resetPersonality ? controller.resetSelectedPersonality() : CompletableFuture.completedFuture(message))
                    .thenCompose(message -> clearHistory ? controller.clearSelectedHistory() : CompletableFuture.completedFuture(message))
                    .whenComplete((message, problem) -> client.execute(() -> {
                        if (problem != null) { saveFailed(Text.translatable("mobtalk.settings.operation.failed").getString()); return; }
                        saving = false; if (client.currentScreen == this) client.setScreen(parent);
                    }));
        })); } catch (RuntimeException failure) { saveFailed(Text.translatable("mobtalk.settings.save.failed").getString()); }
    }
    private void saveFailed(String failure) { saving = false; status = Text.literal(cleanMessage(failure)); error = true; if (client.currentScreen == this) clearAndInit(); }

    private void refreshMicrophones() {
        if (listing || saving) return; enumerated = true; listing = true; status = Text.translatable("mobtalk.settings.listing"); error = false;
        if (refreshButton != null) refreshButton.active = false;
        try { controller.listMicrophones(names -> client.execute(() -> {
            if (disposed) return; microphones = names == null ? List.of() : List.copyOf(names); listing = false;
            status = names == null ? Text.translatable("mobtalk.settings.list.failed") : names.isEmpty() ? Text.translatable("mobtalk.settings.list.empty") : Text.empty();
            error = names == null || names.isEmpty(); if (client.currentScreen == this && !saving && tab == 1) clearAndInit();
        })); } catch (RuntimeException failure) { listing = false; error = true; status = Text.translatable("mobtalk.settings.list.failed"); if (refreshButton != null) refreshButton.active = true; }
    }
    private Text microphoneName(String name) {
        String value = name.isBlank() ? Text.translatable("mobtalk.settings.microphone.default").getString()
                : !microphones.contains(name) && !listing ? Text.translatable("mobtalk.settings.microphone.missing", name).getString() : name;
        return clipped(value, contentWidth - 24);
    }
    private void microphoneTooltip() { if (microphonePicker != null) microphonePicker.setTooltip(Tooltip.of(Text.literal((draft.microphoneName == null || draft.microphoneName.isBlank()
            ? Text.translatable("mobtalk.settings.microphone.default").getString() : draft.microphoneName) + "\n" + Text.translatable("mobtalk.settings.microphone.cycle").getString()))); }

    private void open(Screen screen) { stopPreview(); openingChild = true; client.setScreen(screen); }
    private void edit(String title, String value, int maximum, Consumer<String> apply, String reset) { open(new TextEditorScreen(this, Text.translatable(title), value, maximum, apply, reset)); }
    private void read(String title, String value) { open(new ReadOnlyTextScreen(this, Text.translatable(title), value == null ? "" : value)); }
    private int half() { return (contentWidth - 6) / 2; }
    private static List<String> retain(List<String> values, String current) { List<String> result = new ArrayList<>(values); if (current != null && !result.contains(current)) result.add(current); return result; }
    private Text clipped(String value, int max) { return textRenderer.getWidth(value) <= max ? Text.literal(value) : Text.literal(textRenderer.trimToWidth(value, Math.max(1, max - 12)) + "…"); }
    private void label(Text text, int x, int y, int maximum) { labels.add(new Label(text, x, y, maximum)); }
    private ButtonWidget button(String key, int x, int y, int size, ButtonWidget.PressAction action) { return addDrawableChild(ButtonWidget.builder(Text.translatable(key), action).dimensions(x, y, size, 20).build()); }
    private void toggle(String key, int x, int y, int size, boolean value, Consumer<Boolean> changed) { addDrawableChild(CyclingButtonWidget.onOffBuilder(value).build(x, y, size, 20, Text.translatable(key), (button, chosen) -> changed.accept(chosen))); }
    private void cycling(String key, int x, int y, int size, List<String> values, String current, Consumer<String> changed) { addDrawableChild(CyclingButtonWidget.<String>builder(Text::literal).values(values)
            .initially(current == null ? values.getFirst() : current).build(x, y, size, 20, Text.translatable(key), (button, chosen) -> changed.accept(chosen))); }
    private TextFieldWidget field(String key, int x, int y, int size, String value, int maximum, Consumer<String> changed) {
        label(Text.translatable(key), x, y, size); TextFieldWidget field = addDrawableChild(new TextFieldWidget(textRenderer, x, y + 11, size, 20, Text.translatable(key)));
        field.setMaxLength(Math.max(maximum, value == null ? 0 : value.length())); field.setText(value == null ? "" : value); field.setChangedListener(changed); return field;
    }
    private void slider(String key, int x, int y, int size, int min, int max, int current, IntConsumer changed) { addDrawableChild(new IntegerSlider(x, y, size, key, min, max, current, changed)); }
    private void controls(boolean active) { for (var child : children()) if (child instanceof ClickableWidget widget) widget.active = active; }
    @Override public void close() { if (!saving) client.setScreen(parent); }
    @Override public boolean shouldCloseOnEsc() { return !saving; }
    @Override public boolean shouldPause() { return true; }
    @Override public void tick() { if (refreshOnTick && !saving) { refreshOnTick = false; clearAndInit(); } }
    @Override public void removed() { stopPreview(); if (openingChild) { openingChild = false; return; } disposed = true; if (voices != null) voices.close(); }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta); context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 13, 0xFFFFFF);
        for (Label label : labels) context.drawTextWithShadow(textRenderer, clipped(label.text.getString(), label.width), label.x, label.y, 0xD5D5D5);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("mobtalk.settings.page", pages[tab] + 1, pageCount()), width / 2, height - 66, 0xBBBBBB);
        if (!status.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, clipped(status.getString(), contentWidth), width / 2, height - 42, error ? 0xFF9999 : 0xBBBBBB);
            if (mouseY >= height - 45 && mouseY < height - 29) context.drawOrderedTooltip(textRenderer, textRenderer.wrapLines(status, Math.min(360, width - 24)), mouseX, mouseY);
        }
    }
    static String cleanMessage(String message) { String clean = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ').strip(); return clean.length() > 1000 ? clean.substring(0, 1000) : clean; }
    private record Label(Text text, int x, int y, int width) { }
    private static final class IntegerSlider extends SliderWidget {
        private final String key; private final int min, max; private final IntConsumer changed; private double displayed;
        IntegerSlider(int x, int y, int width, String key, int min, int max, double current, IntConsumer changed) { super(x, y, width, 20, Text.empty(), Math.max(0, Math.min(1, (current - min) / (max - min)))); this.key = key; this.min = min; this.max = max; this.changed = changed; displayed = current; updateMessage(); }
        @Override protected void updateMessage() { setMessage(Text.translatable(key, displayed == Math.rint(displayed) ? Integer.toString((int) displayed) : String.format(Locale.ROOT, "%.1f", displayed))); }
        @Override protected void applyValue() { int amount = min + (int) Math.round(value * (max - min)); displayed = amount; changed.accept(amount); }
    }
}
