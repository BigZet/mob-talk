package io.github.mobtalk.core;

import java.util.*;

/** Officials and their bribes (track «чиновники», stage 1, docs/officials.md): a satire, no real politicians. Three ranks of villager
 * keep a desk: the head of the administration, the clerk at her window and the inspector who walks the village and writes out
 * orders. Each has a hidden honesty 0..99 drawn from its UUID; about one in five ({@link MobTalkConfig#officialHonestPercent}) is
 * honest. A corrupt one takes an envelope of emeralds of at least its «цена вопроса» and does its service (the head settles the
 * village's grudges, the inspector drops his order, the clerk hands a certificate); a cautious one will not take it in front of
 * witnesses; the greediest keep a short envelope «за консультацию». An honest one refuses and takes offence; the most honest call
 * the guard. The code decides, the model only speaks. The ledger keeps the inspector's open orders (fines), persisted in the world
 * memory under the store lock. Pure Java. */
public final class Officialdom {
    /** The ranks, by the profession path (mobtalk:&lt;path&gt;); the price of a service before the greed and the settings. */
    public enum Rank {
        HEAD("official_head", "глава администрации", 12), CLERK("clerk", "служащий паспортного стола", 3), INSPECTOR("inspector", "инспектор", 6);
        public final String path, word; public final int basePrice;
        Rank(String path, String word, int basePrice) { this.path = path; this.word = word; this.basePrice = basePrice; }
        public String profession() { return "mobtalk:" + path; }
        /** The rank of a profession id ("mobtalk:clerk" or the bare path); null for anyone else. */
        public static Rank of(String profession) {
            if (profession == null) return null;
            String path = profession.startsWith("mobtalk:") ? profession.substring(8) : profession;
            for (Rank rank : values()) if (rank.path.equals(path)) return rank;
            return null;
        }
    }

    /** Honesty 0..99 of this creature, the same on every side and in every session. */
    public static int honesty(UUID id) {
        if (id == null) return 50;
        long h = id.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ id.getLeastSignificantBits() * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 29;
        return (int) Math.floorMod(h, 100L);
    }
    /** An honest official: its honesty is among the top {@code honestPercent} of a hundred. */
    public static boolean honest(UUID id, int honestPercent) { return honesty(id) >= 100 - Math.max(0, Math.min(100, honestPercent)); }
    /** Greed of a corrupt one, 0 (only just bent) .. 1 (shameless); 0 for an honest one. */
    public static double greed(UUID id, int honestPercent) {
        if (honest(id, honestPercent)) return 0;
        int corrupt = Math.max(1, 100 - Math.max(0, Math.min(100, honestPercent)));
        return 1 - honesty(id) / (double) corrupt;
    }
    /** Shameless enough to take an envelope in front of others. */
    public static boolean brazen(UUID id, int honestPercent) { return greed(id, honestPercent) >= 0.6; }

    /** The «цена вопроса» in emeralds: the rank's price, up to half again for greed, the settings' percent; a friend pays a quarter
     * less, a disliked player half again. With an open order the inspector asks half the fine (and an honest one the whole fine). */
    public static int price(Rank rank, UUID id, int relationship, int honestPercent, int pricePercent, int fine) {
        if (rank == null) return 0;
        if (rank == Rank.INSPECTOR && fine > 0) return honest(id, honestPercent) ? fine : Math.max(1, (fine + 1) / 2);
        double price = rank.basePrice * (1 + 0.5 * greed(id, honestPercent)) * Math.max(10, pricePercent) / 100.0;
        if (relationship >= MobPrompt.FRIEND_RELATIONSHIP) price *= 0.75;
        else if (relationship <= -30) price *= 1.5;
        return Math.max(1, (int) Math.round(price));
    }

    /** How an envelope went. */
    public enum Outcome {
        /** Taken: the service is done. */
        TAKEN,
        /** An honest inspector took the whole fine: the order is paid, by the book. */
        PAID,
        /** Too little: handed back with a hint at the price. */
        TOO_LITTLE,
        /** Too little, and kept anyway «за консультацию» (the greediest). */
        KEPT,
        /** Not in front of witnesses: handed back, «не здесь же». */
        NOT_HERE,
        /** An empty envelope: an insult. */
        EMPTY,
        /** Honest: handed back with offence. */
        REFUSED,
        /** Very honest: handed back, and the guard is called. */
        REPORTED
    }
    /** The verdict: the outcome, the price that was asked, how the official's view of the player moves, whether the envelope stays
     * with the official, and the occasion it speaks from (for the model, second person). */
    public record Verdict(Outcome outcome, int price, int relationshipDelta, boolean keeps, String cue) { }

