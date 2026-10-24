package io.github.mobtalk.core;

import java.util.*;
import java.util.regex.Pattern;

/** Исповедь у священника (идея Б4): слова игрока («хочу исповедаться», «всё», хула), неизменные тексты Требника, выбор епитимьи
 * по смыслу греха, запасное сопоставление названного по словам и порядок самой исповеди ({@link Rite}). Исповедует только иерей —
 * житель-клирик мужского пола; что отпущено и сколько, считает Божественный блок ({@link DivineLink}), реплики озвучивает сервер.
 * Слова исповеди нигде не сохраняются. Чистая Java. */
public final class Confession {
    private Confession() { }

    // ---- Слова игрока. ----

    private static final List<String> ASKS = List.of("исповедаться", "исповедоваться", "исповедуй", "прими исповедь", "принять исповедь", "на исповедь",
            "исповедь прими", "хочу исповедь", "можно исповедь", "покаяться хочу", "хочу покаяться", "исповедуюсь", "исповедать");
    private static final List<String> RETURNS = List.of("исполнил епитимью", "исполнила епитимью", "выполнил епитимью", "выполнила епитимью",
            "сделал епитимью", "сделала епитимью", "епитимью исполнил", "епитимью исполнила", "епитимью выполнил", "епитимью выполнила", "я вернулся", "я вернулась");
    /** Игрок просит исповеди. */
    public static boolean asks(String said) { return contains(said, ASKS); }
    /** Игрок вернулся, исполнив епитимью. */
    public static boolean returns(String said) { return contains(said, RETURNS); }

