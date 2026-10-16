package io.github.mobtalk.core;

/** The provider is asked to be brief; these bounds enforce that request locally. */
final class TextBounds {
    private TextBounds() { }

    static String characters(String text, int maxChars) {
        if (text == null) return "";
        String clean = text.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "").strip();
        if (maxChars <= 0) return "";
        if (clean.length() <= maxChars) return clean;
        int end = maxChars;
        if (end > 0 && Character.isHighSurrogate(clean.charAt(end - 1))) end--;
        return clean.substring(0, end).strip();
    }

    static String reply(String text, int maxWords) {
        String clean = characters(text, 8000).replaceAll("\\s+", " ");
        String[] sentences = clean.split("(?<=[.!?…])\\s+", 3);
        if (sentences.length > 2) clean = sentences[0] + " " + sentences[1];
        String[] words = clean.split("\\s+");
        int limit = Math.max(1, Math.min(120, maxWords));
        if (words.length > limit) clean = String.join(" ", java.util.Arrays.copyOf(words, limit));
        return clean.strip();
    }
}
