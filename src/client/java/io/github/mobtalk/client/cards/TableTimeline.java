package io.github.mobtalk.client.cards;

import io.github.mobtalk.core.cards.CardTable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.sound.SoundEvent;

/** Время стола на клиенте. Сервер присылает итог раздачи сразу (все карты, расчёт), а экран показывает его по порядку: карты летят
 * из колоды одна за другой, карта жителя переворачивается, житель добирает с паузами 0,6–1,2 с, потом перебор, сияние, фишки едут к
 * победителю. Здесь — когда что начинается; рисует {@link CardTableScreen}, эффекты — {@link TableFx}. Время в миллисекундах. */
final class TableTimeline {
    static final long FLY = 250, FLIP = 300, CHIPS = 400, BUST = 500, NATURAL = 1200, CHEAT = 400;
    /** Куда и откуда: точки экрана, которые знает только раскладка. */
    interface Places {
        float[] shoe();
        float[] wallet();
        float[] purse();
        float[] bet(int seat);
        float[] hand(int seat, int hand);
        float scale();
    }
    enum Point { SHOE, WALLET, PURSE, BET }
    /** Фишки в пути: от точки к точке (у {@link Point#BET} — место {@code seat}). */
    record Move(int amount, Point from, int fromSeat, Point to, int toSeat, long start, long life, boolean sparkle) { }

    private final Random random = new Random();
    private final TableFx fx;
    private final Places places;
    /** 1 — обычная скорость, 2 — быстрая, 0 — без анимаций. */
    float speed = 1;
    private int round = -1;
    private long cursor;
    private boolean dealerDrawing;
    private final Map<String, Long> arrive = new HashMap<>();
    private final Map<Integer, Long> reveal = new HashMap<>();
    private final Map<String, Long> bust = new HashMap<>(), natural = new HashMap<>();
    private final Map<Integer, Long> settleAt = new HashMap<>();
    private final Map<Integer, Integer> flying = new HashMap<>();
    private final Map<Integer, List<Integer>> dealt = new HashMap<>();
    private final List<Move> moves = new ArrayList<>();
    private final List<Object[]> sounds = new ArrayList<>(); // {время, SoundEvent или Runnable, высота тона}
    long cheatAt = -1;
    int cheatCard = -1;

    TableTimeline(TableFx fx, Places places) { this.fx = fx; this.places = places; }

    private long d(long millis) { return speed <= 0 ? 0 : (long) (millis / speed); }
    private int next(int seat, int hand) {
        List<Integer> counts = dealt.computeIfAbsent(seat, s -> new ArrayList<>());
        while (counts.size() <= hand) counts.add(0);
        int index = counts.get(hand);
        counts.set(hand, index + 1);
        return index;
    }
    private int count(int seat, int hand) { List<Integer> c = dealt.get(seat); return c == null || c.size() <= hand ? 0 : c.get(hand); }
    static String key(int seat, int hand, int index) { return seat + ":" + hand + ":" + index; }