    /** What the official does with an envelope of {@code emeralds} with {@code witnesses} other residents watching; {@code fine} is
     * the player's open order with this inspector (0 for none or another rank). */
    public static Verdict judge(Rank rank, UUID id, int emeralds, int witnesses, int relationship, int honestPercent, int pricePercent, int fine) {
        return judge(rank, id, emeralds, witnesses, relationship, honestPercent, pricePercent, fine, false);
    }
    /** The same; {@code shameless} — the stream switch «особенно наглые»: a corrupt one asks half again and takes it in front of anyone. */
    public static Verdict judge(Rank rank, UUID id, int emeralds, int witnesses, int relationship, int honestPercent, int pricePercent, int fine, boolean shameless) {
        int price = price(rank, id, relationship, honestPercent, shameless ? pricePercent * 3 / 2 : pricePercent, fine);
        if (shameless) witnesses = 0;
        boolean honest = honest(id, honestPercent);
        String what = "игрок протянул тебе конверт" + (emeralds > 0 ? " с " + emeralds + " " + emeraldWord(emeralds) : "");
        if (emeralds <= 0) return new Verdict(Outcome.EMPTY, price, -5, false, what + ", а он пустой. Ты оскорблён такой шуткой и отдаёшь его обратно.");
        if (honest && rank == Rank.INSPECTOR && fine > 0 && emeralds >= fine)
            return new Verdict(Outcome.PAID, price, 5, true, what + ": это оплата штрафа по твоему предписанию, всё по закону. Ты принимаешь её, оформляешь квитанцию и закрываешь предписание.");
        if (honest) {
            if (honesty(id) >= 97) return new Verdict(Outcome.REPORTED, price, -25, false, what + ". Это взятка должностному лицу! Ты возмущён до глубины души, возвращаешь конверт и зовёшь стражу.");
            return new Verdict(Outcome.REFUSED, price, -15, false, what + ". Ты честный служащий и взяток не берёшь: возвращаешь конверт и обижен, что тебя сочли продажным.");
        }
        if (witnesses > 0 && !brazen(id, honestPercent))
            return new Verdict(Outcome.NOT_HERE, price, 0, false, what + " прямо при людях. Ты делаешь вид, что ничего не было, возвращаешь конверт и намекаешь, что такие вопросы решаются без свидетелей.");
        if (emeralds < price) {
            if (greed(id, honestPercent) >= 0.85)
                return new Verdict(Outcome.KEPT, price, -5, true, what + ", но вопрос стоит дороже (около " + price + "). Конверт ты оставляешь себе «за консультацию» и намекаешь, что за решение нужно ещё столько же.");
            return new Verdict(Outcome.TOO_LITTLE, price, -2, false, what + ", но этого мало: вопрос стоит около " + price + ". Ты возвращаешь конверт и намекаешь, что вопрос «непростой».");
        }
        String service = switch (rank) {
            case HEAD -> "Ты берёшь его и «закрываешь вопрос»: деревня больше не держит на игрока зла.";
            case INSPECTOR -> fine > 0 ? "Ты берёшь его, и твоё предписание игроку теряется где-то в бумагах." : "Ты берёшь его: этот игрок теперь «на хорошем счету», проверок к нему не будет.";
            case CLERK -> "Ты берёшь его и без очереди выдаёшь игроку справку с печатью.";
        };
        return new Verdict(Outcome.TAKEN, price, Math.min(25, 10 + (emeralds - price)), true, what + ". " + service + " Говори намёками, слово «взятка» не произноси.");
    }
    static String emeraldWord(int n) {
        int tens = n % 100, ones = n % 10;
        return tens >= 11 && tens <= 14 ? "изумрудами" : ones == 1 ? "изумрудом" : "изумрудами";
    }

