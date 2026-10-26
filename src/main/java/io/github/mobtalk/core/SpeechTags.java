package io.github.mobtalk.core;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Пометки интонации в реплике — «[шёпотом]», «[смеётся]», «[вздыхает]»: модель ставит их по-русски из короткого списка, озвучка
 * получает английские теги ElevenLabs v3/v4 ({@code [whispers]}, {@code [laughs]}…), в чат и память они не попадают. Модели без
 * тегов (Flash, Turbo, Multilingual v2, Venice) прочли бы их вслух, поэтому им пометки вырезаются. */
public final class SpeechTags {
    private SpeechTags() { }

    /** Русская пометка → тег ElevenLabs. Только то, что v3 произносит надёжно; прочее в скобках отбрасывается. */
    static final Map<String, String> TAGS = Map.ofEntries(
            Map.entry("шёпотом", "whispers"), Map.entry("шепотом", "whispers"), Map.entry("шёпот", "whispers"), Map.entry("шепот", "whispers"), Map.entry("шепчет", "whispers"),
            Map.entry("смеётся", "laughs"), Map.entry("смеется", "laughs"), Map.entry("смех", "laughs"), Map.entry("хохочет", "laughing loudly"),
            Map.entry("хихикает", "giggles"), Map.entry("усмехается", "chuckles"), Map.entry("смешок", "chuckles"),
            Map.entry("вздыхает", "sighs"), Map.entry("вздох", "sighs"), Map.entry("выдыхает", "exhales"),
            Map.entry("плачет", "crying"), Map.entry("всхлипывает", "crying"), Map.entry("сквозь слёзы", "crying"), Map.entry("сквозь слезы", "crying"),
            Map.entry("кричит", "shouting"), Map.entry("громко", "shouting"), Map.entry("сердито", "angry"), Map.entry("зло", "angry"),
            Map.entry("радостно", "happy"), Map.entry("восторженно", "excited"), Map.entry("взволнованно", "excited"),
            Map.entry("удивлённо", "surprised"), Map.entry("удивленно", "surprised"), Map.entry("ахает", "happy gasp"),
            Map.entry("саркастично", "sarcastic"), Map.entry("с издёвкой", "sarcastic"), Map.entry("с издевкой", "sarcastic"),
            Map.entry("нервно", "nervously"), Map.entry("испуганно", "alarmed"), Map.entry("в панике", "panicking"),
            Map.entry("ласково", "warmly"), Map.entry("нежно", "warmly"), Map.entry("грустно", "sad"), Map.entry("печально", "sad"),
            Map.entry("устало", "exhales"), Map.entry("равнодушно", "deadpan"), Map.entry("безразлично", "deadpan"),
            Map.entry("хитро", "mischievously"), Map.entry("задумчиво", "thoughtful"), Map.entry("с любопытством", "curious"),
            Map.entry("бормочет", "muttering"), Map.entry("фыркает", "snorts"), Map.entry("откашливается", "clears throat"),
            Map.entry("сглатывает", "gulps"), Map.entry("пауза", "short pause"), Map.entry("долгая пауза", "long pause"));
    /** Больше трёх пометок на реплику — уже театр: лишние отбрасываются. */
    static final int MAX_TAGS = 3;
    private static final Pattern BRACKETS = Pattern.compile("\\s*\\[([^\\[\\]]{1,40})\\]\\s*");

    /** Модель озвучки понимает теги: ElevenLabs v3 и v4 через OpenRouter. */
    public static boolean supports(String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return m.startsWith("elevenlabs/eleven-v3") || m.startsWith("elevenlabs/eleven-v4");
    }
    /** Пометки включены и модель озвучки их понимает. */
    public static boolean on(MobTalkConfig c) { return c != null && c.ttsTags && !c.usesVeniceSpeech() && supports(c.ttsModel); }

    /** Реплика для чата, субтитров и памяти: без пометок в квадратных скобках. */
    public static String strip(String text) {
        if (text == null || text.indexOf('[') < 0) return text;
        return tidy(BRACKETS.matcher(text).replaceAll(" "));
    }
    /** Реплика для озвучки моделью {@code model}: известные пометки — английскими тегами (не больше {@link #MAX_TAGS}), остальные
     * скобки убраны; модели без тегов (или {@code enabled} = false) — текст без пометок. */
    static String forSpeech(String text, String model, boolean enabled) {
        if (text == null || text.indexOf('[') < 0) return text;
        if (!enabled || !supports(model)) return strip(text);
        Matcher m = BRACKETS.matcher(text);
        StringBuilder out = new StringBuilder();
        int used = 0;
        while (m.find()) {
            String tag = tag(m.group(1));
            m.appendReplacement(out, tag != null && used++ < MAX_TAGS ? Matcher.quoteReplacement(" [" + tag + "] ") : " ");
        }
        m.appendTail(out);
        return tidy(out.toString());
    }
    /** Тег ElevenLabs для пометки: русская из списка или уже английская из того же набора; null — неизвестная. */
    static String tag(String raw) {
        String key = raw.strip().toLowerCase(Locale.ROOT);
        String mapped = TAGS.get(key);
        if (mapped != null) return mapped;
        return TAGS.containsValue(key) ? key : null;
    }
    private static String tidy(String text) {
        return text.replaceAll("\\s{2,}", " ").replaceAll("\\s+([,.!?…;:])", "$1").strip();
    }

    /** Строка для промпта реплики; "" — пометки выключены или модель их не понимает. */
    static String prompt(MobTalkConfig c) {
        if (!on(c)) return "";
        return "Интонация: в reply можно поставить 0–2 пометки в квадратных скобках прямо перед словами, к которым они относятся, — "
                + "озвучка их сыграет, вслух они не звучат и в чате не видны. Только из списка: [шёпотом], [смеётся], [хихикает], [усмехается], "
                + "[вздыхает], [плачет], [кричит], [сердито], [радостно], [восторженно], [удивлённо], [саркастично], [нервно], [испуганно], "
                + "[ласково], [грустно], [устало], [равнодушно], [хитро], [задумчиво], [бормочет], [фыркает], [откашливается], [пауза]. "
                + "Ставь, только когда интонация правда важна (секрет — шёпотом, горе — плачет, страх — испуганно); в большинстве реплик пометок нет.\n";
    }
}
