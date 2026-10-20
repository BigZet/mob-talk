package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.List;

/** Беременность в паре игрока (трек N, docs/child.md): всё как у людей. После согласия «давай заведём ребёнка» пара старается —
 * каждая ночь вместе даёт шанс, особая ночь — больший; носит та из пары, кто женщина (жительница или сам игрок); однополая пара
 * остаётся на прежнем пути (малыш через игровой день). Срок по умолчанию 5 игровых дней: три периода, потом роды, всегда
 * благополучные. Узнают не сразу, а через игровой день. Здесь чистые правила и слова для промпта; игру двигает ChildController. */
public final class Pregnancy {
    private Pregnancy() { }
    /** О беременности узнают через столько тиков после зачатия (не больше трети срока). */
    public static final long KNOWN_TICKS = ChildGrowth.DAY_TICKS;
    /** Схватки до родов: столько тиков жительница идёт к кровати и зовёт игрока. */
    public static final long LABOUR_TICKS = 600;

    /** Кто носит ребёнка. NONE — однополая пара: ребёнок приходит прежним путём, без беременности. */
    public enum Carrier { VILLAGER, PLAYER, NONE }
    /** {@code player} — null, когда пол игрока не задан («any»): тогда носит жительница, а с жителем-мужчиной — игрок. */
    public static Carrier carrier(Gender villager, Gender player) {
        if (villager == Gender.FEMALE) return player == Gender.FEMALE ? Carrier.NONE : Carrier.VILLAGER;
        return player == Gender.MALE ? Carrier.NONE : Carrier.PLAYER;
    }

    public enum Term {
        EARLY("ранний срок"), MIDDLE("середина срока"), LATE("поздний срок"), DUE("роды");
        public final String word;
        Term(String word) { this.word = word; }
        public static Term of(String name) {
            if (name == null || name.isBlank()) return null;
            try { return valueOf(name); } catch (IllegalArgumentException unknown) { return null; }
        }
    }
    /** Период на тике {@code now}: три равные трети срока, на дне родов и после — DUE. */
    public static Term term(long conceived, long now, int days) {
        long total = Math.max(1, days) * ChildGrowth.DAY_TICKS, passed = Math.max(0, now - conceived);
        if (passed >= total) return Term.DUE;
        int third = (int) Math.min(2, passed * 3 / total);
        return Term.values()[third];
    }
    /** Какой идёт день срока: 1..days. */
    public static int day(long conceived, long now, int days) {
        int d = (int) (Math.max(0, now - conceived) / ChildGrowth.DAY_TICKS) + 1;
        return Math.max(1, Math.min(Math.max(1, days), d));
    }
    /** Знают ли уже о малыше: прошёл игровой день (или треть короткого срока). */
    public static boolean known(long conceived, long now, int days) {
        long total = Math.max(1, days) * ChildGrowth.DAY_TICKS;
        return now - conceived >= Math.min(KNOWN_TICKS, total / 3);
    }
    /** Насколько округлить живот на фигурке: в начале не видно, в середине чуть, к концу заметно. */
    public static float belly(Term term) {
        if (term == null) return 0;
        return switch (term) { case EARLY -> 0f; case MIDDLE -> 0.5f; case LATE, DUE -> 1f; };
    }
    /** Строка под именем: «ждёт малыша · 3 из 5 дней», в последний день «скоро роды». */
    public static String status(Term term, int day, int days) {
        if (term == null) return "";
        if (term == Term.DUE) return "роды";
        if (day >= days) return "ждёт малыша · скоро роды";
        return "ждёт малыша · " + day + " из " + days + " дн.";
    }

    /** Шанс зачатия за ночь вместе (проценты из настроек): обычная ночь — {@code ordinary}, особая — от {@code low} до {@code high}
     * по уюту в доме. */
    public static double chance(boolean special, int cozy, int ordinary, int low, int high) {
        if (!special) return clamp(ordinary) / 100.0;
        int lo = clamp(Math.min(low, high)), hi = clamp(Math.max(low, high));
        return (lo + (hi - lo) * Math.max(0, Math.min(Cozy.MAX, cozy)) / (double) Cozy.MAX) / 100.0;
    }
    private static int clamp(int percent) { return Math.max(0, Math.min(100, percent)); }

