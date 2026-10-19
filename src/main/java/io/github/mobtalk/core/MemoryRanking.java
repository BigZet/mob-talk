package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Which memories matter: every fact and event has an importance (1–5) and an age, and its score is importance × freshness,
 * after Generative Agents. The prompt lists facts by score, and when memory is full the lowest-scoring ones go first.
 * Freshness never falls below {@link #FLOOR}: a blow remembered for a month still outranks yesterday's small talk. */
final class MemoryRanking {
    private MemoryRanking() { }

    /** Half of a memory's freshness is gone after this long (real time). */
    static final long HALF_LIFE_MILLIS = 7L * 24 * 60 * 60 * 1000;
    /** The part of importance that never decays. */
    static final double FLOOR = 0.3;
    /** Most facts a profile keeps, whatever the character limit allows. */
    static final int MAX_FACTS = 60;
    /** Most conclusions about one player a profile keeps. */
    static final int MAX_REFLECTIONS = 6;
    /** Most game events a profile keeps (what rememberedEvents shows). */
    static final int MAX_EVENTS = 20;

    // Importance of what the game itself records; a plain exchange is MemoryFact.ORDINARY.
    static final int HIT = 5, WITNESSED_KILL = 5, THREAT = 5, WITNESSED_HIT = 4, HELP = 4, PURCHASE = 4, FAVOR_DONE = 4, UNLOCK = 3, FAVOR_ASKED = 3;

    /** importance × freshness, where freshness decays from 1 towards {@link #FLOOR} with a half-life of a week. */
    static double score(int importance, long time, long nowMillis) {
        double age = Math.max(0, nowMillis - time);
        MobTalkConfig tuning = Tuning.get();
        double floor = tuning.memoryFloorPercent / 100.0;
        double freshness = floor + (1 - floor) * Math.pow(0.5, age / (tuning.memoryHalfLifeDays * 24.0 * 60 * 60 * 1000));
        return MemoryFact.clamp(importance) * freshness;
    }
    static double score(MemoryFact fact, long nowMillis) { return score(fact.importance(), fact.time(), nowMillis); }

    /** Appends a fact (reflections and exact repeats of the same text are refreshed instead of duplicated) and evicts the
     * lowest scores until the memory fits {@code maxChars} characters and {@link #MAX_FACTS} lines. */
    static void remember(List<MemoryFact> facts, MemoryFact fact, int maxChars, long nowMillis) {
        if (fact == null || fact.text().isBlank()) return;
        facts.removeIf(known -> known.text().equalsIgnoreCase(fact.text()));
        facts.add(fact);
        evictFacts(facts, maxChars, nowMillis);
    }

    /** Drops the lowest-scoring facts until the lines fit; conclusions are capped separately so events cannot crowd them out
     * and they cannot crowd out events. */
    static void evictFacts(List<MemoryFact> facts, int maxChars, long nowMillis) {
        List<MemoryFact> reflections = new ArrayList<>(facts.stream().filter(MemoryFact::reflection).toList());
        evict(reflections, MAX_REFLECTIONS, f -> score(f, nowMillis));
        facts.removeIf(f -> f.reflection() && !reflections.contains(f));
        evict(facts, Tuning.get().memoryMaxFacts, f -> score(f, nowMillis));
        while (facts.size() > 1 && renderLength(facts) > maxChars) removeLowest(facts, f -> score(f, nowMillis));
        if (facts.size() == 1 && facts.getFirst().text().length() > maxChars)
            facts.set(0, new MemoryFact(TextBounds.characters(facts.getFirst().text(), maxChars), facts.getFirst().importance(), facts.getFirst().time(), facts.getFirst().reflection()));
    }

    /** Removes the lowest-scoring items until at most {@code max} remain; order of the rest is kept. */
    static <T> void evict(List<T> items, int max, ToDoubleFunction<T> score) { while (items.size() > max) removeLowest(items, score); }

    private static <T> void removeLowest(List<T> items, ToDoubleFunction<T> score) {
        int lowest = 0;
        for (int i = 1; i < items.size(); i++) if (score.applyAsDouble(items.get(i)) < score.applyAsDouble(items.get(lowest))) lowest = i;
        items.remove(lowest);
    }

    /** Facts by score, highest first; equal scores keep their order. */
    static List<MemoryFact> ranked(List<MemoryFact> facts, long nowMillis) {
        return facts.stream().sorted(Comparator.comparingDouble((MemoryFact f) -> score(f, nowMillis)).reversed()).toList();
    }

    /** The memory as a player reads and edits it: one line per fact, in the order learned (conclusions marked). */
    static String render(List<MemoryFact> facts) {
        StringBuilder text = new StringBuilder();
        for (MemoryFact fact : facts) {
            if (fact.text().isBlank()) continue;
            if (!text.isEmpty()) text.append('\n');
            text.append(fact.reflection() ? REFLECTION_MARK : "").append(fact.text());
        }
        return text.toString();
    }
    private static int renderLength(List<MemoryFact> facts) { return render(facts).length(); }
    /** How a conclusion is marked in the editable text. */
    static final String REFLECTION_MARK = "[вывод] ";

    /** Facts from a text saved or typed without importance: one per line, {@link MemoryFact#MIGRATED}; marked lines are conclusions. */
    static List<MemoryFact> parse(String memory, long nowMillis) {
        List<MemoryFact> facts = new ArrayList<>();
        if (memory == null) return facts;
        for (String line : memory.split("\n")) {
            String text = line.strip();
            if (text.isEmpty()) continue;
            boolean reflection = text.startsWith(REFLECTION_MARK.strip());
            if (reflection) text = text.substring(REFLECTION_MARK.strip().length()).strip();
            if (!text.isEmpty()) facts.add(new MemoryFact(text, reflection ? MemoryFact.REFLECTION : MemoryFact.MIGRATED, nowMillis, reflection));
        }
        return facts;
    }

    /** The player edited the memory text: lines that match a known fact keep its importance and age, new lines are
     * {@link MemoryFact#MIGRATED}, missing lines are forgotten. */
    static List<MemoryFact> edited(List<MemoryFact> known, String memory, long nowMillis) {
        List<MemoryFact> result = new ArrayList<>();
        for (MemoryFact typed : parse(memory, nowMillis)) {
            MemoryFact match = known.stream().filter(f -> f.text().equalsIgnoreCase(typed.text())).findFirst().orElse(null);
            result.add(match != null ? match : typed);
        }
        return result;
    }

    /** What the prompt says about the player: facts by score, then the conclusions as their own line. */
    static String promptBlock(List<MemoryFact> facts, String legacyMemory, long nowMillis) {
        List<MemoryFact> all = facts == null || facts.isEmpty() ? parse(legacyMemory, nowMillis) : facts;
        List<MemoryFact> events = ranked(all.stream().filter(f -> !f.reflection()).toList(), nowMillis);
        List<MemoryFact> reflections = all.stream().filter(MemoryFact::reflection).toList();
        StringBuilder block = new StringBuilder("\nПамять о нём (важное первым): ");
        if (events.isEmpty()) block.append("пока ничего.");
        else for (MemoryFact fact : events) block.append("\n— ").append(fact.text());
        if (!reflections.isEmpty()) {
            block.append("\nЧто я понял об этом игроке: ");
            for (int i = 0; i < reflections.size(); i++) block.append(i > 0 ? " " : "").append(reflections.get(i).text());
        }
        return block.toString();
    }
}
