package io.github.mobtalk.core;

import java.util.*;
import java.util.regex.Pattern;

/** Свадьба (трек L): чистые правила церемонии у колокола. Кто ведёт, кто свидетели и гости, где кто стоит, порядок службы, что
 * считается отказом, тексты на случай, когда модель или сеть молчат. Сам порядок — {@link Ceremony}: игра двигает людей и делает
 * эффекты, клиент озвучивает реплики, а церемония решает, что дальше, и всегда доходит до конца — сбой реплики только пропускает её. */
public final class Wedding {
    private Wedding() { }

    /** Житель, которого может позвать свадьба. {@code bond}: отношение к игроку (в свадьбе жителей — лучшее к паре); {@code kin}: родня
     * пары; {@code children}: сколько у него детей (старейшина — у кого больше). */
    public record Person(UUID id, String name, Gender gender, String profession, String persona, String voiceId, int bond, boolean kin, int children) {
        public Person {
            name = name == null || name.isBlank() ? "Житель" : TextBounds.characters(name, 60);
            gender = gender == null ? Gender.MALE : gender;
            profession = profession == null ? "" : profession; persona = persona == null ? "" : persona; voiceId = voiceId == null ? "" : voiceId;
        }
        public boolean cleric() { return profession.endsWith(":cleric") || "cleric".equals(profession); }
    }

    /** Кто на свадьбе. {@code partner} null — второй в паре игрок; иначе это свадьба двух жителей. {@code officiant} может быть null:
     * ведёт тогда свидетель, а без свидетелей — сам {@code spouse}. */
    public record Cast(Person spouse, Person partner, Person officiant, Person witnessMan, Person witnessWoman, List<Person> guests) {
        public Cast { guests = guests == null ? List.of() : List.copyOf(guests); }
        public boolean playerWedding() { return partner == null; }
        /** Кто произносит слова ведущего. */
        public Person leader() { return officiant != null ? officiant : witnessMan != null ? witnessMan : witnessWoman != null ? witnessWoman : spouse; }
        public boolean priest() { return officiant != null && officiant.cleric(); }
        /** Все жители на свадьбе, без повторов: пара, ведущий, свидетели, гости. */
        public List<Person> everyone() {
            Map<UUID, Person> all = new LinkedHashMap<>();
            for (Person p : Arrays.asList(spouse, partner, officiant, witnessMan, witnessWoman)) if (p != null) all.putIfAbsent(p.id(), p);
            for (Person p : guests) all.putIfAbsent(p.id(), p);
            return List.copyOf(all.values());
        }
        public Person find(UUID id) { for (Person p : everyone()) if (p.id().equals(id)) return p; return null; }
        /** Кто говорит пожелания и в каком порядке: свидетели, потом гости; ведущий и пара не желают сами себе. */
        public List<Person> wishers(int limit) {
            List<Person> list = new ArrayList<>();
            for (Person p : Arrays.asList(witnessMan, witnessWoman)) if (p != null && p != officiant) list.add(p);
            list.addAll(guests);
            return list.subList(0, Math.min(list.size(), Math.max(0, limit)));
        }
        public Role role(UUID id) {
            if (id == null) return null;
            if (spouse.id().equals(id)) return Role.SPOUSE;
            if (partner != null && partner.id().equals(id)) return Role.PARTNER;
            if (officiant != null && officiant.id().equals(id)) return Role.OFFICIANT;
            if (witnessMan != null && witnessMan.id().equals(id)) return Role.WITNESS_MAN;
            if (witnessWoman != null && witnessWoman.id().equals(id)) return Role.WITNESS_WOMAN;
            for (Person p : guests) if (p.id().equals(id)) return Role.GUEST;
            return null;
        }
    }
    public enum Role { SPOUSE, PARTNER, OFFICIANT, WITNESS_MAN, WITNESS_WOMAN, GUEST }

