package io.github.mobtalk.core;

import java.util.*;

/** Соперники за одно сердце (идея П3): кто из игроков ухаживает за этим жителем, когда был последний романтический жест, сколько
 * испытаний выиграл, кого житель отверг и до какого времени. Хранится в профиле жителя, создаётся при первом ухаживании. Решения —
 * кто ухажёр, можно ли отбить, кого житель выберет — здесь, чистой Java; модель их только озвучивает. Реальное время, все изменения
 * под замком хранилища. */
public final class Rivalry {
    /** Разница счёта выбора меньше этой — ничья: житель не выбирает наугад. */
    public static final int TIE = 3;
    /** Испытание даёт к счёту выбора столько за каждую победу. */
    public static final int TRIAL_WEIGHT = 5;
    /** Поправка характера к счёту выбора — не больше. */
    public static final int PERSONA_CAP = 5;
    /** Сколько после ухода к другому житель помнит, что должен сказать об этом брошенному. */
    public static final long LEFT_FRESH_MILLIS = 60 * 60_000L;
    /** Испытание длится столько; следующее — не раньше чем через игровые сутки (20 минут) после конца прошлого. */
    public static final long TRIAL_MILLIS = 10 * 60_000L, TRIAL_GAP_MILLIS = 20 * 60_000L;
    /** Любимые подарки для испытания «кто первым подарит»: редкие, но добываемые вещи, по одной. */
    public static final List<String> FAVORITES = List.of("minecraft:cake", "minecraft:pumpkin_pie", "minecraft:honey_bottle", "minecraft:golden_apple",
            "minecraft:diamond", "minecraft:emerald", "minecraft:amethyst_shard", "minecraft:glow_berries", "minecraft:music_disc_cat",
            "minecraft:spyglass", "minecraft:name_tag", "minecraft:enchanted_book", "minecraft:nautilus_shell", "minecraft:glow_ink_sac");

    /** Один ухажёр. */
    static final class Suitor {
        String name = "";
        /** Последний романтический жест (реальные мс), −1 — не было; сколько жестов в текущем окне интереса. */
        long lastRomance = -1; int romances;
        /** Последний заметный поступок словами жителя («подарил тебе minecraft:poppy»), для сравнения в репликах. */
        String deed = ""; long deedAt = -1;
        /** Выигранные испытания. */
        int trialWins;
        /** Отвергнут при выборе: до этого времени не ухажёр и букет с кольцом не принимаются. */
        long pausedUntil = -1;
        /** Житель ушёл от этого игрока к другому: к кому и когда (сказать при встрече). */
        String leftFor = ""; long leftAt = -1;
        Suitor copy() {
            Suitor c = new Suitor(); c.name = name; c.lastRomance = lastRomance; c.romances = romances; c.deed = deed; c.deedAt = deedAt;
            c.trialWins = trialWins; c.pausedUntil = pausedUntil; c.leftFor = leftFor; c.leftAt = leftAt;
            return c;
        }
        void sanitize() {
            if (name == null) name = ""; if (deed == null) deed = ""; if (leftFor == null) leftFor = "";
            romances = Math.max(0, Math.min(1000, romances)); trialWins = Math.max(0, Math.min(100, trialWins));
        }
    }

    /** Испытание соперников: кто первым принесёт {@code count} предмета ({@link #BRING}) или первым подарит любимую вещь
     * ({@link #FAVORITE}, {@code count} = 1). Прогресс — сколько принёс каждый, по UUID игрока. Реальное время. */
    static final class Trial {
        static final String BRING = "BRING", FAVORITE = "FAVORITE";
        String kind = BRING, item = "";
        int count = 1;
        long startedAt = -1, endsAt = -1;
        Map<String, Integer> progress = new LinkedHashMap<>();
        Trial copy() {
            Trial c = new Trial(); c.kind = kind; c.item = item; c.count = count; c.startedAt = startedAt; c.endsAt = endsAt;
            c.progress.putAll(progress);
            return c;
        }
        boolean valid() {
            if (progress == null) progress = new LinkedHashMap<>();
            progress.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
            count = Math.max(1, Math.min(64, count));
            return item != null && item.contains(":") && startedAt >= 0 && endsAt > startedAt;
        }
    }

    /** Ухажёры по UUID игрока, в порядке появления. */
    Map<String, Suitor> suitors = new LinkedHashMap<>();
    /** Когда житель последний раз говорил о соперничестве по своей воле (оба рядом), реальные мс. */
    long lastTease = -1;
    /** Идущее испытание или null; когда кончилось прошлое (реальные мс, −1 — не было). */
    Trial trial; long lastTrialAt = -1;
    /** Выбор у колокола назначен на это время (−1 — нет); кто принёс кольцо. */
    long sceneAt = -1; String sceneBy = "";

    Suitor suitor(UUID player) { return player == null ? null : suitors.get(player.toString()); }
    Suitor ensure(UUID player, String name) {
        Suitor s = suitors.computeIfAbsent(player.toString(), ignored -> new Suitor());
        if (name != null && !name.isBlank()) s.name = TextBounds.characters(name, 60);
        return s;
    }

