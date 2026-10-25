package io.github.mobtalk.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Words of the moods in both genders: a text marks each ending that differs as {@code {he|she}} — «запуган{|а}», «спок{оен|ойна}»,
 * «ты забросил{|а}» — and {@link #form} keeps the one that fits the creature. A villager woman is «запугана», not «запуган». */
public final class MoodWords {
    private static final Pattern CHOICE = Pattern.compile("\\{([^{}|]*)\\|([^{}|]*)}");
    private MoodWords() { }

    public static String form(String text, boolean female) {
        if (text == null || text.indexOf('{') < 0) return text == null ? "" : text;
        Matcher m = CHOICE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(female ? m.group(2) : m.group(1)));
        m.appendTail(out);
        return out.toString();
    }
}