    /** Строка в промпт жителю, пока в деревне свадьба: кто женится, что сейчас идёт и кто он на ней ({@code mob}). Пусто — служба
     * кончилась. */
    public static String contextLine(Cast cast, Names names, Ceremony.Phase phase, UUID mob) {
        String couple = cast.spouse().name() + " и " + (cast.playerWedding() ? names.player() : cast.partner().name());
        String now = switch (phase) {
            case GATHER -> "гости собираются у колокола";
            case SERVICE -> "у колокола идёт служба";
            case DONE -> "молодые только что поженились, идёт праздник";
            case JILTED -> "свадьба сорвалась: у алтаря прозвучало «нет»";
            case CANCELLED -> "";
        };
        if (now.isEmpty()) return "";
        Role role = cast.role(mob);
        String you = role == null ? "Тебя на церемонии нет, но ты о ней знаешь." : switch (role) {
            case SPOUSE, PARTNER -> "Это твоя свадьба.";
            case OFFICIANT -> "Ты ведёшь службу.";
            case WITNESS_MAN, WITNESS_WOMAN -> "Ты свидетель.";
            case GUEST -> "Ты среди гостей.";
        };
        return "Сейчас в деревне свадьба: женятся " + couple + "; " + now + ". " + you + " Говори об этом к месту, если речь зайдёт.";
    }

    /** Состав свадьбы из жителей вокруг колокола. Свидетели, которых назвал игрок ({@code askedMan}, {@code askedWoman}), идут первыми,
     * затем ведущий — священник, иначе старейшина (больше детей, при равенстве лучшее отношение), затем недостающие свидетели из
     * лучших друзей нужного пола, затем гости: родня, потом по отношению; недолюбливающие игрока не приходят. Изгнанника навещает
     * только родня. */
    public static Cast cast(Person spouse, Person partner, List<Person> villagers, UUID askedMan, UUID askedWoman, boolean exile, int maxGuests) {
        Objects.requireNonNull(spouse, "spouse");
        Map<UUID, Person> pool = new LinkedHashMap<>();
        for (Person p : villagers == null ? List.<Person>of() : villagers) {
            if (p == null || p.id().equals(spouse.id()) || partner != null && p.id().equals(partner.id())) continue;
            if (exile && !p.kin()) continue;
            pool.putIfAbsent(p.id(), p);
        }
        Person man = askedMan == null ? null : pool.get(askedMan), woman = askedWoman == null ? null : pool.get(askedWoman);
        if (man != null && man.gender() != Gender.MALE) man = null;
        if (woman != null && woman.gender() != Gender.FEMALE) woman = null;
        Set<UUID> taken = new HashSet<>();
        if (man != null) taken.add(man.id());
        if (woman != null) taken.add(woman.id());
        Comparator<Person> elder = Comparator.comparingInt(Person::children).thenComparingInt(Person::bond);
        Person officiant = pool.values().stream().filter(p -> !taken.contains(p.id()) && p.cleric()).max(Comparator.comparingInt(Person::bond)).orElse(null);
        if (officiant == null) officiant = pool.values().stream().filter(p -> !taken.contains(p.id()) && (p.bond() >= 0 || p.kin())).max(elder).orElse(null);
        if (officiant != null) taken.add(officiant.id());
        Comparator<Person> closest = Comparator.comparing((Person p) -> !p.kin()).thenComparing(Comparator.comparingInt(Person::bond).reversed());
        if (man == null) man = pool.values().stream().filter(p -> !taken.contains(p.id()) && p.gender() == Gender.MALE && (p.bond() >= 0 || p.kin()))
                .max(Comparator.comparingInt(Person::bond)).orElse(null);
        if (man != null) taken.add(man.id());
        if (woman == null) woman = pool.values().stream().filter(p -> !taken.contains(p.id()) && p.gender() == Gender.FEMALE && (p.bond() >= 0 || p.kin()))
                .max(Comparator.comparingInt(Person::bond)).orElse(null);
        if (woman != null) taken.add(woman.id());
        List<Person> guests = pool.values().stream().filter(p -> !taken.contains(p.id()) && (p.bond() >= 0 || p.kin())).sorted(closest)
                .limit(Math.max(0, maxGuests)).toList();
        return new Cast(spouse, partner, officiant, man, woman, guests);
    }

    // ---- Где кто стоит. ----

