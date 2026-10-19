package io.github.mobtalk.core;

import java.util.Random;

/** A villager's character in one word (the social layer, see docs/mca-social-spec.md §2.3): a label over the profile's temperament,
 * never a second temperament. When it is first given it shifts the four temperament numbers once and moves the rest point of the
 * mood axis; afterwards it only weighs the outcomes of gestures ({@link GestureTable}) and colours speech in the prompt. */
public enum Persona {
    FRIENDLY("дружелюбный", "легко сходишься с людьми и говоришь тепло", 0, 0, 0, 20, 0, false),
    FLIRTY("кокетливый", "любишь пофлиртовать и падок на комплименты; заигрываешь словами", 0, 10, 0, 10, 0, true),
    PLAYFUL("игривый", "любишь игры, розыгрыши и подначки", 0, 0, 15, 0, 0, false),
    GLOOMY("мрачный", "всегда ждёшь худшего и ворчишь о бедах, но хорошую шутку оценишь", 0, 0, 0, 0, -15, false),
    SENSITIVE("ранимый", "обидчив: колкость и неловкость принимаешь близко к сердцу", -15, 0, 0, 0, 0, false),
    GREEDY("жадный", "любишь ценное и считаешь выгоду; дорогой подарок растопит тебя быстрее слов", 0, 0, 0, 0, 0, false),
    ODD("чудной", "странный, говоришь невпопад и не любишь, когда лезут в твоё личное пространство", 0, 0, 10, 0, 0, false),
    GRUMPY("ворчливый", "с тобой трудно: бурчишь, придираешься, шутки тебя раздражают", 0, 0, 0, -15, 0, false),
    EXTROVERT("компанейский", "любишь компанию и сам заводишь разговор", 0, 0, 0, 25, 0, false),
    INTROVERT("нелюдимый", "любишь тишину и одиночество, говоришь коротко", 0, 0, 0, -25, 0, false),
    UNFLAPPABLE("невозмутимый", "спокоен и нетороплив, тебя трудно вывести из себя", 20, 0, 0, 0, 0, false),
    ANXIOUS("тревожный", "легко пугаешься и во всём видишь угрозу", -25, 0, 0, 0, 0, false),
    PEACEFUL("миролюбивый", "избегаешь ссор и стараешься всех примирить", 0, -10, 0, 0, 0, false),
    CHEERFUL("жизнерадостный", "оптимист: смеёшься легко и заражаешь весельем", 0, 0, 0, 0, 15, false);

    /** The word for the prompt and the debug line. */
    public final String word;
    /** How this character colours speech, second person, for the prompt. */
    public final String speech;
    /** The one-time temperament shift (calm, confidence, curiosity, sociability) and the mood rest shift on the 0..100 axis. */
    public final int calm, confidence, curiosity, sociability, moodRest;
    /** Flirty is not given to children. */
    public final boolean adultOnly;

    Persona(String word, String speech, int calm, int confidence, int curiosity, int sociability, int moodRest, boolean adultOnly) {
        this.word = word; this.speech = speech; this.calm = calm; this.confidence = confidence; this.curiosity = curiosity;
        this.sociability = sociability; this.moodRest = moodRest; this.adultOnly = adultOnly;
    }

    /** One character, every one equally likely; {@code child} skips the adult-only ones. */
    public static Persona pick(Random random, boolean child) {
        Persona[] all = values();
        while (true) {
            Persona persona = all[random.nextInt(all.length)];
            if (!child || !persona.adultOnly) return persona;
        }
    }

    /** The saved name back to the character; null for blank or unknown. */
    public static Persona of(String name) {
        if (name == null || name.isBlank()) return null;
        try { return valueOf(name); } catch (IllegalArgumentException unknown) { return null; }
    }
}