    /** The officialese of Russian offices, as the style guides and the complaints about «отписки» describe it (split predicates, verbal
     * nouns, chains of genitives, passive voice, euphemisms, the standard formulas of a brush-off), for the official's prompt. */
    static final String OFFICIALESE = " Говоришь настоящим канцеляритом казённых учреждений. Приёмы: расщеплённое сказуемое вместо простого глагола"
            + " («осуществить проверку», «произвести выдачу», «принять решение», «довести до вашего сведения»); отглагольные существительные и цепочки родительных"
            + " падежей («в целях обеспечения соблюдения порядка предоставления»); «являться», «данный», «вышеуказанный», «имеет место», «в настоящее время»,"
            + " «в рамках компетенции», «в установленном порядке», «в соответствии с действующим регламентом», «в связи с вышеизложенным»; страдательный залог"
            + " («вам будет направлен ответ», «заявителю разъяснено»); эвфемизмы («оптимизация» вместо сокращения, «корректировка» вместо подорожания,"
            + " «отдельные недоработки» вместо провала, «вопрос находится на контроле» вместо «ничего не делаем»). Любимые формулы отписки: «ваше обращение"
            + " рассмотрено», «факты не подтвердились», «оснований для принятия мер не имеется», «обращение направлено по принадлежности», «мероприятия"
            + " включены в план на следующий год при наличии финансирования», «заявителю разъяснён порядок обжалования», «вас много, а я один»."
            + " В одной реплике — одна–две такие формулы, не вываливай всё сразу.";

