package io.github.mobtalk.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.net.MobSpeechS2C;
import io.github.mobtalk.overlay.OverlayFeed;
import io.github.mobtalk.overlay.OverlayWeb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Оверлей для OBS в игре стримера (идея З3, docs/overlay.md): поднимает {@link OverlayWeb} на 127.0.0.1 по личной настройке F7,
 * кормит страницу субтитрами из {@link MobSpeechS2C} (в момент, когда начинается звук), данными сервера из {@code OverlayStateS2C}
 * и пробными данными по кнопке F7. Ничего не шлёт наружу; ошибка порта — строка в F7 и в чат, игра идёт дальше. Поток клиента. */
final class OverlayClient {
    private OverlayClient() { }
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk-overlay");
    /** Реплика со звуком ждёт его начала не дольше этого, потом показывается без него. */
    private static final long VOICE_WAIT_MILLIS = 4_000;
    private static final OverlayFeed feed = new OverlayFeed();
    private static final Map<Integer, Pending> pending = new HashMap<>();
    private static OverlayWeb web;
    private static String problem = "";
    private static long testUntil;
    private static JsonObject realState = new JsonObject();

    private record Pending(MobSpeechS2C packet, long since) { }

    /** Настройки F7 применены (загрузка, сохранение): поднять, перезапустить на другом порту или погасить сервер страницы. */
    static String apply(MobTalkConfig config) {
        if (!config.overlayEnabled) { stop(); problem = ""; return ""; }
        if (web != null && web.port() != config.overlayPort) stop();
        if (web == null) {
            try { web = OverlayWeb.start(config.overlayPort, feed); problem = ""; LOGGER.info("Mob Talk: оверлей для OBS на {}", web.address()); }
            catch (IOException busy) {
                problem = "Порт " + config.overlayPort + " занят: оверлей не запущен. Выберите другой порт в F7.";
                LOGGER.warn("Mob Talk: оверлей не запущен ({})", busy.toString());
                return problem;
            }
        }
        feed.config(config(config));
        return "";
    }
    static void stop() {
        if (web != null) { web.stop(); web = null; }
        pending.clear();
    }
    static boolean running() { return web != null; }
    static String address() { return web == null ? "" : web.address(); }
    /** Строка для F7: адрес, сколько страниц открыто, что пришло от сервера, или беда с портом. */
    static String status() {
        if (!problem.isEmpty()) return problem;
        if (web == null) return "Оверлей выключен";
        JsonObject state = realState;
        String server = state.has("you") ? "сервер шлёт данные бога и событий" : ClientNet.features().version().isEmpty() ? "только субтитры" : "сервер не шлёт данные (нет права или выключено на сервере)";
        return web.address() + " · страниц открыто: " + feed.pages() + " · " + server;
    }

    static JsonObject config(MobTalkConfig config) {
        JsonObject out = new JsonObject();
        out.addProperty("subtitles", config.overlaySubtitles); out.addProperty("god", config.overlayGod);
        out.addProperty("mercy", config.overlayMercy); out.addProperty("now", config.overlayNow);
        out.addProperty("heard", config.overlayHeard); out.addProperty("chatter", config.overlayChatter);
        out.addProperty("holdSeconds", config.overlayHoldSeconds); out.addProperty("scale", config.overlayScale);
        out.addProperty("style", config.overlayStyle); out.addProperty("layout", config.overlayLayout); out.addProperty("reduceMotion", config.overlayReduceMotion);
        return out;
    }

    /** Данные сервера; пробные данные, пока идут, их не перебивают. */
    static void serverState(String json) {
        try { realState = JsonParser.parseString(json).getAsJsonObject(); }
        catch (RuntimeException bad) { realState = new JsonObject(); }
        if (System.currentTimeMillis() >= testUntil) feed.state(realState);
    }
    static void disconnected() { realState = new JsonObject(); pending.clear(); feed.state(realState); }

