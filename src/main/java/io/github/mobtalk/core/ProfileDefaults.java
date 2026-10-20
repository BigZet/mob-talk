package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** What a profile gets at birth and whenever the catalog or the player's overrides change: an archetype slot and voice,
 * a temperament, a personal concern and fear, and the personality text that follows from them. */
final class ProfileDefaults {
    private ProfileDefaults() { }
    private static final String VILLAGER = "minecraft:villager";
    static final java.util.Set<String> RETIRED_VILLAGER_INTERESTS = java.util.Set.of("кровать в деревне", "колокол на площади");
    /** Желание жителя про кровати и колокол (так писала биография по старой подсказке вида): сбрасывается к желанию вида. */
    static final java.util.regex.Pattern FIXTURE_WISH = java.util.regex.Pattern.compile("кроват|колокол", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    /** Fills every missing or stale field; returns whether anything changed and should be saved. */
    static boolean refresh(ConversationStore.Profile p, Map<String, ConversationStore.Profile> all, MobTalkConfig config) {
        SpeciesCatalog.Species species = SpeciesCatalog.effective(p.type, config);
        boolean changed = false, legacy = p.variantSlot < 0 || p.variantSlot >= 3;
        if (legacy) {
            p.variantSlot = leastUsedSlot(species.id(), all); p.voiceId = species.variants().get(p.variantSlot).voice().id();
            p.customPersonality = ""; p.traits = new ArrayList<>(); changed = true;
        }
        if (p.customPersonality == null) {
            boolean generated = p.personality == null || p.personality.isBlank()
                    || SpeciesCatalog.get(p.type).variants().stream().anyMatch(v -> v.prompt().equals(p.personality))
                    || species.variants().stream().anyMatch(v -> v.prompt().equals(p.personality));
            p.customPersonality = generated ? "" : TextBounds.characters(p.personality, 4000); changed = true;
        }
        if (p.calm < 0 || p.calm > 100) { p.calm = randomAxis(); changed = true; }
        if (p.confidence < 0 || p.confidence > 100) { p.confidence = randomAxis(); changed = true; }
        if (p.curiosity < 0 || p.curiosity > 100) { p.curiosity = randomAxis(); changed = true; }
        if (p.sociability < 0 || p.sociability > 100) { p.sociability = randomAxis(); changed = true; }
        if (VILLAGER.equals(p.type) && p.preference != null && RETIRED_VILLAGER_INTERESTS.contains(p.preference)) {
            // Жители 0.7.0 получали «кровать в деревне» и «колокол на площади» как личное пристрастие и сводили к ним разговор.
            String retired = p.preference; p.preference = "";
            if (p.traits != null) p.traits.removeIf(trait -> trait.equals("Особенно ценит: " + retired));
        }
        if (VILLAGER.equals(p.type) && p.wish != null && FIXTURE_WISH.matcher(p.wish).find()) { p.wish = ""; changed = true; }
        if (p.preference == null || p.preference.isBlank()) {
            p.preference = concern(species.interests()); changed = true;
            if (p.traits != null && !p.traits.isEmpty() && p.traits.stream().noneMatch(trait -> trait.startsWith("Особенно ценит: ")))
                p.traits.add(0, "Особенно ценит: " + p.preference);
        }
        if (p.fear == null || p.fear.isBlank()) { p.fear = concern(species.fears()); changed = true; }
        if (p.traits == null || p.traits.isEmpty()) {
            p.traits = new ArrayList<>(List.of("Особенно ценит: " + p.preference, "Лично тревожит: " + p.fear)); changed = true;
        }
        String personality = p.customPersonality.isBlank() ? species.variants().get(p.variantSlot).prompt() : p.customPersonality;
        if (!personality.equals(p.personality)) { p.personality = TextBounds.characters(personality, 4000); changed = true; }
        return changed;
    }

    /** The first three individuals of a species get three different archetypes; later ones join the least crowded. */
    private static int leastUsedSlot(String canonical, Map<String, ConversationStore.Profile> all) {
        int[] counts = new int[3];
        for (ConversationStore.Profile existing : all.values())
            if (canonical.equals(existing.type) && existing.variantSlot >= 0 && existing.variantSlot < 3) counts[existing.variantSlot]++;
        int least = Math.min(counts[0], Math.min(counts[1], counts[2])); List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < 3; i++) if (counts[i] == least) candidates.add(i);
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }
    private static int randomAxis() { return ThreadLocalRandom.current().nextInt(101); }
    private static String concern(String themes) {
        String[] choices = themes.replaceAll("[.]+$", "").split(",|\\s+и\\s+");
        return TextBounds.characters(choices[ThreadLocalRandom.current().nextInt(choices.length)].strip(), 300);
    }
}
