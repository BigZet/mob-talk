package io.github.mobtalk.client;

import com.google.gson.GsonBuilder;
import io.github.mobtalk.core.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.*;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Four tabs of scrollable sections; preference/profile edits remain private until Save, the API key is stored separately. */
final class MobTalkSettingsScreen extends Screen {
    interface Controller {
        void listMicrophones(Consumer<List<String>> callback);
        void save(MobTalkConfig draft, Consumer<String> completion);
        int talkKeyCode();
        default CompletableFuture<OpenRouterToken.Status> keyStatus(OpenRouterToken.Provider provider) { return CompletableFuture.completedFuture(new OpenRouterToken.Status(OpenRouterToken.Source.NONE, "")); }
        default CompletableFuture<OpenRouterToken.Status> saveKey(OpenRouterToken.Provider provider, String key) { return CompletableFuture.failedFuture(new UnsupportedOperationException()); }
        default CompletableFuture<OpenRouterToken.Status> removeKey(OpenRouterToken.Provider provider) { return CompletableFuture.failedFuture(new UnsupportedOperationException()); }
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
    private static final String[] TABS = {"general", "mic", "species", "mob"};
    private static final int GENERAL = 0, MIC = 1, SPECIES = 2, MOB = 3, ROW = 24, LIST_TOP = 48;
    private final Screen parent;
    private final Controller controller;
    private final String originalMicrophone;
    private MobTalkConfig draft;
    private final List<ClickableWidget> widgets = new ArrayList<>();
    private final double[] scrolls = new double[TABS.length];
    private SettingsList list;
    private List<String> microphones = List.of();
    private boolean enumerated, listing, saving, openingChild, disposed;
    private int tab, left, contentWidth;
    private String species = "minecraft:cow", search = "";
    private int slot;
    private CyclingButtonWidget<String> speciesPicker;
    private TextFieldWidget searchField;
    private boolean noSpeciesMatches, refreshOnTick;
    private ButtonWidget refreshButton;
    private Text status = Text.empty();
    private boolean error;
    private VoicePreviewService voices;
    private final Map<OpenRouterToken.Provider, OpenRouterToken.Status> keyStatus = new EnumMap<>(OpenRouterToken.Provider.class);
    private final Map<OpenRouterToken.Provider, String> pendingKey = new EnumMap<>(OpenRouterToken.Provider.class);
    private boolean keyRequested, keyBusy;
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
        widgets.clear(); speciesPicker = null; searchField = null; refreshButton = null;
        contentWidth = Math.min(460, width - 40); left = (width - contentWidth) / 2;
        int tabWidth = (contentWidth - 4 * (TABS.length - 1)) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            int chosen = i;
            ButtonWidget button = addDrawableChild(ButtonWidget.builder(Text.translatable("mobtalk.settings.tab." + TABS[i]),
                    ignored -> { stopPreview(); tab = chosen; status = Text.empty(); error = false; clearAndInit(); })
                    .dimensions(left + i * (tabWidth + 4), 22, tabWidth, 20).build());
            button.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.tab." + TABS[i] + ".full")));
            button.active = i != tab && !saving;
        }
        int top = switch (tab) { case SPECIES -> LIST_TOP + 50; case MOB -> LIST_TOP + 26; default -> LIST_TOP; };
        list = new SettingsList(top, height - 56);
        switch (tab) { case MIC -> microphone(); case SPECIES -> speciesTab(); case MOB -> selectedMob(); default -> general(); }
        addDrawableChild(list);
        list.restore(scrolls[tab]);
        if (tab == SPECIES && noSpeciesMatches) for (ClickableWidget widget : widgets) if (widget != searchField) widget.active = false;

