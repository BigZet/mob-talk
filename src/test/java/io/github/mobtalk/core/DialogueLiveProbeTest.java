package io.github.mobtalk.core;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Live probe of whole conversations against OpenRouter on the user's own key: villagers of different trades with goods, in
 * different moods, a player who haggles, gifts, jokes, hits and apologises; an apathetic one shaken; a pillager; a wandering
 * trader. Each scenario is a few turns of one talk; {@link PromptEcho} marks where the model leans on its prompt (copies it,
 * repeats its sample lines, keeps to one motif). Off by default; {@code MOBTALK_LIVE=1} runs it, {@code MOBTALK_LIVE_OUT} names
 * the Markdown file. Speech goes to a dead local port: no TTS. */
class DialogueLiveProbeTest {
    @TempDir Path directory;
    private static final long MINUTE = 60_000L;

    private record Scenario(String title, String type, String name, boolean female, String story, Map<String, Object> details,
                            BiConsumer<ConversationStore, UUID> setup, List<String> turns) { }

    private static Map<String, Object> offer(String buyName, int buyCount, String sellName, int sellCount) {
        return Map.of("buy", Map.of("name", buyName, "count", buyCount), "secondBuy", Map.of("count", 0), "sell", Map.of("name", sellName, "count", sellCount));
    }
    private static Map<String, Object> trade(String profession, int level, List<Map<String, Object>> offers) {
        return Map.of("profession", profession, "level", level, "offers", offers, "bargaining", Map.of("open", false, "status", "NONE"));
    }
    private static final List<String> HAGGLE = List.of("Привет! Что у тебя есть на продажу?", "Дороговато. Скинь цену, а? По-соседски.",
            "А если я тебе тыкву подарю, уступишь?", "Ладно, бывай, загляну ещё.");

