package io.github.mobtalk.core;

/** A villager's sex in the social layer: whom it is drawn to ({@link Romance}), how a child is named and how the prompt speaks of it. */
public enum Gender {
    MALE("мужчина", "мальчик", "муж", "отец", "сын", "брат"),
    FEMALE("женщина", "девочка", "жена", "мать", "дочь", "сестра");

    public final String adult, child, spouse, parent, offspring, sibling;
    Gender(String adult, String child, String spouse, String parent, String offspring, String sibling) {
        this.adult = adult; this.child = child; this.spouse = spouse; this.parent = parent; this.offspring = offspring; this.sibling = sibling;
    }
    public Gender opposite() { return this == MALE ? FEMALE : MALE; }
    /** The saved name back to the sex; null for blank or unknown. */
    public static Gender of(String name) {
        if (name == null || name.isBlank()) return null;
        try { return valueOf(name); } catch (IllegalArgumentException unknown) { return null; }
    }
    /** The player's sex from the config ("male", "female", anything else: not counted). */
    public static Gender ofPlayer(String setting) {
        return "male".equals(setting) ? MALE : "female".equals(setting) ? FEMALE : null;
    }
}
