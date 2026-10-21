package io.github.divineblock.core;

import java.util.List;
import java.util.Locale;

/** The fixed catalogue of what the god can do. Jev and the rules pick an id from it; the game code performs it and checks
 * every parameter. Tiers run 0 (omen) .. 4 (apocalypse); {@code minTier} is the least power the act needs. */
public enum Miracle {
    // Omens: the god answers without changing anything.
    OMEN_LIGHT(Kind.OMEN, 0, 0, false, "столб света над алтарём и хор", List.of("знак", "свет", "услышь")),
    OMEN_THUNDER(Kind.OMEN, 0, 0, false, "гром без молнии, небо темнеет", List.of("гром", "гнев")),
    OMEN_LAUGH(Kind.OMEN, 0, 0, false, "хохот с неба и дым над алтарём", List.of("смех", "шутк")),
    IGNORE(Kind.OMEN, 0, 0, false, "бог молчит, алтарь тускнеет", List.of()),

    // Help.
    HEAL(Kind.HELP, 1, 3, false, "исцеление и снятие дурных эффектов", List.of("исцел", "вылеч", "здоров", "болит", "ран", "умира", "heal")),
    FEED(Kind.HELP, 1, 2, false, "сытость и дождь из жареного мяса", List.of("еда", "еды", "голод", "есть хочу", "покорми", "хлеб", "food")),
    WEATHER_CLEAR(Kind.HELP, 1, 2, false, "ясная погода, а на силе 2 ещё и утро", List.of("солнц", "ясн", "дождь", "гроз", "утр", "ночь", "погод")),
    BUFF(Kind.HELP, 1, 3, false, "скорость, сила, ночное зрение и удача на несколько минут", List.of("сил", "быстр", "благослов", "удач", "сражен")),
    GIFT_ITEM(Kind.HELP, 1, 3, false, "предмет по названию из просьбы, число и редкость по силе", List.of("дай", "подари", "хочу", "нужен", "нужна", "нужно", "пошли", "give")),
    ORE_VEIN(Kind.HELP, 2, 3, false, "жила руды под ногами (на силе 3 алмазы)", List.of("руд", "алмаз", "желез", "золот", "шахт", "богатств")),
    ENCHANT(Kind.HELP, 2, 3, false, "зачарование предмета в руке", List.of("зачар", "чары", "меч", "кирк", "оруж")),
    REPAIR(Kind.HELP, 2, 2, false, "починка инструмента в руке и брони", List.of("почин", "сломал", "ремонт", "repair")),
    GUARDIAN(Kind.HELP, 2, 3, false, "ручной волк, а на силе 3 железный голем-защитник", List.of("защит", "охран", "друг", "один", "страшно", "волк", "голем")),
    GROW(Kind.HELP, 2, 2, false, "посевы и саженцы вокруг мгновенно растут", List.of("урожа", "рост", "посев", "ферм", "дерев", "саженц")),
    SMITE_ENEMIES(Kind.HELP, 2, 3, false, "молнии по враждебным мобам рядом", List.of("монстр", "зомби", "скелет", "враг", "спаси", "помоги", "крипер")),
    SAFE_HOME(Kind.HELP, 3, 3, false, "перенос к кровати или точке возрождения", List.of("домой", "дом", "заблуд", "верни")),
    TOTEM(Kind.HELP, 3, 4, false, "тотем бессмертия", List.of("бессмерт", "тотем", "жизн", "не умереть")),
    BLESSING(Kind.HELP, 4, 4, false, "долгое благословение: регенерация, удача, сопротивление на игровой день", List.of("благослов", "милост", "навсегда")),

