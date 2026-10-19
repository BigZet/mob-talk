package io.github.mobtalk.core;

/** The name mobs know the player by: {@link MobTalkConfig#playerName} when set, else the Minecraft nick. Every place where the
 * player's name reaches a prompt or a memory (the snapshot, witnesses, rumours, the chronicle, gifts, families, summaries) goes
 * through here, so renaming yourself in the settings renames you for every creature from then on. */
public final class PlayerIdentity {
    private PlayerIdentity() { }
    /** Longest name kept; a whole sentence in the field would only bloat every prompt. */
    public static final int MAX_LENGTH = 40;

    public static String name(MobTalkConfig config, String nick) {
        String chosen = config == null ? "" : clean(config.playerName);
        if (!chosen.isEmpty()) return chosen;
        return nick == null ? "" : nick;
    }
    /** With the live settings ({@link Tuning}). */
    public static String name(String nick) { return name(Tuning.get(), nick); }

    /** One line, no control characters, at most {@link #MAX_LENGTH} characters; "" means "use the nick". */
    public static String clean(String name) {
        if (name == null) return "";
        String line = name.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        return TextBounds.characters(line, MAX_LENGTH);
    }
}
