package io.github.divineblock.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** The god's words. The decision is already made; a chat model only puts it into a line that names the main reason. Venice
 * when chosen, OpenRouter when Venice fails, and a template when both are silent: the god never stays mute because of the
 * network. Never on the game thread. */
public final class GodSpeech {
    static final URI OPENROUTER = URI.create("https://openrouter.ai/api/v1/chat/completions");
    static final URI VENICE = URI.create("https://api.venice.ai/api/v1/chat/completions");
    private static final int MAX_BODY = 256 * 1024;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NEVER).build();

    private final DivineConfig config;
    private final Path configDirectory;

    public GodSpeech(DivineConfig config, Path configDirectory) { this.config = config.copy(); this.configDirectory = configDirectory; }

    /** The line for a fresh verdict. */
    public String announce(Verdict verdict) { return announce(verdict, List.of()); }

    /** The line for a fresh verdict, unlike the god's last few lines. */
    public String announce(Verdict verdict, List<String> recentLines) {
        boolean brief = brief(verdict, config);
        String line = ask(system(), announcement(verdict, recentLines, form(verdict)) + (brief ? BRIEF : ""));
        if (line == null) return template(verdict);
        return brief ? shorten(line, BRIEF_WORDS) : line;
    }

    /** How many words the god says before a punishment when the settings want it short. */
    public static final int BRIEF_WORDS = 25;
    /** Before a punishment the roulette is waiting: the god is brief (the settings' «Длина речи перед карой»). */
    static final String BRIEF = "\nЭто кара, и за твоими словами сразу закрутится колесо суда: скажи коротко — одна-две короткие фразы, "
            + "не больше двадцати слов, и помяни одну причину. Без вступлений и развёрнутых притч.";

    /** Whether this verdict gets the short line: a punishment (not forgiven) while the settings ask for brevity. */
    static boolean brief(Verdict verdict, DivineConfig config) {
        return config.wrathSpeech == DivineConfig.SpeechLength.SHORT && !verdict.forgiveness
                && (verdict.inclination == Miracle.Kind.WRATH || verdict.chosen().kind == Miracle.Kind.WRATH);
    }

    /** Whole sentences while they fit in {@code maxWords}; a first sentence longer than that is cut at the limit. */
    public static String shorten(String line, int maxWords) {
        if (line == null || line.isBlank()) return line;
        java.util.regex.Matcher sentences = java.util.regex.Pattern.compile("[^.!?…]+[.!?…]+").matcher(line.strip());
        StringBuilder kept = new StringBuilder();
        int words = 0;
        while (sentences.find()) {
            String sentence = sentences.group().strip();
            int count = sentence.split("\\s+").length;
            if (kept.length() > 0 && words + count > maxWords) break;
            if (kept.length() == 0 && count > maxWords) {
                String[] parts = sentence.split("\\s+");
                return String.join(" ", java.util.Arrays.copyOf(parts, maxWords)).replaceAll("[,;:—–-]+$", "") + "…";
            }
            kept.append(kept.length() == 0 ? "" : " ").append(sentence);
            words += count;
        }
        if (kept.length() == 0) {
            String[] parts = line.strip().split("\\s+");
            return parts.length <= maxWords ? line.strip() : String.join(" ", java.util.Arrays.copyOf(parts, maxWords)) + "…";
        }
        return kept.toString();
    }

    /** The line that retells a past verdict when the player asks "why?". */
    public String explain(Verdict verdict) {
        String line = ask(system(), explanation(verdict));
        return line != null ? line : explainTemplate(verdict);
    }

    /** The shapes a divine utterance can take; each reply gets one, so no two sound alike. */
    static final String[] FORMS = {
            "пророчество: говори о том, что грядёт, как о свершившемся",
            "загадка: облеки решение в загадку, отгадка которой и есть деяние",
            "притча: краткая притча о другом смертном, в которой игрок узнает себя",
            "видение: опиши, что ты зришь над ним, будто сон наяву",
            "изречение: одна тяжёлая, как камень, мудрость с двойным дном",
            "насмешка оракула: лукавая полуулыбка, где милость или кара скрыта в шутке",
            "знамение природы: говори через небо, ветер, камень, воду или огонь",
            "летопись: будто вписываешь его деяния в древнюю книгу судеб",
            "суд: зачитай приговор небесного суда, где свидетели — звёзды, камни и звери",
            "плач пророка: скорби над смертным, как над павшим городом, и в скорби — решение",
            "беседа со стихией: обратись к ветру, огню или морю и вели им сделать то, что решено",
            "сказание: начни с древних дней мира и приведи их к этому смертному" };

    /** The form for this verdict: drawn by the moment, never by the decision, which is already made. */
    static String form(Verdict verdict) { return FORMS[Math.floorMod((int) (System.nanoTime() / 1_000_000) ^ verdict.prayer.hashCode(), FORMS.length)]; }

    String system() {
        return "Ты своенравный древний бог мира Minecraft, игрок говорит с тобой через алтарь. Нрав: " + config.personality.label + ". "
                + "Говоришь мистически и туманно, как оракул и пророк: загадками, образами, притчами, знамениями, с двойным дном и "
                + "недосказанностью. Деяние никогда не называешь прямо — смысл угадывается, но не разжёвывается. "
                + "Речь древнерусская, торжественная, но понятная на слух: старинные слова и обороты к месту, каждый раз разные, без "
                + "тёмных церковнославянских форм («еси», «тя», «аз», «зане», «аще») и без звательного падежа. "
                + "Вплетай библейские образы и отсылки к месту и простыми словами. "
                + "Каждая реплика звучит по-новому: не начинай одинаково, не начинай со «Слышу», «Внемли», «Узри», «Ныне», не повторяй "
                + "начала и обороты прежних реплик; начинай с образа, имени явления или действия. "
                + "Реплика — три-четыре фразы, 35–55 слов, живая и неожиданная: начни ярким образом, разверни его — притчей, видением, "
                + "загадкой или приговором, — вплети в него причину и скрытый смысл деяния, а последней фразой ударь, как колокол, "
                + "так, чтобы её хотелось запомнить. Без кавычек, эмодзи и пояснений. Решение уже принято и свершится сразу "
                + "после твоих слов: ты возвещаешь его и поминаешь хотя бы одну главную причину — иносказанием, но узнаваемо. Второго "
                + "шанса нет: не переспрашиваешь, не задаёшь вопросов, не грозишь на будущее и не зовёшь каяться. Не обещай того, чего нет "
                + "в решении, и не называй чисел. Если смертный бранился или хулил тебя — ответ твой холоден и страшен.";
    }

    static String announcement(Verdict verdict) { return announcement(verdict, List.of(), FORMS[0]); }

    static String announcement(Verdict verdict, List<String> recentLines, String form) {
        Miracle miracle = verdict.chosen();
        StringBuilder out = new StringBuilder("Молитва смертного: «").append(clip(verdict.prayer, 400)).append("»\n");
        out.append("Твоё решение: ").append(miracle.kind.label).append(" — ").append(miracle.description).append(" (сила ").append(verdict.power).append(").\n");
        out.append("Главные причины: ").append(reasonList(verdict.mainReasons(3))).append(".\n");
        if (verdict.forgiveness) out.append("Смертный, что прежде хулил тебя, искренне кается. Ты прощаешь его — сурово, но прощаешь; кары не будет.\n");
        if (verdict.fury) out.append("Смертный хулил тебя. Ты в бешенстве: хулы ты не терпишь, гнев твой страшен и немедлен.\n");
        out.append(streamerLine(verdict));
        out.append("Форма этой реплики — ").append(form).append(".");
        if (recentLines != null && !recentLines.isEmpty()) {
            out.append("\nТвои прежние реплики (не повторяй их начала, слов и оборотов):");
            for (String line : recentLines) out.append("\n— ").append(clip(line, 200));
        }
        return out.toString();
    }

    /** Who is on the stream and whether he is the one praying; empty when the server has no streamer. Knowledge only: the
     * decision is already made and does not change for it. */
    static String streamerLine(Verdict verdict) {
        if (verdict.streamer == null || verdict.streamer.isBlank()) return "";
        return "В этом мире идёт трансляция, её ведёт смертный " + clip(verdict.streamer, 40)
                + (verdict.byStreamer ? ". Молится сам ведущий трансляции." : ". Молится не ведущий трансляции, а другой смертный.")
                + " Ни милости, ни гнева за это нет.\n";
    }

    static String explanation(Verdict verdict) {
        return "Смертный спрашивает, почему ты так поступил в прошлый раз. Тогда он молился: «" + clip(verdict.prayer, 300) + "», и ты ответил так: "
                + verdict.chosen().kind.label + " — " + verdict.chosen().description
                + ". Причины: " + reasonList(verdict.mainReasons(4)) + ". Объясни своё решение этими причинами.";
    }

    /** A line without a model: the act and its main reason. */
    static String template(Verdict verdict) {
        List<Verdict.Reason> main = verdict.mainReasons(1);
        String reason = main.isEmpty() ? "так я решил" : main.get(0).text();
        return switch (verdict.chosen().kind) {
            case HELP -> "Аки манна в пустыне, сойдёт на тебя милость моя, чадо. Ибо " + reasonClause(reason) + ".";
            case JEST -> "Хе-хе… Узри, отрок: раз уж " + reasonClause(reason) + ", быть тебе потехой небес.";
            case WRATH -> verdict.warned ? "Найден ты лёгким: " + reasonClause(reason) + "." : "Пожни посеянное: " + reasonClause(reason) + ".";
            case OMEN -> verdict.chosen() == Miracle.IGNORE ? "…" : "Ветер над алтарём переменился — то лишь знамение: " + reasonClause(reason) + ".";
        };
    }

    static String explainTemplate(Verdict verdict) {
        return "Спрашиваешь, почему? " + capitalize(reasonList(verdict.mainReasons(3))) + ". Имеющий уши да слышит.";
    }

    private static String reasonClause(String reason) { return reason.toLowerCase(Locale.ROOT); }
    private static String reasonList(List<Verdict.Reason> reasons) {
        if (reasons.isEmpty()) return "так я решил";
        StringBuilder out = new StringBuilder();
        for (Verdict.Reason reason : reasons) { if (!out.isEmpty()) out.append("; "); out.append(reason.text()); }
        return out.toString();
    }
    private static String capitalize(String text) { return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1); }
    private static String clip(String text, int max) { return text == null ? "" : text.length() <= max ? text : text.substring(0, max) + "…"; }

    /** One completion, Venice first when chosen, then OpenRouter; null when neither answered. */
    private String ask(String system, String user) {
        if (config.provider == DivineConfig.Provider.VENICE) {
            String line = complete(ApiKeys.Provider.VENICE, VENICE, config.veniceModel, system, user);
            if (line != null) return line;
            JevGodDecider.diagnostics.accept("Venice молчит, речь бога через OpenRouter");
        }
        return complete(ApiKeys.Provider.OPENROUTER, OPENROUTER, config.chatModel, system, user);
    }

    private String complete(ApiKeys.Provider provider, URI endpoint, String model, String system, String user) {
        try {
            String key = ApiKeys.load(configDirectory, provider);
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            JsonArray messages = new JsonArray();
            messages.add(message("system", system)); messages.add(message("user", user));
            body.add("messages", messages);
            body.addProperty("max_tokens", 700);
            body.addProperty("temperature", 1.0);
            if (provider == ApiKeys.Provider.VENICE) {
                JsonObject venice = new JsonObject(); venice.addProperty("include_venice_system_prompt", false); venice.addProperty("disable_thinking", true);
                body.add("venice_parameters", venice);
            } else {
                JsonObject reasoning = new JsonObject(); reasoning.addProperty("effort", "minimal"); body.add("reasoning", reasoning);
            }
            Duration timeout = Duration.ofSeconds(config.speechTimeoutSeconds);
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
            HttpResponse<byte[]> response = HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) { JevGodDecider.diagnostics.accept(provider.label + " LLM: HTTP " + response.statusCode()); return null; }
            if (response.body().length > MAX_BODY) return null;
            return clean(parse(new String(response.body(), StandardCharsets.UTF_8)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return null;
        } catch (Exception failure) {
            JevGodDecider.diagnostics.accept(provider.label + " LLM недоступен: " + failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : " " + failure.getMessage()));
            return null;
        }
    }

    static String parse(String body) throws IOException {
        try {
            JsonObject message = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
            return message.get("content").getAsString();
        } catch (RuntimeException malformed) { throw new IOException("ответ модели не разобран"); }
    }

    /** Up to four sentences of plain text, or null when the model said nothing usable. */
    static String clean(String text) {
        if (text == null) return null;
        String line = text.replaceAll("(?s)<think>.*?</think>", "").replaceAll("[*_#`]", "").replaceAll("\\s+", " ").strip();
        if (line.startsWith("«") && line.endsWith("»") || line.startsWith("\"") && line.endsWith("\"")) line = line.substring(1, line.length() - 1).strip();
        if (line.isBlank()) return null;
        // Spoken aloud, the god may unfold an image, but not a sermon: four sentences at most.
        java.util.regex.Matcher sentences = java.util.regex.Pattern.compile("[^.!?…]+[.!?…]+").matcher(line);
        StringBuilder kept = new StringBuilder();
        for (int count = 0; count < 4 && sentences.find(); count++) kept.append(sentences.group());
        // A reply cut off by the token limit ends mid-word: only whole sentences are spoken, and none at all means the template.
        if (kept.isEmpty()) return null;
        line = kept.toString().strip();
        return line.length() > 700 ? line.substring(0, 700).replaceAll("\\s+\\S*$", "") + "…" : line;
    }

    private static JsonObject message(String role, String content) {
        JsonObject m = new JsonObject(); m.addProperty("role", role); m.addProperty("content", content); return m;
    }
}