    // Jests: humiliating, never lethal.
    CHICKEN_RAIN(Kind.JEST, 1, 2, false, "дождь из куриц", List.of("куриц", "птиц", "мяс")),
    SWAP_ITEMS(Kind.JEST, 1, 1, false, "перемешать хотбар", List.of("порядок", "инвентар")),
    FLIP(Kind.JEST, 1, 2, false, "мир переворачивается: смертный и все твари вокруг ходят вверх ногами", List.of("переверн", "наоборот", "вверх ногами")),
    MOON_GRAVITY(Kind.JEST, 1, 2, false, "лунная гравитация: прыжки и медленное падение на минуту", List.of("летать", "прыг", "крыль", "полёт", "полет")),
    TRANSMUTE(Kind.JEST, 2, 2, false, "предмет в руке превращается во что-то «похожее» (алмаз в стекло, меч в палку)", List.of("преврат", "золото", "алхим")),
    RANDOM_TP(Kind.JEST, 2, 3, false, "телепорт в случайное место за 100–200 блоков", List.of("далеко", "путешеств", "уйти", "телепорт")),
    TIME_RUSH(Kind.JEST, 1, 2, false, "круговорот дней: солнце и луна мчатся по небу, сутки за три секунды", List.of("время", "день", "ночь", "солнц", "рассвет")),
    SHEEP_PARTY(Kind.JEST, 1, 2, false, "радужные овцы водят хоровод вокруг смертного", List.of("овц", "радуг", "праздник", "весел")),
    FISH_RAIN(Kind.JEST, 1, 2, false, "дождь из рыбы: треска и лосось шлёпаются с неба", List.of("рыб", "улов", "ужин")),
    CREEPER_PRANK(Kind.JEST, 1, 1, false, "шипение крипера за спиной и дым — но взрыва нет", List.of("испуг", "страх", "крипер")),
    PUMPKIN_HEAD(Kind.JEST, 2, 2, false, "на голову смертного садится тыква, шлем падает в инвентарь", List.of("тыкв", "голов", "шлем", "красот")),
    LEVITATE(Kind.JEST, 2, 2, false, "смертного поднимает в воздух на несколько секунд и мягко опускает", List.of("взлет", "взлёт", "небес", "вознес")),
    DIZZY(Kind.JEST, 2, 2, false, "хмель небесный: голова кругом, ноги несут сами", List.of("вино", "пьян", "хмел", "мёд", "мед")),