    /** Новые события со свежим видом стола: расписание анимаций. */
    void accept(List<CardTableClient.Event> events, CardTable.View view, long now) {
        if (view != null && view.round() != round) {
            round = view.round();
            arrive.clear(); reveal.clear(); bust.clear(); natural.clear(); settleAt.clear(); dealt.clear();
            dealerDrawing = false; cheatAt = -1; cheatCard = -1;
        }
        cursor = Math.max(cursor, now);
        long settle = -1;
        for (CardTableClient.Event e : events) {
            int seat = e.seat(), hand = e.hand();
            switch (e.kind()) {
                case "SHUFFLE" -> { sound(cursor, TableSounds.SHUFFLE, 1); cursor += d(650); }
                case "BET", "DOUBLE", "VA_BANK" -> {
                    if (e.amount() > 0) { chips(e.amount(), Point.WALLET, -1, Point.BET, seat, now, false); flying.merge(seat, e.amount(), Integer::sum); }
                    else if (e.amount() < 0) chips(-e.amount(), Point.BET, seat, Point.WALLET, -1, now, false);
                    sound(now, TableSounds.CHIPS, 1.0f + random.nextFloat() * 0.15f);
                }
                case "DEAL" -> {
                    if (seat == CardTable.DEALER && dealerDrawing) cursor += d(600 + random.nextInt(600));
                    int index = next(seat, hand);
                    arrive.put(key(seat, hand, index), cursor);
                    sound(cursor, TableSounds.DEAL, 0.9f + random.nextFloat() * 0.2f);
                    cursor += d(dealerDrawing ? FLY : 170);
                }
                case "CHEAT" -> {
                    cursor += d(600 + random.nextInt(600));
                    int index = next(CardTable.DEALER, 0);
                    arrive.put(key(CardTable.DEALER, 0, index), cursor);
                    cheatAt = cursor; cheatCard = index;
                    sound(cursor, TableSounds.DEAL, 1.1f);
                    cursor += d(FLY);
                }
                case "REVEAL" -> {
                    int index = Math.max(0, count(CardTable.DEALER, 0) - 1);
                    long at = cursor + d(250);
                    reveal.put(index, at);
                    sound(at, TableSounds.FLIP, 1);
                    cursor = at + d(FLIP + 150);
                    dealerDrawing = true;
                }
                case "SPLIT" -> {
                    List<Integer> counts = dealt.computeIfAbsent(seat, s -> new ArrayList<>());
                    while (counts.size() <= hand) counts.add(0);
                    counts.set(hand, 1);
                    counts.add(hand + 1, 1);
                    chips(e.amount(), Point.WALLET, -1, Point.BET, seat, now, false);
                }
                case "BUST" -> {
                    long at = cursor + d(100);
                    bust.put(seat + ":" + hand, at);
                    float[] p = places.hand(seat, hand);
                    fx.burst(TableFx.Burst.Kind.FLASH, p[0], p[1], 90 * places.scale(), 0xFF2A20, at, BUST, "");
                    fx.burst(TableFx.Burst.Kind.TEXT, p[0], p[1] - 34 * places.scale(), 1.5f + 0.5f * places.scale(), 0xFF5A4A, at, 1100, "Перебор!");
                    later(at, TableSounds::bust);
                    cursor = at + d(350);
                }
                case "NATURAL" -> {
                    long at = cursor + d(120);
                    natural.put(seat + ":" + hand, at);
                    float[] p = places.hand(seat, hand);
                    float s = places.scale();
                    fx.burst(TableFx.Burst.Kind.RAYS, p[0], p[1], 105 * s, 0xFFB43C, at, NATURAL + 300, "");
                    fx.burst(TableFx.Burst.Kind.GLOW, p[0], p[1], 90 * s, 0xFFA020, at, NATURAL, "");
                    fx.burst(TableFx.Burst.Kind.RING, p[0], p[1], 120 * s, 0xFFE08A, at, 700, "");
                    fx.sparks(p[0], p[1], 46, 140 * s, 0xFFD060, at + d(80));
                    fx.burst(TableFx.Burst.Kind.TEXT, p[0], p[1] - 34 * s, 2.2f + 0.4f * s, 0xFFD24A, at + d(100), NATURAL + 400, view != null && "BLACKJACK".equals(view.variant()) ? "БЛЭКДЖЕК!" : "ЗОЛОТОЕ ОЧКО!");
                    later(at, TableSounds::win);
                    cursor = at + d(900);
                }
                case "SETTLE" -> {
                    if (settle < 0) settle = cursor + d(350);
                    int bet = handBet(view, seat, hand);
                    int back = e.amount();
                    settleAt.merge(seat, settle, Math::min);
                    float[] p = places.bet(seat);
                    float s = places.scale();
                    if (back <= 0) {
                        chips(bet, Point.BET, seat, Point.PURSE, -1, settle, false);
                        if (bet > 0) fx.burst(TableFx.Burst.Kind.TEXT, p[0], p[1] - 30 * s, 1.5f + 0.4f * s, 0xFF6A5A, settle, 1300, "−" + bet);
                    } else if (back <= bet) {
                        chips(back, Point.BET, seat, Point.WALLET, -1, settle, false);
                        fx.burst(TableFx.Burst.Kind.TEXT, p[0], p[1] - 30 * s, 1.5f + 0.4f * s, 0xE4E8EF, settle, 1300, "Ничья");
                    } else {
                        // Житель доплачивает на круг, стопка растёт, вспыхивает и едет к игроку, сыплются искры.
                        chips(back - bet, Point.PURSE, -1, Point.BET, seat, settle, false);
                        long go = settle + d(CHIPS + 250);
                        chips(back, Point.BET, seat, Point.WALLET, -1, go, true);
                        fx.burst(TableFx.Burst.Kind.GLOW, p[0], p[1] - 10 * s, 70 * s, 0x5CFF9A, settle + d(CHIPS), 700, "");
                        fx.burst(TableFx.Burst.Kind.RING, p[0], p[1] - 8 * s, 90 * s, 0xB8FFD0, settle + d(CHIPS), 600, "");
                        fx.sparks(p[0], p[1] - 10 * s, 48, 150 * s, 0x8CFFB0, settle + d(CHIPS));
                        fx.burst(TableFx.Burst.Kind.TEXT, p[0] + 46 * s, p[1] - 16 * s, 2.0f + 0.5f * s, 0x8CF0A8, settle + d(CHIPS), 1600, "+" + (back - bet));
                        later(settle + d(CHIPS), TableSounds::win);
                    }
                    sound(settle, TableSounds.CHIPS, back > bet ? 1.2f : 0.9f);
                }
                case "CAUGHT" -> {
                    float[] p = places.purse();
                    fx.burst(TableFx.Burst.Kind.TEXT, p[0] + 60 * places.scale(), p[1], 2.4f, 0xFFD24A, cursor, 1500, "Поймал!");
                    fx.sparks(p[0], p[1], 30, 110 * places.scale(), 0xFFD060, cursor);
                    if (e.amount() > 0) chips(e.amount(), Point.PURSE, -1, Point.WALLET, -1, cursor, true);
                    later(cursor, TableSounds::villagerNo);
                }
                case "FALSE_ACCUSE" -> {
                    float[] p = places.purse();
                    fx.burst(TableFx.Burst.Kind.TEXT, p[0] + 60 * places.scale(), p[1], 2.0f, 0xFF8A7A, cursor, 1300, "Зря!");
                    later(cursor, TableSounds::villagerHmm);
                }
                default -> { }
            }
        }
        if (settle >= 0) {
            boolean dealerWon = false, playerWon = false;
            for (CardTableClient.Event e : events) if ("SETTLE".equals(e.kind())) {
                int bet = handBet(view, e.seat(), e.hand());
                if (e.amount() <= 0) dealerWon = true; else if (e.amount() > bet) playerWon = true;
            }
            if (dealerWon && !playerWon) later(settle, TableSounds::villagerYes);
            else if (playerWon && !dealerWon) later(settle, TableSounds::villagerNo);
            cursor = settle + d(CHIPS * 2 + 200);
        }
    }
    private static int handBet(CardTable.View view, int seat, int hand) {
        if (view == null || seat < 0 || seat >= view.seats().size() || view.seats().get(seat) == null) return 0;
        List<CardTable.HandView> hands = view.seats().get(seat).hands();
        return hand < hands.size() ? hands.get(hand).bet() : 0;
    }
    private void chips(int amount, Point from, int fromSeat, Point to, int toSeat, long start, boolean sparkle) {
        if (amount > 0) moves.add(new Move(amount, from, fromSeat, to, toSeat, start, Math.max(1, d(CHIPS)), sparkle));
    }
    private void sound(long at, SoundEvent sound, float pitch) { if (sound != null) sounds.add(new Object[]{at, sound, pitch}); }
    private void later(long at, Runnable run) { sounds.add(new Object[]{at, run, 1f}); }

