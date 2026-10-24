package io.github.divineblock.core;

import java.util.List;

/** The symbols on the reels of the punishment roulette (see {@link Roulette}): twelve on every strip, the locked gates of the
 * eternal ban and the dove of a pardon. Each punishment lands on one of them; the golden seven is kept for the end of the world.
 * The order is the order of the sprites in {@code textures/gui/roulette/symbols.png}. */
public enum RouletteSymbol {
    LIGHTNING("⚡", "Молния"), FLAME("🔥", "Пламя"), METEOR("☄", "Метеорит"), CRACK("🪨", "Трещина"), WAVE("🌊", "Волна"),
    LOCUST("🦗", "Саранча"), SKULL("💀", "Череп"), RIDER("🐎", "Всадник"), SCROLL("📜", "Свиток"), ANVIL("🔨", "Наковальня"),
    TOWER("🗼", "Башня"), SEVEN("7", "Золотая семёрка"),
    /** Group К6, the eternal ban: three locked gates on a blackened strip. */
    GATE("⛓", "Запертые врата"),
    /** A pardon while the reels still spin: they jam and every symbol turns into a dove. */
    DOVE("🕊", "Голубь");

    /** The twelve symbols of an ordinary strip. */
    public static final List<RouletteSymbol> STRIP = List.of(LIGHTNING, FLAME, METEOR, CRACK, WAVE, LOCUST, SKULL, RIDER, SCROLL, ANVIL, TOWER, SEVEN);

    /** For the chat line («🎰 ⚡⚡⚡ Молния»). */
    public final String glyph;
    public final String title;

    RouletteSymbol(String glyph, String title) { this.glyph = glyph; this.title = title; }

    /** The punishment's own symbol by what it does; null for an act that has none yet (a new one falls back on its group). */
    public static RouletteSymbol own(Miracle miracle) {
        if (miracle == null) return null;
        return switch (miracle) {
            case LIGHTNING, BOSS_FACELESS, BOSS_ARCHANGEL, GOD_MACE -> LIGHTNING;
            case FIRE_RING, BLAZE_HORDE, HELL_EXILE, DRAGON, SODOM, BOSS_SERAPH, BOSS_CHERUB -> FLAME;
            case METEOR, METEOR_SHOWER -> METEOR;
            case EARTHQUAKE, SINKHOLE, RIFTS, CHUNK_VOID, GREAT_CRATER, END_EXILE, BOSS_BEHEMOTH -> CRACK;
            case ACID_RAIN, FLOOD, BOSS_LEVIATHAN -> WAVE;
            case SWARM, PLAGUES -> LOCUST;
            case UNDEAD_HORDE, RAID, WARDEN, WITHER, BOSS_WAILER, BOSS_DESTROYING_ANGEL, BOSS_BEAST -> SKULL;
            case SKELETON_RIDERS, BOSS_HORSEMEN -> RIDER;
            case CURSE_MINOR, BLIGHT, CAIN, MEMORY_WIPE, BOSS_SCHEMNIK -> SCROLL;
            case ANVIL_RAIN, TNT_RAIN, TORNADO, CURSE_TOOL, TRANSMUTE, BOSS_GOLIATH -> ANVIL;
            case BABEL, JUDGEMENT, BOSS_BELL_RINGER, BOSS_NIMROD -> TOWER;
            case ETERNAL_BAN -> GATE;
            default -> null;
        };
    }

    /** The symbol of a group, for an act without its own. */
    public static RouletteSymbol of(MiracleGroup group) {
        if (group == null) return LIGHTNING;
        return switch (group) {
            case K1 -> LIGHTNING; case K2 -> LOCUST; case K3 -> CRACK; case K4, KS -> SKULL; case K5 -> SEVEN; case K6 -> GATE;
            default -> LIGHTNING;
        };
    }

    /** The bosses of the end of the world: they land on the seven wherever they come from. */
    public static boolean doomsday(Miracle miracle) {
        return miracle == Miracle.BOSS_ARCHANGEL || miracle == Miracle.BOSS_HORSEMEN || miracle == Miracle.BOSS_BEAST || miracle == Miracle.GOD_MACE;
    }

    /** Where the reels stop for this punishment of this group (null: the group it belongs to): the gates for the ban, the
     * seven for К5 and the end of the world, otherwise its own symbol or its group's. */
    public static RouletteSymbol result(Miracle miracle, MiracleGroup group) {
        MiracleGroup at = group != null ? group : Roulette.groupOf(miracle);
        if (miracle == Miracle.ETERNAL_BAN || at == MiracleGroup.K6) return GATE;
        if (at == MiracleGroup.K5 || doomsday(miracle)) return SEVEN;
        RouletteSymbol own = own(miracle);
        return own != null ? own : of(at);
    }
}