    private List<Scenario> scenarios(UUID player) {
        long now = System.currentTimeMillis();
        Map<String, Object> farmer = trade("minecraft:farmer", 2, List.of(offer("Морковь", 22, "Изумруд", 1), offer("Изумруд", 1, "Хлеб", 6)));
        Map<String, Object> librarian = trade("minecraft:librarian", 3, List.of(offer("Изумруд", 9, "Зачарованная книга «Починка»", 1), offer("Бумага", 24, "Изумруд", 1)));
        Map<String, Object> armorer = trade("minecraft:armorer", 2, List.of(offer("Изумруд", 7, "Железный нагрудник", 1), offer("Уголь", 15, "Изумруд", 1)));
        Map<String, Object> cleric = trade("minecraft:cleric", 2, List.of(offer("Изумруд", 1, "Красная пыль", 2), offer("Гнилая плоть", 32, "Изумруд", 1)));
        return List.of(
                new Scenario("Фермер, обычное настроение, торг", "minecraft:villager", "Кузьма", false, "Ты фермер в Ольховке, растишь морковь у реки.", farmer,
                        (s, m) -> { }, HAGGLE),
                new Scenario("Библиотекарь, счастлив, торг", "minecraft:villager", "Елисей", false, "Ты библиотекарь в Ольховке, собираешь редкие книги.", librarian,
                        (s, m) -> s.beginSpell(m, MoodSpell.JOY, now - 10 * MINUTE), HAGGLE),
                new Scenario("Бронник, озлоблен на игрока (три оскорбления), торг", "minecraft:villager", "Прохор", false, "Ты бронник в Ольховке, куёшь нагрудники.", armorer,
                        (s, m) -> { for (int i = 0; i < 3; i++) s.recordMood(m, MobState.Event.INSULT, "Стив", now + i); }, HAGGLE),
                new Scenario("Жрица, запугана игроком, торг", "minecraft:villager", "Марфа", true, "Ты жрица в Ольховке, варишь зелья у стойки.", cleric,
                        (s, m) -> { for (int i = 0; i < 3; i++) s.recordMood(m, MobState.Event.THREAT, "Стив", now + i); }, HAGGLE),
                new Scenario("Фермерша, сильный голод, игрок кормит", "minecraft:villager", "Дарья", true, "Ты фермерша в Ольховке, растишь пшеницу.", farmer,
                        (s, m) -> {
                            s.surroundings(m, null, new MoodSample(Set.of(), Set.of(), true, false, false, 1, false), now - 5L * Tuning.get().moodHungerMinutes * MINUTE);
                            s.surroundings(m, null, new MoodSample(Set.of(), Set.of(), true, false, false, 1, false), now);
                        },
                        List.of("Привет! Как торговля?", "Ты чего такая бледная?", "Держи, вот тебе хлеб, поешь.", "Теперь скинешь цену на морковь?")),
                new Scenario("Жительница в трауре, игрок шутит", "minecraft:villager", "Ульяна", true, "Ты ткачиха в Ольховке.", Map.of("profession", "minecraft:shepherd", "level", 1),
                        (s, m) -> s.recordMood(m, MobState.Event.BEREAVED, "Фёдор", now),
                        List.of("Чего такая кислая? Анекдот хочешь?", "Да ладно, не раскисай, всё пройдёт!", "Ой… прости, я не знал.")),
                new Scenario("Житель, которого игрок только что ударил", "minecraft:villager", "Тихон", false, "Ты рыбак в Ольховке.", Map.of("profession", "minecraft:fisherman", "level", 1),
                        (s, m) -> s.recordDamage(m, "minecraft:villager", "Житель", player, "Стив", now),
                        List.of("Ой, извини, я промахнулся!", "Ну правда случайно, я по зомби бил!", "Давай мириться? Вот тебе изумруд.")),
                new Scenario("Житель в апатии, игрок тормошит", "minecraft:villager", "Савва", false, "Ты каменщик в Ольховке.", Map.of("profession", "minecraft:mason", "level", 2),
                        (s, m) -> s.beginSpell(m, MoodSpell.APATHY, now - 30 * MINUTE),
                        List.of("Эй, вставай! Пошли гулять, день какой хороший!", "Я тебе пирог принёс, тыквенный.", "Ну скажи хоть что-нибудь, что с тобой?")),
                new Scenario("Девушка игрока ревнует и злится, игрок ухаживает и извиняется", "minecraft:villager", "Светлана", true, "Ты библиотекарша в Ольховке.",
                        Map.of("profession", "minecraft:librarian", "level", 1),
                        (s, m) -> {
                            UUID rival = villager(true);
                            synchronized (s) {
                                s.ensure(rival, "minecraft:villager", "Житель").givenName = "Евгения";
                                s.state.profiles.get(rival.toString()).villageId = "v1";
                                s.state.profiles.get(m.toString()).villageId = "v1";
                                ConversationStore.Relation r = s.relation(s.state.profiles.get(m.toString()), player);
                                r.rapport = new Rapport(); r.rapport.courtship(Courtship.DATING); r.score = 100;
                            }
                            s.jealousy().noticed(rival, player, "Стив", Jealousy.Cause.GIFT, 4, "золотое яблоко", Map.of(m, 3.0), now - 6 * MINUTE);
                            s.jealousy().noticed(rival, player, "Стив", Jealousy.Cause.FAVOR, 0, "", Map.of(), now - 5 * MINUTE);
                            s.beginSpell(m, MoodSpell.ANGER, now - 3 * MINUTE);
                        },
                        List.of("Пойдём погуляем вместе этим тихим вечером.", "Смотри, какие красивые звёзды сегодня. Какая тихая ночь.",
                                "Как я могу тебя ласково называть? Светлана звучит как-то официально.", "Прости меня, если я тебя обидел.")),
                new Scenario("Разбойник у деревни", "minecraft:pillager", "", false, "", Map.of(),
                        (s, m) -> { },
                        List.of("Уходи из деревни, разбойник!", "Давай договоримся: дам изумруд — уйдёшь?", "Ну всё, сейчас я тебя зарублю!")),
                new Scenario("Странствующий торговец", "minecraft:wandering_trader", "", false, "", Map.of(),
                        (s, m) -> { },
                        List.of("Здравствуй! Что везёшь?", "Откуда ты родом?", "А где ты ночуешь, не страшно одному?")));
    }

