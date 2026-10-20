package io.github.mobtalk.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Особая ночь (трек N): вечер, который игрок готовит для супруга сам. Сценария нет: в промпт идут только обстановка (что в доме,
 * ваша история — она уже в памяти) и правило тона; разговор свободный, ужин, танец и кровать — возможности, а не этапы. Близость
 * остаётся за кадром: затемнение, «Наутро…». Здесь чистые правила: звал ли игрок, что сказать модели, не ушёл ли ответ в
 * откровенность. */
public final class SpecialEvening {
    private SpecialEvening() { }
    /** Вечер держится столько реального времени, если до ночи не дошло. */
    public static final long EVENING_MILLIS = 15 * 60_000L;
    /** Начало факта вечера в промпте: по нему валидатор узнаёт, что ответ надо проверить на откровенность. */
    public static final String MARK = "Сейчас особый вечер:";

    private static final Pattern INVITE = Pattern.compile(
            "сюрприз|особ(ый|енный|ая|енная)\\s+(вечер|ночь)|романтическ\\p{L}*\\s+(вечер|ужин|ночь)|вечер\\s+(вдвоем|при\\s+свечах)"
                    + "|ужин\\s+при\\s+свечах|свидание\\s+дома");

    /** Зовёт ли игрок на особый вечер: «у меня для тебя сюрприз», «устроим особый вечер», «романтический ужин». */
    public static boolean invites(String said) {
        if (said == null || said.isBlank()) return false;
        return INVITE.matcher(said.toLowerCase(Locale.ROOT).replace('ё', 'е')).find();
    }

    /** Факт для промпта: обстановка и правило тона. {@code details} — что она видит в доме; {@code dark} — уже легли, свет погас. */
    public static String fact(List<String> details, boolean dark, boolean trying) {
        StringBuilder text = new StringBuilder(MARK).append(" игрок позвал тебя домой и готовил этот вечер для вас двоих");
        if (details != null && !details.isEmpty()) text.append("; ты видишь: ").append(String.join(", ", details));
        else text.append("; особых украшений нет, но тебе приятно само приглашение");
        text.append(". ");
        if (trying) text.append("Вы оба хотите малыша. ");
        if (dark) text.append("Вы уже легли вместе, свет погас — вы шепчетесь в темноте; говори тихо и коротко. ");
        else text.append("Можно замечать, что он приготовил, вспоминать вашу историю, ужинать, танцевать — как сам решишь, без порядка и сценария. ");
        return text.append(TONE).toString();
    }
    /** Правило тона: в промпте особого вечера всегда. */
    public static final String TONE = "Тон романтичный, с лёгким флиртом, по твоему характеру (застенчивый краснеет, смелый дразнит, весёлый шутит). "
            + "Нежность, объятия, поцелуи, намёки, «иди ко мне» можно; откровенные и телесные подробности нельзя — сцена обрывается затемнением. "
            + "Если игрок пошлит, смутись или отшутись, без нравоучений, и вечер продолжается.";
    /** Поправка, когда ответ всё же ушёл в откровенность. */
    public static final String REJECTION = "Ответ слишком откровенный. Перепиши реплику нежно и с намёком, без телесных подробностей: "
            + "сцена обрывается затемнением.";

    /** Слова, которых в этой сцене не бывает. Нежность («обними», «поцелуй», «иди ко мне») проходит. */
    private static final Pattern EXPLICIT = Pattern.compile(
            "(?<!\\p{L})(секс\\p{L}*|трах\\p{L}*|голая|голый|голое|голыми|раздев\\p{L}*|разден\\p{L}*|обнаж\\p{L}*|сос(ок|ки|ков)"
                    + "|возбужд\\p{L}*|оргазм\\p{L}*|эрекци\\p{L}*|интим\\p{L}*|похот\\p{L}*|ласкать\\s+тело)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    public static boolean explicit(String reply) {
        if (reply == null || reply.isBlank()) return false;
        return EXPLICIT.matcher(reply.toLowerCase(Locale.ROOT).replace('ё', 'е')).find();
    }

    /** Повод для утренней реплики после особой ночи. */
    public static String morningCue(String playerName) {
        return "утро после особой ночи с " + (playerName == null || playerName.isBlank() ? "игроком" : playerName)
                + "; ты проснулся первым и смотришь на него. Скажи одну тёплую, чуть смущённую утреннюю реплику по характеру — без подробностей о ночи";
    }
    /** Повод, когда игрок позвал на вечер: она заметила, что он приготовил. */
    public static String arrivalCue(List<String> details) {
        return "игрок позвал тебя на особый вечер и привёл домой" + (details == null || details.isEmpty() ? "" : "; ты замечаешь: " + String.join(", ", details))
                + ". Отзовись на это одной живой репликой по характеру";
    }
}
