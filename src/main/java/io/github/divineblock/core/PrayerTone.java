package io.github.divineblock.core;

import java.util.List;
import java.util.Locale;

/** What the words of a prayer show without a model: courtesy, rudeness, swearing at the god, repentance, a "why?" and urgency. */
public record PrayerTone(boolean polite, boolean rude, boolean profane, boolean repents, boolean asksWhy, boolean urgent) {
    /** Russian obscenities and the worst insults. The unmistakable roots match anywhere ("нахуй", "распиздяй"); the short ones
     * only at the start of a word, so "употребляю" is not "бля" and "себе" is not swearing. */
    private static final java.util.regex.Pattern PROFANITY = java.util.regex.Pattern.compile(
            "(?U)(ху[йёяю]|хуе[вт]|хуйн|пизд|п[иі]зж|[её]бан|[её]бат|[её]бну|бляд|блят|(?<![\\p{L}])([её]б[аеиуоыё]|[её]бл|бля|сук[аиу]|суч|мудак|мудил"
                    + "|гандон|долбо[её]б|говн|залуп|шлюх|пид[оа]р|нахер|похер|f+u+c+k|shit|bitch))",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final List<String> POLITE = List.of("пожалуйста", "прошу", "молю", "смиренно", "будь добр", "милостив", "please", "благодар", "спасибо", "слава");
    private static final List<String> RUDE = List.of("дурак", "тупой", "тупица", "идиот", "заткнись", "сдохни", "ненавижу тебя", "плевать на тебя", "ты никто",
            "бесполезн", "fuck", "stupid", "хрен", "чёрт тебя", "черт тебя", "лох", "урод");
    private static final List<String> REPENTS = List.of("прости", "извини", "каюсь", "помилуй", "пощади", "раскаива", "виноват", "sorry", "forgive");
    private static final List<String> WHY = List.of("почему", "за что", "зачем ты", "why");
    private static final List<String> URGENT = List.of("срочно", "умираю", "спаси", "помогите", "помоги", "скорее", "быстрее", "help");

    public static PrayerTone of(String prayer) {
        String text = prayer == null ? "" : prayer.toLowerCase(Locale.ROOT);
        boolean profane = PROFANITY.matcher(text).find();
        return new PrayerTone(any(text, POLITE) && !profane, any(text, RUDE) || profane, profane, any(text, REPENTS), any(text, WHY), any(text, URGENT));
    }

    /** The tone for the web admin, the strongest first: profane, rude, penitent, urgent, why, polite, plain. */
    public String id() {
        if (profane) return "profane";
        if (rude) return "rude";
        if (repents) return "penitent";
        if (urgent) return "urgent";
        if (asksWhy) return "why";
        if (polite) return "polite";
        return "plain";
    }
    /** {@link #id} in Russian. */
    public static String title(String id) {
        return switch (id) {
            case "profane" -> "брань"; case "rude" -> "грубо"; case "penitent" -> "покаяние"; case "urgent" -> "срочно";
            case "why" -> "вопрос"; case "polite" -> "вежливо"; default -> "обычно";
        };
    }

    /** A bare "why?" asks the god to explain his last verdict; a "why" inside a longer request is just a prayer. */
    public static boolean isWhyQuestion(String prayer) {
        if (prayer == null) return false;
        String text = prayer.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").strip();
        return any(text, WHY) && text.split(" ").length <= 6;
    }

    private static final List<String> ADDRESS = List.of("бож", "господ", "всевышн", "великий", "творец", "создател", "отче", "владык", "небес",
            "молю", "прошу", "помилуй", "услышь", "внемли", "даруй", "дай", "пошли", "ниспошли", "помоги", "спаси", "благослов", "прости",
            "хвала", "слава", "аминь", "пожалуйста", "докажи", "покажи", "не верю", "существ", "явись", "ответь", "слышишь", "god", "lord", "please");

    /** The rules' answer to "is this a prayer?" when Jev is silent: the words address the god, ask, praise, repent or insult him. */
    public static boolean addressesGod(String prayer) {
        if (prayer == null || prayer.isBlank()) return false;
        String text = prayer.toLowerCase(Locale.ROOT);
        return any(text, ADDRESS) || any(text, REPENTS) || any(text, RUDE) || any(text, WHY) || text.strip().startsWith("о ")
                || PROFANITY.matcher(text).find();
    }

    private static boolean any(String text, List<String> stems) {
        for (String stem : stems) if (text.contains(stem)) return true;
        return false;
    }
}
