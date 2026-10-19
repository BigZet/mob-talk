package io.github.mobtalk.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Сон вместе (трек J, часть 2): игрок спит как в ванили, а житель-пара ложится рядом — в свободную кровать в 1–2 блоках или в ту же
 * кровать в обычной ванильной позе. Здесь чистые правила: звал ли игрок спать, кто приходит, какую кровать занять. Никаких сцен
 * и анимаций сверх ванильного сна. */
public final class CoSleep {
    private CoSleep() { }
    /** Свободная кровать не дальше стольких блоков от кровати игрока (по каждой оси). */
    public static final int BED_RADIUS = 2;
    /** Сон рядом с другим жителем: его кровать не дальше стольких блоков от кровати игрока. */
    public static final double NEAR_SLEEPER = 3.0;
    /** Приглашение «пойдём спать» держится столько реального времени. */
    public static final long INVITE_MILLIS = 5 * 60_000L;

    private static final Pattern INVITE = Pattern.compile(
            "(пойд[её]м|пошли|идём|идем|айда|давай|пора)\\s+(со\\s+мной\\s+)?(спать|в\\s+кровать|в\\s+постель|ложиться|баиньки|на\\s+боковую)"
                    + "|(ляг|ложись|ляжешь|ляжем|спи)\\s+(со\\s+мной|рядом)"
                    + "|спать\\s+(пойд[её]м|пошли|идём|идем)");

    /** Зовёт ли игрок свою пару спать этими словами («пойдём спать», «ложись со мной», «пора в кровать»). */
    public static boolean invites(String said) {
        if (said == null || said.isBlank()) return false;
        return INVITE.matcher(said.toLowerCase(Locale.ROOT).replace('ё', 'е')).find();
    }

    /** Подойдёт ли пара лечь рядом: взрослая, не занята, в пределах {@code range} (позванная — вдвое дальше). */
    public static boolean comes(boolean partner, boolean adult, boolean busy, double distance, boolean invited, int range) {
        if (!partner || !adult || busy) return false;
        return distance <= (invited ? 2.0 * range : range);
    }

    /** Кровать для пары: ближайшая свободная из {@code free} (смещения {x, y, z} от кровати игрока) в {@link #BED_RADIUS}, иначе
     * null — тогда пара ложится в кровать игрока. */
    public static int[] bed(List<int[]> free) {
        int[] best = null; int nearest = Integer.MAX_VALUE;
        if (free == null) return null;
        for (int[] offset : free) {
            if (offset == null || offset.length < 3) continue;
            if (offset[0] == 0 && offset[1] == 0 && offset[2] == 0) continue;
            if (Math.abs(offset[0]) > BED_RADIUS || Math.abs(offset[2]) > BED_RADIUS || Math.abs(offset[1]) > 1) continue;
            int d = offset[0] * offset[0] + offset[1] * offset[1] + offset[2] * offset[2];
            if (d < nearest) { nearest = d; best = offset; }
        }
        return best;
    }

    /** Повод для утренней реплики пары после ночи вместе. */
    public static String morningCue(String playerName) {
        return "утро после ночи, которую вы с игроком " + (playerName == null || playerName.isBlank() ? "" : playerName + " ")
                + "проспали рядом; вы только что проснулись. Скажи ему одну тёплую утреннюю реплику по характеру — без пошлостей и описаний";
    }
    /** Повод для вспышки ревности, когда пара сама видела подарок или сон рядом с соперником. */
    public static String jealousCue(String rivalName, String what) {
        return "ты только что своими глазами видел, как игрок " + what + " (" + rivalName + "); ты ревнуешь — скажи ему это одной репликой по характеру";
    }
}
