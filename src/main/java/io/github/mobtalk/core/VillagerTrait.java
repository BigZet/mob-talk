package io.github.mobtalk.core;

import java.util.*;

/** Inborn marks of a villager (spec §2.5): rare, some inherited. Diet and strength change what a gift is worth ({@link GiftCatalog}),
 * orientation and infertility the family rules ({@link Romance}); the rest are only facts for the model. */
public enum VillagerTrait {
    LACTOSE("не переносит молочного", 1, 1.0, true), CELIAC("не переносит хлеба и мучного", 1, 1.0, true),
    DIABETIC("нельзя сладкого", 1, 1.0, true), VEGETARIAN("не ест мяса и рыбы", 1, 1.0, true),
    COLORBLIND("не различает цветов", 1, 0.5, true), STRONG("крепкий и сильный", 1, 1.0, true), WEAK("слабосильный", 1, 1.0, true),
    LEFT_HANDED("левша", 1, 0.5, true),
    BISEXUAL("", 1, 0, false), HOMOSEXUAL("", 1, 0, false), ASEXUAL("", 1, 0, false), INFERTILE("", 1, 0, false);

    /** The fact for the prompt; "" for marks the model must not make a topic of. */
    public final String fact;
    public final double weight, inheritance;
    /** Whether the model hears of it. */
    public final boolean spoken;
    /** The overall chance of marks per villager (spec: 0.25 spread over the weights). */
    public static final double CHANCE = 0.25;

    VillagerTrait(String fact, double weight, double inheritance, boolean spoken) { this.fact = fact; this.weight = weight; this.inheritance = inheritance; this.spoken = spoken; }

    private static double totalWeight() { double sum = 0; for (VillagerTrait trait : values()) sum += trait.weight; return sum; }

    /** Marks for a villager met in the world: each with {@code CHANCE × weight / Σweights}; two orientations never together. */
    public static List<VillagerTrait> roll(Random random) {
        double total = totalWeight();
        List<VillagerTrait> marks = new ArrayList<>();
        for (VillagerTrait trait : values()) if (random.nextDouble() < CHANCE * trait.weight / total) marks.add(trait);
        return exclusive(marks);
    }
    /** A child's marks: each mark of each parent passes with {@code inheritance × 0.5}. */
    public static List<VillagerTrait> inherit(Collection<VillagerTrait> mother, Collection<VillagerTrait> father, Random random) {
        Set<VillagerTrait> marks = new LinkedHashSet<>();
        for (Collection<VillagerTrait> parent : List.of(mother == null ? List.<VillagerTrait>of() : mother, father == null ? List.<VillagerTrait>of() : father))
            for (VillagerTrait trait : parent) if (random.nextDouble() < trait.inheritance * 0.5) marks.add(trait);
        return exclusive(new ArrayList<>(marks));
    }
    private static List<VillagerTrait> exclusive(List<VillagerTrait> marks) {
        boolean seen = false;
        for (Iterator<VillagerTrait> it = marks.iterator(); it.hasNext(); ) {
            VillagerTrait trait = it.next();
            if (trait == BISEXUAL || trait == HOMOSEXUAL || trait == ASEXUAL) { if (seen) it.remove(); seen = true; }
        }
        if (marks.contains(STRONG) && marks.contains(WEAK)) marks.remove(WEAK);
        return marks;
    }
    public static VillagerTrait of(String name) {
        if (name == null || name.isBlank()) return null;
        try { return valueOf(name); } catch (IllegalArgumentException unknown) { return null; }
    }
}