    /** Романтический жест игрока: начинает или продолжает окно интереса ({@code windowMillis}). */
    void romance(UUID player, String name, String deed, long now, long windowMillis) {
        Suitor s = ensure(player, name);
        if (s.lastRomance < 0 || now - s.lastRomance > windowMillis) s.romances = 0;
        s.lastRomance = now; s.romances++;
        if (deed != null && !deed.isBlank()) { s.deed = TextBounds.characters(deed, 80); s.deedAt = now; }
    }
    /** Ухаживает ли игрок сейчас: был жест в окне и он не отвергнут. Сердечки и прочие условия проверяет книга. */
    boolean courting(UUID player, long now, long windowMillis) {
        Suitor s = suitor(player);
        return s != null && s.lastRomance >= 0 && now - s.lastRomance <= windowMillis && !paused(player, now);
    }
    boolean paused(UUID player, long now) { Suitor s = suitor(player); return s != null && s.pausedUntil > now; }

    /** Сколько нужно сердечек сверху, чтобы житель ушёл от пары к другому: кокетливому хватает меньшего, ранимый и невозмутимый
     * держатся дольше. */
    public static int stealMargin(Persona persona, int base) {
        if (persona == null) return base;
        return switch (persona) {
            case FLIRTY -> Math.max(0, base - 10);
            case SENSITIVE, UNFLAPPABLE -> base + 10;
            default -> base;
        };
    }
    /** Поправка характера к счёту выбора: жадному важны подарки, кокетливому и игривому — частые знаки внимания. */
    public static int personaBonus(Persona persona, int giftsRemembered, int romances) {
        if (persona == null) return 0;
        return switch (persona) {
            case GREEDY -> Math.min(PERSONA_CAP, Math.max(0, giftsRemembered));
            case FLIRTY, PLAYFUL -> Math.min(PERSONA_CAP, Math.max(0, romances - 1));
            default -> 0;
        };
    }
    /** Счёт выбора: сердечки, победы в испытаниях, минус ревность (кто заигрывал с другими), плюс характер. */
    public static int score(int hearts, int trialWins, int jealousy, int personaBonus) {
        return hearts + TRIAL_WEIGHT * Math.max(0, trialWins) - Math.max(0, jealousy) / 10 + personaBonus;
    }

    /** Один претендент при выборе. */
    public record Candidate(UUID player, String name, int score) { }
    /** Итог выбора: победитель или ничья (тогда {@code winner} null, а {@code tied} — кто наравне с лучшим). */
    public record Choice(Candidate winner, List<Candidate> losers, List<Candidate> tied) {
        public boolean tie() { return winner == null; }
    }
    /** Кого житель выберет: лучший по счёту, если отрыв от второго не меньше {@link #TIE}; иначе ничья. */
    public static Choice choose(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return new Choice(null, List.of(), List.of());
        List<Candidate> order = new ArrayList<>(candidates);
        order.sort(Comparator.comparingInt(Candidate::score).reversed());
        Candidate best = order.getFirst();
        if (order.size() == 1) return new Choice(best, List.of(), List.of());
        if (best.score() - order.get(1).score() < TIE) {
            List<Candidate> tied = order.stream().filter(c -> best.score() - c.score() < TIE).toList();
            return new Choice(null, List.of(), tied);
        }
        return new Choice(best, List.copyOf(order.subList(1, order.size())), List.of());
    }
    /** Насколько соперник впереди этого игрока, словами, без чисел: для промпта и HUD. */
    public enum Standing {
        AHEAD("впереди"), EVEN("наравне"), BEHIND("позади");
        public final String word;
        Standing(String word) { this.word = word; }
        /** Соперник относительно игрока по счёту выбора. */
        public static Standing of(int rivalScore, int ownScore) {
            int diff = rivalScore - ownScore;
            return diff >= TIE ? AHEAD : diff <= -TIE ? BEHIND : EVEN;
        }
    }

    void sanitize() {
        if (suitors == null) suitors = new LinkedHashMap<>();
        suitors.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        suitors.values().forEach(Suitor::sanitize);
        if (trial != null && !trial.valid()) trial = null;
        if (sceneBy == null) sceneBy = "";
        // Не больше десятка записей: самые давние уходят.
        while (suitors.size() > 10) {
            String oldest = null; long at = Long.MAX_VALUE;
            for (Map.Entry<String, Suitor> e : suitors.entrySet()) if (e.getValue().lastRomance < at) { at = e.getValue().lastRomance; oldest = e.getKey(); }
            suitors.remove(oldest);
        }
    }
    Rivalry copy() {
        Rivalry c = new Rivalry(); suitors.forEach((key, s) -> c.suitors.put(key, s.copy())); c.lastTease = lastTease;
        c.trial = trial == null ? null : trial.copy(); c.lastTrialAt = lastTrialAt; c.sceneAt = sceneAt; c.sceneBy = sceneBy;
        return c;
    }
}