    @Test void conversationsInScenarios() throws Exception {
        assumeTrue("1".equals(System.getenv("MOBTALK_LIVE")), "live probes run only with MOBTALK_LIVE=1");
        String key = OpenRouterToken.load(Path.of(System.getenv().getOrDefault("MOBTALK_CONFIG_DIR", "config-not-here")));
        MobTalkConfig config = new MobTalkConfig();
        Tuning.apply(config);
        StringBuilder body = new StringBuilder(), summary = new StringBuilder();
        int answered = 0, findings = 0, calls = 0;
        try (OpenRouterConversationClient llm = new OpenRouterConversationClient(key, config)) {
            ConversationEngine engine = new ConversationEngine(config, directory, llm, new OpenRouterSpeechClient(key, config, URI.create("http://127.0.0.1:9/speech")));
            UUID player = UUID.randomUUID();
            for (Scenario sc : scenarios(player)) {
                UUID mob = sc.type().equals("minecraft:villager") ? villager(sc.female()) : UUID.randomUUID();
                engine.memory().observe(snapshot(sc, mob, player, List.of(), 0));
                if (!sc.story().isEmpty()) engine.memory().setBackstory(mob, sc.name(), sc.story(), "", "", "");
                sc.setup().accept(engine.memory(), mob);
                MobState.View view = engine.memory().stateView(mob, player);
                body.append("\n## ").append(sc.title()).append("\n\n");
                if (view != null) body.append("Состояние: ").append(view.marker() == null ? "ровно" : view.marker().word()).append(" · настроение ").append(view.mood())
                        .append(", спокойствие ").append(view.calm()).append(", доверие ").append(view.trust())
                        .append(view.reasons().isEmpty() ? "" : " · причины: " + String.join(", ", view.reasons().stream().map(MobState.Reason::text).toList())).append("\n\n");
                List<String> replies = new ArrayList<>(), prompts = new ArrayList<>();
                for (String turn : sc.turns()) {
                    MobSnapshot current = snapshot(sc, mob, player, engine.memory().events(mob), engine.memory().revision(mob));
                    prompts.add(llm.systemPrompt(SpeciesCatalog.get(sc.type()), engine.memory().profile(current), current));
                    long started = System.nanoTime();
                    ConversationEngine.PreparedReply prepared = engine.prepare(current, turn);
                    double seconds = (System.nanoTime() - started) / 1e9;
                    calls++;
                    String line = prepared.reply().responseText();
                    replies.add(line);
                    if (!line.isBlank()) answered++;
                    body.append("> **Игрок:** ").append(turn).append("\n>\n> **").append(prepared.reply().speakerName()).append(":** ")
                            .append(line.isBlank() ? "(промолчал)" : line).append(String.format(Locale.ROOT, " _(%.1f с)_", seconds)).append("\n\n");
                    engine.commitDelivered(prepared, engine.memory().revision(mob));
                }
                List<PromptEcho.Finding> echo = PromptEcho.check(replies, prompts, sc.turns().stream().map(t -> t + " Стив").toList()); // calling the player by name is no echo
                findings += echo.size();
                if (echo.isEmpty()) body.append("Держится ли за промпт: замечаний нет.\n");
                else {
                    body.append("**Держится за промпт:**\n");
                    for (PromptEcho.Finding f : echo) body.append("- ").append(f.kind()).append(": «").append(f.text()).append("»\n");
                    summary.append("- ").append(sc.title()).append(": ").append(String.join("; ", echo.stream().map(f -> f.kind() + " «" + f.text() + "»").toList())).append('\n');
                }
            }
            engine.close();
            assertTrue(answered > 0, "no reply came back");
        } finally {
            String out = "# Живая проба диалогов в сценариях\n\nМодель: `" + config.chatModel() + "`, вызовов: " + calls + ", без озвучки.\n\n"
                    + "## Где модель держится за промпт\n\n" + (summary.isEmpty() ? "Замечаний нет.\n" : summary) + body;
            String path = System.getenv("MOBTALK_LIVE_OUT");
            if (path != null && !path.isBlank()) Files.writeString(Path.of(path), out, StandardCharsets.UTF_8);
            System.out.println(out);
        }
    }

    private static UUID villager(boolean female) {
        while (true) {
            UUID id = UUID.randomUUID();
            if ((SocialProfile.of(id, false).gender() == Gender.FEMALE) == female) return id;
        }
    }

    private static MobSnapshot snapshot(Scenario sc, UUID mob, UUID player, List<String> events, long revision) {
        String name = SpeciesCatalog.get(sc.type()).name();
        return new MobSnapshot(mob, sc.type(), name, 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 3,
                "minecraft:stick", List.of(), events, player, Map.of("name", "Стив", "health", 20, "maxHealth", 20),
                new HashMap<>(sc.details()), Map.of(), List.of(), List.of(ActionType.NONE), revision);
    }
}
