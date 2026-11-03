package io.github.mobtalk.core;

import java.util.*;

/** The passport and its paperwork (track «чиновники», stage 2, docs/officials.md): a satire of the office round. The clerk opens a
 * case and sends the player around the offices: an application on the form, then a few papers from the head, the inspector and the
 * clerk herself (a certificate of residence, the state fee, a photo, a housing inspection, the stamp «Согласовано»), each in its
 * office, some «ready tomorrow»; the last step is collecting the passport. The chain is drawn from the player and the case number,
 * so it stays the same across sessions. A corrupt clerk's envelope rushes the whole case; a small gift skips the current wait. The
 * code decides, the model only speaks. Pure Java; the cases live in {@link Officialdom}, under the store lock. */
public final class Paperwork {
    private Paperwork() { }

    /** A paper of the round: the office it is done in, what the player must bring ({@code item} by id, {@code count}), whether it
     * costs the state fee, and how many game days the office takes before it is ready («приходите завтра»). */
    public enum Step {
        APPLICATION(Officialdom.Rank.CLERK, "заявление по форме №1П", "minecraft:paper", 1, false, 0),
        RESIDENCE(Officialdom.Rank.HEAD, "справка о прописке", "", 0, false, 1),
        DUTY(Officialdom.Rank.HEAD, "квитанция об уплате госпошлины", "", 0, true, 0),
        PHOTO(Officialdom.Rank.CLERK, "фотография 3×4", "minecraft:ink_sac", 1, false, 0),
        HOUSING(Officialdom.Rank.INSPECTOR, "акт обследования жилищных условий", "", 0, false, 0),
        STAMP(Officialdom.Rank.HEAD, "печать «Согласовано»", "", 0, false, 1),
        ISSUE(Officialdom.Rank.CLERK, "получение паспорта", "", 0, false, -1);
        public final Officialdom.Rank office; public final String title, item; public final int count, waitDays; public final boolean fee;
        Step(Officialdom.Rank office, String title, String item, int count, boolean fee, int waitDays) {
            this.office = office; this.title = title; this.item = item; this.count = count; this.fee = fee; this.waitDays = waitDays;
        }
        /** The game days this step takes; the last one takes the configured passport days. */
        public int days(int passportDays) { return waitDays < 0 ? Math.max(0, passportDays) : waitDays; }
        public static Step of(String name) {
            for (Step s : values()) if (s.name().equals(name)) return s;
            return null;
        }
    }
    /** The papers between the application and the passport, in the order they are drawn. */
    static final List<Step> MIDDLE = List.of(Step.RESIDENCE, Step.DUTY, Step.PHOTO, Step.HOUSING, Step.STAMP);

    /** The round for this case: the application, {@code extra} papers whose office the village has, then collecting the passport. */
    public static List<Step> chain(UUID player, int number, int extra, Set<Officialdom.Rank> offices) {
        List<Step> pool = new ArrayList<>();
        for (Step s : MIDDLE) if (offices == null || offices.contains(s.office)) pool.add(s);
        long seed = (player == null ? 0 : player.getMostSignificantBits() ^ player.getLeastSignificantBits()) * 31 + number;
        Collections.shuffle(pool, new Random(seed));
        List<Step> out = new ArrayList<>();
        out.add(Step.APPLICATION);
        out.addAll(pool.subList(0, Math.max(0, Math.min(extra, pool.size()))));
        out.add(Step.ISSUE);
        return out;
    }