    /** Место в системе колокола: {@code forward} блоков от колокола в сторону гостей, {@code right} вбок. */
    public record Seat(UUID id, double forward, double right) { }
    /** Пара стоит в стольких блоках от колокола, ведущий — между ними и колоколом. */
    public static final double COUPLE_FORWARD = 3.5, OFFICIANT_FORWARD = 1.5, PARTNER_SIDE = 0.8, WITNESS_SIDE = 2.2;
    /** Места: ведущий у колокола лицом к паре, пара рядом, свидетели по бокам, гости рядами позади пары. {@code playerRight}: на
     * какой стороне стоит игрок (его место — {@link #playerSeat}), его пара — на другой. Без игрока жених справа. */
    public static List<Seat> seats(Cast cast, boolean playerRight, Gender playerGender) {
        List<Seat> seats = new ArrayList<>();
        double side = playerRight ? 1 : -1;
        if (cast.officiant() != null) seats.add(new Seat(cast.officiant().id(), OFFICIANT_FORWARD, 0));
        seats.add(new Seat(cast.spouse().id(), COUPLE_FORWARD, -side * PARTNER_SIDE));
        if (cast.partner() != null) seats.add(new Seat(cast.partner().id(), COUPLE_FORWARD, side * PARTNER_SIDE));
        // Свидетель того же пола, что игрок (жених), стоит с его стороны; без пола игрока — свидетель-мужчина.
        Gender near = cast.playerWedding() ? (playerGender == null ? Gender.MALE : playerGender) : cast.partner().gender();
        Person nearWitness = near == Gender.MALE ? cast.witnessMan() : cast.witnessWoman(), farWitness = near == Gender.MALE ? cast.witnessWoman() : cast.witnessMan();
        if (nearWitness != null && nearWitness != cast.officiant()) seats.add(new Seat(nearWitness.id(), COUPLE_FORWARD, side * WITNESS_SIDE));
        if (farWitness != null && farWitness != cast.officiant()) seats.add(new Seat(farWitness.id(), COUPLE_FORWARD, -side * WITNESS_SIDE));
        int row = 0, inRow = 0;
        int[] widths = {6, 7, 8, 9};
        for (Person guest : cast.guests()) {
            int width = widths[Math.min(row, widths.length - 1)];
            if (inRow >= width) { row++; inRow = 0; width = widths[Math.min(row, widths.length - 1)]; }
            double right = (inRow - (width - 1) / 2.0) * 1.2;
            seats.add(new Seat(guest.id(), COUPLE_FORWARD + 2.5 + row * 1.3, right));
            inRow++;
        }
        return seats;
    }
    /** Место игрока: рядом с парой, со своей стороны. */
    public static Seat playerSeat(boolean playerRight) { return new Seat(null, COUPLE_FORWARD, (playerRight ? 1 : -1) * PARTNER_SIDE); }

    // ---- Согласие и слова, которые зовут на свадьбу. ----