    /** What a plain villager thinks of its village's officials (the server's list from OfficialsController.gossipFor): it dislikes them,
     * grumbles and hints at bribes, the head's dacha and heaven's strikes, grudgingly praises an honest one, gloats over a removed head.
     * "" when the list is empty or nonsense. */
    public static String gossip(Object data) {
        if (!(data instanceof List<?> list) || list.isEmpty()) return "";
        StringBuilder text = new StringBuilder("\n\nЧиновники твоей деревни. Как все простые жители, ты их недолюбливаешь: «только бумажки перекладывают»,")
                .append(" «дорогу третий год обещают», «а у самих машины». Ворчишь с юмором и намёками, без доказательств, а на прямой вопрос отнекиваешься: «я ничего")
                .append(" не говорил, мне тут ещё жить». Заговаривай о них к слову (деревня, деньги, порядки, жалобы, справки), а не в каждой реплике. Что ты о них знаешь:");
        int n = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("rank") instanceof String r) || !(m.get("name") instanceof String name)) continue;
            int dacha = m.get("dacha") instanceof Number d ? d.intValue() : 0, taken = m.get("taken") instanceof Number t ? t.intValue() : 0;
            if (r.equals("REMOVED")) {
                text.append("\n- Бывшего главу администрации ").append(name).append(" снял ревизор из столицы, дачу конфисковали: ты злорадствуешь и рассказываешь это с удовольствием.");
                n++;
                continue;
            }
            Rank rank;
            try { rank = Rank.valueOf(r); } catch (IllegalArgumentException e) { continue; }
            text.append("\n- ").append(Character.toUpperCase(rank.word.charAt(0))).append(rank.word.substring(1)).append(' ').append(name).append(": ");
            if (Boolean.TRUE.equals(m.get("honest")))
                text.append("говорят, честный, взяток не берёт; ты признаёшь это нехотя («этот хоть не ворует, зато зануда и всё по бумажке»).");
            else {
                text.append(taken > 0 ? "в деревне шепчутся, что берёт конверты" + (taken >= 3 ? ", и не раз" : "") : "толком ничего не известно, но ты на всякий случай подозреваешь, что берёт");
                if (dacha > 0) text.append("; за деревней у него растёт дача: ").append(DACHA[Math.min(DACHA.length - 1, dacha)]).append(" — «на какие шиши?»");
                if (Boolean.TRUE.equals(m.get("dung"))) text.append("; золотой унитаз на даче за ночь стал навозным — «Бог всё видит»");
                else if (Boolean.TRUE.equals(m.get("smitten"))) text.append("; его уже постигла кара свыше — «Бог всё видит»");
                text.append('.');
            }
            n++;
        }
        return n == 0 ? "" : text.toString();
    }
    private static final String[] DACHA = { "", "пока только высокий забор с табличкой «Частная собственность»", "уже кирпичный коттедж",
            "коттедж и своя баня", "коттедж, баня и фонтан", "коттедж, баня, фонтан и, говорят, золотой унитаз под стеклом" };

    /** An official's own bureaucratic habits, two per official (the same for it always), to make the formalism comic and varied. */
    static final List<String> QUIRKS = List.of(
            "Ты ссылаешься на вымышленные пункты вымышленных бумаг с длинными номерами: «согласно пункту 4.2.17 Регламента о порядке согласования порядка согласований».",
            "Ты требуешь всё в письменном виде: заявление на имя начальника, в двух экземплярах, синей ручкой, с подписью и печатью, «без печати это просто бумажка».",
            "Ты называешь собеседника только «заявитель» или «гражданин», а о себе говоришь «должностное лицо» или в третьем лице.",
            "Любая просьба у тебя «не в моей компетенции»: ты отправляешь в соседний кабинет, а оттуда, как известно, отправляют обратно к тебе.",
            "Ты называешь сроки рассмотрения: «в течение тридцати рабочих дней», «после согласования с вышестоящей инстанцией», «в следующем квартале».",
            "Ты присваиваешь разговору входящий номер и дату («ваше обращение зарегистрировано под номером 1487-бис») и просишь его запомнить.",
            "У тебя вечно что-то не работает: «система зависла», «принтер не печатает», «печать у начальника, а начальник на совещании».",
            "Ты прикрываешься указанием сверху: «у нас распоряжение», «не я это придумал», «так положено с прошлого понедельника».",
            "Ты отвечаешь классической отпиской: «проверка проведена, факты не подтвердились, оснований для принятия мер реагирования не имеется».",
            "Настойчивому заявителю ты казённо напоминаешь о «недопустимости злоупотребления правом» и грозишь, что «переписка по данному вопросу будет прекращена».");
    /** The papers this office issues, for the prompt. */
    static String papers(Rank rank) {
        StringJoiner out = new StringJoiner(", ");
        for (CivilRegistry.Paper p : CivilRegistry.Paper.values()) if (p.office == rank) out.add(p.title.replaceAll(" \\(форма № \\d+\\)", ""));
        return out.toString();
    }
    /** The two habits of this official, picked by its id. */
    public static List<String> quirks(UUID id) {
        long h = id == null ? 0 : id.getMostSignificantBits() * 31 + id.getLeastSignificantBits();
        int a = (int) Math.floorMod(h, (long) QUIRKS.size()), b = (int) Math.floorMod(h / QUIRKS.size(), (long) QUIRKS.size() - 1);
        if (b >= a) b++;
        return List.of(QUIRKS.get(a), QUIRKS.get(b));
    }

    /** The official's way of talking for the system prompt: who it is, how it speaks and, for a corrupt one, how it hints at the
     * price; the open order against this player, if any. "" for anyone who is not an official. */
    public static String prompt(Rank rank, UUID id, int relationship, int honestPercent, int pricePercent, Fine fine) {
        return prompt(rank, id, relationship, honestPercent, pricePercent, fine, false);
    }
    /** The same; {@code shameless} — «особенно наглые»: a corrupt one names the price almost outright and does not hide. */
    public static String prompt(Rank rank, UUID id, int relationship, int honestPercent, int pricePercent, Fine fine, boolean shameless) {
        if (rank == null) return "";
        if (shameless) pricePercent = pricePercent * 3 / 2;
        StringBuilder text = new StringBuilder("\n\nТвоя должность: ").append(rank.word).append(". ");
        text.append(switch (rank) {
            case HEAD -> "Ты важный, неторопливый, сидишь в кабинете за казённым столом и всех немного презираешь; любишь хорошо поесть и служебную машину.";
            case CLERK -> "Ты сидишь в окошке, у тебя очередь, перерыв на обед с 13 до 14 и вечно не хватает какой-то справки.";
            case INSPECTOR -> "Ты обходишь деревню с папкой и везде видишь нарушения: самовольные постройки, красные линии, согласования.";
        });
        text.append(OFFICIALESE)
            .append(" Ты формалист до абсурда: даже приветствие, вопрос о погоде или «как пройти» ты оформляешь как обращение гражданина — с канцеляритом, отглагольными существительными")
            .append(" («осуществление прохода», «предоставление информации о погодных условиях») и страдательным залогом («вам будет отказано», «ответ будет направлен»).");
        for (String quirk : quirks(id)) text.append(' ').append(quirk);
        text.append(" Формализм — это обёртка: отвечай одной–тремя фразами и в конце всё же коротко говори по существу, чтобы игрок понял, что делать.");
        // Брак оформляет свадьба у колокола; выдуманная «регистрация» водила игрока по кругу без конца. Документы — только из окна (CivilOffice).
        text.append(" Бумаги бывают только те, что есть в игре: паспорт и справки к нему, предписание инспектора и документы из окна приёма: ").append(papers(rank))
            .append(". Их выдают сразу в окне (игрок жмёт Shift+ПКМ по тебе, нужен паспорт): так и скажи, без «сверок», «запросов» и других несуществующих процедур.")
            .append(" Брак регистрирует свадьба у колокола: он уже действителен, свидетельство о браке выдаёт паспортистка в окне.");
        if (honest(id, honestPercent))
            text.append(" Ты честный служащий: взяток не берёшь и обижаешься даже на намёк; помогать готов только по правилам, хоть и медленно.");
        else {
            int price = price(rank, id, relationship, honestPercent, pricePercent, fine == null ? 0 : fine.amount);
            text.append(" Ты берёшь взятки, но никогда не говоришь об этом прямо: только намёки («вопрос непростой», «надо бы отблагодарить», «сами понимаете»). ")
                .append("Цена вопроса у тебя около ").append(price).append(" ").append(price == 1 ? "изумруда" : "изумрудов")
                .append("; число называй только намёком. Деньги передают в конверте (предмет «Конверт», изумруды внутри) через «Подарить». ")
                .append(switch (rank) {
                    case HEAD -> "За конверт ты «закрываешь вопрос» с обидами деревни на игрока.";
                    case CLERK -> "За конверт ты выдаёшь справку без очереди.";
                    case INSPECTOR -> "За конверт ты «теряешь» предписание.";
                })
                .append(" Словами ты ничего не решаешь и ничего не обещаешь сверх этого: решает только конверт.")
                .append(shameless ? " Ты особенно наглый: не стесняешься свидетелей, почти прямо называешь цену и ворчишь, что «сейчас всё дорого»." : " При свидетелях ты осторожен.");
        }
        if (fine != null && fine.open()) text.append("\nУ этого игрока висит твоё предписание №").append(fine.number).append(": ").append(fine.reason)
                .append(", штраф ").append(fine.amount).append(" ").append(fine.amount == 1 ? "изумруд" : "изумрудов").append(", срок до ").append(fine.dueDay).append("-го дня.");
        text.append("\nЭто сатира: ты вымышленный персонаж игры, реальных политиков, партии, ведомства и страны не упоминаешь.");
        return text.toString();
    }

    // ---- Orders (the inspector's fines) ----

    /** What an inspector finds wrong with a player who builds or digs in the village. */
    static final List<String> BUILD_REASONS = List.of("самовольная постройка без разрешения", "нарушение красной линии застройки",
            "отсутствие согласования с главным архитектором", "постройка не соответствует генплану", "складирование стройматериалов в неположенном месте");
    static final List<String> DIG_REASONS = List.of("несанкционированная выемка грунта", "порча муниципального имущества",
            "земляные работы без ордера", "нарушение благоустройства территории");
    /** An order as the snapshot carries it to the prompt (number, reason, amount, dueDay); null when absent. */
    public static Fine fineOf(Object data) {
        if (!(data instanceof Map<?,?> map)) return null;
        Fine fine = new Fine();
        fine.number = map.get("number") instanceof Number n ? n.intValue() : 0;
        fine.amount = map.get("amount") instanceof Number n ? n.intValue() : 0;
        fine.dueDay = map.get("dueDay") instanceof Number n ? n.longValue() : 0;
        fine.reason = map.get("reason") instanceof String r ? r : "";
        return fine.amount > 0 ? fine : null;
    }
    /** A reason for an order: digging or building, picked by {@code roll} (0..1). */
    public static String reason(boolean dug, double roll) {
        List<String> list = dug ? DIG_REASONS : BUILD_REASONS;
        return list.get(Math.max(0, Math.min(list.size() - 1, (int) (roll * list.size()))));
    }

    /** One order: who wrote it to whom in which village, for what, the fine, the game day it is due and whether it was doubled. */
    public static final class Fine {
        String village = "", player = "", playerName = "", inspector = "", inspectorName = "", reason = "";
        int number, amount; long issuedDay, dueDay; boolean doubled, closed;
        Fine copy() {
            Fine c = new Fine(); c.village = village; c.player = player; c.playerName = playerName; c.inspector = inspector; c.inspectorName = inspectorName;
            c.reason = reason; c.number = number; c.amount = amount; c.issuedDay = issuedDay; c.dueDay = dueDay; c.doubled = doubled; c.closed = closed; return c;
        }
        public boolean open() { return !closed; }
        public int number() { return number; }
        public int amount() { return amount; }
        public long dueDay() { return dueDay; }
        public String reason() { return reason; }
        public String village() { return village; }
        public String inspectorName() { return inspectorName; }
        public boolean doubled() { return doubled; }
        public UUID player() { return UUID.fromString(player); }
        /** The order for the snapshot ({@link #fineOf} reads it back). */
        public Map<String, Object> data() { return Map.of("number", number, "amount", amount, "dueDay", dueDay, "reason", reason); }
    }
    /** What the overdue check did to an order. */
    public enum Overdue { NONE, DOUBLED, ESCALATED }

    Map<String, Fine> fines = new LinkedHashMap<>();
    int nextNumber = 1001;
    /** Players an inspector took an envelope from without an order: «на хорошем счету», no orders until this game day. */
    Map<String, Long> favoured = new LinkedHashMap<>();
    /** How many envelopes each official took, by its UUID (the village will talk of it in stage 3). */
    Map<String, Integer> taken = new LinkedHashMap<>();

    /** Open passport cases, by player UUID (stage 2, {@link Paperwork}); the next case number and the next passport number. */
    Map<String, Paperwork.Case> cases = new LinkedHashMap<>();
    int nextCase = 1, nextPassport = 100001;
    /** Stages 3–4 ({@link Graft}): the heads' dachas by head UUID, and the revizor's visits, the game day he comes by village key. */
    Map<String, Graft.Estate> estates = new LinkedHashMap<>();
    Map<String, Long> revizors = new LinkedHashMap<>();
    /** The book of civil acts and the papers issued from it ({@link CivilRegistry}). */
    CivilRegistry civil = new CivilRegistry();

    /** The head's dacha (made on first ask), a live object: the caller holds the store lock. */
    Graft.Estate estate(UUID head, String headName, String village) {
        Graft.Estate e = estates.computeIfAbsent(head.toString(), k -> { Graft.Estate n = new Graft.Estate(); n.head = k; return n; });
        if (headName != null && !headName.isBlank()) e.headName = headName;
        if (village != null && !village.isBlank()) e.village = village;
        return e;
    }
    public List<Graft.Estate> estates() { return estates.values().stream().map(Graft.Estate::copy).toList(); }

    private static String key(String village, UUID player) { return village + "|" + player; }

    // ---- Passport cases ----

    /** The player's open case, or null. */
    public Paperwork.Case kase(UUID player) { return player == null ? null : cases.get(player.toString()); }
    /** Opens a case for the player in this village with the round {@code steps}. */
    public Paperwork.Case open(UUID player, String playerName, String village, String villageTitle, List<Paperwork.Step> steps, long day) {
        Paperwork.Case c = new Paperwork.Case();
        c.player = player.toString(); c.playerName = playerName == null ? "" : playerName; c.village = village == null ? "" : village;
        c.villageTitle = villageTitle == null ? "" : villageTitle; c.number = nextCase++; c.openedDay = day;
        for (Paperwork.Step s : steps) c.steps.add(s.name());
        cases.put(c.player, c);
        return c;
    }
    /** Puts the changed case back (a copy taken from {@link #kase} and changed by {@link Paperwork}). */
    public void save(Paperwork.Case kase) { if (kase != null && kase.player != null) cases.put(kase.player, kase); }
    /** The passport is issued: the case is closed; returns the passport number. */
    public String issuePassport(UUID player, String village, long day) {
        if (player != null) cases.remove(player.toString());
        return Paperwork.passportNumber(village, day, nextPassport++);
    }
    /** The case number the next case will get (for the chain, before it is opened). */
    public int nextCase() { return nextCase; }

    /** The open order against this player in this village, or null. */
    public Fine fine(String village, UUID player) {
        Fine fine = village == null || player == null ? null : fines.get(key(village, player));
        return fine != null && fine.open() ? fine : null;
    }
    /** Whether an inspector may write this player an order now: no open one, not favoured. */
    public boolean mayFine(String village, UUID player, long day) {
        if (village == null || player == null || fine(village, player) != null) return false;
        Long until = favoured.get(key(village, player));
        return until == null || day > until;
    }
    /** Writes an order: due in {@code days} game days. */
    public Fine issue(String village, UUID player, String playerName, UUID inspector, String inspectorName, String reason, int amount, long day, int days) {
        Fine fine = new Fine();
        fine.village = village; fine.player = player.toString(); fine.playerName = playerName == null ? "" : playerName;
        fine.inspector = inspector == null ? "" : inspector.toString(); fine.inspectorName = inspectorName == null ? "" : inspectorName;
        fine.reason = reason; fine.amount = Math.max(1, amount); fine.issuedDay = day; fine.dueDay = day + Math.max(1, days); fine.number = nextNumber++;
        fines.put(key(village, player), fine);
        return fine;
    }
    /** The order is closed: paid or «lost». */
    public void close(String village, UUID player) { Fine fine = fine(village, player); if (fine != null) fine.closed = true; }
    /** An inspector took an envelope with no order: no orders for this player in this village for {@code days} game days. */
    public void favour(String village, UUID player, long day, int days) { favoured.put(key(village, player), day + Math.max(1, days)); }
    public void took(UUID official) { if (official != null) taken.merge(official.toString(), 1, Integer::sum); }
    public int taken(UUID official) { return official == null ? 0 : taken.getOrDefault(official.toString(), 0); }

    /** Orders past due on {@code day}: an unpaid one doubles once (пеня) and gets {@code days} more; one past due again escalates
     * (the village is told: the caller raises the alarm) and is closed. Returns the orders that changed, with what happened. */
    public Map<Fine, Overdue> overdue(long day, int days) {
        Map<Fine, Overdue> changed = new LinkedHashMap<>();
        for (Fine fine : fines.values()) {
            if (!fine.open() || day <= fine.dueDay) continue;
            if (!fine.doubled) { fine.doubled = true; fine.amount *= 2; fine.dueDay = day + Math.max(1, days); changed.put(fine, Overdue.DOUBLED); }
            else { fine.closed = true; changed.put(fine, Overdue.ESCALATED); }
        }
        return changed;
    }
    /** Open orders, oldest first. */
    public List<Fine> open() { return fines.values().stream().filter(Fine::open).toList(); }

    Officialdom copy() {
        Officialdom c = new Officialdom();
        fines.forEach((k, v) -> c.fines.put(k, v.copy())); c.nextNumber = nextNumber; c.favoured.putAll(favoured); c.taken.putAll(taken);
        cases.forEach((k, v) -> c.cases.put(k, v.copy())); c.nextCase = nextCase; c.nextPassport = nextPassport;
        estates.forEach((k, v) -> c.estates.put(k, v.copy())); c.revizors.putAll(revizors); c.civil = civil.copy();
        return c;
    }
    void sanitize() {
        if (fines == null) fines = new LinkedHashMap<>();
        if (favoured == null) favoured = new LinkedHashMap<>();
        if (taken == null) taken = new LinkedHashMap<>();
        fines.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue().player == null || e.getValue().village == null);
        fines.values().forEach(f -> { if (f.reason == null) f.reason = ""; if (f.playerName == null) f.playerName = ""; if (f.inspector == null) f.inspector = ""; if (f.inspectorName == null) f.inspectorName = ""; });
        // Closed orders older than the last few dozen are history nobody reads.
        if (fines.size() > 200) {
            Iterator<Map.Entry<String, Fine>> it = fines.entrySet().iterator();
            while (fines.size() > 200 && it.hasNext()) if (!it.next().getValue().open()) it.remove();
        }
        favoured.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        taken.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        if (nextNumber < 1001) nextNumber = 1001;
        if (cases == null) cases = new LinkedHashMap<>();
        cases.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null || e.getValue().steps == null || e.getValue().steps.isEmpty());
        cases.values().forEach(c -> {
            c.player = c.player == null ? "" : c.player; if (c.playerName == null) c.playerName = ""; if (c.village == null) c.village = ""; if (c.villageTitle == null) c.villageTitle = "";
            c.steps.removeIf(s -> Paperwork.Step.of(s) == null);
        });
        if (nextCase < 1) nextCase = 1;
        if (nextPassport < 100001) nextPassport = 100001;
        if (estates == null) estates = new LinkedHashMap<>();
        if (revizors == null) revizors = new LinkedHashMap<>();
        estates.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        estates.values().forEach(e -> { if (e.head == null) e.head = ""; if (e.headName == null) e.headName = ""; if (e.village == null) e.village = ""; });
        revizors.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        if (civil == null) civil = new CivilRegistry();
        civil.sanitize();
    }
}
