package io.github.divineblock.core;

import java.util.List;
import java.util.Locale;

/** What the words of a prayer show without a model: courtesy, rudeness, repentance, a "why?" and urgency. */
public record PrayerTone(boolean polite, boolean rude, boolean repents, boolean asksWhy, boolean urgent) {
    private static final List<String> POLITE = List.of("пожалуйста", "прошу", "молю", "смиренно", "будь добр", "милостив", "please", "благодар", "спасибо", "слава");
    private static final List<String> RUDE = List.of("дурак", "тупой", "тупица", "идиот", "заткнись", "сдохни", "ненавижу тебя", "плевать на тебя", "ты никто",
            "бесполезн", "fuck", "stupid", "хрен", "чёрт тебя", "черт тебя", "лох", "урод");
    private static final List<String> REPENTS = List.of("прости", "извини", "каюсь", "помилуй", "пощади", "раскаива", "виноват", "sorry", "forgive");
    private static final List<String> WHY = List.of("почему", "за что", "зачем ты", "why");
    private static final List<String> URGENT = List.of("срочно", "умираю", "спаси", "помогите", "помоги", "скорее", "быстрее", "help");

    public static PrayerTone of(String prayer) {
        String text = prayer == null ? "" : prayer.toLowerCase(Locale.ROOT);
        return new PrayerTone(any(text, POLITE), any(text, RUDE), any(text, REPENTS), any(text, WHY), any(text, URGENT));
    }

    /** A bare "why?" asks the god to explain his last verdict; a "why" inside a longer request is just a prayer. */
    public static boolean isWhyQuestion(String prayer) {
        if (prayer == null) return false;
        String text = prayer.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").strip();
        return any(text, WHY) && text.split(" ").length <= 6;
    }

    private static final List<String> ADDRESS = List.of("бож", "господ", "всевышн", "великий", "творец", "создател", "отче", "владык", "небес",
            "молю", "прошу", "помилуй", "услышь", "внемли", "даруй", "дай", "пошли", "ниспошли", "помоги", "спаси", "благослов", "прости",
            "хвала", "слава", "аминь", "пожалуйста", "god", "lord", "please");

    /** The rules' answer to "is this a prayer?" when Jev is silent: the words address the god, ask, praise, repent or insult him. */
    public static boolean addressesGod(String prayer) {
        if (prayer == null || prayer.isBlank()) return false;
        String text = prayer.toLowerCase(Locale.ROOT);
        return any(text, ADDRESS) || any(text, REPENTS) || any(text, RUDE) || any(text, WHY) || text.strip().startsWith("о ");
    }

    private static boolean any(String text, List<String> stems) {
        for (String stem : stems) if (text.contains(stem)) return true;
        return false;
    }
}