    private static final Pattern REFUSAL = Pattern.compile(
            "^(нет|не-а|неа|ни за что|никогда)([,.!…]|\\s*$)|\\b(не\\s+(согласен|согласна|готов|готова)|передумал[аи]?|отказываюсь|я\\s+против"
                    + "|свадьбы\\s+не\\s+будет|не\\s+женюсь|не\\s+выйду|не\\s+хочу\\s+(жениться|замуж|свадьбы)|сбегаю)", Pattern.UNICODE_CHARACTER_CLASS);
    /** Слова согласия, которые перевешивают отрицание рядом («не готов тебя потерять — люблю тебя»). */
    private static final Pattern CONSENT = Pattern.compile("(?<!не\\s)\\b(да|клянусь|обещаю|люблю|беру)\\b", Pattern.UNICODE_CHARACTER_CLASS);
    /** Явный отказ в ответе на вопрос ведущего: «нет», «не согласен», «я передумал». Шутка, сомнение, молчание и клятва с «не» внутри
     * («никогда тебя не обижу, клянусь») — не отказ: риск сбежать из-под венца низкий. */
    public static boolean refuses(String said) {
        if (said == null || said.isBlank()) return false;
        String text = said.toLowerCase(Locale.ROOT).replace('ё', 'е').strip();
        return REFUSAL.matcher(text).find() && !CONSENT.matcher(text).find();
    }
    private static final Pattern CALL = Pattern.compile(
            "(пойд[её]м|пошли|идём|идем|айда|давай|пора|веди|ведите|начинаем|начнём|начнем|сыграем|играем)\\s+(же\\s+|скорее\\s+|уже\\s+)?(на\\s+|нашу\\s+|к\\s+)?(свадьб|венчат|жениться|под\\s+венец|к\\s+колоколу|расписыват)"
                    + "|(пора|время|пойд[её]м|пошли|давай)\\s+(нам\\s+)?(жениться|пожениться|венчаться|под\\s+венец)"
                    + "|свадьб\\w*\\s+(сейчас|сегодня|начинаем|начин|пора)", Pattern.UNICODE_CHARACTER_CLASS);
    /** Зовёт ли игрок свою невесту (жениха) играть свадьбу этими словами («идём на свадьбу», «пора жениться», «пошли к колоколу»). */
    public static boolean calls(String said) {
        if (said == null || said.isBlank()) return false;
        return CALL.matcher(said.toLowerCase(Locale.ROOT).replace('ё', 'е')).find();
    }
    private static final Pattern PROPOSAL = Pattern.compile(
            "выход\\w*\\s+за\\s+меня|выйдешь\\s+за\\s+меня|будь\\s+мо(ей|им)\\s+(женой|мужем)|стань\\s+мо(ей|им)\\s+(женой|мужем)|руку\\s+и\\s+сердце|женись\\s+на\\s+мне"
                    + "|(давай|хочу)\\s+(поженимся|пожениться|жениться\\s+на\\s+тебе)|замуж\\s+за\\s+меня", Pattern.UNICODE_CHARACTER_CLASS);
    /** Делает ли игрок предложение этими словами («выходи за меня», «будь моей женой», «предлагаю руку и сердце»). */
    public static boolean proposes(String said) {
        if (said == null || said.isBlank()) return false;
        return PROPOSAL.matcher(said.toLowerCase(Locale.ROOT).replace('ё', 'е')).find();
    }
    private static final Pattern WITNESS = Pattern.compile("свидетел|свидетельниц|дружк|шафер");
    /** Просит ли игрок быть свидетелем («будь моим свидетелем», «будешь свидетельницей?»). */
    public static boolean asksWitness(String said) {
        if (said == null || said.isBlank()) return false;
        return WITNESS.matcher(said.toLowerCase(Locale.ROOT).replace('ё', 'е')).find();
    }

    // ---- Тексты. ----

    /** Что говорится на свадьбе: каждая реплика по ключу. Модель пишет их заранее, пока все идут к колоколу; чего она не дала,
     * берётся из {@link #fallback}. */
    public enum Key { OPENING, ASK_PLAYER, REASK, SILENCE, ASK_SPOUSE, SPOUSE_VOW, ASK_PARTNER, PARTNER_VOW, RINGS, NO_RINGS, PRONOUNCE, WISH, JILTED }
    /** Тексты одной свадьбы: по ключу, а пожелания — по жителю. */
    public static final class Script {
        final Map<Key, String> lines = new EnumMap<>(Key.class);
        final Map<UUID, String> wishes = new HashMap<>();
        public Script put(Key key, String text) { if (key != null && text != null && !text.isBlank()) lines.put(key, text.strip()); return this; }
        public Script wish(UUID id, String text) { if (id != null && text != null && !text.isBlank()) wishes.put(id, text.strip()); return this; }
        public String get(Key key) { return lines.get(key); }
        public String wish(UUID id) { return wishes.get(id); }
        public int size() { return lines.size() + wishes.size(); }
    }

    /** Имена и формы речи для шаблонов. {@code playerGender} null — игрок без пола: говорим о нём в мужском роде. */
    public record Names(String player, Gender playerGender, String village) {
        public Names { player = player == null || player.isBlank() ? "путник" : player; village = village == null ? "" : village; }
    }