    /** Реплика жителя: без звука — сразу, со звуком — когда он начнётся ({@link #voiceStarted}) или через 4 с. */
    static void speech(MobSpeechS2C packet) {
        if (web == null || packet.silent() || MobSpeechS2C.FALLBACK.equals(packet.kind())) return;
        if (packet.clipId() == 0) emit(packet, estimate(packet.text()));
        else pending.put(packet.clipId(), new Pending(packet, System.currentTimeMillis()));
    }
    static void voiceStarted(int clipId, int millis) {
        Pending waiting = pending.remove(clipId);
        if (waiting != null) emit(waiting.packet(), millis);
    }
    static void tick() {
        if (pending.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
            Pending waiting = it.next();
            if (now - waiting.since() < VOICE_WAIT_MILLIS) continue;
            it.remove();
            emit(waiting.packet(), estimate(waiting.packet().text()));
        }
        if (testUntil != 0 && now >= testUntil) { testUntil = 0; feed.state(realState); }
    }
    private static void emit(MobSpeechS2C packet, int millis) {
        JsonObject line = new JsonObject();
        line.addProperty("t", System.currentTimeMillis());
        line.addProperty("speaker", packet.speaker()); line.addProperty("text", packet.text()); line.addProperty("kind", packet.kind());
        line.addProperty("heard", packet.heard()); line.addProperty("asker", packet.asker()); line.addProperty("millis", Math.max(0, millis));
        line.addProperty("toYou", packet.toYou());
        feed.line(line);
    }
    /** Без звука строка «звучит» по 65 мс на знак, но не меньше 1,5 с. */
    static int estimate(String text) { return Math.max(1500, (text == null ? 0 : text.length()) * 65); }

    /** «Пробные данные» в F7: 30 секунд выдуманного состояния и реплик, без LLM и озвучки. */
    static void test(String player) {
        if (web == null) return;
        long now = System.currentTimeMillis();
        testUntil = now + 30_000;
        String me = player == null || player.isBlank() ? "Стример" : player;
        JsonObject state = JsonParser.parseString("""
                {"god":{"mood":-42,"word":"хмурится"},
                 "verdict":{"t":%d,"player":{"uuid":"00000000-0000-0000-0000-000000000002","name":"Петя"},"act":"wrath","title":"Мор","reasons":["хула у алтаря","третий грех за день","нет подношения"]},
                 "souls":[{"player":{"uuid":"00000000-0000-0000-0000-000000000001","name":"%s"},"mercy":78,"label":"праведник","sins":1,"ladder":0,"banned":false,"cursed":false},
                          {"player":{"uuid":"00000000-0000-0000-0000-000000000003","name":"Маша"},"mercy":55,"label":"обычная душа","sins":2,"ladder":1,"banned":false,"cursed":false},
                          {"player":{"uuid":"00000000-0000-0000-0000-000000000002","name":"Петя"},"mercy":18,"label":"грешник","sins":7,"ladder":3,"banned":false,"cursed":true}],
                 "active":[{"kind":"curse","title":"Мор","target":{"uuid":"00000000-0000-0000-0000-000000000002","name":"Петя"},"until":%d,"health":0,"maxHealth":0},
                           {"kind":"boss","title":"Голиаф","target":{"uuid":"00000000-0000-0000-0000-000000000002","name":"Петя"},"until":0,"health":180,"maxHealth":300}],
                 "events":[{"kind":"banner","title":"Пробные данные оверлея: так выглядит баннер админа","player":null,"until":%d}],
                 "lines":[{"t":%d,"text":"Петя, третий раз хулишь Меня у алтаря. Да будет мор.","millis":4200}],
                 "you":"00000000-0000-0000-0000-000000000001","streamer":"00000000-0000-0000-0000-000000000001"}
                """.formatted(now, me.replace("\"", ""), now + 5 * 60_000, testUntil, now + 6_000)).getAsJsonObject();
        feed.state(state);
        JsonObject line = new JsonObject();
        line.addProperty("t", now); line.addProperty("speaker", "Тереза"); line.addProperty("kind", MobSpeechS2C.ANSWER);
        line.addProperty("text", "Кузнец за колодцем, где дым. Только не говори ему, что от меня — он мне должен три хлеба.");
        line.addProperty("heard", "где тут кузнец?"); line.addProperty("asker", ""); line.addProperty("millis", 5200); line.addProperty("toYou", true);
        feed.line(line);
    }
}
