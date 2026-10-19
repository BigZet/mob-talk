package io.github.mobtalk.core;

import java.util.*;

/** A villager child's growth and inheritance (docs/mca-social-spec.md §5.4–5.7): five stages over a configurable span of game time —
 * longer than the vanilla twenty minutes; the game keeps a tracked child small until its own age says it is grown. A child is born only
 * to a couple; it takes its sex by chance, its marks and temperament from its parents. Pure Java. */
public final class ChildGrowth {
    private ChildGrowth() { }
    /** One game day in ticks. */
    public static final long DAY_TICKS = 24_000L;
    /** The player's couple waits this long between children (real millis: three game days), and the birth comes a game day later. */
    public static final long CHILD_COOLDOWN_MILLIS = 3 * 20 * 60_000L, BIRTH_DELAY_TICKS = DAY_TICKS;
    /** Twins for the player's couple (spec: 5 %), and at most this many children per couple from the village rule. */
    public static final double TWINS = 0.05;
    public static final int MAX_CHILDREN = 4;
    /** Temperament noise when it is passed on, on the 0..100 scale. */
    public static final int NOISE = 10;

    public enum Stage {
        INFANT("младенец", "ты младенец: не говоришь словами, только лепечешь, агукаешь, смеёшься или хнычешь — два-три звука, без слов"),
        TODDLER("малыш", "ты малыш: говоришь отдельными простыми словами и короткими обрывками, путаешь слова"),
        CHILD("ребёнок", "ты ребёнок: говоришь по-детски, просто и прямо, любопытен и непоседлив"),
        TEEN("подросток", "ты подросток: дерзишь, стесняешься, споришь со взрослыми"),
        ADULT("взрослый", "");
        public final String word, speech;
        Stage(String word, String speech) { this.word = word; this.speech = speech; }
        public static Stage of(String name) {
            if (name == null || name.isBlank()) return null;
            try { return valueOf(name.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException unknown) { return null; }
        }
    }

    /** The stage at {@code ageTicks} when growing up takes {@code days} game days: four equal spans, then adulthood. */
    public static Stage stage(long ageTicks, int days) {
        long quarter = Math.max(1, Math.max(1, days) * DAY_TICKS / 4);
        if (ageTicks < 0) return Stage.INFANT;
        int index = (int) Math.min(4, ageTicks / quarter);
        return Stage.values()[index];
    }
    /** The chance that a married mother has one more child now (spec: {@code 1 / (children + 0.1)}), none past {@link #MAX_CHILDREN}. */
    public static double birthChance(int children) { return children >= MAX_CHILDREN ? 0 : Math.min(1, 1 / (children + 0.1)); }

    /** What a newborn takes from its parents: sex by chance, a child's character, inherited marks and a blended temperament. */
    public record Newborn(Gender gender, Persona persona, List<VillagerTrait> traits, int calm, int confidence, int curiosity, int sociability) { }
    /** {@code mother}/{@code father}: the parents' marks (empty for a player) and temperaments (−1 axes for a player: the other parent's
     * value or the middle is used). */
    public static Newborn born(Collection<VillagerTrait> motherTraits, int[] mother, Collection<VillagerTrait> fatherTraits, int[] father, Random random) {
        Gender gender = random.nextBoolean() ? Gender.MALE : Gender.FEMALE;
        Persona persona = Persona.pick(random, true);
        List<VillagerTrait> traits = VillagerTrait.inherit(motherTraits, fatherTraits, random);
        int[] blended = new int[4];
        double t = random.nextDouble();
        for (int i = 0; i < 4; i++) {
            int m = axis(mother, i), f = axis(father, i);
            if (m < 0) m = f; if (f < 0) f = m; if (m < 0) { m = 50; f = 50; }
            double value = m * t + f * (1 - t) + (random.nextDouble() * 2 - 1) * NOISE;
            blended[i] = (int) Math.round(Math.max(0, Math.min(100, value)));
        }
        return new Newborn(gender, persona, traits, blended[0], blended[1], blended[2], blended[3]);
    }
    private static int axis(int[] values, int i) { return values == null || values.length <= i || values[i] < 0 || values[i] > 100 ? -1 : values[i]; }
}