    /** Запасные тексты: годятся, когда модель не ответила, ответила мусором или сети нет совсем. */
    public static Script fallback(Cast cast, Names names, long seed) {
        Script s = new Script();
        Person spouse = cast.spouse(), partner = cast.partner();
        String a = cast.playerWedding() ? names.player() : partner.name(), b = spouse.name();
        Gender aGender = cast.playerWedding() ? (names.playerGender() == null ? Gender.MALE : names.playerGender()) : partner.gender();
        String village = names.village().isBlank() ? "нашей деревни" : "деревни «" + names.village() + "»";
        s.put(Key.OPENING, cast.priest()
                ? "Дорогие соседи! Сегодня у колокола " + village + " мы собрались, чтобы соединить " + a + " и " + b + ". Пусть этот день запомнится всем нам надолго."
                : "Ну что, народ, собрались — и ладно. Священника у нас нет, так что поведу я, по-простому, по-деревенски. Сегодня женятся " + a + " и " + b + "!");
        s.put(Key.ASK_PLAYER, question(a, aGender, b, spouse.gender(), cast.priest()));
        s.put(Key.ASK_PARTNER, question(a, aGender, b, spouse.gender(), cast.priest()));
        s.put(Key.REASK, a + ", мы все ждём твоего слова. Скажи его — " + (aGender == Gender.FEMALE ? "готова" : "готов") + " ли ты?");
        s.put(Key.SILENCE, "Молчание — знак согласия! Так и запишем.");
        s.put(Key.ASK_SPOUSE, question(b, spouse.gender(), a, aGender, cast.priest()));
        s.put(Key.SPOUSE_VOW, vow(b, spouse.gender(), a));
        s.put(Key.PARTNER_VOW, vow(a, aGender, b));
        s.put(Key.RINGS, "А теперь обменяйтесь кольцами — в знак того, что слово ваше крепко.");
        Person witness = cast.witnessMan() != null ? cast.witnessMan() : cast.witnessWoman();
        s.put(Key.NO_RINGS, witness != null && witness.gender() == Gender.FEMALE ? "Ой… кольца-то я и забыла! Ну ничего, любовь и без колец крепка."
                : "Ой… кольца-то я и забыл! Ну ничего, любовь и без колец крепка.");
        s.put(Key.PRONOUNCE, (cast.priest() ? "Властью, данной мне этой деревней, объявляю вас " : "Ну всё, объявляю вас ")
                + couple(aGender, spouse.gender()) + "! Можете поцеловать друг друга. Горько!");
        s.put(Key.JILTED, "Вот так дела… Свадьбы не будет. Расходитесь, люди добрые.");
        List<String> wishes = List.of(
                "Совет вам да любовь, " + a + " и " + b + "!",
                "Живите дружно, не ссорьтесь по пустякам, а поссоритесь — миритесь до заката.",
                "Пусть дом ваш будет полон, а амбар никогда не пустеет!",
                "Берегите друг друга. Остальное приложится.",
                "Желаю вам тёплого очага, крепких стен и смеха под окнами.",
                "Пусть урожай будет богатым, а ночи — спокойными!",
                "Любите друг друга, как в этот день. И ещё немножко сильнее.",
                "Будьте счастливы! И зовите в гости.");
        int i = (int) Math.floorMod(seed, wishes.size());
        for (Person p : cast.wishers(Integer.MAX_VALUE)) s.wish(p.id(), wishes.get(i++ % wishes.size()));
        return s;
    }
    private static String question(String who, Gender whoGender, String other, Gender otherGender, boolean priest) {
        boolean she = otherGender == Gender.FEMALE;
        String ready = whoGender == Gender.FEMALE ? "готова" : "готов";
        return who + ", " + ready + " ли ты, чтобы " + other + " стал" + (she ? "а твоей женой" : " твоим мужем") + ", — быть с " + (she ? "ней" : "ним")
                + " в радости и в беде, в ссоре и в мире, заботиться " + (she ? "о ней" : "о нём") + " и беречь " + (she ? "её" : "его")
                + (priest ? ", пока смерть не разлучит вас?" : " всю жизнь?");
    }
    private static String vow(String who, Gender whoGender, String other) {
        return "Да, " + (whoGender == Gender.FEMALE ? "готова" : "готов") + ". " + other + ", я обещаю быть рядом в радости и в беде, делить с тобой хлеб и кров, "
                + "мириться после каждой ссоры и беречь тебя всю жизнь.";
    }
    private static String couple(Gender a, Gender b) {
        if (a == b) return a == Gender.FEMALE ? "жёнами друг другу" : "супругами";
        return "мужем и женой";
    }

    // ---- Порядок службы. ----