    private static final List<String> DONE_PHRASES = List.of("больше ничего", "больше нет", "это всё", "вот и всё", "больше не помню",
            "нечего больше", "больше нечего", "на этом всё", "я всё сказал", "я всё сказала", "всё, батюшка", "всё, отче");
    private static final Set<String> DONE_WORDS = Set.of("все", "нет", "ничего", "аминь", "закончил", "закончила", "хватит");
    /** Игрок закончил исповедь: «всё», «больше ничего», короткое «нет» в ответ на «нет ли ещё». */
    public static boolean done(String said) {
        if (said == null) return false;
        String text = said.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String phrase : DONE_PHRASES) if (text.contains(phrase.replace('ё', 'е'))) return true;
        String[] words = text.replaceAll("[^\\p{L}\\s]", " ").strip().split("\\s+");
        if (words.length == 0 || words.length > 3) return false;
        for (String word : words) if (DONE_WORDS.contains(word)) return true;
        return false;
    }

    /** Мат и худшие слова, как у Божественного блока: корни узнаются в любом месте слова, короткие — только в начале. */
    private static final Pattern PROFANITY = Pattern.compile(
            "(?U)(ху[йёяю]|хуе[вт]|хуйн|пизд|п[иі]зж|[её]бан|[её]бат|[её]бну|бляд|блят|(?<![\\p{L}])([её]б[аеиуоыё]|[её]бл|бля|сук[аиу]|суч|мудак|мудил"
                    + "|гандон|долбо[её]б|говн|залуп|шлюх|пид[оа]р|нахер|похер|f+u+c+k|shit|bitch))", Pattern.CASE_INSENSITIVE);
    private static final List<String> MOCKERY = List.of("бога нет", "плевать на бога", "плевал я на", "к черту бога", "бог дурак", "ненавижу бога",
            "насрать на", "не верю в эту чушь", "чушь все это", "сам ты грешник", "иди ты", "отстань, поп", "поп толоконный");
    /** Мат или хула у аналоя: не покаяние, а глумление (искренность 0 решает код, а не модель). */
    public static boolean mocks(String said) {
        if (said == null || said.isBlank()) return false;
        return PROFANITY.matcher(said.toLowerCase(Locale.ROOT)).find() || contains(said, MOCKERY);
    }
    private static final List<String> CONTRITION = List.of("каюсь", "прости", "простите", "раскаива", "грешен", "грешна", "виноват", "виновата", "стыдно", "помилуй", "сожалею");
    /** Искренность по словам, когда классификатор молчит: 0 — глумление, 2 — слышно раскаяние, иначе 1. */
    public static int sincerity(String said) { return mocks(said) ? 0 : contains(said, CONTRITION) ? 2 : 1; }

    private static boolean contains(String said, List<String> stems) {
        if (said == null || said.isBlank()) return false;
        String text = said.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String stem : stems) if (text.contains(stem.toLowerCase(Locale.ROOT).replace('ё', 'е'))) return true;
        return false;
    }

    // ---- Неотпущенные дела и запасное сопоставление. ----

    /** Дело на душе, как его видит бог: id, вид ({@code villager_killed}…), слова, вес, тяжкое ли, повторное ли, недавно ли этот вид
     * отпускался. */
    public record Deed(long id, String kind, String text, double weight, boolean heavy, boolean relapse, boolean recent) {
        public Deed { kind = kind == null ? "" : kind; text = text == null ? "" : text; }
    }

    /** Основы слов, по которым игрок называет вид греха без модели. */
    static final Map<String, List<String>> STEMS = Map.ofEntries(
            Map.entry("villager_killed", List.of("убил", "убила", "убийств", "зарезал", "прикончил", "лишил жизни")),
            Map.entry("child_killed", List.of("ребен", "дитя", "малыш", "детей", "мальчик", "девочк")),
            Map.entry("priest_killed", List.of("священник", "батюшк", "клирик", "иерея")),
            Map.entry("golem_killed", List.of("голем")),
            Map.entry("pet_killed", List.of("питом", "собак", "волка", "кошк", "кота", "попугая")),
            Map.entry("slaughter", List.of("животн", "скот", "коров", "свин", "овец", "овцу", "куриц", "бойн")),
            Map.entry("vandalism", List.of("громил", "ломал", "разрушил", "разбил", "сломал", "кроват")),
            Map.entry("desecration", List.of("осквернил", "храм", "церк", "кладбищ", "могил", "святын")),
            Map.entry("blasphemy", List.of("хул", "ругал бога", "богохул", "бранил", "матерился", "материл", "проклинал бога", "оскорбил бога", "ругался на бога")),
            Map.entry("rude_prayer", List.of("хамил", "дерзил", "груб")));

    /** Какие дела игрок назвал, по словам (когда классификатор не ответил): вид узнаётся по основам слов. */
    public static Set<Long> namedByWords(String words, List<Deed> deeds) {
        Set<Long> named = new LinkedHashSet<>();
        if (words == null || words.isBlank()) return named;
        String text = " " + words.toLowerCase(Locale.ROOT).replace('ё', 'е') + " ";
        for (Deed deed : deeds)
            for (String stem : STEMS.getOrDefault(deed.kind(), List.of())) if (text.contains(stem.replace('ё', 'е'))) { named.add(deed.id()); break; }
        return named;
    }

    // ---- Епитимья. ----

    /** Виды епитимьи: что сделать (говорит батюшка), строка HUD и сколько раз (1 — одно дело или срок). */
    public enum Penance {
        RESTITUTION("возмещение", "возмести обиду: подари что-нибудь доброе жителю деревни", "подарок жителю деревни", 1),
        ALMS("милостыня", "подай милостыню бедному: три хлеба или пять изумрудов жителю без ремесла", "милостыня бедному: 3 хлеба или 5 изумрудов", 1),
        CANDLE("свеча", "поставь свечу у аналоя или у алтаря и помолись", "поставить свечу у аналоя или алтаря", 1),
        BOWS("поклоны", "положи двенадцать поклонов перед аналоем", "поклоны перед аналоем", 12),
        PRAYER("молитва", "помолись у алтаря с покаянием", "покаянная молитва у алтаря", 1),
        FAST("пост", "попостись: не ешь мяса и рыбы до завтра", "пост — без мяса и рыбы", 1),
        LABOR("труд на храм", "потрудись на храм: принеси священнику шестнадцать белого камня или дерева", "труд на храм: белый камень или дерево", 16);

        public final String title, task, hud;
        public final int target;
        Penance(String title, String task, String hud, int target) { this.title = title; this.task = task; this.hud = hud; this.target = target; }
        public String id() { return name().toLowerCase(Locale.ROOT); }
        public static Penance of(String id) {
            for (Penance p : values()) if (p.id().equals(id) || p.name().equals(id)) return p;
            return null;
        }
    }
    /** Какая епитимья подходит к виду греха. */
    static Penance fits(String kind) {
        return switch (kind == null ? "" : kind) {
            case "villager_killed", "child_killed" -> Penance.CANDLE;
            case "priest_killed", "golem_killed", "desecration" -> Penance.LABOR;
            case "pet_killed" -> Penance.ALMS;
            case "slaughter" -> Penance.FAST;
            case "vandalism" -> Penance.RESTITUTION;
            case "blasphemy" -> Penance.BOWS;
            default -> Penance.PRAYER;
        };
    }
    /** Одна епитимья по смыслу самого тяжкого названного греха; выключенные в настройках виды пропускаются (замена — свеча, поклоны,
     * молитва, милостыня, пост, труд, возмещение). Все выключены — молитва. */
    public static Penance choose(List<Deed> named, Collection<String> off) {
        Deed worst = named.stream().max(Comparator.comparingDouble((Deed d) -> (d.heavy() ? 1000 : 0) + d.weight())).orElse(null);
        List<Penance> order = new ArrayList<>();
        order.add(fits(worst == null ? "" : worst.kind()));
        order.addAll(List.of(Penance.CANDLE, Penance.BOWS, Penance.PRAYER, Penance.ALMS, Penance.FAST, Penance.LABOR, Penance.RESTITUTION));
        for (Penance p : order) if (off == null || !off.contains(p.id())) return p;
        return Penance.PRAYER;
    }
    /** Выключенные виды епитимьи из настройки (id через запятую). */
    public static Set<String> off(String setting) {
        Set<String> out = new LinkedHashSet<>();
        if (setting == null) return out;
        for (String id : setting.split(",")) { String clean = id.strip().toLowerCase(Locale.ROOT); if (Penance.of(clean) != null) out.add(clean); }
        return out;
    }

    // ---- Тексты Требника и неизменные реплики. ----

    /** Увещание перед исповедью по Требнику («Последование исповеди») — две реплики — и его русский перевод. */
    public static final List<String> EXHORTATION = List.of(
            "Се, чадо, Христос невидимо стоит, приемля исповедание твое. Не устыдися, ниже убойся, и да не скрыеши что от мене, но не обинуяся рцы вся, "
                    + "елика соделал еси, да приимеши оставление от Господа нашего Иисуса Христа.",
            "Се и икона Его пред нами: аз же точию свидетель есмь, да свидетельствую пред Ним вся, елика речеши мне. Аще же скрыеши что от мене, "
                    + "сугуб грех имаши. Внемли убо: понеже пришел еси во врачебницу, да не неисцелен отыдеши.");
    public static final List<String> EXHORTATION_RU = List.of(
            "Вот, чадо, Христос невидимо стоит здесь, принимая твою исповедь. Не стыдись и не бойся, ничего не скрывай от меня, но без смущения "
                    + "скажи всё, что сделал, и получишь прощение от Господа нашего Иисуса Христа.",
            "Вот и икона Его перед нами, а я только свидетель, чтобы засвидетельствовать перед Ним всё, что ты мне скажешь. Если же что скроешь "
                    + "от меня — двойной грех на тебе. Внимай же: раз пришёл ты во врачебницу, не уйди неисцелённым.");
    /** Разрешительная молитва (русская практика) с именем кающегося. */
    public static String absolution(String name) {
        return "Господь и Бог наш Иисус Христос, благодатию и щедротами Своего человеколюбия, да простит ти, чадо " + clean(name)
                + ", вся согрешения твоя: и аз, недостойный иерей, властию Его мне данною, прощаю и разрешаю тя от всех грехов твоих, "
                + "во имя Отца, и Сына, и Святаго Духа. Аминь.";
    }
    public static String absolutionRu(String name) {
        return "Господь и Бог наш Иисус Христос по благодати и щедротам Своего человеколюбия да простит тебе, чадо " + clean(name)
                + ", все согрешения твои; и я, недостойный иерей, властью Его, мне данной, прощаю и разрешаю тебя от всех грехов твоих "
                + "во имя Отца, и Сына, и Святого Духа. Аминь.";
    }
    private static String clean(String name) { return name == null || name.isBlank() ? "Божие" : name.strip(); }

    public static String welcome(boolean temple) { return temple ? "Подойди, чадо, к аналою." : "Добре, чадо. Подойди поближе, встань передо мной."; }
    public static final String ASK_MORE = "Нет ли ещё чего на душе, чадо?";
    public static final String BOW = "Преклони главу, чадо.";
    public static final String RETURN_GREETING = "Исполнил епитимью, чадо? Слава Богу. Подойди.";
    public static final String REFUSAL = "Не покаяние это, чадо, а глумление. Не могу разрешить тебя. Приходи, когда сердце смирится.";
    public static final String SILENT = "Молчишь, чадо… Видно, не время. Приходи, когда будешь готов.";
    public static final String NIGHT = "Ночь на дворе, чадо. Приходи днём.";
    public static final String RAID = "Не время, чадо, — на деревню напали!";
    public static final String BUSY = "Подожди, чадо: исповедую другого. Позову, как освобожусь.";
    public static final String TODAY = "Ты уже исповедовался ныне, чадо. Приходи завтра.";
    public static final String ABBOTS_ONLY = "Исповедь принимают в храме, у настоятеля. Ступай в церковь, чадо.";
    public static final String NOT_DONE_YET = "Епитимья твоя ещё не исполнена, чадо.";
    public static String matushka(String priest) {
        return "Я матушка, чадо, а исповедь принимает батюшка." + (priest == null || priest.isBlank() ? " Ступай к батюшке." : " Ступай к " + priest + ".");
    }
    public static String pending(Penance penance) { return "Сперва исполни епитимью, чадо: " + penance.task + "."; }
    public static String cover(String priest) { return "* " + priest + " накрывает голову кающегося епитрахилью *"; }
    public static String kiss(String player) { return "* " + player + " целует Крест и Евангелие *"; }
    /** Что игрок чувствует после: слова; числа — только в отладке и админке. */
    public static String afterwards(boolean lighter) { return lighter ? "Душе стало легче." : "На душе ещё тяжело."; }
    public static String penanceLine(Penance penance, boolean heavy) {
        return "Епитимья тебе: " + penance.task + "." + (heavy ? " Исполни и приходи — тогда разрешу тебя и от остального." : "");
    }
    public static final String RELAPSE = "Опять тот же грех, чадо? Только недавно я разрешал тебя от него.";

    private static final List<String> COUNSEL = List.of(
            "Бог милостив, чадо, и ждёт тебя. Не унывай, но и к прежнему не возвращайся: что признал — то и оставь.",
            "Хорошо, что пришёл, чадо. Грех признанный — уже наполовину побеждённый. Береги себя и не повторяй худого.",
            "Господь видит сердце твоё. Живи тише, будь добрее к людям и к твари Божией — и душа найдёт покой.");
    /** Наставление без модели: доброе слово, при надобности замечание о повторном грехе и епитимья. */
    public static String fallbackCounsel(String player, boolean relapse, Penance penance, boolean heavy) {
        String counsel = COUNSEL.get(Math.floorMod(Objects.hashCode(player), COUNSEL.size()));
        return (relapse ? RELAPSE + " " : "") + counsel + (penance == null ? "" : " " + penanceLine(penance, heavy));
    }
    /** Модель назвала епитимью своими словами? */
    static boolean mentions(String counsel, Penance penance) {
        if (counsel == null || penance == null) return false;
        String text = counsel.toLowerCase(Locale.ROOT).replace('ё', 'е');
        String key = switch (penance) {
            case CANDLE -> "свеч"; case BOWS -> "поклон"; case PRAYER -> "помол"; case FAST -> "пост"; case ALMS -> "милостын";
            case LABOR -> "храм"; case RESTITUTION -> "подар";
        };
        return text.contains(key);
    }
    /** Наставление модели с непременной епитимьей: не назвала — дописывается неизменная строка. */
    public static String withPenance(String counsel, Penance penance, boolean heavy) {
        String text = counsel == null ? "" : counsel.strip();
        if (penance == null || mentions(text, penance)) return text;
        return (text.isEmpty() ? "" : text + " ") + penanceLine(penance, heavy);
    }

    // ---- Порядок исповеди. ----

    /** Где исповедь: в храме у настоятеля или у сельского батюшки. */
    public enum Place { TEMPLE, VILLAGE; public String id() { return this == TEMPLE ? "temple" : "village"; } }

    /** Одна реплика исповеди: текст, перевод для чата, слышна ли всем рядом, ремарка ли (без голоса). */
    public record Line(int seq, String speaker, String text, String translation, boolean audible, boolean action) { }

    /** Исповедь одного игрока у одного священника: фазы, очередь реплик, слова по заходам, названное, искренность, наставление,
     * епитимья. Время — миллисекунды. Методы синхронизированы: сцена (поток сервера) и запросы к модели (пул) трогают её вместе.
     * Ничего не ждёт вечно: сторож отменяет зависшее, тишина кончает заход. */
    public static final class Rite {
        public enum Phase { GATHER, EXHORT, LISTEN, THINK, COUNSEL, BOW, ABSOLVE, KISS, DONE, CANCELLED }
        public static final long GATHER_MILLIS = 45_000, SILENCE_MILLIS = 30_000, BOW_MILLIS = 20_000, THINK_MILLIS = 20_000, WATCHDOG_MILLIS = 8 * 60_000;

        public final UUID priest, player;
        public final String priestName, playerName;
        public final Place place;
        /** Возвращение после епитимьи: только разрешительная молитва и целование (шаги 6–7). */
        public final boolean returning;
        public final List<Deed> deeds;
        /** На игроке идёт кара: и лёгкое ждёт епитимьи. */
        public final boolean punished;
        private final int rounds;
        private final boolean slavonic;
        private final Set<String> penancesOff;
        private final long started;
        private Phase phase = Phase.GATHER;
        private long phaseSince;
        private final Deque<Line> queue = new ArrayDeque<>();
        private Line speaking;
        private int seq;
        private final List<String> words = new ArrayList<>();
        private int pendingClassify;
        private long lastHeard;
        private final Set<Long> named = new LinkedHashSet<>();
        private int sincerity = 1;
        private boolean mocked, sincere, askedCounsel, askedAbsolve, absolved, refused, lighter, silent;
        private String counsel = "";
        private Penance penance;
        private String cancelReason = "";
        private final Deque<Integer> classifyRequests = new ArrayDeque<>();

        public Rite(UUID priest, String priestName, UUID player, String playerName, Place place, boolean returning, List<Deed> deeds, boolean punished,
                    int rounds, boolean slavonic, Collection<String> penancesOff, long now) {
            this.priest = priest; this.priestName = priestName; this.player = player; this.playerName = playerName; this.place = place;
            this.returning = returning; this.deeds = List.copyOf(deeds); this.punished = punished; this.rounds = Math.max(1, Math.min(3, rounds));
            this.slavonic = slavonic; this.penancesOff = Set.copyOf(penancesOff == null ? Set.of() : penancesOff); this.started = now; this.phaseSince = now;
            say(returning ? RETURN_GREETING : welcome(place == Place.TEMPLE), "", false);
        }

        public synchronized Phase phase() { return phase; }
        public synchronized boolean over() { return phase == Phase.DONE || phase == Phase.CANCELLED; }
        /** Ждёт слов игрока: идёт заход, батюшка молчит. */
        public synchronized boolean listening() { return phase == Phase.LISTEN && speaking == null && queue.isEmpty(); }
        /** Ждёт, когда игрок преклонит главу (присядет). */
        public synchronized boolean wantsBow() { return phase == Phase.BOW && speaking == null && queue.isEmpty(); }
        public synchronized Penance penance() { return penance; }
        public synchronized Set<Long> named() { return Set.copyOf(named); }
        public synchronized int sincerity() { return sincerity; }
        public synchronized boolean refused() { return refused; }
        /** Отказ из-за молчания: исповеди не было, откат не тратится. */
        public synchronized boolean silent() { return silent; }
        public synchronized boolean lighter() { return lighter; }
        public synchronized String counsel() { return counsel; }
        public synchronized String cancelReason() { return cancelReason; }
        public synchronized int rounds() { return words.size(); }
        /** Всё, что игрок сказал (только для журнала, если админ включил запись текста). */
        public synchronized String words() { return String.join(" / ", words); }
        /** Названные дела: только их видит наставление. */
        public synchronized List<Deed> namedDeeds() { return deeds.stream().filter(d -> named.contains(d.id())).toList(); }
        /** В словах было глумление: наставления не будет, только отказ. */
        public synchronized boolean mocking() { return mocked; }
        /** Епитимья, которую наложит батюшка по названному сейчас (её же получит наставление); null — не нужна. */
        public synchronized Penance plannedPenance() { return needsReturn() ? choose(namedDeeds(), penancesOff) : null; }
        /** Тяжкое среди названного (или что-то названное под идущей карой) ждёт епитимьи и возвращения. */
        public synchronized boolean needsReturn() { return !refused && namedDeeds().stream().anyMatch(d -> d.heavy() || punished); }

        private void say(String text, String translation, boolean audible) { queue.add(new Line(++seq, priestName, text, translation, audible, false)); }
        private void act(String text, boolean audible) { queue.add(new Line(++seq, priestName, text, "", audible, true)); }
        private void enter(Phase next, long now) { phase = next; phaseSince = now; }

        /** Следующая реплика, если сейчас ничего не звучит. */
        public synchronized Line claim() {
            if (speaking != null || queue.isEmpty() || over()) return null;
            speaking = queue.poll();
            return speaking;
        }
        /** Реплика договорена (или пропущена): исповедь идёт дальше. */
        public synchronized void lineDone(int lineSeq, long now) {
            if (speaking == null || speaking.seq() != lineSeq) return;
            speaking = null;
            if (queue.isEmpty()) advance(now);
        }
        /** Заход, который пора отдать классификатору (номер), или null. */
        public synchronized Integer takeClassify() { return classifyRequests.poll(); }
        public synchronized String roundWords(int index) { return index >= 0 && index < words.size() ? words.get(index) : ""; }
        /** Пора писать наставление: заходы разобраны (или классификатор ждали достаточно). Отдаётся один раз. */
        public synchronized boolean takeCounsel(long now) {
            if (phase != Phase.THINK || askedCounsel) return false;
            if (pendingClassify > 0 && now - phaseSince < THINK_MILLIS) return false;
            askedCounsel = true;
            return true;
        }
        /** Пора просить отпущения у бога (игрок преклонил главу). Отдаётся один раз. */
        public synchronized boolean takeAbsolve() {
            if (phase != Phase.ABSOLVE || askedAbsolve) return false;
            askedAbsolve = true;
            return true;
        }

        /** Игрок и священник на местах (или сбор вышел по времени): увещание, а при возвращении — сразу «преклони главу». */
        public synchronized void gathered(long now) {
            if (phase != Phase.GATHER) return;
            if (returning) { enter(Phase.BOW, now); say(BOW, "", false); return; }
            enter(Phase.EXHORT, now);
            for (int i = 0; i < EXHORTATION.size(); i++) say(slavonic ? EXHORTATION.get(i) : EXHORTATION_RU.get(i), slavonic ? EXHORTATION_RU.get(i) : "", false);
        }
        /** Слова игрока в заходе копятся и уходят классификатору; «всё» или последний заход кончает исповедание, иначе «нет ли ещё». */
        public synchronized void heard(String text, long now) {
            if (phase != Phase.LISTEN || text == null || text.isBlank()) return;
            lastHeard = now;
            boolean finished = done(text);
            if (mocks(text)) mocked = true;
            if (Confession.sincerity(text) == 2) sincere = true;
            boolean onlyDone = finished && text.strip().split("\\s+").length <= 3;
            if (!onlyDone) {
                words.add(text.strip());
                classifyRequests.add(words.size() - 1);
                pendingClassify++;
            }
            queue.clear();
            if (finished || words.size() >= rounds) { enter(Phase.THINK, now); return; }
            say(ASK_MORE, "", false);
        }
        /** Ответ классификатора на заход: названные id и искренность (мат и хула в словах сильнее модели). */
        public synchronized void classified(Set<Long> ids, int sincerityOfRound) {
            pendingClassify = Math.max(0, pendingClassify - 1);
            for (Long id : ids) if (deeds.stream().anyMatch(d -> d.id() == id)) named.add(id);
            if (sincerityOfRound <= 0) mocked = true;
            if (sincerityOfRound >= 2) sincere = true;
        }
        /** Классификатор не ответил: сопоставление по словам захода. */
        public synchronized void classifyFailed(int index) {
            String text = roundWords(index);
            classified(namedByWords(text, deeds), Confession.sincerity(text));
        }
        /** Наставление (модель или запасное, пусто — запасное): потом епитимья и «преклони главу». Глумление — отказ без молитвы. */
        public synchronized void counselled(String text, long now) {
            if (phase != Phase.THINK) return;
            sincerity = mocked ? 0 : sincere ? 2 : 1;
            if (sincerity == 0) {
                refused = true;
                enter(Phase.KISS, now);
                say(REFUSAL, "", false);
                return;
            }
            List<Deed> mine = namedDeeds();
            boolean heavy = needsReturn();
            penance = heavy ? choose(mine, penancesOff) : null;
            counsel = text == null || text.isBlank() ? fallbackCounsel(playerName, mine.stream().anyMatch(d -> d.relapse() || d.recent()), penance, heavy)
                    : withPenance(text, penance, heavy);
            enter(Phase.COUNSEL, now);
            say(counsel, "", false);
        }
        /** Игрок присел — преклонил главу: дальше отпущение у бога. */
        public synchronized void bowed(long now) { if (wantsBow()) enter(Phase.ABSOLVE, now); }
        /** Бог отпустил (или отложил тяжкое): епитрахиль и разрешительная молитва (её слышат все рядом), затем целование Креста.
         * {@code lighter} — душе стало легче: что-то отпущено сейчас. */
        public synchronized void absolvedNow(boolean lighter, long now) {
            if (phase != Phase.ABSOLVE || absolved) return;
            absolved = true; this.lighter = lighter;
            act(cover(priestName), true);
            say(absolution(playerName), slavonic ? absolutionRu(playerName) : "", true);
        }
        /** Отменить исповедь: без отпущения, откат не тратится. */
        public synchronized void cancel(String reason, long now) {
            if (over()) return;
            cancelReason = reason == null ? "" : reason;
            queue.clear(); speaking = null;
            enter(Phase.CANCELLED, now);
        }
        /** Раз в тик: сбор и поклон по времени, тишина в заходе, сторож. */
        public synchronized void tick(long now) {
            if (over()) return;
            if (now - started > WATCHDOG_MILLIS) { cancel("исповедь затянулась", now); return; }
            switch (phase) {
                case GATHER -> { if (now - phaseSince > GATHER_MILLIS) gathered(now); }
                case LISTEN -> {
                    if (speaking != null || !queue.isEmpty()) break;
                    if (now - Math.max(lastHeard, phaseSince) <= SILENCE_MILLIS) break;
                    if (words.isEmpty()) { silent = true; refused = true; say(SILENT, "", false); enter(Phase.KISS, now); }
                    else enter(Phase.THINK, now);
                }
                case THINK -> { if (now - phaseSince > THINK_MILLIS * 3) counselled("", now); }
                case BOW -> { if (wantsBow() && now - phaseSince > BOW_MILLIS) bowed(now); }
                default -> { }
            }
        }
        private void advance(long now) {
            switch (phase) {
                case EXHORT -> { enter(Phase.LISTEN, now); lastHeard = now; }
                case LISTEN, BOW -> phaseSince = now; // «нет ли ещё», «преклони главу» договорены: время считается с этой минуты
                case COUNSEL -> { enter(Phase.BOW, now); say(BOW, "", false); }
                case ABSOLVE -> { if (absolved) { enter(Phase.KISS, now); act(kiss(playerName), true); } }
                case KISS -> enter(silent ? Phase.CANCELLED : Phase.DONE, now);
                default -> { }
            }
        }
    }
}
