package io.github.mobtalk.core;

import java.util.Locale;

/** A villager child's growth (docs/mca-social-spec.md §5.5): five stages over a configurable span of game time, longer than the
 * vanilla twenty minutes; the game keeps a tracked child small until its own age says it is grown. Pure Java. */
public final class ChildGrowth {
    private ChildGrowth() { }

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
}