    /** Что делает игра в этом месте службы: обмен кольцами, сам брак со звоном колокола и салютом, праздник. */
    public enum Act { RINGS, WED, FEAST }
    /** Один шаг службы: реплика ({@code key} и кто говорит), ожидание клятвы игрока или действие игры. */
    public record Beat(int seq, Key key, UUID speaker, Act act, boolean vow) {
        public boolean line() { return key != null; }
    }
    /** Реплика, которую клиент озвучивает: номер шага, кто, текст, его голос. */
    public record Line(int seq, Key key, UUID speaker, String name, String voiceId, String text) { }

    /** Сколько ждать: реплика, которую клиент так и не взял (клиент умер или занят); запас на озвучку сверх оценки длины; клятву
     * игрока до переспроса; праздник; предел всей службы. */
    public static final long UNCLAIMED_MILLIS = 20_000, LINE_SLACK_MILLIS = 20_000, FEAST_MILLIS = 15_000, SERVICE_MILLIS = 6 * 60_000L;
    /** Оценка длительности реплики вслух: около 13 знаков в секунду, но не меньше 2,5 с. */
    public static long speakMillis(String text) { return Math.max(2_500, (text == null ? 0 : text.length()) * 1000L / 13); }

    /** Служба от сбора до праздника. Методы вызываются из потоков игры и клиента, поэтому синхронизированы. Время — реальные мс. */
    public static final class Ceremony {
        public enum Phase { GATHER, SERVICE, DONE, JILTED, CANCELLED }
        private final Cast cast;
        private final Names names;
        private final long started;
        private final long gatherMillis, vowWaitMillis;
        private final int wishLimit;
        private Phase phase = Phase.GATHER;
        private Script script, written;
        private final List<Beat> beats = new ArrayList<>();
        private int index, nextSeq;
        private long beatStarted, serviceStarted = -1;
        private boolean claimed, executed, wed, reasked, jilted, scriptFailed;
        private String vowText = "";
        private boolean scriptAsked;

        public Ceremony(Cast cast, Names names, long gatherMillis, long vowWaitMillis, int wishLimit, long now) {
            this.cast = Objects.requireNonNull(cast); this.names = names == null ? new Names("", null, "") : names;
            this.gatherMillis = Math.max(5_000, gatherMillis); this.vowWaitMillis = Math.max(5_000, vowWaitMillis); this.wishLimit = Math.max(0, wishLimit);
            this.started = now; this.script = fallback(cast, this.names, now);
        }
        public Cast cast() { return cast; }
        public Names names() { return names; }
        public synchronized Phase phase() { return phase; }
        public synchronized boolean over() { return phase == Phase.DONE || phase == Phase.JILTED || phase == Phase.CANCELLED; }
        public synchronized boolean married() { return wed; }
        public synchronized String vowText() { return vowText; }
        /** Модель написала тексты (или null — не написала): чего в них нет, остаётся из запасных. */
        public synchronized void scriptReady(Script fromModel) {
            if (fromModel == null) return;
            written = fromModel;
            Script merged = fallback(cast, names, started);
            merged.lines.putAll(fromModel.lines); merged.wishes.putAll(fromModel.wishes);
            Script spouseVow = script; // Клятва пары, написанная по словам игрока, важнее заготовленной.
            if (spouseVow.lines.containsKey(Key.SPOUSE_VOW) && overridden) merged.lines.put(Key.SPOUSE_VOW, spouseVow.lines.get(Key.SPOUSE_VOW));
            script = merged;
        }
        private boolean overridden;
        /** Клятва пары, написанная в ответ на слова игрока; приходит, пока ведущий спрашивает пару. */
        public synchronized void spouseVow(String text) {
            if (text == null || text.isBlank()) return;
            script.put(Key.SPOUSE_VOW, text); overridden = true;
        }
        public synchronized boolean scripted() { return written != null; }
        /** Текст реплики по ключу, как он прозвучит (модель или запасной). */
        public synchronized String said(Key key) { String text = script.get(key); return text == null ? "" : text; }
        /** Модель не написала тексты (ошибка, таймаут): служба идёт на запасных, не дожидаясь. */
        public synchronized void scriptFailed() { scriptFailed = true; }
        /** Просить ли тексты у модели: один раз на свадьбу. */
        public synchronized boolean takeScriptRequest() { if (scriptAsked) return false; scriptAsked = true; return true; }