    /** Звуки, чей час пришёл; фишки, что долетели, перестают считаться «в пути». */
    void tick(long now) {
        for (Iterator<Object[]> it = sounds.iterator(); it.hasNext(); ) {
            Object[] s = it.next();
            if ((long) s[0] > now) continue;
            it.remove();
            if (s[1] instanceof SoundEvent sound) TableSounds.play(sound, (float) s[2], 0.8f);
            else if (s[1] instanceof Runnable run) run.run();
        }
        for (Iterator<Move> it = moves.iterator(); it.hasNext(); ) {
            Move m = it.next();
            if (now < m.start() + m.life()) continue;
            it.remove();
            if (m.to() == Point.BET) flying.computeIfPresent(m.toSeat(), (k, v) -> v - m.amount() <= 0 ? null : v - m.amount());
        }
    }

    // ---- Что видно сейчас. ----

    /** Доля полёта карты 0..1, −1 — ещё в колоде, 1 — лежит (и у карт, что пришли без события). */
    float flight(int seat, int hand, int index, long now) {
        Long start = arrive.get(key(seat, hand, index));
        if (start == null) return 1;
        if (now < start) return -1;
        return Math.min(1, (now - start) / (float) Math.max(1, d(FLY)));
    }
    /** Переворот закрытой карты жителя: −1 — ещё рубашкой, 0..1 — переворачивается, 1 — открыта (или нечего переворачивать). */
    float flip(int index, long now) {
        Long start = reveal.get(index);
        if (start == null) return 1;
        if (now < start) return -1;
        return Math.min(1, (now - start) / (float) Math.max(1, d(FLIP)));
    }
    /** Тряска руки после перебора: смещение по x; и доля красного затемнения 0..1 (держится). */
    float shake(int seat, int hand, long now) {
        Long at = bust.get(seat + ":" + hand);
        if (at == null || now < at || now > at + d(BUST)) return 0;
        float t = (now - at) / (float) Math.max(1, d(BUST));
        return (float) Math.sin(t * 40) * 4 * (1 - t) * places.scale();
    }
    /** Красная вспышка перебора 0..1 (полсекунды). */
    float bustFlash(int seat, int hand, long now) {
        Long at = bust.get(seat + ":" + hand);
        if (at == null || now < at || now > at + d(BUST)) return 0;
        return 1 - (now - at) / (float) Math.max(1, d(BUST));
    }
    boolean busted(int seat, int hand, long now) { Long at = bust.get(seat + ":" + hand); return at != null && now >= at; }
    boolean natural(int seat, int hand, long now) { Long at = natural.get(seat + ":" + hand); return at != null && now >= at; }
    /** Ставка места ещё лежит на круге (до расчёта). */
    boolean stakeShown(int seat, long now) { Long at = settleAt.get(seat); return at == null || now < at; }
    /** Изумруды, что ещё летят к кругу места: стопка на круге пока без них. */
    int inFlight(int seat) { return flying.getOrDefault(seat, 0); }
    List<Move> moves() { return moves; }
    /** Что-то ещё показывается (кнопки хода ждут). */
    boolean busy(long now) { return cursor > now; }
    /** Рывок «руки» жителя: смещение портрета, пока он лезет в рукав. */
    float tell(long now) {
        if (cheatAt < 0 || now < cheatAt - d(150) || now > cheatAt + d(CHEAT)) return 0;
        return (float) Math.sin((now - cheatAt) / 18.0) * 3;
    }
    /** Карта из рукава мигает. */
    boolean blink(int index, long now) {
        return index == cheatCard && cheatAt >= 0 && now >= cheatAt && now <= cheatAt + d(CHEAT) && (now - cheatAt) / 70 % 2 == 0;
    }
}
