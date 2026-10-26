package io.github.mobtalk.core;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds where a model leans on its prompt instead of talking: a reply that repeats the prompt word for word (four words or more
 * that the player did not say), quotes one of the prompt's sample lines («не знаю», «какая разница»), keeps returning to the same
 * word from the character sheet in most of its replies (a baker who brings up bread in every line), or opens every reply the
 * same way. For the live probes; pure text, no model. */
final class PromptEcho {
    private PromptEcho() { }
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern QUOTE = Pattern.compile("«([^«»]{2,80})»");
    /** Words too common to count as a motif. */
    private static final Set<String> COMMON = Set.of("тебе", "тебя", "меня", "мне", "себе", "чтобы", "только", "сейчас", "здесь", "потом", "этого",
            "этот", "если", "когда", "может", "можно", "будет", "просто", "очень", "тоже", "ничего", "всего", "какой", "какая", "такой", "сегодня",
            "давай", "ладно", "нет", "что", "это", "так", "как", "уже", "ещё", "там", "тут", "был", "была", "было", "есть", "надо", "хочу", "знаю");

    record Finding(String kind, String text) { }

    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT).replace('ё', 'е'));
        while (m.find()) out.add(m.group());
        return out;
    }

    /** Runs of {@code n}+ words of the reply found in the prompt and not in what the player said. */
    static List<String> copied(String reply, String prompt, String player, int n) {
        List<String> r = words(reply);
        String p = " " + String.join(" ", words(prompt)) + " ", heard = " " + String.join(" ", words(player)) + " ";
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i + n <= r.size()) {
            int len = n;
            String run = String.join(" ", r.subList(i, i + len));
            if (!p.contains(" " + run + " ") || heard.contains(" " + run + " ")) { i++; continue; }
            while (i + len < r.size() && p.contains(" " + String.join(" ", r.subList(i, i + len + 1)) + " ")) len++;
            out.add(String.join(" ", r.subList(i, i + len)));
            i += len;
        }
        return out;
    }

    /** The prompt's sample lines in «quotes» that the reply repeats (two words or more). */
    static List<String> quoted(String reply, String prompt) {
        String r = " " + String.join(" ", words(reply)) + " ";
        List<String> out = new ArrayList<>();
        Matcher m = QUOTE.matcher(prompt);
        while (m.find()) {
            List<String> q = words(m.group(1));
            if (q.size() >= 2 && r.contains(" " + String.join(" ", q) + " ") && !out.contains(m.group(1))) out.add(m.group(1));
        }
        return out;
    }

    /** Words (4+ letters, stem of 4) from the prompt that turn up in at least {@code share}% of the replies, when there are 3 or more. */
    static List<String> motifs(List<String> replies, String prompt, String players, int share) {
        if (replies.size() < 3) return List.of();
        Set<String> inPrompt = new HashSet<>(), heard = new HashSet<>();
        for (String w : words(prompt)) if (w.length() >= 4) inPrompt.add(w.substring(0, 4));
        for (String w : words(players)) if (w.length() >= 4) heard.add(w.substring(0, 4));
        Map<String, Integer> count = new TreeMap<>();
        for (String reply : replies) {
            Set<String> seen = new HashSet<>();
            for (String w : words(reply)) if (w.length() >= 4 && !COMMON.contains(w)) seen.add(w.substring(0, 4));
            for (String stem : seen) if (inPrompt.contains(stem) && !heard.contains(stem)) count.merge(stem, 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        count.forEach((stem, n) -> { if (n * 100 >= share * replies.size()) out.add(stem + "… (" + n + " из " + replies.size() + ")"); });
        return out;
    }

    /** The first two words, when every reply (3+) starts with them. */
    static String sameOpening(List<String> replies) {
        if (replies.size() < 3) return "";
        String first = null;
        for (String reply : replies) {
            List<String> w = words(reply);
            String open = String.join(" ", w.subList(0, Math.min(2, w.size())));
            if (first == null) first = open; else if (!first.equals(open)) return "";
        }
        return first == null ? "" : first;
    }

    /** All of it for one scenario: the replies with the system prompt each was given and what the player said. */
    static List<Finding> check(List<String> replies, List<String> prompts, List<String> players) {
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < replies.size(); i++) {
            for (String run : copied(replies.get(i), prompts.get(i), players.get(i), 4)) out.add(new Finding("дословно из промпта", run));
            for (String q : quoted(replies.get(i), prompts.get(i))) out.add(new Finding("пример реплики из промпта", q));
        }
        for (String m : motifs(replies, String.join("\n", prompts), String.join("\n", players), 75)) out.add(new Finding("навязчивый мотив", m));
        String open = sameOpening(replies);
        if (!open.isEmpty()) out.add(new Finding("одинаковое начало", open));
        return out;
    }
}