    /** Уют дома для особой ночи: что игрок приготовил. Всё ванильное. */
    public record Cozy(boolean candles, boolean flowers, boolean carpet, boolean music, boolean cake, boolean alone) {
        public static final int MAX = 6;
        public static final Cozy NONE = new Cozy(false, false, false, false, false, false);
        public int score() { return (candles ? 1 : 0) + (flowers ? 1 : 0) + (carpet ? 1 : 0) + (music ? 1 : 0) + (cake ? 1 : 0) + (alone ? 1 : 0); }
        /** Что она видит в доме, словами для промпта. */
        public List<String> words() {
            List<String> words = new ArrayList<>();
            if (candles) words.add("зажжённые свечи и мягкий свет");
            if (flowers) words.add("цветы");
            if (carpet) words.add("ковёр у кровати");
            if (music) words.add("в проигрывателе играет пластинка");
            if (cake) words.add("торт на столе");
            if (alone) words.add("в доме никого, кроме вас двоих");
            return words;
        }
    }

    /** Чего хочется сегодня: по дню срока, чтобы желание держалось весь день. */
    private static final String[] CRAVINGS = {"сладких ягод", "арбуза", "торта", "жареной рыбы", "мёда", "тыквенного пирога", "печенья", "яблок"};
    public static String craving(long dayIndex) { return CRAVINGS[(int) Math.floorMod(dayIndex * 7 + 3, CRAVINGS.length)]; }

    /** Факт для промпта супруга: что с вами сейчас. {@code day}/{@code days} — день и длина срока; {@code craving} — желание дня. */
    public static String fact(Carrier carrier, Term term, int day, int days, String craving, boolean known) {
        if (term == null || carrier == Carrier.NONE) return "";
        if (!known) return carrier == Carrier.VILLAGER ? "Тебя с утра немного мутит и тянет на странную еду; ты ещё не знаешь почему — может, и догадываешься."
                : "";
        String when = term == Term.DUE ? "сегодня роды" : day + "-й день из " + days;
        if (carrier == Carrier.PLAYER) return "Вы ждёте малыша: его носит игрок (" + when + "). Ты заботлив, бережёшь, приносишь еду, ревнуешь меньше и радуешься.";
        String feel = switch (term) {
            case EARLY -> "по утрам мутит, тянет на странную еду, ты то плачешь, то смеёшься";
            case MIDDLE -> "ты сияешь, думаешь об имени, о том, каким будет малыш";
            case LATE -> "тяжело ходить, ноет спина, просишь игрока не уходить далеко";
            case DUE -> "кажется, началось — тебе страшно и радостно";
        };
        return "Ты ждёшь малыша от игрока (" + when + "): " + feel + (craving == null || craving.isBlank() ? "." : "; сегодня тебе ужасно хочется " + craving + ".")
                + " Говори об этом естественно, не в каждой реплике.";
    }
    /** Пара решила завести ребёнка и старается: можно позвать игрока на особый вечер. */
    public static final String TRYING = "Вы с игроком решили завести ребёнка и теперь стараетесь. Можешь сам(а) предложить ему особый вечер дома "
            + "(свечи, музыка, ужин) или просто ночь вдвоём — по настроению, без нажима.";

    /** Повод узнать о малыше (реплика носящей жительницы игроку). */
    public static String newsCue(String playerName) {
        return "ты только что поняла, что ждёшь ребёнка от " + who(playerName) + ". Скажи ему об этом сама — взволнованно и счастливо, по характеру, одной-двумя фразами";
    }
    /** Тот же повод, когда носит игрок: житель только что узнал. */
    public static String newsCuePlayer(String playerName) {
        return "ты только что узнал, что " + who(playerName) + " ждёт от тебя ребёнка. Скажи, что чувствуешь, по характеру, одной-двумя фразами";
    }
    public static String termCue(Term term) {
        return switch (term) {
            case EARLY -> "";
            case MIDDLE -> "срок уже середина, малыш шевелится. Поделись с игроком радостью или подумай вслух об имени — одной репликой";
            case LATE -> "срок поздний: тяжело ходить, ты просишь игрока быть рядом и не уходить далеко — одной репликой по характеру";
            case DUE -> "кажется, началось — роды. Скажи игроку об этом одной короткой взволнованной репликой, ты идёшь домой к кровати";
        };
    }
    public static String cravingCue(String craving) {
        return "тебе, беременной, сегодня ужасно хочется " + craving + ". Попроси игрока принести — ласково или капризно, по характеру, одной репликой";
    }
    /** После родов: тёплая реплика матери (отца). {@code near} — был ли игрок рядом. */
    public static String birthCue(String babyWord, String playerName, boolean near) {
        return "только что родился ваш с " + who(playerName) + " малыш: " + babyWord + ". " + (near ? "Игрок был рядом. " : "Игрока не было рядом — ты ждала его и скажешь об этом без упрёка. ")
                + "Скажи одну-две счастливые, усталые фразы";
    }
    private static String who(String playerName) { return playerName == null || playerName.isBlank() ? "игрока" : playerName; }
}
