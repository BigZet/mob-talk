package io.github.mobtalk.server;

import io.github.mobtalk.core.MobClock;
import com.google.gson.JsonObject;
import io.github.mobtalk.core.ConversationEngine;
import io.github.mobtalk.core.SpeechStream;
import io.github.mobtalk.core.Wedding;
import io.github.mobtalk.core.WeddingScript;
import io.github.mobtalk.game.GameBridge;
import io.github.mobtalk.game.GameBridge.WeddingView;
import io.github.mobtalk.net.MobSpeechS2C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import java.util.concurrent.TimeUnit;

/** Ведущий свадьбы на выделенном сервере (то, что в одиночке делает клиентский {@code WeddingVoice}): просит у модели тексты,
 * говорит реплики службы по очереди всем игрокам в {@link #RANGE} от говорящего (текстом и голосом его говорящего), ждёт клятву
 * игрока-жениха и отдаёт её службе. Клятва приходит обычной репликой ({@link DialogueService#talkText}) и перехватывается до
 * разговора. Любой сбой — модель, озвучка — не останавливает службу: реплика идёт текстом, сторож службы пропускает зависшее.
 * Поток сервера, кроме запросов (пул). */
final class WeddingHost {
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk");
    static final double RANGE = 48;
    /** Длиннее этого фраза делится (одна озвучка — не больше полуминуты звука). */
    static final int SEGMENT_CHARS = 260;
    /** Сколько ждать озвучку фразы, прежде чем показать её только текстом. */
    private static final long VOICE_WAIT_MILLIS = 12_000;
    private final DialogueService service;
    private final GameBridge bridge;
    private Wedding.Ceremony ceremony;
    private WeddingView view;
    /** Реплика, которая сейчас звучит: её звук, сколько его ждать и до какого времени она звучит. */
    private Wedding.Line line;
    private CompletableFuture<byte[]> audio;
    /** Озвучки, заказанные заранее: номер реплики → звук. */
    private final java.util.Map<Integer, CompletableFuture<byte[]>> fetched = new java.util.HashMap<>();
    private long audioDeadline, speakingUntil = -1;
    private int promptedVow = -1, judgedVow = -1;
    /** Для моментов монтажа: когда служба началась и сообщено ли уже, чем она кончилась. */
    private long ceremonySince;
    private boolean ceremonyReported;

    WeddingHost(DialogueService service, GameBridge bridge) { this.service = service; this.bridge = bridge; }

    /** Служба ждёт клятвы этого игрока: его следующая реплика — клятва, а не разговор. */
    boolean takesVow(UUID player) {
        WeddingView now = bridge.wedding();
        return now != null && player.equals(now.player()) && now.ceremony().awaitingVow();
    }

    void tick() {
        WeddingView now = bridge.wedding();
        if (now == null) { if (ceremony != null) { reportOutcome(); reset(); } return; }
        if (ceremony != now.ceremony()) { reportOutcome(); reset(); ceremony = now.ceremony(); ceremonySince = MobClock.now(); ceremonyReported = false; }
        view = now;
        if (ceremony.over()) reportOutcome();
        requestScript();
        if (now.player() != null) promptVow();
        voice();
    }

    // ---- Тексты. ----

    private void requestScript() {
        WeddingScript.Input wanted = bridge.takeWeddingScript();
        if (wanted == null) return;
        Wedding.Ceremony target = ceremony;
        service.pool().submit(DialoguePool.Priority.ANSWER, () -> {
            try {
                ConversationEngine engine = service.engineOrNull();
                if (engine == null) { target.scriptFailed(); return; }
                Wedding.Script script = engine.weddingScript(wanted);
                target.scriptReady(script);
                LOGGER.info("Mob Talk: тексты свадьбы готовы на сервере ({} реплик)", script.size());
            } catch (Exception failure) {
                target.scriptFailed();
                LOGGER.info("Mob Talk: тексты свадьбы не пришли, служба идёт на запасных ({})", String.valueOf(failure.getMessage()));
            } finally { Thread.interrupted(); }
        });
    }

    // ---- Клятва игрока. ----