    // Wrath.
    LIGHTNING(Kind.WRATH, 1, 3, false, "молния в самого грешника (на силе 3 — три удара)", List.of("молни", "гром")),
    CURSE_MINOR(Kind.WRATH, 1, 2, false, "голод, слабость, медлительность и неудача на несколько минут", List.of("прокля", "сдохни")),
    SWARM(Kind.WRATH, 1, 3, false, "рой: чешуйницы, злые пчёлы, на силе 3 фантомы", List.of("пчёл", "пчел", "насеком")),
    CURSE_TOOL(Kind.WRATH, 2, 2, false, "проклятие несъёмности или утраты на предмет в руке", List.of("меч", "инструмент")),
    ACID_RAIN(Kind.WRATH, 2, 3, false, "ядовитый дождь на минуту: травит всех под открытым небом", List.of("дождь", "лив")),
    FIRE_RING(Kind.WRATH, 2, 3, true, "кольцо огня вокруг грешника", List.of("огонь", "жар", "сжечь")),
    METEOR(Kind.WRATH, 2, 4, true, "огненные метеориты падают вокруг грешника (до двадцати), каждый оставляет огромную воронку с магмой", List.of("метеор", "звезд", "небо пад")),
    SKELETON_RIDERS(Kind.WRATH, 3, 4, false, "всадники смерти: скелеты на лошадях-скелетах (до тридцати) скачут на грешника", List.of("всадник", "конь", "лошад", "скелет", "смерт")),
    ANVIL_RAIN(Kind.WRATH, 2, 3, true, "дождь из наковален над грешником", List.of("наковальн", "небо пад", "раздав")),
    EARTHQUAKE(Kind.WRATH, 3, 4, true, "землетрясение: земля вокруг рушится и осыпается, грешника мутит", List.of("земл", "тряс", "дрож")),
    SINKHOLE(Kind.WRATH, 3, 4, true, "провал: земля под грешником уходит в бездонную воронку", List.of("провал", "бездн", "под землю", "яма")),
    TNT_RAIN(Kind.WRATH, 3, 4, true, "огненный дождь из горящего динамита", List.of("взрыв", "огонь", "динамит")),
    UNDEAD_HORDE(Kind.WRATH, 3, 4, false, "из земли встаёт орда нежити в броне", List.of("мертв", "нежит", "зомби", "скелет")),
    RAID(Kind.WRATH, 3, 4, false, "войско разбойников с разорителями и заклинателями", List.of("разбойник", "войн", "рейд", "армия")),
    HELL_EXILE(Kind.WRATH, 3, 4, false, "изгнание грешника в Ад", List.of("ад", "незер", "преисподн", "пекл")),
    BLAZE_HORDE(Kind.WRATH, 3, 4, false, "огненные духи: орда ифритов окружает грешника (на силе 4 — двадцать)", List.of("огонь", "ифрит", "пламя", "дух")),
    METEOR_SHOWER(Kind.WRATH, 4, 4, true, "метеоритный дождь: множество огненных камней и воронок вокруг", List.of("метеор", "звезд", "конец света", "апокалип")),
    WARDEN(Kind.WRATH, 4, 4, false, "из глубин встаёт Хранитель — слепой страж тьмы", List.of("хранител", "варден", "тьма", "глубин", "апокалип")),
    END_EXILE(Kind.WRATH, 3, 4, false, "изгнание грешника в Край, к самому дракону", List.of("энд", "край", "пустот", "дракон")),
    CHUNK_VOID(Kind.WRATH, 4, 4, true, "земля перед грешником вырезана до самой бездны, со всей породой и коренной породой", List.of("бездн", "пустот", "конец света", "апокалип", "мир")),
    TORNADO(Kind.WRATH, 3, 4, true, "смерч: чёрная воронка идёт на грешника, затягивает живое, рвёт деревья и крыши", List.of("смерч", "торнадо", "вихрь", "ураган", "ветер", "буря")),
    RIFTS(Kind.WRATH, 4, 4, true, "земля раскалывается: гигантские разломы до самой коренной породы", List.of("разлом", "раскол", "трещин", "земл", "апокалип")),
    GREAT_CRATER(Kind.WRATH, 4, 4, true, "великий взрыв в алтаре: воронка в 30–150 блоков стирает всё, и сам алтарь", List.of("взрыв", "воронк", "конец света", "апокалип", "уничтож")),
    WITHER(Kind.WRATH, 4, 4, true, "визеры у алтаря: один, а в великом гневе до пяти", List.of("визер", "wither", "конец света", "апокалип")),
    DRAGON(Kind.WRATH, 4, 4, false, "драконы Края кружат над грешником: один, а в великом гневе до пяти", List.of("дракон", "конец света", "апокалип", "энд")),
    // Lingering and ultra-cruel wrath: curses that stay for days, the great biblical punishments, and the eternal ban.
    BLIGHT(Kind.WRATH, 3, 4, true, "порча земли вокруг дома на несколько дней: трава чернеет, посевы гибнут, скот дохнет", List.of("урожа", "земл", "порч", "скот", "голод")),
    CAIN(Kind.WRATH, 3, 4, false, "проклятие Каина на несколько дней: все твари враждебны грешнику, жители бегут от него и не торгуют", List.of("каин", "брат", "изгна", "один", "проклят")),
    SACRED_BOSS(Kind.WRATH, 3, 4, false, "призыв священного босса: страж бога выходит сразиться с грешником", List.of("босс", "страж", "ангел", "архангел", "серафим")),
    PLAGUES(Kind.WRATH, 4, 4, false, "казни египетские: вода в кровь, жабы, саранча, тьма на три дня, мор скота — одна за другой", List.of("казн", "египет", "кров", "жаб", "саранч", "тьм", "мор")),
    SODOM(Kind.WRATH, 4, 4, true, "Содом: огненный дождь по всей округе, а твари вокруг обращаются в соляные столпы", List.of("содом", "гоморр", "сер", "огонь", "соль", "соля")),
    FLOOD(Kind.WRATH, 4, 4, true, "потоп: ливень сорок дней, вода поднимается до неба вокруг грешника и сходит не скоро", List.of("потоп", "вод", "ной", "ковчег", "дождь", "лив")),
    BABEL(Kind.WRATH, 4, 4, true, "Вавилон: ближние постройки рушатся сверху вниз, камень за камнем", List.of("вавилон", "башн", "строй", "дом", "город", "гордын")),
    MEMORY_WIPE(Kind.WRATH, 4, 4, true, "стирание памяти: кровать, точка возрождения и рецепты забыты, смертный нагим за 5000 блоков, вещи в запечатанном сундуке у алтаря", List.of("памят", "забы", "забудь", "кто я", "потерял")),
    JUDGEMENT(Kind.WRATH, 4, 4, false, "суд: смертного возносит в небо взглянуть на мир с высоты, и он падает", List.of("суд", "небес", "вознес", "взлет", "взлёт", "высот")),
    ETERNAL_BAN(Kind.WRATH, 4, 4, false, "вечное изгнание: бог выкидывает смертного из мира с приговором, и мир больше его не впускает", List.of("изгна", "навсегда", "вечн", "прочь", "уйди"));

