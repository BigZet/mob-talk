package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** The names mobs know the player by, set in the settings as three comma-separated lists of forms of address: hostile or
 * mocking, ordinary, close. Facts, memories, witnesses and rumours use one name — the first ordinary one, else the Minecraft
 * nick — so the world calls the player the same everywhere; the prompt also tells the creature which forms fit its relationship
 * (at or below {@link #HOSTILE_BELOW} the hostile list, from the friend heart the close one; an empty list falls back to the
 * ordinary one). Every place where the player's name reaches a prompt or a memory goes through here. */
public final class PlayerIdentity {
    private PlayerIdentity() { }
    /** At most this many names in one list, and this many characters in one name. */
    public static final int MAX_NAMES = 10, MAX_LENGTH = 30;
    /** A relationship at or below this calls for the hostile list. */
    public static final int HOSTILE_BELOW = -20;

    public enum Tone { HOSTILE, NEUTRAL, CLOSE }

    /** The one name for facts and memories: the first ordinary form, else the nick. */
    public static String name(MobTalkConfig config, String nick) {
        List<String> neutral = config == null ? List.of() : parse(config.playerNamesNeutral);
        return !neutral.isEmpty() ? neutral.getFirst() : nick == null ? "" : nick;
    }
    /** With the live settings ({@link Tuning}). */
    public static String name(String nick) { return name(Tuning.get(), nick); }

    /** On a server every player has their own forms of address and sex (the personal layer, {@link PlayerSettings}): the server
     * sets a lookup by UUID that gives the server snapshot with the player's personal fields, or empty when the player sent none.
     * Without a lookup, and for a player it does not know, everything goes by the shared settings, as in single player. */
    private static volatile Function<UUID, Optional<MobTalkConfig>> personal = null;
    public static void resolver(Function<UUID, Optional<MobTalkConfig>> lookup) { personal = lookup; named.clear(); }

    /** The settings the world names this player by: their personal fields over the server's, else {@code fallback}. */
    public static MobTalkConfig configOf(UUID player, MobTalkConfig fallback) {
        Function<UUID, Optional<MobTalkConfig>> lookup = personal;
        if (lookup == null || player == null) return fallback;
        try { return lookup.apply(player).orElse(fallback); }
        catch (RuntimeException failure) { return fallback; }
    }
    /** The same over the live settings ({@link Tuning}). */
    public static MobTalkConfig configOf(UUID player) { return configOf(player, Tuning.get()); }
    /** One particular player's name for facts, rumours and witnesses: their first ordinary form, else the nick. */
    public static String name(UUID player, String nick) { return name(player, Tuning.get(), nick); }
    /** The same with {@code fallback} for a player the server does not know (single player: the shared config). */
    public static String name(UUID player, MobTalkConfig fallback, String nick) {
        String chosen = name(configOf(player, fallback), nick);
        if (player == null || nick == null || nick.isBlank() || personal == null) return chosen; // Одиночка: игрок один.
        named.put(player, chosen);
        if (chosen.equalsIgnoreCase(nick)) return chosen;
        // Два игрока с одним обращением («Дима» и «Дима») для мобов — разные люди: при совпадении к обращению добавляется ник.
        for (var other : named.entrySet()) if (!other.getKey().equals(player) && other.getValue().equalsIgnoreCase(chosen)) return chosen + " (" + nick + ")";
        return chosen;
    }
    /** Каждый игрок, которого мир уже называл, и его обращение: по нему видно, совпадает ли обращение с чужим. */
    private static final Map<UUID, String> named = new ConcurrentHashMap<>();
    /** One particular player's sex from their personal settings; {@code fallback} is the shared config (single player). */
    public static Gender gender(MobTalkConfig fallback, UUID player) {
        MobTalkConfig config = configOf(player, fallback);
        return Gender.ofPlayer(config == null ? null : config.playerGender);
    }

    /** Which list a relationship calls for; {@code friendFrom} is the friend heart ({@link MobTalkConfig#heartsFriend}). */
    public static Tone tone(int relationship, int friendFrom) {
        return relationship <= HOSTILE_BELOW ? Tone.HOSTILE : relationship >= friendFrom ? Tone.CLOSE : Tone.NEUTRAL;
    }
    /** The forms for this relationship: its own list, else the ordinary one, else just the nick. */
    public static List<String> forms(MobTalkConfig config, String nick, int relationship) {
        if (config == null) return nick == null || nick.isBlank() ? List.of() : List.of(nick);
        List<String> chosen = switch (tone(relationship, config.heartsFriend)) {
            case HOSTILE -> parse(config.playerNamesHostile);
            case CLOSE -> parse(config.playerNamesClose);
            case NEUTRAL -> List.of();
        };
        if (chosen.isEmpty()) chosen = parse(config.playerNamesNeutral);
        if (chosen.isEmpty() && nick != null && !nick.isBlank()) chosen = List.of(nick);
        return chosen;
    }
    /** The prompt line about the player's names; "" when no list is set (the nick in the snapshot is enough). */
    public static String promptLine(MobTalkConfig config, String nick, int relationship) { return promptLine(config, nick, relationship, name(config, nick)); }
    /** The same for one particular player: {@code called} is how the world names them ({@link #name(UUID, String)}, with the nick when
     * another player goes by the same name). */
    public static String promptLine(MobTalkConfig config, String nick, int relationship, String called) {
        if (config == null || parse(config.playerNamesHostile).isEmpty() && parse(config.playerNamesNeutral).isEmpty() && parse(config.playerNamesClose).isEmpty()) return "";
        List<String> forms = forms(config, nick, relationship);
        if (forms.isEmpty()) return "";
        return "Игрока зовут " + called + "; по твоему отношению к нему обращайся так: " + String.join(", ", forms)
                + (forms.size() > 1 ? " (выбирай по настроению, не все сразу)." : ".");
    }

    /** One list from the settings: split by commas, one line each, trimmed, without duplicates, at most {@link #MAX_NAMES} of at
     * most {@link #MAX_LENGTH} characters. */
    public static List<String> parse(String list) {
        List<String> names = new ArrayList<>();
        if (list == null) return names;
        for (String raw : list.split(",")) {
            String name = TextBounds.characters(raw.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip(), MAX_LENGTH);
            if (!name.isEmpty() && names.stream().noneMatch(name::equalsIgnoreCase)) names.add(name);
            if (names.size() >= MAX_NAMES) break;
        }
        return names;
    }
    /** The list as the config keeps it: "Дима, Димка". */
    public static String clean(String list) { return String.join(", ", parse(list)); }
}
