package io.github.mobtalk.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Talking a night bandit down (game/BanditEntity): the player's words are read without the model — a number offered, a plea, a
 * threat to call the guard or the police, a claim of empty pockets — and the game decides what the gang does: take less, refuse,
 * raise the price, back off, or go for the knife. The bandit's spoken line is the model's; the price and the outcome are only this.
 * Each kind of move works once per robbery ({@link State#used}). Pure Java. */
public final class BanditHaggle {
    private BanditHaggle() { }

    public enum Move { NONE, OFFER, PLEA, THREAT, EMPTY }
    public enum Kind { NONE, LOWER, REFUSE, RAISE, LEAVE, FIGHT }

    /** What the player said, as a move; {@code amount} is the number offered (OFFER) or -1. */
    public record Said(Move move, int amount) { public static final Said NONE = new Said(Move.NONE, -1); }

    /** The robbery as it stands: what is asked and already paid, the emeralds the player carries, whether a guard or police post
     * is near enough to be feared, and the moves already tried (bits of {@link Move#ordinal()}). */
    public record State(int demand, int paid, int emeralds, boolean guarded, int used) {
        public int owed() { return Math.max(0, demand - paid); }
        boolean tried(Move move) { return (used & (1 << move.ordinal())) != 0; }
    }

    /** The game's answer: {@code demand} is the new whole demand (paid included), {@code seconds} added to (or, negative, taken
     * from) the time he waits, {@code used} the moves tried after this one, {@code note} the line for the player. */
    public record Outcome(Kind kind, int demand, int seconds, int used, String note) {
        public static Outcome none(State state) { return new Outcome(Kind.NONE, state.demand(), 0, state.used(), ""); }
    }

    /** The least share of the debt (percent) a number offered must reach to be taken. */
    public static final int OFFER_FLOOR = 60;
    /** A plea takes a quarter off; a lie about empty pockets or a threat with nobody near adds this share (percent). */
    public static final int PLEA_CUT = 25, LIE_RAISE = 50, BLUFF_RAISE = 25;
    /** At most this many emeralds a gang asks, as in {@code BanditController.accost}. */
    public static final int MAX_DEMAND = 64;

    private static final Pattern THREAT = Pattern.compile("(полиц|милиц|(?<![а-я])мент|страж|охран|патрул|позову|закричу|кричать|караул|помогите|(?<![а-я])сдам(?!ся)|посадят|участков|(?<![а-я])ппс|омон|(?<![а-я])коп(ы|ов))");
    private static final Pattern EMPTY = Pattern.compile("(нет\\s+(у\\s+меня\\s+)?(ни\\s+)?(изумруд|денег|деньг|бабл|ничего)|ничего\\s+нет|пуст(ой|ая|ые)|без\\s+денег|на\\s+мели|нищ)");
    private static final Pattern PLEA = Pattern.compile("(пожалуйста|пожалей|пощади|прошу|умоляю|сжальс|войди\\s+в\\s+положение|последн|дети|детей|семь[яи]|голода|не\\s+надо|отпусти|помилуй|скинь|скости|поменьше|подешевле|сбавь|уступи)");
    private static final Pattern OFFER = Pattern.compile("(дам|даю|отдам|отдаю|возьми|бери|забирай|держи|могу|есть\\s+только|только|хватит|на\\s+тебе)");

    /** The player's move in these words; NONE when they touch none of it. A number with an offering word, or a bare number, is an
     * offer; a threat outranks a plea; a claim of no money is EMPTY even when it pleads too. */
    public static Said read(String said) {
        if (said == null || said.isBlank()) return Said.NONE;
        String text = said.toLowerCase(Locale.ROOT).replace('ё', 'е');
        List<Integer> numbers = AnswerValidator.spokenNumbers(text);
        int amount = -1;
        for (int i = numbers.size() - 1; i >= 0; i--) if (numbers.get(i) >= 1 && numbers.get(i) <= 999) { amount = numbers.get(i); break; }
        if (amount > 0 && (OFFER.matcher(text).find() || text.strip().matches("\\d+\\D{0,12}"))) return new Said(Move.OFFER, amount);
        if (THREAT.matcher(text).find()) return new Said(Move.THREAT, -1);
        if (EMPTY.matcher(text).find()) return new Said(Move.EMPTY, -1);
        if (PLEA.matcher(text).find()) return new Said(Move.PLEA, -1);
        return Said.NONE;
    }

    /** The least number the gang takes instead of the debt. */
    public static int floor(State state) { return Math.max(1, (state.owed() * OFFER_FLOOR + 99) / 100); }
    /** Whether a plea would still soften him: he hasn't heard one, and the player is not rich next to what is asked. */
    public static boolean pleaWorks(State state) { return !state.tried(Move.PLEA) && state.emeralds() <= state.owed() * 2; }

    /** What the gang does about this move. */
    public static Outcome decide(Said said, State state) {
        if (said == null || said.move() == Move.NONE || state == null || state.owed() <= 0) return state == null ? new Outcome(Kind.NONE, 0, 0, 0, "") : Outcome.none(state);
        int used = state.used() | (1 << said.move().ordinal());
        int owed = state.owed();
        boolean again = state.tried(said.move());
        return switch (said.move()) {
            case OFFER -> {
                if (said.amount() >= owed) yield Outcome.none(state); // offers the whole debt: just hand it over
                if (!again && said.amount() >= floor(state))
                    yield new Outcome(Kind.LOWER, state.paid() + said.amount(), 5, used, "Бандит согласился на " + said.amount() + " изумр.");
                yield new Outcome(Kind.REFUSE, state.demand(), -5, used, again ? "Второй раз торговаться бандит не стал" : "Бандит не взял " + said.amount() + ": слишком мало");
            }
            case PLEA -> {
                if (pleaWorks(state) && owed > 1) {
                    int cut = Math.max(1, owed * PLEA_CUT / 100);
                    yield new Outcome(Kind.LOWER, state.demand() - cut, 5, used, "Бандит сбавил цену до " + (owed - cut) + " изумр.");
                }
                yield new Outcome(Kind.REFUSE, state.demand(), -3, used, again ? "Жалобы бандита больше не трогают" : "Бандит видит, что изумруды у вас есть, и не уступил");
            }
            case EMPTY -> {
                if (state.emeralds() >= owed) {
                    int raised = Math.min(MAX_DEMAND, state.demand() + Math.max(1, owed * LIE_RAISE / 100));
                    yield new Outcome(Kind.RAISE, raised, -5, used, "Бандит поймал вас на вранье: теперь " + (raised - state.paid()) + " изумр.");
                }
                if (state.emeralds() <= 0) yield new Outcome(Kind.FIGHT, state.demand(), 0, used, "Изумрудов нет: бандиты берут, что есть");
                yield new Outcome(Kind.LOWER, state.paid() + state.emeralds(), 5, used, "Бандит согласен на всё, что есть: " + state.emeralds() + " изумр.");
            }
            case THREAT -> {
                if (state.guarded()) yield new Outcome(Kind.LEAVE, state.demand(), 0, used, "Пост рядом: банда не стала связываться и ушла");
                if (again) yield new Outcome(Kind.FIGHT, state.demand(), 0, used, "Угрозы надоели бандитам");
                int raised = Math.min(MAX_DEMAND, state.demand() + Math.max(1, owed * BLUFF_RAISE / 100));
                yield new Outcome(Kind.RAISE, raised, -5, used, "Поста рядом нет, бандит только разозлился: теперь " + (raised - state.paid()) + " изумр.");
            }
            case NONE -> Outcome.none(state);
        };
    }

    /** The bandit's lines in his prompt: what he asks, and how the game will answer each move, so his words match the outcome. */
    public static String prompt(State state, String player) {
        if (state == null || state.owed() <= 0) return "";
        StringBuilder text = new StringBuilder("\n\nСейчас ты с бандой грабишь игрока ").append(player).append(": требуешь ").append(state.owed())
                .append(" изумр. (кошелёк или жизнь). Платят ПКМ изумрудами или через «Подарить». ")
                .append("Цену и исход решает игра, а ты говоришь в лад с ней:")
                .append("\n— если игрок предлагает число не меньше ").append(floor(state)).append(" — соглашайся на его число; меньше — грубо отказывай;");
        text.append(pleaWorks(state) ? "\n— если просит пожалеть — нехотя скидываешь четверть;" : "\n— на жалобы не ведёшься: видишь, что у него есть изумруды;");
        text.append(state.emeralds() >= state.owed() ? "\n— если врёт, что денег нет, — ты видишь, что есть, злишься и поднимаешь цену;"
                : "\n— у него правда мало: соглашайся на всё, что есть;");
        text.append(state.guarded() ? "\n— рядом пост стражи или полиции: если он грозит позвать — ты нервничаешь и уходишь с бандой;"
                : "\n— поста рядом нет: на угрозы позвать стражу или полицию смеёшься и поднимаешь цену;");
        text.append("\nНе называй других чисел, кроме этих. Ты грубый уличный бандит, но без мата.");
        return text.toString();
    }
}