    public enum Kind {
        OMEN("знамение"), HELP("помощь"), JEST("шутка"), WRATH("кара");
        public final String label;
        Kind(String label) { this.label = label; }
    }

    public final Kind kind;
    public final int minTier, maxTier;
    /** Changes or destroys blocks: limited by the protection settings. */
    public final boolean destructive;
    /** What the act does, for Jev's criteria and the settings screen. */
    public final String description;
    /** Stems in the player's words that make this act fit the prayer. */
    public final List<String> hints;

    Miracle(Kind kind, int minTier, int maxTier, boolean destructive, String description, List<String> hints) {
        this.kind = kind; this.minTier = minTier; this.maxTier = maxTier; this.destructive = destructive; this.description = description; this.hints = hints;
    }

    public String id() { return name().toLowerCase(Locale.ROOT); }

    /** How heavy the act is on a ladder of 1..10, within its kind: the god climbs it as insult, sin and pestering grow (or,
     * for mercy, as favour grows), so punishments escalate instead of always being the biggest one at hand. */
    public int gravity() {
        return switch (this) {
            case OMEN_LIGHT, OMEN_THUNDER, OMEN_LAUGH, IGNORE -> 0;
            case HEAL, FEED, WEATHER_CLEAR -> 1;
            case BUFF, GIFT_ITEM, REPAIR, GROW -> 2;
            case ORE_VEIN, ENCHANT, GUARDIAN, SMITE_ENEMIES, SAFE_HOME -> 3;
            case TOTEM -> 4;
            case BLESSING -> 5;
            case SWAP_ITEMS, CHICKEN_RAIN, MOON_GRAVITY, TIME_RUSH, SHEEP_PARTY, FISH_RAIN, CREEPER_PRANK -> 1;
            case FLIP, TRANSMUTE, PUMPKIN_HEAD, LEVITATE, DIZZY -> 2;
            case RANDOM_TP -> 3;
            case LIGHTNING, CURSE_MINOR -> 1;
            case SWARM, CURSE_TOOL -> 2;
            case ACID_RAIN, FIRE_RING, ANVIL_RAIN -> 3;
            case METEOR, EARTHQUAKE, UNDEAD_HORDE -> 4;
            case SINKHOLE, TNT_RAIN, RAID, SKELETON_RIDERS -> 5;
            case BLAZE_HORDE, TORNADO, HELL_EXILE -> 6;
            case END_EXILE, WARDEN -> 7;
            case METEOR_SHOWER, RIFTS -> 8;
            case CHUNK_VOID, WITHER -> 9;
            case DRAGON, GREAT_CRATER -> 10;
            case BLIGHT -> 5;
            case CAIN -> 6;
            case SACRED_BOSS -> 7;
            case BABEL, JUDGEMENT -> 8;
            case PLAGUES, SODOM, FLOOD, MEMORY_WIPE -> 9;
            case ETERNAL_BAN -> 10;
        };
    }

    public static Miracle byId(String id) {
        if (id == null) return null;
        for (Miracle miracle : values()) if (miracle.id().equalsIgnoreCase(id.strip())) return miracle;
        return null;
    }

    /** How many of this act's hints the prayer mentions. */
    public int hintMatches(String prayer) {
        if (prayer == null || prayer.isBlank()) return 0;
        String text = prayer.toLowerCase(Locale.ROOT);
        int matches = 0;
        for (String hint : hints) if (text.contains(hint)) matches++;
        return matches;
    }
}