    /** One player's open case for a passport. */
    public static final class Case {
        String player = "", playerName = "", village = "", villageTitle = "";
        int number;
        List<String> steps = new ArrayList<>();
        int index;
        /** The game day the current step is ready; -1 while it has not been handed in yet. */
        long readyDay = -1;
        long openedDay;
        Case copy() {
            Case c = new Case(); c.player = player; c.playerName = playerName; c.village = village; c.villageTitle = villageTitle; c.number = number;
            c.steps = new ArrayList<>(steps); c.index = index; c.readyDay = readyDay; c.openedDay = openedDay; return c;
        }
        public UUID player() { try { return UUID.fromString(player); } catch (RuntimeException e) { return null; } }
        public String playerName() { return playerName; }
        public String village() { return village; }
        public String villageTitle() { return villageTitle; }
        public int number() { return number; }
        public long readyDay() { return readyDay; }
        public int index() { return index; }
        public List<Step> steps() { return steps.stream().map(Step::of).filter(Objects::nonNull).toList(); }
        /** The paper the player is on now; null once everything is done. */
        public Step current() { return index >= 0 && index < steps.size() ? Step.of(steps.get(index)) : null; }
        public boolean waiting() { return readyDay >= 0; }
        /** For the snapshot (and the prompt on the client). */
        public Map<String, Object> data() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("number", number); out.put("steps", List.copyOf(steps)); out.put("index", index); out.put("readyDay", readyDay);
            out.put("village", villageTitle);
            return out;
        }
    }
    /** A case back from the snapshot map; null for none or nonsense. */
    @SuppressWarnings("unchecked")
    public static Case caseOf(Object map) {
        if (!(map instanceof Map<?, ?> m)) return null;
        Case c = new Case();
        c.number = m.get("number") instanceof Number n ? n.intValue() : 0;
        c.index = m.get("index") instanceof Number n ? n.intValue() : 0;
        c.readyDay = m.get("readyDay") instanceof Number n ? n.longValue() : -1;
        c.villageTitle = m.get("village") instanceof String s ? s : "";
        if (m.get("steps") instanceof List<?> list) for (Object o : list) if (o instanceof String s && Step.of(s) != null) c.steps.add(s);
        return c.steps.isEmpty() ? null : c;
    }

    /** What happens at an office. */
    public enum Result {
        /** The clerk opened a case (and took the application if the player had the paper on him). */
        OPENED,
        /** Nothing for this office now: the current paper is done elsewhere. */
        WRONG_OFFICE,
        /** The paper was handed in and is not ready yet: «приходите завтра». */
        NOT_READY,
        /** Handed in just now, ready on {@code readyDay}. */
        ACCEPTED,
        /** The paper is done; the next one is named. */
        DONE,
        /** The passport is ready: the case is closed. */
        ISSUED,
        /** The player lacks the item or the fee. */
        MISSING,
        /** Lunch break, 13:00–14:00. */
        LUNCH,
        /** Not the clerk and no case: «паспорта выдаёт паспортный стол». */
        NO_CASE
    }
    /** The outcome of a visit: what to take from the player ({@code item} × {@code count}, {@code fee} emeralds) and the paper now due. */
    public record Visit(Result result, Step step, Step next, String item, int count, int fee, long readyDay) {
        static Visit of(Result result, Step step) { return new Visit(result, step, null, "", 0, 0, -1); }
    }
    /** How many of an item (by id) and how many emeralds the player has. */
    public interface Pockets { int count(String item); int emeralds(); }

    /** The hour of the game day (0..23) for {@code timeOfDay} ticks; tick 0 is 6:00. */
    public static int hour(long timeOfDay) { return (int) Math.floorMod(Math.floorDiv(timeOfDay, 1000L) + 6, 24L); }
    /** «Обед с 13 до 14». */
    public static boolean lunch(long timeOfDay) { return hour(timeOfDay) == 13; }

    /** A visit of the player with {@code kase} (null for none) to the {@code office}; changes {@code kase} when a step is handed in
     * or done (the caller saves it and takes what the visit says from the player). {@code fee} is the state fee in emeralds. */
    public static Visit visit(Case kase, Officialdom.Rank office, long day, long timeOfDay, boolean lunchBreak, Pockets pockets, int fee, int passportDays) {
        if (lunchBreak && lunch(timeOfDay) && office != Officialdom.Rank.INSPECTOR) return Visit.of(Result.LUNCH, kase == null ? null : kase.current());
        if (kase == null) return Visit.of(office == Officialdom.Rank.CLERK ? Result.OPENED : Result.NO_CASE, null);
        Step step = kase.current();
        if (step == null) return Visit.of(Result.ISSUED, null);
        if (step.office != office) return Visit.of(Result.WRONG_OFFICE, step);
        if (kase.waiting()) {
            if (day < kase.readyDay) return new Visit(Result.NOT_READY, step, null, "", 0, 0, kase.readyDay);
            return advance(kase, step, day, passportDays, "", 0, 0);
        }
        int due = step.fee ? Math.max(0, fee) : 0;
        boolean hasItem = step.item.isEmpty() || pockets.count(step.item) >= step.count;
        if (!hasItem || pockets.emeralds() < due) return new Visit(Result.MISSING, step, null, step.item, step.count, due, -1);
        int days = step.days(passportDays);
        if (days > 0) {
            kase.readyDay = day + days;
            return new Visit(Result.ACCEPTED, step, null, step.item, step.item.isEmpty() ? 0 : step.count, due, kase.readyDay);
        }
        return advance(kase, step, day, passportDays, step.item, step.item.isEmpty() ? 0 : step.count, due);
    }
    private static Visit advance(Case kase, Step step, long day, int passportDays, String item, int count, int fee) {
        kase.index++; kase.readyDay = -1;
        Step next = kase.current();
        if (next == null) return new Visit(Result.ISSUED, step, null, item, count, fee, -1);
        // Collecting the passport needs nothing but time: it starts the moment the last paper is in.
        if (next == Step.ISSUE) {
            int days = next.days(passportDays);
            if (days <= 0) { kase.index++; return new Visit(Result.ISSUED, step, null, item, count, fee, -1); }
            kase.readyDay = day + days;
        }
        return new Visit(Result.DONE, step, next, item, count, fee, kase.readyDay);
    }

    /** A corrupt clerk took the envelope: every paper is «found», the passport is ready now. */
    public static void rush(Case kase) {
        if (kase == null) return;
        kase.index = kase.steps.size(); kase.readyDay = -1;
    }
    /** A small gift («шоколадка»): the current paper is ready today. True when it changed anything. */
    public static boolean hurry(Case kase, long day) {
        if (kase == null || !kase.waiting() || kase.readyDay <= day) return false;
        kase.readyDay = day;
        return true;
    }

    /** How many stand at the clerk's window ahead of a player who comes now: the villagers waiting by the desk plus a few «who were
     * here before», the same for the whole game day. */
    public static int queue(UUID clerk, long day, int waiting) {
        long h = (clerk == null ? 0 : clerk.getLeastSignificantBits()) * 0x9E3779B97F4A7C15L + day * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        return Math.max(0, Math.min(6, waiting + (int) Math.floorMod(h, 3L)));
    }
    /** The chance «я только спросить» gets the player to the window at once. */
    public static final double JUST_ASK = 0.35;

    /** The passport's series and number: «45 07 №123456» — the series from the village and the year, the number from the counter. */
    public static String passportNumber(String village, long day, int counter) {
        int region = Math.floorMod(village == null ? 0 : village.hashCode(), 90) + 10;
        int year = (int) Math.floorMod(day / 365 + 7, 100L);
        return String.format(Locale.ROOT, "%02d %02d №%06d", region, year, counter);
    }

    /** The case in the official's prompt: where the player is in the round, so the model can send him on to the next office. */
    public static String prompt(Officialdom.Rank rank, Case kase, long day) {
        if (rank == null || kase == null) return "";
        Step step = kase.current();
        if (step == null) return "";
        StringBuilder out = new StringBuilder("\nУ игрока открыто дело №").append(kase.number).append(" на паспорт. Сейчас ему нужно: «")
                .append(step.title).append("» — это ").append(step.office == rank ? "к тебе" : "в другой кабинет: " + step.office.word).append('.');
        if (kase.waiting()) out.append(kase.readyDay > day ? " Документы приняты, готовы будут только через " + (kase.readyDay - day) + " дн.: «приходите завтра»." : " Документы уже готовы.");
        out.append(" Принимают документы по Shift+ПКМ у казённого стола. Говори казённо, гоняй по кабинетам, но не выдумывай новых справок сверх этих.");
        return out.toString();
    }
}