    /** Пришла очередь клятвы: игрок видит, как её сказать, а клиент — что запись идёт до {@code weddingVowSeconds}. */
    private void promptVow() {
        Wedding.Beat beat = ceremony.current();
        if (beat == null || !beat.vow() || promptedVow == beat.seq()) return;
        promptedVow = beat.seq();
        UUID player = view.player();
        int seconds = service.config().weddingVowSeconds;
        bridge.mob(view.spouse(), view.dimension()).whenComplete((spouse, error) -> service.onServer(() -> {
            JsonObject json = new JsonObject();
            json.addProperty("open", true); json.addProperty("seconds", seconds);
            json.addProperty("entityId", error == null && spouse != null ? spouse.entityId() : -1);
            service.out().screen(player, "wedding_vow", json.toString());
            service.out().chat(player, "§d[Mob Talk] Ваша клятва: удерживайте клавишу речи и говорите (до " + seconds + " с) или напишите её: /mobtalk say …");
        }));
    }
    /** Игрок сказал (или написал) клятву: решатель смотрит, не отказ ли это, служба идёт дальше; пара готовит свою клятву в ответ. */
    void vowHeard(UUID player, String text) {
        Wedding.Ceremony target = ceremony;
        WeddingView scene = view;
        if (target == null || scene == null || !target.awaitingVow()) return;
        Wedding.Beat beat = target.current();
        if (beat == null || beat.seq() == judgedVow) return; // Эту клятву уже судят: второй раз не считается.
        judgedVow = beat.seq();
        service.out().screen(player, "wedding_vow", "{\"open\":false}");
        service.out().status(player, "Клятва произнесена", false, "");
        broadcast(scene.spouse(), "", "", text, player);
        String question = target.said(Wedding.Key.ASK_PLAYER);
        var decider = service.decider();
        service.pool().submit(DialoguePool.Priority.ANSWER, () -> {
            boolean consents;
            try { consents = decider.consents(question, text); } catch (RuntimeException failure) { consents = !Wedding.refuses(text); }
            target.vow(text, !consents, MobClock.now());
            LOGGER.info("Mob Talk: клятва игрока принята сервером ({})", consents ? "согласие" : "отказ");
            if (!consents) return;
            try {
                ConversationEngine engine = service.engineOrNull();
                String vow = engine == null ? "" : engine.weddingVow(scene.input(), text);
                if (!vow.isBlank()) target.spouseVow(vow);
            } catch (Exception failure) { LOGGER.info("Mob Talk: клятва пары по словам игрока не пришла ({})", String.valueOf(failure.getMessage())); }
            finally { Thread.interrupted(); }
        });
    }

    // ---- Реплики. ----