        /** Сбор окончен: все на местах ({@code seated}) или вышло время. Ждём ещё и тексты модели, но не дольше 20 с сверх сбора. */
        public synchronized boolean gatherDone(boolean seated, long now) {
            if (phase != Phase.GATHER) return false;
            boolean texts = written != null || scriptFailed, timeUp = now - started >= gatherMillis;
            if (!(texts && (seated || timeUp)) && now - started < gatherMillis + 20_000) return false;
            begin(now);
            return true;
        }
        private void begin(long now) {
            phase = Phase.SERVICE; serviceStarted = now;
            UUID leader = cast.leader().id();
            add(Key.OPENING, leader);
            if (cast.playerWedding()) {
                add(Key.ASK_PLAYER, leader);
                beats.add(new Beat(nextSeq++, null, null, null, true));
                add(Key.ASK_SPOUSE, leader);
                add(Key.SPOUSE_VOW, cast.spouse().id());
                add(Key.RINGS, leader);
                beats.add(new Beat(nextSeq++, null, null, Act.RINGS, false));
            } else {
                add(Key.ASK_PARTNER, leader);
                add(Key.PARTNER_VOW, cast.partner().id());
                add(Key.ASK_SPOUSE, leader);
                add(Key.SPOUSE_VOW, cast.spouse().id());
            }
            add(Key.PRONOUNCE, leader);
            beats.add(new Beat(nextSeq++, null, null, Act.WED, false));
            for (Person p : cast.wishers(wishLimit)) add(Key.WISH, p.id());
            beats.add(new Beat(nextSeq++, null, null, Act.FEAST, false));
            index = 0; beatStarted = now; claimed = false; executed = false;
        }
        private void add(Key key, UUID speaker) { beats.add(new Beat(nextSeq++, key, speaker, null, false)); }

        public synchronized Beat current() { return phase == Phase.SERVICE && index < beats.size() ? beats.get(index) : null; }
        /** Следующие реплики, ещё не сказанные, для заблаговременной озвучки. */
        public synchronized List<Line> upcoming(int count) {
            List<Line> lines = new ArrayList<>();
            if (phase != Phase.SERVICE) return lines;
            for (int i = index; i < beats.size() && lines.size() < count; i++) {
                Beat beat = beats.get(i);
                if (beat.vow()) break; // Клятва пары зависит от слов игрока: дальше не заглядываем.
                if (beat.line() && !(i == index && claimed)) lines.add(line(beat));
            }
            return lines;
        }
        /** Реплика, которую пора говорить; клиент берёт её один раз. */
        public synchronized Line claim(long now) {
            Beat beat = current();
            if (beat == null || !beat.line() || claimed) return null;
            claimed = true; beatStarted = now;
            return line(beat);
        }
        private Line line(Beat beat) {
            Person who = cast.find(beat.speaker());
            String text = beat.key() == Key.WISH ? script.wish(beat.speaker()) : script.get(beat.key());
            if (text == null || text.isBlank()) text = beat.key() == Key.WISH ? fallback(cast, names, beat.seq()).wish(beat.speaker()) : fallback(cast, names, beat.seq()).get(beat.key());
            return new Line(beat.seq(), beat.key(), beat.speaker(), who == null ? "" : who.name(), who == null ? "" : who.voiceId(), text == null ? "" : text);
        }
        /** Клиент договорил реплику {@code seq} (или не смог её озвучить — тогда тоже дальше). */
        public synchronized void lineDone(int seq, long now) {
            Beat beat = current();
            if (beat == null || beat.seq() != seq || !beat.line()) return;
            next(now);
        }
        /** Ждёт ли служба клятвы игрока. */
        public synchronized boolean awaitingVow() { Beat beat = current(); return beat != null && beat.vow(); }
        /** Игрок сказал клятву. {@code refused}: явный отказ (сбежал из-под венца). */
        public synchronized void vow(String text, boolean refused, long now) {
            if (!awaitingVow()) return;
            vowText = text == null ? "" : TextBounds.characters(text.strip(), 2000);
            if (refused) { jilt(now); return; }
            next(now);
        }
        /** Действие игры, которое пора выполнить; null — сейчас не действие или уже выполняется. */
        public synchronized Act pendingAct(long now) {
            Beat beat = current();
            if (beat == null || beat.act() == null || executed) return null;
            executed = true; beatStarted = now;
            return beat.act();
        }
        /** Игра выполнила действие. Для колец {@code ringsExchanged} false — свидетель «забыл кольца». */
        public synchronized void actDone(Act act, boolean ringsExchanged, long now) {
            Beat beat = current();
            if (beat == null || beat.act() != act) return;
            if (act == Act.WED) wed = true;
            if (act == Act.RINGS && !ringsExchanged) {
                Person witness = cast.witnessMan() != null ? cast.witnessMan() : cast.witnessWoman() != null ? cast.witnessWoman() : cast.leader();
                beats.add(index + 1, new Beat(nextSeq++, Key.NO_RINGS, witness.id(), null, false));
            }
            if (act == Act.FEAST) return; // Праздник длится сам: его конец считает tick.
            next(now);
        }
        private void next(long now) {
            index++; claimed = false; executed = false; beatStarted = now;
            if (index >= beats.size()) phase = jilted ? Phase.JILTED : Phase.DONE;
        }
        /** Сбежал из-под венца: ведущий говорит одно слово, и все расходятся. */
        private void jilt(long now) {
            jilted = true;
            beats.clear(); beats.add(new Beat(nextSeq++, Key.JILTED, cast.leader().id(), null, false));
            index = 0; claimed = false; executed = false; beatStarted = now;
        }
        public synchronized boolean jilted() { return jilted; }
        /** Свадьбу прервали (пара погибла, игрок ушёл, ночь, рейд): служба кончена, брак не записан, если до него не дошли. */
        public synchronized void cancel() { if (!over()) phase = Phase.CANCELLED; }

