package io.github.mobtalk.core;

import java.util.*;

/** Who may court and marry whom (docs/mca-social-spec.md §5.2–5.6, §5.10): attraction by sex and orientation, the checks for a bouquet,
 * a ring and a village wedding, the forbidden ones (children, blood relatives). The answers are decided here, never by the model: the
 * reply only voices them. Pure Java. */
public final class Romance {
    private Romance() { }
    /** The bouquet: any flower of the {@code flowers} gift category. The rings: a gold nugget or a gold ingot. */
    public static final Set<String> RINGS = Set.of("minecraft:gold_nugget", "minecraft:gold_ingot");

    /** Whether someone of {@code self} sex with these marks is drawn to {@code other}; a player of no counted sex attracts everyone. */
    public static boolean drawn(Gender self, Set<VillagerTrait> traits, Gender other) {
        if (traits != null && traits.contains(VillagerTrait.ASEXUAL)) return false;
        if (other == null || self == null) return true;
        if (traits != null && traits.contains(VillagerTrait.BISEXUAL)) return true;
        if (traits != null && traits.contains(VillagerTrait.HOMOSEXUAL)) return self == other;
        return self != other;
    }
    /** A pair is compatible only if each is drawn to the other. */
    public static boolean compatible(Gender a, Set<VillagerTrait> traitsA, Gender b, Set<VillagerTrait> traitsB) {
        return drawn(a, traitsA, b) && drawn(b, traitsB, a);
    }
    public static boolean bouquet(String item) {
        if (item == null) return false;
        for (GiftCatalog.Category category : GiftCatalog.categories()) if (category.id().equals("flowers")) return category.items().containsKey(item);
        return false;
    }
    public static boolean ring(String item) { return item != null && RINGS.contains(item); }

    /** What the game knows when the player courts or proposes. {@code playerPartner}: the villager the player already courts or married,
     * null for none; {@code villagerSpouse}: the villager's own spouse among villagers, null for none. */
    public record Suit(boolean adult, boolean related, Courtship courtship, UUID villager, UUID playerPartner, UUID villagerSpouse,
                       int score, Gender villagerGender, Set<VillagerTrait> traits, Gender playerGender, String held) { }

    /** The reason a bouquet (DATE) is turned down, or null when it is accepted. */
    public static String refuseDate(Suit s) {
        String common = common(s, Hearts.DATING);
        if (common != null) return common;
        return bouquet(s.held()) ? null : "без цветов это не ухаживание — пусть принесёт букет (любой цветок в руке)";
    }
    /** The reason a ring (PROPOSE) is turned down, or null when it is accepted. A courtship first is not required. */
    public static String refusePropose(Suit s) {
        String common = common(s, Hearts.MARRIAGE);
        if (common != null) return common;
        return ring(s.held()) ? null : "без кольца не сватаются — пусть принесёт кольцо (золотой самородок или слиток в руке)";
    }
    private static String common(Suit s, int threshold) {
        if (!s.adult()) return "ты ещё ребёнок";
        if (s.related()) return "вы родня — об этом и думать нельзя";
        if (s.villagerSpouse() != null) return "ты уже в браке с другим";
        if (s.playerPartner() != null && !s.playerPartner().equals(s.villager())) return "у него уже есть пара, и это не ты";
        if (s.score() < threshold) return "ты пока недостаточно его любишь (нужно отношение " + threshold + ", сейчас " + s.score() + ")";
        if (!drawn(s.villagerGender(), s.traits(), s.playerGender())) return "тебя не тянет к нему — дело не в нём";
        return null;
    }

    /** A villager the village may marry off: sex, marks, relationship to the player at its best, whether it is free. */
    public record Single(UUID id, Gender gender, Set<VillagerTrait> traits, int bestScore, boolean free, boolean adult) { }
    /** A village wedding (§5.2): more than half of the adults must be free; the groom is the free one players like least, the bride the
     * first other free, compatible, unrelated adult. Null when nobody can marry now. */
    public static UUID[] wedding(List<Single> residents, java.util.function.BiPredicate<UUID, UUID> related) {
        List<Single> adults = residents.stream().filter(Single::adult).toList();
        List<Single> free = adults.stream().filter(Single::free).toList();
        if (free.size() <= 1 || free.size() * 2 <= adults.size()) return null;
        List<Single> order = free.stream().sorted(Comparator.comparingInt(Single::bestScore)).toList();
        for (Single first : order)
            for (Single second : free)
                if (!second.id().equals(first.id()) && compatible(first.gender(), first.traits(), second.gender(), second.traits())
                        && !related.test(first.id(), second.id())) return new UUID[]{first.id(), second.id()};
        return null;
    }
}