    private void voice() {
        long now = MobClock.now();
        if (line != null) { advance(now); return; }
        Wedding.Line next = ceremony.claim(now);
        if (next == null) { prefetch(ceremony.upcoming(1)); return; }
        line = next;
        audio = fetched.remove(next.seq());
        if (audio == null || next.key() == Wedding.Key.SPOUSE_VOW) audio = fetch(next);
        audioDeadline = now + VOICE_WAIT_MILLIS * segments(next.text()).size(); speakingUntil = -1;
    }
    private void advance(long now) {
        if (speakingUntil > 0) {
            if (now < speakingUntil) { prefetch(ceremony.upcoming(1)); return; }
            ceremony.lineDone(line.seq(), now);
            line = null; audio = null; speakingUntil = -1;
            return;
        }
        if (!audio.isDone() && now < audioDeadline) return;
        byte[] pcm = audio.getNow(new byte[0]);
        int millis = pcm.length / DialogueService.PCM_BYTES_PER_MILLI;
        int clipId = pcm.length == 0 ? 0 : service.nextClipId();
        broadcast(line.speaker(), line.name(), line.text(), "", null, clipId, pcm, millis);
        speakingUntil = now + (pcm.length == 0 ? Wedding.speakMillis(line.text()) : millis + 400L);
    }
    /** Реплики, которые скоро прозвучат, озвучиваются заранее (клятва пары — нет: она ещё может смениться). */
    private void prefetch(List<Wedding.Line> upcoming) {
        for (Wedding.Line next : upcoming) {
            if (next.key() == Wedding.Key.SPOUSE_VOW || next.text().isBlank() || fetched.containsKey(next.seq())) continue;
            fetched.keySet().removeIf(seq -> seq < next.seq() - 4);
            fetched.put(next.seq(), fetch(next));
        }
    }
    /** Звук реплики целиком: фразы по очереди голосом говорящего, склеенные; пустой — озвучка недоступна. */
    private CompletableFuture<byte[]> fetch(Wedding.Line next) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        if (next.text().isBlank()) { result.complete(new byte[0]); return result; }
        service.pool().submit(DialoguePool.Priority.ANSWER, () -> {
            java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
            try {
                ConversationEngine engine = service.engineOrNull();
                for (String text : segments(next.text())) {
                    SpeechStream stream = engine == null ? null : engine.voice(next.voiceId(), text);
                    if (stream == null) { pcm.reset(); break; }
                    try { pcm.writeBytes(stream.awaitAll(TimeUnit.SECONDS.toNanos(60))); } finally { stream.close(); }
                }
            } catch (Exception failure) { pcm.reset(); }
            finally { Thread.interrupted(); result.complete(pcm.toByteArray()); }
        });
        return result;
    }

    private void broadcast(UUID speaker, String name, String text, String heard, UUID asker) { broadcast(speaker, name, text, heard, asker, 0, new byte[0], 0); }
    /** Реплика службы (или слова жениха {@code heard}) всем игрокам рядом с говорящим; жених слышит всегда. */
    private void broadcast(UUID speaker, String name, String text, String heard, UUID asker, int clipId, byte[] pcm, int millis) {
        WeddingView scene = view;
        if (scene == null) return;
        bridge.mob(speaker, scene.dimension()).whenComplete((mob, error) -> service.onServer(() -> {
            int entityId = error == null && mob != null ? mob.entityId() : -1;
            bridge.playersNear(speaker, scene.dimension(), RANGE).whenComplete((players, failure) -> service.onServer(() -> {
                List<UUID> listeners = new ArrayList<>(failure != null || players == null ? List.of() : players);
                if (scene.player() != null && !listeners.contains(scene.player())) listeners.add(scene.player());
                String askerName = asker == null ? "" : sessionName(asker);
                for (UUID listener : listeners)
                    service.out().speech(listener, new MobSpeechS2C(entityId, speaker, name, text, listener.equals(scene.player()), clipId, RANGE,
                            MobSpeechS2C.WEDDING, 0, heard, askerName));
                if (clipId == 0 || pcm.length == 0) return;
                SpeechService.Clip clip = new SpeechService.Clip(clipId, DialogueService.PCM_FORMAT, pcm, millis);
                for (UUID listener : listeners) service.out().clip(listener, clip);
            }));
        }));
    }
    private String sessionName(UUID player) {
        for (DialogueService.Session session : service.sessions()) if (session.player.equals(player)) return session.name;
        return "";
    }

    /** Фразы не длиннее {@link #SEGMENT_CHARS} по границам предложений; пустой текст — одна пустая фраза. */
    static List<String> segments(String text) {
        String rest = text == null ? "" : text.strip();
        List<String> parts = new ArrayList<>();
        while (rest.length() > SEGMENT_CHARS) {
            int cut = -1;
            for (int i = Math.min(SEGMENT_CHARS, rest.length() - 1); i > SEGMENT_CHARS / 3; i--) {
                char c = rest.charAt(i);
                if ((c == '.' || c == '!' || c == '?' || c == '…') && (i + 1 >= rest.length() || Character.isWhitespace(rest.charAt(i + 1)))) { cut = i + 1; break; }
            }
            if (cut < 0) { cut = rest.lastIndexOf(' ', SEGMENT_CHARS); if (cut <= SEGMENT_CHARS / 3) cut = SEGMENT_CHARS; }
            parts.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty() || parts.isEmpty()) parts.add(rest);
        return parts;
    }

    /** Свадьба кончилась или сменилась: недоигранное бросается, клиенту жениха клятва закрывается. */
    /** Чем кончилась свадьба — в историю админки (вид «wedding»): так она попадает в моменты для монтажа. */
    private void reportOutcome() {
        WeddingView now = view;
        if (ceremonyReported || now == null) return;
        ceremonyReported = true;
        Wedding.Ceremony done = now.ceremony();
        Wedding.Cast cast = done.cast();
        String outcome = done.phase() == Wedding.Ceremony.Phase.JILTED ? "jilted" : done.phase() == Wedding.Ceremony.Phase.CANCELLED ? "cancelled" : done.married() ? "married" : "cancelled";
        String first = cast.playerWedding() ? done.names().player() : cast.partner().name(), second = cast.spouse().name();
        String text = switch (outcome) {
            case "married" -> (cast.playerWedding() ? "Свадьба: " : "Свадьба жителей: ") + first + " и " + second;
            case "jilted" -> "Сорванная свадьба: " + first + " и " + second;
            default -> "Свадьба отменена: " + first + " и " + second;
        };
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("outcome", outcome);
        data.put("since", ceremonySince);
        data.put("village", done.names().village());
        if (done.vowText() != null && !done.vowText().isBlank()) data.put("vow", done.vowText());
        service.journal().event("wedding", now.player(), cast.playerWedding() ? done.names().player() : null, cast.spouse().id(), second, "villager", text, data);
    }

    void reset() {
        fetched.clear();
        WeddingView old = view;
        if (old != null && old.player() != null && promptedVow >= 0) service.out().screen(old.player(), "wedding_vow", "{\"open\":false}");
        ceremony = null; view = null; line = null; audio = null; speakingUntil = -1; promptedVow = -1; judgedVow = -1;
    }
}
