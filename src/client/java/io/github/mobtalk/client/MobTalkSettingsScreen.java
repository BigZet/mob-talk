package io.github.mobtalk.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.mobtalk.core.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.*;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import io.github.mobtalk.client.settings.PanelScreen;
import io.github.mobtalk.client.settings.ServerSettingsView;
import io.github.mobtalk.client.settings.TunableSlider;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Eight tabs of scrollable sections (general, voice, model, conversation, social, actions and place, species, the selected mob); preference/profile edits remain private until Save, the API key is stored separately.
 * The sliders of a group fold under its header (the group's switches stay in view), and one search field looks through the six settings tabs at once. */
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
        /** The mod's keys as they are bound now, one line each («V — говорить с мобом»). */
        default List<String> keyHints() { return List.of(); }
        /** The player's standing in the village where they stand, in lines. */
        default CompletableFuture<List<String>> villageStatus() { return CompletableFuture.completedFuture(List.of()); }
        /** Opens «Моб и деревня» over this menu. */
        default void openInfo(Screen parent) { }
    }
    static final List<String> WHISPER_MODELS = List.of("small", "medium", "large-v2", "large-v3", "large-v3-turbo");
    private static final String[] TABS = {"general", "voice", "model", "talk", "social", "actions", "species", "mob"};
    /** The tabs stand in the sidebar «Разделы»; the search (or the species pickers) sits at {@code LIST_TOP} above the list. */
    private static final int GENERAL = 0, VOICE = 1, MODEL = 2, TALK = 3, SOCIAL = 4, ACTIONS = 5, SPECIES = 6, MOB = 7, ROW = 24, LIST_TOP = 34;
    /** The panel palette of Viewer Parrots' control panel. */
    private static final int GOLD = PanelScreen.GOLD, TEXT = PanelScreen.TEXT, MUTED = PanelScreen.MUTED, DIM = PanelScreen.DIM, ERROR = PanelScreen.ERROR;
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
    /** Slider groups shown unfolded; the few everyday numbers start open. Kept until the game closes, not in the config. */
    private static final Set<String> EXPANDED = new HashSet<>(Set.of("reply", "recording", "requests"));
    /** The search over every settings tab; while it holds text, the list shows matches from all six tabs. */
    private String find = "";
    private TextFieldWidget findField;
    /** The tab name before every header while searching, so a match says where it lives. */
    private String prefix = "";
    /** Rows of a search are collected here first and filtered before they reach the list. */
    private List<Row> collecting;
    /** Tooltip text of a control, so the search also finds a setting by its explanation. */
    private final Map<ClickableWidget, String> tips = new IdentityHashMap<>();
    /** The label a switch or choice gets at the left of its own row, instead of «Name: value» on the button. */
    private final Map<ClickableWidget, Text> captions = new IdentityHashMap<>();
    /** The settings as opened, to tell «Сохранено» from «Есть изменения». */
    private final String initialJson;
    private boolean changed;
    private int ticks;
    /** Трек C: on a dedicated server, what the server said about its settings (null in single player or before it answered), the
     * player's own config as opened (its server fields stay in mobtalk.json untouched), and the setting each control edits. */
    private final ServerSettingsView.Snapshot server;
    private final MobTalkConfig local;
    private final Map<ClickableWidget, String> bound = new IdentityHashMap<>();

    MobTalkSettingsScreen(Screen parent, MobTalkConfig initial, Controller controller) {
        super(Text.translatable("mobtalk.settings.title")); this.parent = parent; this.controller = controller;
        ServerSettingsView.Snapshot view = ServerSettingsView.get();
        server = view != null && net.minecraft.client.MinecraftClient.getInstance().getServer() == null ? view : null;
        local = initial.copy();
        draft = server == null ? initial.copy() : SettingsScope.merge(server.config(), initial);
        originalMicrophone = initial.microphoneName == null ? "" : initial.microphoneName;
        initialJson = new Gson().toJson(draft);
    }

    @Override protected void init() {
        widgets.clear(); tips.clear(); captions.clear(); bound.clear(); speciesPicker = null; searchField = null; findField = null; refreshButton = null;
        // The layout of Viewer Parrots' panel: sections on the left, the list on the right up to a small margin.
        left = Math.min(157, Math.max(119, width / 4)); contentWidth = width - 13 - left;
        int step = Math.max(17, Math.min(24, (height - 95) / TABS.length));
        for (int i = 0; i < TABS.length; i++) {
            int chosen = i;
            ButtonWidget button = addDrawableChild(ButtonWidget.builder(Text.translatable("mobtalk.settings.tab." + TABS[i]),
                    ignored -> { stopPreview(); tab = chosen; find = ""; status = Text.empty(); error = false; clearAndInit(); })
                    .dimensions(8, 47 + i * step, left - 21, Math.min(20, step - 1)).build());
            button.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.tab." + TABS[i] + ".full")));
            button.active = (i != tab || searching()) && !saving;
        }
        int top = switch (tab) { case SPECIES -> LIST_TOP + 50; default -> LIST_TOP + 26; };
        list = new SettingsList(top, height - 50);
        if (section(tab) != null) findHeader();
        if (searching()) searchAll();
        else {
            if (section(tab) != null && !Tunables.of(section(tab)).isEmpty()) foldButtons(section(tab));
            build(tab);
        }
        lockServerFields();
        addDrawableChild(list);
        if (!searching()) list.restore(scrolls[tab]);
        if (tab == SPECIES && noSpeciesMatches) for (ClickableWidget widget : widgets) if (widget != searchField) widget.active = false;

        // Bottom right, as in the parrots' panel: «Сбросить», «Сохранить», «Отмена».
        int footer = footerWidth(), cancelX = width - 12 - footer;
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), ignored -> close()).dimensions(cancelX, height - 28, footer, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("mobtalk.settings.save"), ignored -> save()).dimensions(cancelX - footer - 6, height - 28, footer, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("mobtalk.settings.reset"), ignored -> {
            draft = new MobTalkConfig(); dirtyCard = false; clearHistory = false; resetPersonality = false;
            if (card != null) { cardName = card.name(); cardPersonality = card.personality(); cardVoice = card.voiceId(); cardMemory = card.memory(); }
            clearAndInit(); status = Text.translatable("mobtalk.settings.reset.draft"); error = false;
        }).dimensions(cancelX - 2 * (footer + 6), height - 28, footer, 20).tooltip(Tooltip.of(Text.translatable("mobtalk.settings.reset.tooltip"))).build());
        if (saving) controls(false);
        if (!enumerated) refreshMicrophones();
        if (!keyRequested) loadKeyStatus();
    }

    private void build(int which) {
        if (server != null && which != SPECIES && which != MOB) serverNote();
        switch (which) {
            case VOICE -> voice(); case MODEL -> model(); case TALK -> talk(); case SOCIAL -> social(); case ACTIONS -> actions();
            case SPECIES -> speciesTab(); case MOB -> selectedMob(); default -> general();
        }
    }
    /** The tabs of plain settings and their slider table; the species and the selected mob have none. */
    private static Tunables.Section section(int which) {
        return switch (which) {
            case GENERAL -> Tunables.Section.GENERAL; case VOICE -> Tunables.Section.VOICE; case MODEL -> Tunables.Section.MODEL;
            case TALK -> Tunables.Section.TALK; case SOCIAL -> Tunables.Section.SOCIAL; case ACTIONS -> Tunables.Section.ACTIONS; default -> null;
        };
    }

    // ---- Поиск и свёрнутые группы ----

    private boolean searching() { return !find.isBlank() && section(tab) != null; }

    /** Fixed above the list on the six settings tabs: typing rebuilds the list from every tab and keeps the caret. */
    private void findHeader() {
        TextFieldWidget field = addDrawableChild(new TextFieldWidget(textRenderer, left, LIST_TOP, contentWidth, 20, Text.translatable("mobtalk.settings.find")));
        findField = track(field);
        field.setMaxLength(60); field.setText(find);
        field.setPlaceholder(Text.translatable("mobtalk.settings.find").formatted(Formatting.DARK_GRAY));
        field.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.find.tooltip")));
        field.setChangedListener(value -> {
            // Restoring the caret below notifies the new field with the same text: only a real edit rebuilds, or the rebuilds never end.
            if (value.equals(find)) return;
            boolean was = searching(); find = value;
            if (!was && !searching()) return;
            int cursor = field.getCursor(); clearAndInit();
            if (findField != null) { setInitialFocus(findField); findField.setCursor(cursor, false); }
        });
    }

    /** Builds the six settings tabs into one buffer and keeps the rows that match every word, under their headers. */
    private void searchAll() {
        String[] words = normalized(find).split("\\s+");
        collecting = new ArrayList<>();
        int saved = tab;
        try {
            for (int which = GENERAL; which <= ACTIONS; which++) {
                tab = which; prefix = Text.translatable("mobtalk.settings.tab." + TABS[which]).getString() + " › ";
                build(which);
            }
        } finally { tab = saved; prefix = ""; }
        List<Row> rows = collecting; collecting = null;
        List<Row> segment = new ArrayList<>(), kept = new ArrayList<>();
        int found = 0;
        for (Row row : rows) {
            if (row.kind == RowKind.HEADER) { found += keep(segment, words, kept); segment = new ArrayList<>(); }
            segment.add(row);
        }
        found += keep(segment, words, kept);
        note(found == 0 ? Text.translatable("mobtalk.settings.find.none", find.strip()) : Text.translatable("mobtalk.settings.find.count", found), found == 0 ? 0xFF9999 : 0x9A9A9A);
        for (Row row : kept) list.add(row);
    }
    /** One header and its rows: all of them when the header matches, else only the matching rows; the header goes along with any. */
    private int keep(List<Row> segment, String[] words, List<Row> out) {
        if (segment.isEmpty()) return 0;
        Row head = segment.getFirst().kind == RowKind.HEADER ? segment.getFirst() : null;
        List<Row> body = head == null ? segment : segment.subList(1, segment.size());
        boolean all = head != null && matches(head.label.getString(), words);
        List<Row> hits = new ArrayList<>();
        for (Row row : body) if (all ? row.kind == RowKind.CONTROLS : matches(searchText(row), words)) hits.add(row);
        if (hits.isEmpty()) return 0;
        if (head != null) out.add(head);
        out.addAll(hits);
        int count = 0; for (Row row : hits) if (row.kind == RowKind.CONTROLS) count++;
        return count;
    }
    private String searchText(Row row) {
        StringBuilder text = new StringBuilder(row.label == null ? "" : row.label.getString());
        for (ClickableWidget control : row.controls) {
            text.append(' ').append(control.getMessage().getString());
            String tip = control instanceof TunableSlider slider ? slider.explanation() : tips.get(control);
            if (tip != null) text.append(' ').append(tip);
        }
        return text.toString();
    }
    private static boolean matches(String text, String[] words) {
        String haystack = normalized(text);
        for (String word : words) if (!word.isEmpty() && !haystack.contains(word)) return false;
        return true;
    }
    private static String normalized(String text) { return text.toLowerCase(Locale.ROOT).replace('ё', 'е').strip(); }

    /** «Развернуть всё» and «Свернуть всё» over the slider groups of this tab. */
    private void foldButtons(Tunables.Section section) {
        Set<String> groups = new LinkedHashSet<>(); for (Tunables.Tunable tunable : Tunables.of(section)) groups.add(tunable.group());
        ButtonWidget open = button("mobtalk.settings.expandAll", ignored -> { EXPANDED.addAll(groups); refreshOnTick = true; });
        ButtonWidget shut = button("mobtalk.settings.collapseAll", ignored -> { EXPANDED.removeAll(groups); refreshOnTick = true; });
        open.active = !EXPANDED.containsAll(groups); shut.active = groups.stream().anyMatch(EXPANDED::contains);
        wide(open, shut);
    }

    // ---- Общее ----

    private void general() {
        header("mobtalk.settings.section.main");
        wide(tip(toggle("mobtalk.settings.enabled", draft.enabled, value -> draft.enabled = value), "mobtalk.settings.enabled.tooltip"));
        CyclingButtonWidget<String> vision = track(CyclingButtonWidget.<String>builder(value -> Text.translatable("mobtalk.settings.vision." + value))
                .values(io.github.mobtalk.core.Vision.MODES).initially(io.github.mobtalk.core.Vision.normalize(draft.vision))
                .omitKeyText().build(0, 0, 100, 20, Text.translatable("mobtalk.settings.vision"), (button, chosen) -> draft.vision = chosen));
        caption(vision, "mobtalk.settings.vision").setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.vision.tooltip")));
        wide(vision);
        wide(tip(toggle("mobtalk.settings.stateHud", draft.showStateHud, value -> draft.showStateHud = value), "mobtalk.settings.stateHud.tooltip"),
                tip(toggle("mobtalk.settings.bargainHud", draft.showBargainHud, value -> draft.showBargainHud = value), "mobtalk.settings.bargainHud.tooltip"));
        wide(tip(toggle("mobtalk.settings.nameTags", draft.showNameTags, value -> draft.showNameTags = value), "mobtalk.settings.nameTags.tooltip"),
                tip(toggle("mobtalk.settings.humanVillagers", draft.humanVillagers, value -> draft.humanVillagers = value), "mobtalk.settings.humanVillagers.tooltip"));
        wide(tip(toggle("mobtalk.settings.keepAcquainted", draft.keepAcquainted, value -> draft.keepAcquainted = value), "mobtalk.settings.keepAcquainted.tooltip"));

        header("mobtalk.settings.section.names");
        note(Text.translatable("mobtalk.settings.names.hint"), 0x9A9A9A);
        names("mobtalk.settings.names.neutral", draft.playerNamesNeutral, value -> draft.playerNamesNeutral = value);
        names("mobtalk.settings.names.close", draft.playerNamesClose, value -> draft.playerNamesClose = value);
        names("mobtalk.settings.names.hostile", draft.playerNamesHostile, value -> draft.playerNamesHostile = value);

        header("mobtalk.settings.section.village");
        if (village == null) { note(Text.translatable("mobtalk.settings.village.loading"), 0xBBBBBB); loadVillage(); }
        else for (String line : village) note(Text.literal(line), 0xD5D5D5);
        ButtonWidget info = button("mobtalk.settings.info", ignored -> { stopPreview(); openingChild = true; controller.openInfo(this); });
        info.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.info.tooltip")));
        wide(info, button("mobtalk.settings.village.refresh", ignored -> { village = null; refreshOnTick = true; }));

        header("mobtalk.settings.section.keys");
        for (String line : controller.keyHints()) note(Text.literal(line), 0xBBBBBB);
        if (server != null && server.admin()) players();
        resetSection(Tunables.Section.GENERAL);
    }
    private List<String> village;
    private boolean loadingVillage;
    private void loadVillage() {
        if (loadingVillage) return; loadingVillage = true;
        controller.villageStatus().whenComplete((lines, failure) -> client.execute(() -> {
            loadingVillage = false; if (disposed) return;
            village = failure == null && lines != null ? lines : List.of(Text.translatable("mobtalk.settings.village.failed").getString());
            if (client.currentScreen == this && tab == GENERAL) refreshOnTick = true;
        }));
    }
    /** One list of forms of address: a text field with the example as placeholder and the rule in the tooltip. */
    private void names(String key, String value, Consumer<String> changed) {
        TextFieldWidget field = field(value, 400, changed);
        field.setPlaceholder(Text.translatable(key + ".placeholder").formatted(Formatting.DARK_GRAY));
        field.setTooltip(Tooltip.of(Text.translatable(key + ".tooltip")));
        labeled(key, field);
    }

    // ---- Модель ----

    private void model() {
        header("mobtalk.settings.section.llm");
        CyclingButtonWidget<Boolean> provider = track(CyclingButtonWidget.<Boolean>builder(venice -> Text.literal(venice ? "Venice" : "OpenRouter"))
                .values(false, true).initially(draft.usesVenice())
                .omitKeyText().build(0, 0, 100, 20, Text.translatable("mobtalk.settings.provider"), (button, venice) -> { draft.llmProvider = venice ? "venice" : "openrouter"; refreshOnTick = true; }));
        caption(provider, "mobtalk.settings.provider").setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.provider.tooltip")));
        ButtonWidget check = button("mobtalk.settings.connection", ignored -> withKeys(() -> operation(voiceService().connection(draft.copy()), false)));
        check.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.connection.tooltip")));
        check.active = !keyBusy;
        wide(provider, check);
        TextFieldWidget model = draft.usesVenice() ? field(draft.veniceModel, 160, value -> draft.veniceModel = value) : field(draft.llmModel, 160, value -> draft.llmModel = value);
        model.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.llm.tooltip")));
        labeled("mobtalk.settings.llm", model);
        if (draft.usesVenice()) note(Text.translatable("mobtalk.settings.provider.veniceModels"), 0x9A9A9A);
        CyclingButtonWidget<String> reasoning = track(CyclingButtonWidget.<String>builder(value -> Text.translatable("mobtalk.settings.reasoning." + (value.isEmpty() ? "default" : value)))
                .values(retain(List.of("", "none", "minimal", "low", "medium", "high"), draft.reasoningEffort)).initially(draft.reasoningEffort == null ? "" : draft.reasoningEffort)
                .omitKeyText().build(0, 0, 100, 20, Text.translatable("mobtalk.settings.reasoning"), (button, value) -> draft.reasoningEffort = value));
        caption(reasoning, "mobtalk.settings.reasoning").setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.reasoning.tooltip")));
        wide(reasoning);
        TextFieldWidget background = field(draft.backgroundModel, 160, value -> draft.backgroundModel = value);
        background.setPlaceholder(Text.translatable("mobtalk.settings.backgroundModel.placeholder").formatted(Formatting.DARK_GRAY));
        background.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.backgroundModel.tooltip")));
        labeled("mobtalk.settings.backgroundModel", background);
        CyclingButtonWidget<Boolean> decider = track(CyclingButtonWidget.<Boolean>builder(jev -> Text.translatable(jev ? "mobtalk.settings.decider.jev" : "mobtalk.settings.decider.rules"))
                .values(false, true).initially(draft.usesJev()).omitKeyText().build(0, 0, 100, 20, Text.translatable("mobtalk.settings.decider"), (button, jev) -> draft.decider = jev ? "jev" : "rules"));
        caption(decider, "mobtalk.settings.decider").setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.decider.tooltip")));
        wide(decider);
        tunables(Tunables.Section.MODEL, Map.of());

        if (draft.needsOpenRouter()) keySection(OpenRouterToken.Provider.OPENROUTER, draft.usesVenice() ? "mobtalk.settings.section.openrouter.speech"
                : draft.usesVeniceSpeech() ? "mobtalk.settings.section.openrouter.chat" : "mobtalk.settings.section.openrouter");
        if (draft.needsVenice()) keySection(OpenRouterToken.Provider.VENICE, draft.usesVenice() && draft.usesVeniceSpeech() ? "mobtalk.settings.section.venice.both"
                : draft.usesVenice() ? "mobtalk.settings.section.venice" : "mobtalk.settings.section.venice.speech");
        note(Text.translatable("mobtalk.settings.key.hint"), 0x9A9A9A);
        resetSection(Tunables.Section.MODEL);
    }

    // ---- Общение ----

    private void talk() {
        header("mobtalk.settings.section.style");
        CyclingButtonWidget<Integer> rudeness = track(CyclingButtonWidget.<Integer>builder(value -> Text.translatable("mobtalk.settings.rudeness." + value))
                .values(0, 1, 2, 3).initially(Math.max(0, Math.min(3, draft.rudeness)))
                .omitKeyText().build(0, 0, 100, 20, Text.translatable("mobtalk.settings.rudeness"), (button, value) -> draft.rudeness = value));
        caption(rudeness, "mobtalk.settings.rudeness").setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.rudeness.tooltip")));
        ButtonWidget prompt = button("mobtalk.settings.commonPrompt", ignored -> edit("mobtalk.settings.commonPrompt", draft.commonPrompt, 8000, value -> draft.commonPrompt = value, ""));
        prompt.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.commonPrompt.tooltip")));
        wide(rudeness, prompt);
        tunables(Tunables.Section.TALK, Map.of());
        resetSection(Tunables.Section.TALK);
    }

    // ---- Социальное ----

    private void social() {
        tunables(Tunables.Section.SOCIAL, Map.of(
                "family", () -> {
                    CyclingButtonWidget<String> gender = track(CyclingButtonWidget.<String>builder(value -> Text.translatable("mobtalk.settings.playerGender." + value))
                            .values("any", "male", "female").initially(draft.playerGender == null ? "any" : draft.playerGender)
                            .omitKeyText().build(0, 0, 100, 20, Text.translatable("mobtalk.settings.playerGender"), (button, value) -> draft.playerGender = value));
                    caption(gender, "mobtalk.settings.playerGender").setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.playerGender.tooltip")));
                    wide(tip(toggle("mobtalk.settings.socialLayer", draft.socialLayer, value -> draft.socialLayer = value), "mobtalk.settings.socialLayer.tooltip"), gender);
                    wide(tip(toggle("mobtalk.settings.familyLife", draft.familyLife, value -> draft.familyLife = value), "mobtalk.settings.familyLife.tooltip"));
                },
                "bonds", () -> wide(tip(toggle("mobtalk.settings.social", draft.socialBehavior, value -> draft.socialBehavior = value), "mobtalk.settings.social.tooltip")),
                "jealousy", () -> wide(tip(toggle("mobtalk.settings.coSleep", draft.coSleep, value -> draft.coSleep = value), "mobtalk.settings.coSleep.tooltip")),
                "wedding", () -> wide(tip(toggle("mobtalk.settings.weddingCeremony", draft.weddingCeremony, value -> draft.weddingCeremony = value), "mobtalk.settings.weddingCeremony.tooltip")),
                "child", () -> {
                    wide(tip(toggle("mobtalk.settings.pregnancy", draft.pregnancy, value -> draft.pregnancy = value), "mobtalk.settings.pregnancy.tooltip"),
                            tip(toggle("mobtalk.settings.familyFade", draft.familyFade, value -> draft.familyFade = value), "mobtalk.settings.familyFade.tooltip"));
                    wide(tip(toggle("mobtalk.settings.pregnancyBelly", draft.pregnancyBelly, value -> draft.pregnancyBelly = value), "mobtalk.settings.pregnancyBelly.tooltip"),
                            tip(toggle("mobtalk.settings.pregnancyStatus", draft.pregnancyStatus, value -> draft.pregnancyStatus = value), "mobtalk.settings.pregnancyStatus.tooltip"));
                },
                "initiative", () -> wide(tip(toggle("mobtalk.settings.initiative", draft.initiative, value -> draft.initiative = value), "mobtalk.settings.initiative.tooltip"),
                        tip(toggle("mobtalk.settings.giftsFirst", draft.giftsFirst, value -> draft.giftsFirst = value), "mobtalk.settings.giftsFirst.tooltip")),
                "village", () -> wide(tip(toggle("mobtalk.settings.villageSites", draft.villageSites, value -> draft.villageSites = value), "mobtalk.settings.villageSites.tooltip")),
                "chatter", () -> {
                    wide(tip(toggle("mobtalk.settings.villageChatter", draft.villageChatter, value -> draft.villageChatter = value), "mobtalk.settings.villageChatter.tooltip"),
                            tip(toggle("mobtalk.settings.villageNotices", draft.villageNotices, value -> draft.villageNotices = value), "mobtalk.settings.villageNotices.tooltip"));
                    wide(tip(toggle("mobtalk.settings.villageParrotJokes", draft.villageParrotJokes, value -> draft.villageParrotJokes = value), "mobtalk.settings.villageParrotJokes.tooltip"));
                }));
        resetSection(Tunables.Section.SOCIAL);
    }

    // ---- Действия и место ----

    private void actions() {
        tunables(Tunables.Section.ACTIONS, Map.of());
        header("mobtalk.settings.group.place");
        wide(tip(toggle("mobtalk.settings.placeAnalysis", draft.placeAnalysis, value -> draft.placeAnalysis = value), "mobtalk.settings.placeAnalysis.tooltip"),
                tip(toggle("mobtalk.settings.placeClassifier", draft.placeClassifier, value -> draft.placeClassifier = value), "mobtalk.settings.placeClassifier.tooltip"));
        note(Text.translatable("mobtalk.settings.place.note"), 0x9A9A9A);
        resetSection(Tunables.Section.ACTIONS);
    }

    /** The sliders of one tab under a header per group, in the table's order; {@code before} adds the switches of a group above its sliders. */
    private void tunables(Tunables.Section section, Map<String, Runnable> before) {
        String group = null;
        boolean folded = false;
        for (Tunables.Tunable tunable : Tunables.of(section)) {
            if (!tunable.group().equals(group)) {
                group = tunable.group();
                folded = collecting == null && !EXPANDED.contains(group);
                groupHeader(section, group, folded);
                Runnable extra = before.get(group);
                if (extra != null) extra.run();
            }
            if (!folded) { TunableSlider slider = track(new TunableSlider(tunable, draft)); bound.put(slider, tunable.field()); wide(slider); }
        }
    }
    /** «Сбросить раздел»: this tab's numbers and switches back to their defaults in the draft; texts typed by hand stay. */
    private void resetSection(Tunables.Section section) {
        if (collecting != null) return;
        header("mobtalk.settings.section.resetSection");
        ButtonWidget reset = button("mobtalk.settings.resetSection", ignored -> {
            Tunables.resetSection(draft, section); refreshOnTick = true;
            status = Text.translatable("mobtalk.settings.resetSection.done"); error = false;
        });
        reset.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.resetSection.tooltip")));
        wide(reset);
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
                if (client.currentScreen == this && tab == MODEL) refreshOnTick = true;
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

    // ---- Голос и распознавание ----

    private void voice() {
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
        // Куда идёт запись: свой Whisper, сервер (OpenRouter) или только текст; «авто» — свой Whisper, если установлен, иначе сервер.
        CyclingButtonWidget<String> sttPath = track(CyclingButtonWidget.<String>builder(value -> Text.translatable("mobtalk.settings.sttPath." + value))
                .values(SttRoute.AUTO, SttRoute.LOCAL, SttRoute.SERVER, SttRoute.TEXT).initially(SttRoute.normalize(draft.sttPath)).omitKeyText()
                .build(0, 0, 100, 20, Text.translatable("mobtalk.settings.sttPath"), (button, value) -> draft.sttPath = value));
        sttPath.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.sttPath.tooltip")));
        labeled("mobtalk.settings.sttPath", sttPath);
        wide(cycling("mobtalk.settings.whisper.model", retain(WHISPER_MODELS, draft.whisperModel), draft.whisperModel, value -> draft.whisperModel = value));
        wide(cycling("mobtalk.settings.whisper.device", retain(List.of("auto", "cpu", "cuda"), draft.whisperDevice), draft.whisperDevice, value -> draft.whisperDevice = value),
                toggle("mobtalk.settings.whisper.autostart", draft.whisperAutoStart, value -> draft.whisperAutoStart = value));
        labeled("mobtalk.settings.whisper.language", field(draft.whisperLanguage, 24, value -> draft.whisperLanguage = value));

        tunables(Tunables.Section.VOICE, Map.of());
        header("mobtalk.settings.section.tuning");
        wide(button("mobtalk.settings.whisper.hotwords", ignored -> edit("mobtalk.settings.whisper.hotwords", draft.whisperHotwords, 2000,
                value -> draft.whisperHotwords = value, new MobTalkConfig().whisperHotwords)));
        TextFieldWidget url = field(draft.whisperUrl, 128, value -> draft.whisperUrl = value);
        url.setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.whisper.url.tooltip")));
        labeled("mobtalk.settings.whisper.url", url);

        header("mobtalk.settings.section.tts");
        CyclingButtonWidget<Boolean> tts = track(CyclingButtonWidget.<Boolean>builder(venice -> Text.literal(venice ? "Venice" : "OpenRouter"))
                .values(false, true).initially(draft.usesVeniceSpeech())
                .omitKeyText().build(0, 0, 100, 20, Text.translatable("mobtalk.settings.tts"), (button, venice) -> { draft.ttsProvider = venice ? "venice" : "openrouter"; refreshOnTick = true; }));
        caption(tts, "mobtalk.settings.tts").setTooltip(Tooltip.of(Text.translatable("mobtalk.settings.tts.tooltip")));
        wide(tts);
        if (draft.usesVeniceSpeech()) {
            labeled("mobtalk.settings.ttsModel", field(draft.veniceTtsModel, 160, value -> draft.veniceTtsModel = value));
            labeled("mobtalk.settings.veniceVoice", field(draft.veniceVoice, 160, value -> draft.veniceVoice = value));
            note(Text.translatable("mobtalk.settings.tts.veniceNote"), 0x9A9A9A);
        } else {
            labeled("mobtalk.settings.ttsModel", field(draft.ttsModel, 160, value -> draft.ttsModel = value));
            note(Text.translatable("mobtalk.settings.tts.openRouterNote"), 0x9A9A9A);
        }
        note(Text.translatable("mobtalk.settings.tts.keyNote"), 0x9A9A9A);
        resetSection(Tunables.Section.VOICE);
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
        wide(button("mobtalk.settings.info", ignored -> { stopPreview(); openingChild = true; controller.openInfo(this); }));
        wide(button("mobtalk.settings.mob.history", ignored -> read("mobtalk.settings.mob.history", historyText())),
                button("mobtalk.settings.mob.events", ignored -> read("mobtalk.settings.mob.events", String.join("\n\n", card.events()))));
        wide(button("mobtalk.settings.mob.traits", ignored -> read("mobtalk.settings.mob.traits",
                        (card.age().isBlank() ? "" : Text.translatable("mobtalk.settings.mob.age", card.age()).getString() + "\n\n")
                        + Text.translatable("mobtalk.settings.mob.temperament", card.calm(), card.confidence(), card.curiosity(), card.sociability()).getString()
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
        // On a dedicated server only the personal layer is the player's: the server fields of mobtalk.json stay as they were, and an
        // admin's changes to server fields go to the server as /mobtalk admin set.
        MobTalkConfig saved = server == null ? draft.copy() : SettingsScope.withServerFields(draft, local);
        try { controller.save(saved, failure -> client.execute(() -> {
            if (failure != null) { saveFailed(failure); return; }
            if (server != null && server.admin()) pushServerChanges();
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
            error = names == null || names.isEmpty(); if (client.currentScreen == this && !saving && tab == VOICE) clearAndInit();
        })); } catch (RuntimeException failure) { listing = false; error = true; status = Text.translatable("mobtalk.settings.list.failed"); if (refreshButton != null) refreshButton.active = true; }
    }
    private Text microphoneName(String name) {
        String value = name.isBlank() ? Text.translatable("mobtalk.settings.microphone.default").getString()
                : !microphones.contains(name) && !listing ? Text.translatable("mobtalk.settings.microphone.missing", name).getString() : name;
        return clipped(value, contentWidth * 3 / 5 - 12);
    }
    private void microphoneTooltip(ClickableWidget picker) { picker.setTooltip(Tooltip.of(Text.literal((draft.microphoneName == null || draft.microphoneName.isBlank()
            ? Text.translatable("mobtalk.settings.microphone.default").getString() : draft.microphoneName) + "\n" + Text.translatable("mobtalk.settings.microphone.cycle").getString()))); }

    // ---- Трек C: серверные и личные настройки на выделенном сервере ----

    /** Controls whose lang key names a different field than {@code mobtalk.settings.<field>}. */
    private static final Map<String, String> FIELD_ALIASES = Map.of("stateHud", "showStateHud", "bargainHud", "showBargainHud", "nameTags", "showNameTags",
            "provider", "llmProvider", "tts", "ttsProvider", "microphone", "microphoneName", "whisper.model", "whisperModel", "whisper.device", "whisperDevice",
            "whisper.autostart", "whisperAutoStart", "whisper.language", "whisperLanguage");
    /** Remembers which setting a control edits, by its lang key; keys that name no field stay unbound (and editable). */
    private void bind(String key, ClickableWidget... controls) {
        String field = fieldFor(key);
        if (field == null) return;
        for (ClickableWidget control : controls) bound.putIfAbsent(control, field);
    }
    private static String fieldFor(String key) {
        if (key == null || !key.startsWith("mobtalk.settings.")) return null;
        String name = key.substring("mobtalk.settings.".length());
        name = FIELD_ALIASES.getOrDefault(name, name);
        return SettingsScope.fields().contains(name) ? name : null;
    }
    /** A player or streamer sees the server's settings but cannot move them; the admin can. */
    private void lockServerFields() {
        if (server == null || server.admin()) return;
        Tooltip locked = Tooltip.of(Text.translatable("mobtalk.settings.serverLocked"));
        for (Map.Entry<ClickableWidget, String> entry : bound.entrySet()) {
            if (SettingsScope.personal(entry.getValue())) continue;
            entry.getKey().active = false;
            entry.getKey().setTooltip(locked);
        }
    }
    private void serverNote() {
        note(Text.translatable(server.admin() ? "mobtalk.settings.serverAdmin" : "mobtalk.settings.serverLocked"), server.admin() ? 0xD5D5D5 : 0x9A9A9A);
        if (!server.spentToday().isBlank()) note(Text.translatable("mobtalk.settings.spent", server.spentToday()), 0x9A9A9A);
    }
    /** For the admin: the players online, each with «Сделать стримером»; the server keeps one streamer. */
    private void players() {
        header("mobtalk.settings.players");
        var network = client == null ? null : client.getNetworkHandler();
        if (network == null) return;
        for (var entry : network.getPlayerList()) {
            String name = entry.getProfile().getName();
            boolean streamer = name.equalsIgnoreCase(server.streamer());
            ButtonWidget make = track(ButtonWidget.builder(Text.translatable("mobtalk.settings.players.streamer"),
                    ignored -> { ServerSettingsView.sendToServer("mobtalk admin streamer " + name); close(); }).size(100, 20).build());
            make.active = !streamer;
            add(new Row(Text.literal(name + (streamer ? " — стример" : "")), RowKind.CONTROLS, TEXT, null, List.of(make)));
        }
        if (!server.streamer().isBlank())
            wide(track(ButtonWidget.builder(Text.translatable("mobtalk.settings.players.clearStreamer"),
                    ignored -> { ServerSettingsView.sendToServer("mobtalk admin streamer clear"); close(); }).size(100, 20).build()));
    }
    /** The admin's changes to server fields, one {@code /mobtalk admin set} each; long or multi-line texts are left to the web admin. */
    private void pushServerChanges() {
        int skipped = 0;
        for (String name : SettingsScope.serverFields()) {
            try {
                java.lang.reflect.Field field = MobTalkConfig.class.getField(name);
                Object now = field.get(draft), before = field.get(server.config());
                if (Objects.equals(now, before)) continue;
                Class<?> type = field.getType();
                if (type != boolean.class && type != int.class && type != double.class && type != String.class) { skipped++; continue; }
                String value = type == boolean.class ? ((Boolean) now ? "on" : "off") : String.valueOf(now);
                if (value.contains("\n") || !ServerSettingsView.sendToServer("mobtalk admin set " + name + " " + value)) skipped++;
            } catch (ReflectiveOperationException impossible) { skipped++; }
        }
        if (skipped > 0 && client != null && client.inGameHud != null)
            client.inGameHud.getChatHud().addMessage(Text.literal("[Mob Talk] Не ушло на сервер настроек: " + skipped + " (длинные тексты и списки меняйте в веб-админке)"));
    }

    // ---- Строки списка и виджеты ----

    private void add(Row row) { if (collecting != null) collecting.add(row); else list.add(row); }
    private void header(String key) { add(new Row(Text.literal(prefix + Text.translatable(key).getString()), RowKind.HEADER, GOLD, null, List.of())); }
    /** A slider group's header: a click folds or unfolds it; the right side counts its numbers and those moved off their defaults. */
    private void groupHeader(Tunables.Section section, String group, boolean folded) {
        int count = 0, changed = 0;
        for (Tunables.Tunable tunable : Tunables.of(section)) if (tunable.group().equals(group)) {
            count++; if (tunable.normalize(tunable.get(draft)) != tunable.defaultValue()) changed++;
        }
        String name = prefix + Text.translatable("mobtalk.settings.group." + group).getString();
        Row row = new Row(Text.literal(collecting != null ? name : (folded ? "▶ " : "▼ ") + name), RowKind.HEADER, GOLD, null, List.of());
        row.side = changed == 0 ? Text.translatable("mobtalk.settings.group.count", count) : Text.translatable("mobtalk.settings.group.changed", count, changed);
        if (collecting == null) row.group = group;
        add(row);
    }
    /** The old greys of the notes in the panel's blue-grey palette. */
    private void note(Text text, int color) {
        int shade = switch (color) { case 0x9A9A9A -> DIM; case 0xBBBBBB -> MUTED; case 0xD5D5D5 -> TEXT; case 0xFF9999 -> ERROR; default -> color; };
        add(new Row(text, RowKind.NOTE, shade, null, List.of()));
    }
    /** A row of controls; a switch or choice with a caption gets its own row «caption — value», the rest share a row. */
    private void wide(ClickableWidget... controls) {
        List<ClickableWidget> plain = new ArrayList<>();
        for (ClickableWidget control : controls) {
            Text label = captions.get(control);
            if (label == null) { plain.add(control); continue; }
            if (!plain.isEmpty()) { add(new Row(null, RowKind.CONTROLS, 0, null, List.copyOf(plain))); plain.clear(); }
            add(new Row(label, RowKind.CONTROLS, TEXT, null, List.of(control)));
        }
        if (!plain.isEmpty()) add(new Row(null, RowKind.CONTROLS, 0, null, List.copyOf(plain)));
    }
    private void labeled(String key, ClickableWidget... controls) { labeled(key, null, controls); }
    private void labeled(String key, int[] weights, ClickableWidget... controls) { bind(key, controls); add(new Row(Text.translatable(key), RowKind.CONTROLS, TEXT, weights, List.of(controls))); }

    private <T extends ClickableWidget> T track(T widget) { widgets.add(widget); return widget; }
    private <T extends ClickableWidget> T caption(T widget, String key) { captions.put(widget, Text.translatable(key)); bind(key, widget); return widget; }
    private ButtonWidget button(String key, ButtonWidget.PressAction action) { return track(ButtonWidget.builder(Text.translatable(key), action).size(100, 20).build()); }
    private CyclingButtonWidget<Boolean> toggle(String key, boolean value, Consumer<Boolean> changed) {
        return caption(track(CyclingButtonWidget.onOffBuilder(value).omitKeyText().build(0, 0, 100, 20, Text.translatable(key), (button, chosen) -> changed.accept(chosen))), key);
    }
    private CyclingButtonWidget<String> cycling(String key, List<String> values, String current, Consumer<String> changed) {
        return caption(track(CyclingButtonWidget.<String>builder(Text::literal).values(values).initially(current == null ? values.getFirst() : current).omitKeyText()
                .build(0, 0, 100, 20, Text.translatable(key), (button, chosen) -> changed.accept(chosen))), key);
    }
    private int footerWidth() { return Math.max(60, Math.min(100, (width - left - 30) / 3)); }
    private TextFieldWidget field(String value, int maximum, Consumer<String> changed) {
        TextFieldWidget field = track(new TextFieldWidget(textRenderer, 0, 0, 100, 20, Text.empty()));
        field.setMaxLength(Math.max(maximum, value == null ? 0 : value.length())); field.setText(value == null ? "" : value); field.setChangedListener(changed); return field;
    }
    /** The explanation under the pointer, from the lang file. */
    private <T extends ClickableWidget> T tip(T widget, String key) { widget.setTooltip(Tooltip.of(Text.translatable(key))); tips.put(widget, Text.translatable(key).getString()); return widget; }

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
    @Override public void tick() {
        // «Есть изменения» is checked twice a second, not every frame: it serialises the draft.
        if (ticks++ % 10 == 0) changed = !new Gson().toJson(draft).equals(initialJson) || dirtyCard || clearHistory || resetPersonality
                || pendingKey.values().stream().anyMatch(key -> !key.isBlank());
        if (refreshOnTick && !saving) { refreshOnTick = false; clearAndInit(); }
    }
    @Override public void removed() { stopPreview(); if (openingChild) { openingChild = false; return; } disposed = true; if (voices != null) voices.close(); }
    /** The dark backdrop and the list's panel of the parrots' control panel, under every widget. */
    @Override public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, 0xDD11151C);
        context.fill(left - 3, list.getY() - 3, left + contentWidth + 3, list.getBottom() + 3, 0xB019202A);
    }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawTextWithShadow(textRenderer, clipped(title.getString(), contentWidth), left, 14, GOLD);
        context.drawTextWithShadow(textRenderer, Text.translatable("mobtalk.settings.sections"), 11, 32, MUTED);
        if (tab == MOB && card != null) {
            context.drawTextWithShadow(textRenderer, clipped(cardName + " · " + SpeciesCatalog.get(card.type()).name(), contentWidth), left, LIST_TOP + 1, TEXT);
            context.drawTextWithShadow(textRenderer, clipped(Text.translatable("mobtalk.settings.mob.summary", card.relationship(), card.turns().size(),
                    Text.translatable("mobtalk.action." + card.action().type().name().toLowerCase(Locale.ROOT))).getString(), contentWidth), left, LIST_TOP + 13, MUTED);
        }
        // Bottom left, under the sections: whether the draft differs from what was opened.
        int badgeWidth = Math.max(30, width - 12 - 3 * footerWidth() - 12 - 18 - 11);
        context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(Text.translatable(saving ? "mobtalk.settings.saving" : changed ? "mobtalk.settings.badge.changed" : "mobtalk.settings.badge.saved").getString(),
                badgeWidth), 11, height - 22, changed ? GOLD : MUTED);
        if (!status.getString().isEmpty()) {
            context.drawTextWithShadow(textRenderer, clipped(status.getString(), contentWidth), left, height - 42, error ? ERROR : MUTED);
            if (mouseY >= height - 45 && mouseY < height - 32 && mouseX >= left) setTooltip(textRenderer.wrapLines(status, Math.min(360, width - 24)));
        }
    }
    static String cleanMessage(String message) { String clean = message == null ? "" : message.replace('\n', ' ').replace('\r', ' ').strip(); return clean.length() > 1000 ? clean.substring(0, 1000) : clean; }

    private enum RowKind { HEADER, NOTE, CONTROLS }

    /** The list in the panel on the right: no vanilla backdrop or separators, a thin gold scrollbar at the panel's edge. */
    private final class SettingsList extends ElementListWidget<Row> {
        private boolean restored, drawing;
        SettingsList(int top, int bottom) {
            // Eight pixels wider on the left, so the centred rows start at the panel's padding and end before the scrollbar.
            super(MobTalkSettingsScreen.this.client, contentWidth + 8, Math.max(ROW, bottom - top), top, ROW);
            setX(left - 8);
        }
        void add(Row row) { addEntry(row); }
        /** Rebuilding after an edit, a child screen, or an async refresh keeps the reader's place. */
        void restore(double amount) { setScrollAmount(amount); restored = true; }
        @Override public void setScrollAmount(double amount) { super.setScrollAmount(amount); if (restored && !searching()) scrolls[tab] = getScrollAmount(); }
        @Override public int getRowWidth() { return contentWidth - 12; }
        @Override protected int getScrollbarX() { return left + contentWidth - 4; }
        @Override protected void drawMenuListBackground(DrawContext context) { }
        @Override protected void drawHeaderAndFooterSeparators(DrawContext context) { }
        /** The vanilla scrollbar is hidden while drawing only: dragging it still works. */
        @Override protected boolean isScrollbarVisible() { return !drawing && super.isScrollbarVisible(); }
        @Override public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
            drawing = true;
            try { super.renderWidget(context, mouseX, mouseY, delta); } finally { drawing = false; }
            if (getMaxScroll() <= 0) return;
            int x = left + contentWidth, track = getHeight(), thumb = Math.max(12, Math.min(track - 4, track * track / Math.max(1, getMaxPosition())));
            int y = getY() + (int) (getScrollAmount() * (track - thumb) / getMaxScroll());
            context.fill(x, getY(), x + 2, getBottom(), 0x884A5362);
            context.fill(x, y, x + 2, y + thumb, 0xFF000000 | GOLD);
        }
    }

    private final class Row extends ElementListWidget.Entry<Row> {
        private final Text label;
        private final RowKind kind;
        private final int color;
        private final int[] weights;
        private final List<ClickableWidget> controls;
        /** A slider group this header folds, or null for a plain header. */
        private String group;
        /** Dim text at the right end of a header. */
        private Text side;

        Row(Text label, RowKind kind, int color, int[] weights, List<ClickableWidget> controls) {
            this.label = label; this.kind = kind; this.color = color; this.weights = weights; this.controls = controls;
        }
        @Override public List<? extends Element> children() { return controls; }
        @Override public List<? extends Selectable> selectableChildren() { return controls; }
        @Override public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            int textY = y + (entryHeight - 8) / 2;
            if (kind == RowKind.HEADER) {
                // A gold group title over a thin rule, as in the parrots' panel; a slider group still folds on a click.
                if (group != null && hovered) {
                    context.fill(x - 2, y + 2, x + entryWidth + 2, y + entryHeight, 0x18FFFFFF);
                    setTooltip(textRenderer.wrapLines(Text.translatable("mobtalk.settings.group.toggle"), Math.min(360, width - 24)));
                }
                int sideWidth = side == null ? 0 : textRenderer.getWidth(side);
                if (side != null) context.drawTextWithShadow(textRenderer, side, x + entryWidth - sideWidth, textY + 2, DIM);
                context.drawTextWithShadow(textRenderer, clipped(label.getString(), entryWidth - sideWidth - 8), x + 1, textY + 2, color);
                context.fill(x + 1, y + entryHeight, x + entryWidth, y + entryHeight + 1, 0x665D6570);
                return;
            }
            if (kind == RowKind.NOTE) {
                context.drawTextWithShadow(textRenderer, clipped(label.getString(), entryWidth), x + 1, textY, color);
                if (hovered && textRenderer.getWidth(label) > entryWidth) setTooltip(textRenderer.wrapLines(label, Math.min(360, width - 24)));
                return;
            }
            // A thin separator under every row of controls.
            context.fill(x + 1, y + entryHeight + 1, x + entryWidth, y + entryHeight + 2, 0x303F4857);
            int controlsX = x, controlsWidth = entryWidth;
            if (label != null) {
                int labelWidth = entryWidth / 2;
                context.drawTextWithShadow(textRenderer, clipped(label.getString(), labelWidth - 6), x + 1, textY, color);
                if (hovered && mouseX < x + labelWidth && textRenderer.getWidth(label) > labelWidth - 6) setTooltip(textRenderer.wrapLines(label, Math.min(360, width - 24)));
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
        @Override public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (kind == RowKind.HEADER && group != null && button == 0) {
                if (!EXPANDED.remove(group)) EXPANDED.add(group);
                client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                refreshOnTick = true;
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }
        private int weight(int index) { return weights == null || index >= weights.length ? 1 : weights[index]; }
    }
}
