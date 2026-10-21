package io.github.divineblock.client.boss;

import io.github.divineblock.boss.BossEffects;
import java.util.Random;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

/** «Смешение языков» (Нимрод) on the client: while the player carries it, chat lines, subtitles, titles, the action bar and
 * item names come scrambled. Each word's letters are shuffled the same way every time, so a word stays the same nonsense.
 * The mixins call {@link #scramble(Text)}; it never throws and returns a plain literal with the top-level style. */
public final class NimrodConfusion {
    private NimrodConfusion() { }

    /** Whether the local player is confused right now (only on the client thread). */
    public static boolean active() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || !client.isOnThread()) return false;
        return client.player != null && client.player.hasStatusEffect(BossEffects.CONFUSION);
    }

    /** {@code text} scrambled when the player is confused, otherwise the same text. */
    public static Text scrambleIfActive(Text text) {
        return text != null && active() ? scramble(text) : text;
    }

    public static Text scramble(Text text) {
        if (text == null) return null;
        try {
            MutableText out = Text.literal(scramble(text.getString()));
            return out.setStyle(text.getStyle());
        } catch (RuntimeException failure) {
            return text;
        }
    }

    /** Shuffles the letters inside every word; spaces, digits, signs and formatting codes stay where they are. */
    public static String scramble(String line) {
        StringBuilder out = new StringBuilder(line.length());
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '§' && i + 1 < line.length()) { out.append(c).append(line.charAt(i + 1)); i += 2; continue; }
            if (!Character.isLetter(c)) { out.append(c); i++; continue; }
            int end = i;
            while (end < line.length() && Character.isLetter(line.charAt(end))) end++;
            out.append(word(line.substring(i, end)));
            i = end;
        }
        return out.toString();
    }

    private static String word(String word) {
        if (word.length() < 2) return word;
        char[] letters = word.toLowerCase().toCharArray();
        Random random = new Random(word.toLowerCase().hashCode() * 31L + 7);
        for (int k = letters.length - 1; k > 0; k--) {
            int j = random.nextInt(k + 1);
            char t = letters[k]; letters[k] = letters[j]; letters[j] = t;
        }
        String shuffled = new String(letters);
        if (shuffled.equals(word.toLowerCase())) shuffled = shuffled.substring(1) + shuffled.charAt(0);
        // Keep the capital where the word had it.
        if (Character.isUpperCase(word.charAt(0))) shuffled = Character.toUpperCase(shuffled.charAt(0)) + shuffled.substring(1);
        return shuffled;
    }
}
