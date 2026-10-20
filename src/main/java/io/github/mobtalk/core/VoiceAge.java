package io.github.mobtalk.core;

import java.util.Random;

/** How old a villager sounds. Villagers do not age: the age is rolled once, when the social layer first meets them, and kept. */
public enum VoiceAge {
    YOUNG("young", "молодой", "молодая"),
    MIDDLE("middle", "средних лет", "средних лет"),
    OLD("old", "пожилой", "пожилая");

    /** The key of this age's list in voices.json {@code villagerPool}. */
    public final String key;
    private final String he, she;

    VoiceAge(String key, String he, String she) { this.key = key; this.he = he; this.she = she; }

    public String word(Gender gender) { return gender == Gender.FEMALE ? she : he; }

    /** 30% young, 10% old, the rest middle-aged. */
    static VoiceAge roll(Random random) {
        int x = random.nextInt(100);
        return x < 30 ? YOUNG : x < 40 ? OLD : MIDDLE;
    }

    public static VoiceAge of(String name) {
        if (name == null || name.isBlank()) return null;
        for (VoiceAge age : values()) if (age.name().equalsIgnoreCase(name.trim()) || age.key.equalsIgnoreCase(name.trim())) return age;
        return null;
    }
}