        /** Сторож: реплика, которую не взяли или не договорили, пропускается; клятва без ответа — переспрос, потом «молчание — знак
         * согласия»; служба дольше {@link #SERVICE_MILLIS} сразу идёт к браку и празднику. */
        public synchronized void tick(long now) {
            if (phase != Phase.SERVICE) return;
            Beat beat = current();
            if (beat == null) { phase = jilted ? Phase.JILTED : Phase.DONE; return; }
            if (now - serviceStarted > SERVICE_MILLIS && !wed && !jilted) { skipToWedding(now); return; }
            if (beat.line()) {
                long limit = claimed ? speakMillis(line(beat).text()) + LINE_SLACK_MILLIS : UNCLAIMED_MILLIS;
                if (now - beatStarted > limit) next(now);
            } else if (beat.vow()) {
                if (now - beatStarted > vowWaitMillis) {
                    UUID leader = cast.leader().id();
                    if (!reasked) {
                        reasked = true;
                        beats.add(index, new Beat(nextSeq++, Key.REASK, leader, null, false));
                    } else {
                        vowText = "";
                        beats.set(index, new Beat(beat.seq(), Key.SILENCE, leader, null, false)); // Молчание — знак согласия.
                    }
                    claimed = false; beatStarted = now;
                }
            } else if (beat.act() == Act.FEAST) {
                if (executed && now - beatStarted > FEAST_MILLIS) phase = Phase.DONE;
                else if (!executed && now - beatStarted > UNCLAIMED_MILLIS) phase = Phase.DONE;
            } else if (now - beatStarted > UNCLAIMED_MILLIS) {
                // Действие, которое игра не выполнила (её нет): брак всё равно отмечается, остальное пропускается.
                if (beat.act() == Act.WED) wed = true;
                next(now);
            }
        }
        /** Служба затянулась: всё, что до брака, пропускается. */
        private void skipToWedding(long now) {
            for (int i = index; i < beats.size(); i++) if (beats.get(i).act() == Act.WED) {
                index = i; claimed = false; executed = false; beatStarted = now; return;
            }
            phase = Phase.DONE;
        }
        /** Сколько шагов в службе (для проверок). */
        public synchronized int length() { return beats.size(); }
        /** Время с начала сбора. */
        public long age(long now) { return now - started; }
    }
}