        int footer = Math.min(150, (contentWidth - 12) / 3), footerLeft = (width - footer * 3 - 12) / 2;
        addDrawableChild(ButtonWidget.builder(Text.translatable("mobtalk.settings.save"), ignored -> save()).dimensions(footerLeft, height - 26, footer, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), ignored -> close()).dimensions(footerLeft + footer + 6, height - 26, footer, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("mobtalk.settings.reset"), ignored -> {
            draft = new MobTalkConfig(); dirtyCard = false; clearHistory = false; resetPersonality = false;
            if (card != null) { cardName = card.name(); cardPersonality = card.personality(); cardVoice = card.voiceId(); cardMemory = card.memory(); }
            clearAndInit(); status = Text.translatable("mobtalk.settings.reset.draft"); error = false;
        }).dimensions(footerLeft + 2 * (footer + 6), height - 26, footer, 20).tooltip(Tooltip.of(Text.translatable("mobtalk.settings.reset.tooltip"))).build());
        if (saving) controls(false);
        if (!enumerated) refreshMicrophones();
        if (!keyRequested) loadKeyStatus();
    }

    // ---- Общее ----

    private void general() {
        header("mobtalk.settings.section.llm");
        CyclingButtonWidget<Boolean> provider = track(CyclingButtonWidget.<Boolean>builder(venice -> Text.literal(venice ? "Venice" : "OpenRouter"))
                .values(false, true).initially(draft.usesVenice())
                .build(0, 0, 100, 20, Text.translatable("mobtalk.settings.provider"), (button, venice) -> { draft.llmProvider = venice ? "venice" : "openrouter"; refreshOnTick = true; }));
        provider.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.provider.tooltip")));
        ButtonWidget check = button("mobtalk.settings.connection", ignored -> withKeys(() -> operation(voiceService().connection(draft.copy()), false)));
        check.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.connection.tooltip")));
        check.active = !keyBusy;
        wide(provider, check);
        if (draft.usesVenice()) labeled("mobtalk.settings.llm", field(draft.veniceModel, 160, value -> draft.veniceModel = value));
        else labeled("mobtalk.settings.llm", field(draft.llmModel, 160, value -> draft.llmModel = value));
        if (draft.usesVenice()) note(Text.translatable("mobtalk.settings.provider.veniceModels"), 0x9A9A9A);

        header("mobtalk.settings.section.tts");
        CyclingButtonWidget<Boolean> tts = track(CyclingButtonWidget.<Boolean>builder(venice -> Text.literal(venice ? "Venice" : "OpenRouter"))
                .values(false, true).initially(draft.usesVeniceSpeech())
                .build(0, 0, 100, 20, Text.translatable("mobtalk.settings.tts"), (button, venice) -> { draft.ttsProvider = venice ? "venice" : "openrouter"; refreshOnTick = true; }));
        tts.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.tts.tooltip")));
        wide(tts);
        if (draft.usesVeniceSpeech()) {
            labeled("mobtalk.settings.ttsModel", field(draft.veniceTtsModel, 160, value -> draft.veniceTtsModel = value));
            labeled("mobtalk.settings.veniceVoice", field(draft.veniceVoice, 160, value -> draft.veniceVoice = value));
            note(Text.translatable("mobtalk.settings.tts.veniceNote"), 0x9A9A9A);
        } else {
            labeled("mobtalk.settings.ttsModel", field(draft.ttsModel, 160, value -> draft.ttsModel = value));
            note(Text.translatable("mobtalk.settings.tts.openRouterNote"), 0x9A9A9A);
        }

        if (draft.needsOpenRouter()) keySection(OpenRouterToken.Provider.OPENROUTER, draft.usesVenice() ? "mobtalk.settings.section.openrouter.speech"
                : draft.usesVeniceSpeech() ? "mobtalk.settings.section.openrouter.chat" : "mobtalk.settings.section.openrouter");
        if (draft.needsVenice()) keySection(OpenRouterToken.Provider.VENICE, draft.usesVenice() && draft.usesVeniceSpeech() ? "mobtalk.settings.section.venice.both"
                : draft.usesVenice() ? "mobtalk.settings.section.venice" : "mobtalk.settings.section.venice.speech");
        note(Text.translatable("mobtalk.settings.key.hint"), 0x9A9A9A);

        header("mobtalk.settings.section.conversation");
        wide(toggle("mobtalk.settings.enabled", draft.enabled, value -> draft.enabled = value),
                button("mobtalk.settings.commonPrompt", ignored -> edit("mobtalk.settings.commonPrompt", draft.commonPrompt, 8000, value -> draft.commonPrompt = value, "")));
        CyclingButtonWidget<Integer> rudeness = track(CyclingButtonWidget.<Integer>builder(value -> Text.translatable("mobtalk.settings.rudeness." + value))
                .values(0, 1, 2, 3).initially(Math.max(0, Math.min(3, draft.rudeness)))
                .build(0, 0, 100, 20, Text.translatable("mobtalk.settings.rudeness"), (button, value) -> draft.rudeness = value));
        rudeness.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.rudeness.tooltip")));
        wide(rudeness);
        wide(slider("mobtalk.settings.reply", 5, 120, draft.maxReplyWords, value -> draft.maxReplyWords = value));
        wide(track(new IntegerSlider("mobtalk.settings.distance", 2, 64, draft.maxDistance, value -> draft.maxDistance = value)));
        wide(slider("mobtalk.settings.recording", 1, 15, draft.maxRecordingSeconds, value -> draft.maxRecordingSeconds = value));
        wide(slider("mobtalk.settings.timeout", 5, 90, draft.requestTimeoutSeconds, value -> draft.requestTimeoutSeconds = value));
        wide(slider("mobtalk.settings.witnesses", 0, 2, draft.witnessReplies, value -> draft.witnessReplies = value));
        wide(toggle("mobtalk.settings.stateHud", draft.showStateHud, value -> draft.showStateHud = value));
        CyclingButtonWidget<String> vision = track(CyclingButtonWidget.<String>builder(value -> Text.translatable("mobtalk.settings.vision." + value))
                .values(io.github.mobtalk.core.Vision.MODES).initially(io.github.mobtalk.core.Vision.normalize(draft.vision))
                .build(0, 0, 100, 20, Text.translatable("mobtalk.settings.vision"), (button, chosen) -> draft.vision = chosen));
        vision.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.vision.tooltip")));
        wide(vision);
        wide(toggle("mobtalk.settings.bargainHud", draft.showBargainHud, value -> draft.showBargainHud = value));
        wide(toggle("mobtalk.settings.social", draft.socialBehavior, value -> draft.socialBehavior = value));
        wide(slider("mobtalk.settings.socialDensity", 1, 30, draft.socialActionsPerMinute, value -> draft.socialActionsPerMinute = value));
        socialLayer();
        wide(toggle("mobtalk.settings.initiative", draft.initiative, value -> draft.initiative = value));
        wide(slider("mobtalk.settings.initiativeRate", 1, 6, draft.initiativesPerMinute, value -> draft.initiativesPerMinute = value));
        CyclingButtonWidget<Boolean> decider = track(CyclingButtonWidget.<Boolean>builder(jev -> Text.translatable(jev ? "mobtalk.settings.decider.jev" : "mobtalk.settings.decider.rules"))
                .values(false, true).initially(draft.usesJev()).build(0, 0, 100, 20, Text.translatable("mobtalk.settings.decider"), (button, jev) -> draft.decider = jev ? "jev" : "rules"));
        decider.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.decider.tooltip")));
        wide(decider);

        header("mobtalk.settings.section.memory");
        wide(slider("mobtalk.settings.history", 1, 64, draft.historyTurns, value -> draft.historyTurns = value));
        wide(slider("mobtalk.settings.memory", 200, 4000, draft.maxMemoryChars, value -> draft.maxMemoryChars = value));
    }

    /** The villagers' social layer: the switch and the player's sex for courtship. */
    private void socialLayer() {
        CyclingButtonWidget<String> gender = track(CyclingButtonWidget.<String>builder(value -> Text.translatable("mobtalk.settings.playerGender." + value))
                .values("any", "male", "female").initially(draft.playerGender == null ? "any" : draft.playerGender)
                .build(0, 0, 100, 20, Text.translatable("mobtalk.settings.playerGender"), (button, value) -> draft.playerGender = value));
        gender.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.playerGender.tooltip")));
        CyclingButtonWidget<Boolean> layer = toggle("mobtalk.settings.socialLayer", draft.socialLayer, value -> draft.socialLayer = value);
        layer.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.socialLayer.tooltip")));
        wide(layer, gender);
        CyclingButtonWidget<Boolean> family = toggle("mobtalk.settings.familyLife", draft.familyLife, value -> draft.familyLife = value);
        family.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.familyLife.tooltip")));
        wide(family);
        wide(slider("mobtalk.settings.weddingChance", 0, 100, draft.weddingChance, value -> draft.weddingChance = value),
                slider("mobtalk.settings.birthChance", 0, 100, draft.birthChance, value -> draft.birthChance = value));
        wide(slider("mobtalk.settings.childGrowthDays", 1, 16, draft.childGrowthDays, value -> draft.childGrowthDays = value));
    }

    /** One provider's key: status, masked field with paste, save and remove. */
    private void keySection(OpenRouterToken.Provider provider, String title) {
        header(title);
        note(keyStatusText(provider), keyStatusColor(provider));
        TextFieldWidget key = new TextFieldWidget(textRenderer, 0, 0, 100, 20, Text.translatable("mobtalk.settings.key"));
        key.setMaxLength(512); key.setText(pendingKey.getOrDefault(provider, ""));
        key.setPlaceholder(Text.translatable("mobtalk.settings.key.placeholder." + provider.name().toLowerCase(Locale.ROOT)).formatted(Formatting.DARK_GRAY));
        // Streams may show the menu: the key is never drawn, only its length.
        key.setRenderTextProvider((text, first) -> OrderedText.styledForwardsVisitedString("*".repeat(text.length()), Style.EMPTY));
        key.setChangedListener(value -> pendingKey.put(provider, value));
        ButtonWidget paste = button("mobtalk.settings.key.paste", ignored -> {
            String clipboard = client.keyboard.getClipboard();
            key.setText(clipboard == null ? "" : clipboard.strip());
        });
        paste.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.key.paste.tooltip")));
        labeled("mobtalk.settings.key", new int[] {3, 1}, track(key), paste);
        ButtonWidget store = button("mobtalk.settings.key.save", ignored -> {
            if (pendingKey.getOrDefault(provider, "").isBlank()) { status = Text.translatable("mobtalk.settings.key.empty"); error = true; }
            else keyOperation(provider, controller.saveKey(provider, pendingKey.get(provider).strip()),
                    () -> { pendingKey.remove(provider); status = Text.translatable("mobtalk.settings.key.saved", provider.label); });
        });
        ButtonWidget remove = button("mobtalk.settings.key.remove", ignored -> {
            pendingKey.remove(provider);
            keyOperation(provider, controller.removeKey(provider), () -> status = Text.translatable("mobtalk.settings.key.removed", provider.label));
        });
        remove.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.key.remove.tooltip." + provider.name().toLowerCase(Locale.ROOT))));
        store.active = remove.active = !keyBusy;
        wide(store, remove);
    }

    private Text keyStatusText(OpenRouterToken.Provider provider) {
        OpenRouterToken.Status known = keyStatus.get(provider);
        if (known == null) return Text.translatable("mobtalk.settings.key.checking");
        String name = provider.name().toLowerCase(Locale.ROOT);
        return switch (known.source()) {
            case ENVIRONMENT -> Text.translatable("mobtalk.settings.key.environment." + name, known.hint());
            case FILE -> Text.translatable("mobtalk.settings.key.file." + name, known.hint());
            case ENCRYPTED -> Text.translatable("mobtalk.settings.key.encrypted", known.hint());
            case NONE -> Text.translatable("mobtalk.settings.key.none." + name);
        };
    }
    private int keyStatusColor(OpenRouterToken.Provider provider) {
        OpenRouterToken.Status known = keyStatus.get(provider);
        if (known == null) return 0xBBBBBB;
        return switch (known.source()) { case NONE -> 0xFF9999; case ENVIRONMENT -> 0xFFE08A; default -> 0x9CE59C; };
    }
    private void loadKeyStatus() {
        keyRequested = true;
        for (OpenRouterToken.Provider provider : OpenRouterToken.Provider.values())
            controller.keyStatus(provider).whenComplete((value, failure) -> client.execute(() -> {
                if (disposed) return; keyStatus.put(provider, failure == null ? value : new OpenRouterToken.Status(OpenRouterToken.Source.NONE, ""));
                if (client.currentScreen == this && tab == GENERAL) refreshOnTick = true;
            }));
    }
    /** Saves every pasted key first, so the check and the conversation use them; runs next only when all succeeded. */
    private void withKeys(Runnable next) {
        for (OpenRouterToken.Provider provider : OpenRouterToken.Provider.values()) {
            String key = pendingKey.getOrDefault(provider, "").strip();
            if (!key.isEmpty()) { keyOperation(provider, controller.saveKey(provider, key), () -> { pendingKey.remove(provider); withKeys(next); }); return; }
        }
        next.run();
    }
    private void keyOperation(OpenRouterToken.Provider provider, CompletableFuture<OpenRouterToken.Status> future, Runnable success) {
        keyBusy = true; status = Text.translatable("mobtalk.settings.working"); error = false; refreshOnTick = true;
        future.whenComplete((value, failure) -> client.execute(() -> {
            keyBusy = false; if (disposed) return;
            if (failure == null) { keyStatus.put(provider, value); error = false; success.run(); }
            else { status = Text.literal(failureMessage(failure, "mobtalk.settings.key.failed")); error = true; }
            refreshOnTick = true;
        }));
    }

    // ---- Микрофон ----

    private void microphone() {
        header("mobtalk.settings.section.device");
        List<String> options = new ArrayList<>(List.of(""));
        for (String name : microphones) if (name != null && !name.isBlank() && !options.contains(name)) options.add(name);
        if (!originalMicrophone.isBlank() && !options.contains(originalMicrophone)) options.add(originalMicrophone);
        String current = draft.microphoneName == null ? "" : draft.microphoneName;
        if (!options.contains(current)) options.add(current);
        CyclingButtonWidget<String> picker = track(CyclingButtonWidget.<String>builder(this::microphoneName).values(options).initially(current).omitKeyText()
                .build(0, 0, 100, 20, Text.translatable("mobtalk.settings.microphone"), (button, value) -> { draft.microphoneName = value; microphoneTooltip(button); }));
        microphoneTooltip(picker);
        labeled("mobtalk.settings.microphone.label", picker);
        refreshButton = button("mobtalk.settings.refresh", ignored -> refreshMicrophones()); refreshButton.active = !listing && !saving;
        ButtonWidget test = button("mobtalk.settings.test", ignored -> open(new MicrophoneTestScreen(this, draft.copy(), controller.talkKeyCode(),
                value -> { draft.whisperModel = value; status = Text.translatable("mobtalk.settings.model.chosen", value); })));
        test.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.test.tooltip")));
        wide(refreshButton, test);

        header("mobtalk.settings.section.whisper");
        wide(cycling("mobtalk.settings.whisper.model", retain(WHISPER_MODELS, draft.whisperModel), draft.whisperModel, value -> draft.whisperModel = value));
        wide(cycling("mobtalk.settings.whisper.device", retain(List.of("auto", "cpu", "cuda"), draft.whisperDevice), draft.whisperDevice, value -> draft.whisperDevice = value),
                toggle("mobtalk.settings.whisper.autostart", draft.whisperAutoStart, value -> draft.whisperAutoStart = value));
        labeled("mobtalk.settings.whisper.language", field(draft.whisperLanguage, 24, value -> draft.whisperLanguage = value));

        header("mobtalk.settings.section.tuning");
        wide(slider("mobtalk.settings.whisper.beam", 1, 10, draft.whisperBeamSize, value -> draft.whisperBeamSize = value));
        wide(slider("mobtalk.settings.whisper.vad", 150, 1500, draft.whisperVadSilenceMs, value -> draft.whisperVadSilenceMs = value));
        wide(slider("mobtalk.settings.whisper.cpu", 1, 16, draft.whisperCpuThreads, value -> draft.whisperCpuThreads = value));
        wide(button("mobtalk.settings.whisper.hotwords", ignored -> edit("mobtalk.settings.whisper.hotwords", draft.whisperHotwords, 2000,
                value -> draft.whisperHotwords = value, new MobTalkConfig().whisperHotwords)));
        TextFieldWidget url = field(draft.whisperUrl, 128, value -> draft.whisperUrl = value);
        url.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.whisper.url.tooltip")));
        labeled("mobtalk.settings.whisper.url", url);
    }

    // ---- Виды мобов: характер и голос ----

    private void speciesTab() {
        speciesHeader();
        String type = species; int index = slot;
        SpeciesCatalog.Species effective = SpeciesCatalog.effective(type, draft), bundled = SpeciesCatalog.get(type);
        header("mobtalk.settings.section.character");
        wide(button("mobtalk.settings.species.edit", ignored -> edit("mobtalk.settings.species.edit", effective.prompt(), 8000,
                        value -> override(type).prompt = value, bundled.prompt())),
                button("mobtalk.settings.archetype.edit", ignored -> edit("mobtalk.settings.archetype.edit", effective.variants().get(index).prompt(), 4000,
                        value -> override(type).archetypePrompts[index] = value, bundled.variants().get(index).prompt())));
        wide(button("mobtalk.settings.species.read", ignored -> read("mobtalk.settings.species.read",
                        effective.name() + "\n" + type + "\n\n" + effective.prompt() + "\n\nИнтересы: " + effective.interests() + "\nСтрахи: " + effective.fears()
                        + "\n\n" + effective.variants().get(index).name() + "\n" + effective.variants().get(index).prompt())),
                button("mobtalk.settings.species.reset", ignored -> { draft.speciesOverrides.remove(overrideKey(type)); refreshOnTick = true; }));

        SpeciesCatalog.Voice voice = effective.variants().get(index).voice();
        header("mobtalk.settings.section.voice");
        note(Text.literal(voice.name() + (voice.language().isBlank() ? "" : " · " + voice.language())), 0xBBBBBB);
        TextFieldWidget voiceId = field(voice.id(), 100, value -> override(type).voiceIds[index] = value);
        voiceId.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.voice.id.tooltip")));
        labeled("mobtalk.settings.voice.id", voiceId);
        ButtonWidget preview = button("mobtalk.settings.voice.preview", ignored -> operation(voiceService().preview(voice), false));
        preview.active = voice.previewUrl() != null && !voice.previewUrl().isBlank();
        preview.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.voice.preview.tooltip")));
        ButtonWidget test = button("mobtalk.settings.voice.test",
                ignored -> operation(voiceService().test(draft.copy(), voice.id(), Text.translatable("mobtalk.settings.voice.phrase").getString()), false));
        test.active = voice.id() != null && !voice.id().isBlank(); test.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.voice.test.tooltip")));
        wide(preview, test, button("mobtalk.settings.voice.stop", ignored -> stopPreview()));
        wide(button("mobtalk.settings.voice.metadata", ignored -> read("mobtalk.settings.voice.metadata",
                        voice.name() + "\nID: " + voice.id() + "\nЯзык: " + voice.language() + "\n\n" + voice.description()
                        + "\n\nПревью: " + (voice.previewUrl().isBlank() ? "нет" : voice.previewUrl())
                        + "\n\nПревью библиотеки не подтверждает генерацию OpenRouter Flash v2.5.")),
                button("mobtalk.settings.voice.status", ignored -> operation(voiceService().status(draft.copy(), voice.id()), false)));
    }

    /** Fixed above the list so the chosen species stays visible while scrolling. */
    private void speciesHeader() {
        int half = half();
        TextFieldWidget filter = addDrawableChild(new TextFieldWidget(textRenderer, left, LIST_TOP, contentWidth, 20, Text.translatable("mobtalk.settings.search")));
        searchField = track(filter);
        filter.setMaxLength(100); filter.setText(search); filter.setPlaceholder(Text.translatable("mobtalk.settings.search").formatted(Formatting.DARK_GRAY));
        filter.setChangedListener(value -> {
            search = value; String previous = species; boolean hadNone = noSpeciesMatches; rebuildSpeciesPicker();
            if (!previous.equals(species) || hadNone != noSpeciesMatches) { int cursor = filter.getCursor(); clearAndInit(); setInitialFocus(searchField); searchField.setCursor(cursor, false); }
        });
        rebuildSpeciesPicker();
        addDrawableChild(track(CyclingButtonWidget.<Integer>builder(value -> clipped(Text.translatable("mobtalk.settings.archetype", value + 1,
                        SpeciesCatalog.effective(species, draft).variants().get(value).name()).getString(), half - 12)).values(0, 1, 2).initially(slot).omitKeyText()
                .build(left + half + 6, LIST_TOP + 24, half, 20, Text.translatable("mobtalk.settings.archetype.label"), (button, value) -> { slot = value; clearAndInit(); })));
    }
    private void rebuildSpeciesPicker() {
        if (speciesPicker != null) { remove(speciesPicker); widgets.remove(speciesPicker); }
        String needle = search.strip().toLowerCase(Locale.ROOT);
        List<String> types = SpeciesCatalog.all().stream().filter(item -> item.id().contains(needle) || item.name().toLowerCase(Locale.ROOT).contains(needle)).map(SpeciesCatalog.Species::id).toList();
        noSpeciesMatches = types.isEmpty();
        if (types.isEmpty()) types = List.of(species);
        if (!types.contains(species)) species = types.getFirst();
        int half = half();
        speciesPicker = addDrawableChild(track(CyclingButtonWidget.<String>builder(type -> clipped(SpeciesCatalog.get(type).name(), half - 12))
                .values(types).initially(species).omitKeyText().build(left, LIST_TOP + 24, half, 20, Text.translatable("mobtalk.settings.species"),
                        (button, value) -> { species = value; clearAndInit(); })));
        speciesPicker.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.species.tooltip", SpeciesCatalog.get(species).name(), species)));
        if (noSpeciesMatches) { speciesPicker.setMessage(Text.translatable("mobtalk.settings.search.empty")); speciesPicker.active = false; }
    }
    private String overrideKey(String type) { for (String key : draft.speciesOverrides.keySet()) if ((key.contains(":") ? key : "minecraft:" + key).equals(type)) return key; return type; }
    private SpeciesCatalog.Override override(String type) { return draft.speciesOverrides.computeIfAbsent(overrideKey(type), ignored -> new SpeciesCatalog.Override()); }

    private VoicePreviewService voiceService() { if (voices == null) voices = new VoicePreviewService(client.runDirectory.toPath().resolve("config")); return voices; }
    private void stopPreview() { if (voices != null) voices.stop(); }

    // ---- Выбранный моб ----

    private void selectedMob() {
        if (!cardLoaded && !loadingCard) loadCard();
        if (card == null) {
            note(Text.translatable(loadingCard ? "mobtalk.settings.mob.loading" : "mobtalk.settings.mob.empty"), 0xBBBBBB);
            wide(button("mobtalk.settings.mob.refresh", ignored -> { cardLoaded = false; loadCard(); }),
                    button("mobtalk.settings.mob.stopAll", ignored -> controller.stopAllFollowers()));
            return;
        }
        header("mobtalk.settings.section.card");
        labeled("mobtalk.settings.mob.name", field(cardName, 120, value -> { cardName = value; dirtyCard = true; }));
        List<String> ids = new ArrayList<>();
        for (SpeciesCatalog.Archetype variant : SpeciesCatalog.effective(card.type(), draft).variants()) if (!ids.contains(variant.voice().id())) ids.add(variant.voice().id());
        if (!ids.contains(cardVoice)) ids.add(cardVoice);
        labeled("mobtalk.settings.mob.voice", track(CyclingButtonWidget.<String>builder(id -> id.isBlank() ? Text.translatable("mobtalk.settings.voice.unassigned")
                        : clipped(SpeciesCatalog.voice(id).name(), contentWidth * 3 / 5 - 12)).values(ids).initially(cardVoice).omitKeyText()
                .build(0, 0, 100, 20, Text.translatable("mobtalk.settings.mob.voice"), (button, value) -> { cardVoice = value; dirtyCard = true; })));
        wide(button("mobtalk.settings.mob.personality", ignored -> edit("mobtalk.settings.mob.personality", cardPersonality, 4000,
                        value -> { cardPersonality = value; dirtyCard = true; }, card.personality())),
                button("mobtalk.settings.mob.memory", ignored -> edit("mobtalk.settings.mob.memory", cardMemory, 4000,
                        value -> { cardMemory = value; dirtyCard = true; }, "")));

        header("mobtalk.settings.section.request");
        List<ActionType> actions = snapshot == null ? List.of(ActionType.FOLLOW, ActionType.FLEE, ActionType.ATTACK) : snapshot.availableActions().stream()
                .filter(action -> action != ActionType.NONE && action != ActionType.STOP).toList();
        if (actions.isEmpty()) actions = List.of(ActionType.NONE);
        if (!actions.contains(requestedAction)) requestedAction = actions.getFirst();
        CyclingButtonWidget<ActionType> action = track(CyclingButtonWidget.<ActionType>builder(value -> Text.translatable("mobtalk.action." + value.name().toLowerCase(Locale.ROOT)))
                .values(actions).initially(requestedAction).omitKeyText().build(0, 0, 100, 20, Text.translatable("mobtalk.settings.mob.action"),
                        (button, value) -> { requestedAction = value; refreshOnTick = true; }));
        ButtonWidget request = button("mobtalk.settings.mob.request", ignored -> operation(controller.requestMobAction(new ActionIntent(requestedAction, requestedTarget)), true));
        request.active = requestedAction != ActionType.NONE;
        request.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.mob.request.tooltip")));
        labeled("mobtalk.settings.mob.action", new int[] {2, 1}, action, request);
        List<String> targets = new ArrayList<>(List.of("player"));
        if ((requestedAction == ActionType.ATTACK || requestedAction == ActionType.FLEE || requestedAction == ActionType.SPARE) && snapshot != null)
            for (ObservedEntity entity : snapshot.observedEntities())
                if (entity.visible() || "minecraft:warden".equals(snapshot.type()) && Boolean.TRUE.equals(entity.details().get("audible"))) targets.add(entity.key());
        if (!targets.contains(requestedTarget)) requestedTarget = "player";
        labeled("mobtalk.settings.mob.target", track(CyclingButtonWidget.<String>builder(target -> {
            if (target.equals("player")) return Text.translatable("mobtalk.settings.mob.target.player");
            return snapshot == null ? Text.literal(target) : snapshot.observedEntities().stream().filter(entity -> target.equals(entity.key()))
                    .findFirst().map(entity -> clipped(entity.name() + " · " + String.format(Locale.ROOT, "%.1f", entity.distance()) + " м", contentWidth * 3 / 5 - 12)).orElse(Text.literal(target));
        }).values(targets).initially(requestedTarget).omitKeyText().build(0, 0, 100, 20, Text.translatable("mobtalk.settings.mob.target"), (button, value) -> requestedTarget = value)));
        wide(button("mobtalk.settings.mob.stop", ignored -> { controller.stopSelectedAction(); cardLoaded = false; loadCard(); }),
                button("mobtalk.settings.mob.stopAll", ignored -> controller.stopAllFollowers()));

        header("mobtalk.settings.section.details");
        wide(button("mobtalk.settings.mob.history", ignored -> read("mobtalk.settings.mob.history", historyText())),
                button("mobtalk.settings.mob.events", ignored -> read("mobtalk.settings.mob.events", String.join("\n\n", card.events()))));
        wide(button("mobtalk.settings.mob.traits", ignored -> read("mobtalk.settings.mob.traits",
                        Text.translatable("mobtalk.settings.mob.temperament", card.calm(), card.confidence(), card.curiosity(), card.sociability()).getString()
                        + "\n\n" + card.personality() + "\n\n"
                        + Text.translatable("mobtalk.settings.mob.preferences", card.preference(), card.fear()).getString())),
                button("mobtalk.settings.mob.context", ignored -> showContext()));
        wide(button("mobtalk.settings.mob.refresh", ignored -> { dirtyCard = false; clearHistory = false; resetPersonality = false; cardLoaded = false; loadCard(); }));

        header("mobtalk.settings.section.reset");
        wide(button("mobtalk.settings.mob.clearHistory", ignored -> { clearHistory = true; status = Text.translatable("mobtalk.settings.mob.clearPending"); error = false; }),
                button("mobtalk.settings.mob.clearMemory", ignored -> { cardMemory = ""; dirtyCard = true; status = Text.translatable("mobtalk.settings.mob.memoryPending"); error = false; }));
        ButtonWidget fresh = button("mobtalk.settings.mob.resetPersonality", ignored -> { resetPersonality = true; status = Text.translatable("mobtalk.settings.mob.resetPending"); error = false; });
        fresh.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.mob.resetPersonality.tooltip")));
        wide(fresh);
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
        controller.selectedSnapshot().whenComplete((value, failure) -> client.execute(() -> { if (!disposed && failure == null) { snapshot = value; if (client.currentScreen == this && tab == MOB) refreshOnTick = true; } }));
        controller.selectedCard().whenComplete((value, failure) -> client.execute(() -> {
            if (disposed) return; loadingCard = false; cardLoaded = true; card = value;
            if (value != null && !dirtyCard) { cardName = value.name(); cardPersonality = value.personality(); cardVoice = value.voiceId(); cardMemory = value.memory(); }
            if (failure != null) { status = Text.translatable("mobtalk.settings.mob.loadFailed"); error = true; }
            if (client.currentScreen == this && tab == MOB) refreshOnTick = true;
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
        saveKeysThenSettings();
    }
    /** Pasted but unsaved keys are stored before the settings, one provider at a time. */
    private void saveKeysThenSettings() {
        for (OpenRouterToken.Provider provider : OpenRouterToken.Provider.values()) {
            String key = pendingKey.getOrDefault(provider, "").strip();
            if (key.isEmpty()) continue;
            controller.saveKey(provider, key).whenComplete((value, failure) -> client.execute(() -> {
                if (disposed) return;
                if (failure != null) { saveFailed(failureMessage(failure, "mobtalk.settings.key.failed")); return; }
                pendingKey.remove(provider); keyStatus.put(provider, value);
                saveKeysThenSettings();
            }));
            return;
        }
        saveSettings();
    }
    private void saveSettings() {
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
    private static String failureMessage(Throwable failure, String fallback) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        String message = cleanMessage(cause.getMessage());
        return message.isBlank() ? Text.translatable(fallback).getString() : message;
    }

    private void refreshMicrophones() {
        if (listing || saving) return; enumerated = true; listing = true; status = Text.translatable("mobtalk.settings.listing"); error = false;
        if (refreshButton != null) refreshButton.active = false;
        try { controller.listMicrophones(names -> client.execute(() -> {
            if (disposed) return; microphones = names == null ? List.of() : List.copyOf(names); listing = false;
            status = names == null ? Text.translatable("mobtalk.settings.list.failed") : names.isEmpty() ? Text.translatable("mobtalk.settings.list.empty") : Text.empty();
            error = names == null || names.isEmpty(); if (client.currentScreen == this && !saving && tab == MIC) clearAndInit();
        })); } catch (RuntimeException failure) { listing = false; error = true; status = Text.translatable("mobtalk.settings.list.failed"); if (refreshButton != null) refreshButton.active = true; }
    }
    private Text microphoneName(String name) {
        String value = name.isBlank() ? Text.translatable("mobtalk.settings.microphone.default").getString()
                : !microphones.contains(name) && !listing ? Text.translatable("mobtalk.settings.microphone.missing", name).getString() : name;
        return clipped(value, contentWidth * 3 / 5 - 12);
    }
    private void microphoneTooltip(ClickableWidget picker) { picker.setTooltip(Tooltip.of(Text.literal((draft.microphoneName == null || draft.microphoneName.isBlank()
            ? Text.translatable("mobtalk.settings.microphone.default").getString() : draft.microphoneName) + "\n" + Text.translatable("mobtalk.settings.microphone.cycle").getString()))); }

    // ---- Строки списка и виджеты ----

    private void header(String key) { list.add(new Row(Text.translatable(key), RowKind.HEADER, 0xFFD37F, null, List.of())); }
    private void note(Text text, int color) { list.add(new Row(text, RowKind.NOTE, color, null, List.of())); }
    private void wide(ClickableWidget... controls) { list.add(new Row(null, RowKind.CONTROLS, 0, null, List.of(controls))); }
    private void labeled(String key, ClickableWidget... controls) { labeled(key, null, controls); }
    private void labeled(String key, int[] weights, ClickableWidget... controls) { list.add(new Row(Text.translatable(key), RowKind.CONTROLS, 0xD5D5D5, weights, List.of(controls))); }

    private <T extends ClickableWidget> T track(T widget) { widgets.add(widget); return widget; }
    private ButtonWidget button(String key, ButtonWidget.PressAction action) { return track(ButtonWidget.builder(Text.translatable(key), action).size(100, 20).build()); }
    private CyclingButtonWidget<Boolean> toggle(String key, boolean value, Consumer<Boolean> changed) {
        return track(CyclingButtonWidget.onOffBuilder(value).build(0, 0, 100, 20, Text.translatable(key), (button, chosen) -> changed.accept(chosen)));
    }
    private CyclingButtonWidget<String> cycling(String key, List<String> values, String current, Consumer<String> changed) {
        return track(CyclingButtonWidget.<String>builder(Text::literal).values(values).initially(current == null ? values.getFirst() : current)
                .build(0, 0, 100, 20, Text.translatable(key), (button, chosen) -> changed.accept(chosen)));
    }
    private TextFieldWidget field(String value, int maximum, Consumer<String> changed) {
        TextFieldWidget field = track(new TextFieldWidget(textRenderer, 0, 0, 100, 20, Text.empty()));
        field.setMaxLength(Math.max(maximum, value == null ? 0 : value.length())); field.setText(value == null ? "" : value); field.setChangedListener(changed); return field;
    }
    private IntegerSlider slider(String key, int min, int max, int current, IntConsumer changed) { return track(new IntegerSlider(key, min, max, current, changed)); }

    private void open(Screen screen) { stopPreview(); openingChild = true; client.setScreen(screen); }
    private void edit(String title, String value, int maximum, Consumer<String> apply, String reset) { open(new TextEditorScreen(this, Text.translatable(title), value, maximum, apply, reset)); }
    private void read(String title, String value) { open(new ReadOnlyTextScreen(this, Text.translatable(title), value == null ? "" : value)); }
    private int half() { return (contentWidth - 6) / 2; }
    private static List<String> retain(List<String> values, String current) { List<String> result = new ArrayList<>(values); if (current != null && !result.contains(current)) result.add(current); return result; }
    private Text clipped(String value, int max) { return textRenderer.getWidth(value) <= max ? Text.literal(value) : Text.literal(textRenderer.trimToWidth(value, Math.max(1, max - 12)) + "…"); }
    private void controls(boolean active) {
        for (var child : children()) if (child instanceof ClickableWidget widget) widget.active = active;
        for (ClickableWidget widget : widgets) widget.active = active;
    }
    @Override public void close() { if (!saving) client.setScreen(parent); }
    @Override public boolean shouldCloseOnEsc() { return !saving; }
    @Override public boolean shouldPause() { return true; }
    @Override public void tick() { if (refreshOnTick && !saving) { refreshOnTick = false; clearAndInit(); } }
    @Override public void removed() { stopPreview(); if (openingChild) { openingChild = false; return; } disposed = true; if (voices != null) voices.close(); }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta); context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF);
        if (tab == MOB && card != null) {
            context.drawTextWithShadow(textRenderer, clipped(cardName + " · " + SpeciesCatalog.get(card.type()).name(), contentWidth), left, LIST_TOP + 1, 0xFFFFFF);
            context.drawTextWithShadow(textRenderer, clipped(Text.translatable("mobtalk.settings.mob.summary", card.relationship(), card.turns().size(),
                    Text.translatable("mobtalk.action." + card.action().type().name().toLowerCase(Locale.ROOT))).getString(), contentWidth), left, LIST_TOP + 13, 0xBBBBBB);
        }
        if (!status.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, clipped(status.getString(), contentWidth), width / 2, height - 44, error ? 0xFF9999 : 0xBBBBBB);
            if (mouseY >= height - 47 && mouseY < height - 32) setTooltip(textRenderer.wrapLines(status, Math.min(360, width - 24)));
        }
    }
    static String cleanMessage(String message) { String clean = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ').strip(); return clean.length() > 1000 ? clean.substring(0, 1000) : clean; }

    private enum RowKind { HEADER, NOTE, CONTROLS }

    private final class SettingsList extends ElementListWidget<Row> {
        private boolean restored;
        SettingsList(int top, int bottom) { super(MobTalkSettingsScreen.this.client, MobTalkSettingsScreen.this.width, Math.max(ROW, bottom - top), top, ROW); }
        void add(Row row) { addEntry(row); }
        /** Rebuilding after an edit, a child screen, or an async refresh keeps the reader's place. */
        void restore(double amount) { setScrollAmount(amount); restored = true; }
        @Override public void setScrollAmount(double amount) { super.setScrollAmount(amount); if (restored) scrolls[tab] = getScrollAmount(); }
        @Override public int getRowWidth() { return contentWidth; }
        @Override protected int getScrollbarX() { return getRowRight() + 6; }
    }

    private final class Row extends ElementListWidget.Entry<Row> {
        private final Text label;
        private final RowKind kind;
        private final int color;
        private final int[] weights;
        private final List<ClickableWidget> controls;

        Row(Text label, RowKind kind, int color, int[] weights, List<ClickableWidget> controls) {
            this.label = label; this.kind = kind; this.color = color; this.weights = weights; this.controls = controls;
        }
        @Override public List<? extends Element> children() { return controls; }
        @Override public List<? extends Selectable> selectableChildren() { return controls; }
        @Override public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int textY = y + (entryHeight - 8) / 2;
            if (kind == RowKind.HEADER) {
                context.drawTextWithShadow(textRenderer, label, x, textY + 2, color);
                context.fill(x, y + entryHeight, x + entryWidth, y + entryHeight + 1, 0x55FFFFFF);
                return;
            }
            if (kind == RowKind.NOTE) {
                context.drawTextWithShadow(textRenderer, clipped(label.getString(), entryWidth), x, textY, color);
                if (hovered && textRenderer.getWidth(label) > entryWidth) setTooltip(textRenderer.wrapLines(label, Math.min(360, width - 24)));
                return;
            }
            int controlsX = x, controlsWidth = entryWidth;
            if (label != null) {
                int labelWidth = entryWidth * 2 / 5;
                context.drawTextWithShadow(textRenderer, clipped(label.getString(), labelWidth - 6), x, textY, color);
                controlsX += labelWidth; controlsWidth -= labelWidth;
            }
            int total = 0; for (int i = 0; i < controls.size(); i++) total += weight(i);
            int gap = 4, free = controlsWidth - gap * (controls.size() - 1), cursor = controlsX;
            for (int i = 0; i < controls.size(); i++) {
                ClickableWidget widget = controls.get(i);
                int size = i == controls.size() - 1 ? controlsX + controlsWidth - cursor : free * weight(i) / total;
                widget.setX(cursor); widget.setY(y); widget.setWidth(size);
                widget.render(context, mouseX, mouseY, delta);
                cursor += size + gap;
            }
        }
        private int weight(int index) { return weights == null || index >= weights.length ? 1 : weights[index]; }
    }

    private static final class IntegerSlider extends SliderWidget {
        private final String key; private final int min, max; private final IntConsumer changed; private double displayed;
        IntegerSlider(String key, int min, int max, double current, IntConsumer changed) { super(0, 0, 100, 20, Text.empty(), Math.max(0, Math.min(1, (current - min) / (max - min)))); this.key = key; this.min = min; this.max = max; this.changed = changed; displayed = current; updateMessage(); }
        @Override protected void updateMessage() { setMessage(Text.translatable(key, displayed == Math.rint(displayed) ? Integer.toString((int) displayed) : String.format(Locale.ROOT, "%.1f", displayed))); }
        @Override protected void applyValue() { int amount = min + (int) Math.round(value * (max - min)); displayed = amount; changed.accept(amount); }
    }
}
