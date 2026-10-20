package io.github.mobtalk.client;

import io.github.mobtalk.client.game.MobGameBridge;
import io.github.mobtalk.client.game.WeddingController;
import io.github.mobtalk.core.*;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Свадьба (трек L) на клиенте: просит у модели тексты, пока все идут к колоколу; озвучивает реплики ведущего, пары и гостей по
 * очереди голосами их говорящих, деля длинные на фразы и готовя следующую фразу заранее; ждёт клятву игрока и отдаёт её службе.
 * Любой сбой — модель, озвучка, распознавание — не останавливает службу: реплика идёт текстом, клятву можно написать, а сторож
 * службы ({@link Wedding.Ceremony#tick}) пропускает зависшее. Клиентский поток, кроме запросов. */
final class WeddingVoice {
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk");
    /** Длиннее этого фраза делится (одна озвучка — не больше полуминуты звука). */
    private static final int SEGMENT_CHARS = 260;
    /** Сколько ждать озвучку фразы, прежде чем показать её только текстом. */
    private static final long VOICE_WAIT_MILLIS = 9_000;
    private static final double RANGE = 48;
    private final MobTalkClient owner;
    private final ExecutorService work = Executors.newFixedThreadPool(2, task -> { Thread t = new Thread(task, "mobtalk-wedding"); t.setDaemon(true); return t; });
    private Wedding.Ceremony ceremony;
    private WeddingScript.Input input;
    /** Реплика, которая сейчас звучит, её фразы и текущая. */
    private Wedding.Line line;
    private List<String> segments = List.of();
    private int segment;
    private Future<SpeechStream> audio;
    private long audioDeadline, textUntil = -1;
    private boolean playing;
    /** Озвучки, заказанные заранее: голос + текст → поток. */
    private final Map<String, Future<SpeechStream>> prefetched = new LinkedHashMap<>();
    private int promptedVow = -1, judgedVow = -1;
    /** Сколько фраз свадьбы прозвучало голосом (для самотеста). */
    int voiced;
    /** Игрок, чья свадьба идёт, и ждёт ли служба его клятвы: читается конвейером разговора из другого потока. */
    private volatile boolean vowOpen;

    WeddingVoice(MobTalkClient owner) { this.owner = owner; }

    /** Свадьба идёт: обычные разговоры деревни и инициатива молчат. */
    boolean active() { return ceremony != null; }
    /** Служба ждёт клятвы этого игрока: запись идёт до {@link MobTalkConfig#weddingVowSeconds}, а распознанное уходит в службу. */
    boolean takesVow() {
        if (!vowOpen) {
            // Тик клиента мог ещё не увидеть, что служба уже ждёт клятву: смотрим на саму службу.
            var server = owner.client.getServer();
            MobGameBridge game = server == null ? null : MobGameBridge.active(server);
            WeddingController.Scene scene = game == null ? null : game.wedding.scene();
            var player = owner.client.player;
            return scene != null && player != null && scene.playerWedding() && scene.player.equals(player.getUuid()) && scene.ceremony.awaitingVow();
        }
        return true;
    }

    void tick(long ticks) {
        MobGameBridge game = owner.client.getServer() == null ? null : MobGameBridge.active(owner.client.getServer());
        WeddingController.Scene scene = game == null || owner.client.player == null || owner.world == null ? null : game.wedding.scene();
        if (scene == null) { if (ceremony != null) reset(); return; }
        if (ceremony != scene.ceremony) { reset(); ceremony = scene.ceremony; input = scene.input; }
        boolean mine = scene.playerWedding() && scene.player.equals(owner.client.player.getUuid());
        vowOpen = mine && ceremony.awaitingVow();
        requestScript(game);
        if (mine) promptVow(scene);
        voice();
    }

    // ---- Тексты. ----

    private void requestScript(MobGameBridge game) {
        WeddingScript.Input wanted = game.wedding.takeScriptRequest();
        if (wanted == null) return;
        Wedding.Ceremony target = ceremony;
        Supplier<ConversationEngine> engines = owner.engineSupplier();
        work.execute(() -> {
            try {
                ConversationEngine engine = engines.get();
                if (engine == null) { target.scriptFailed(); return; }
                Wedding.Script script = engine.weddingScript(wanted);
                target.scriptReady(script);
                LOGGER.info("Mob Talk: тексты свадьбы готовы ({} реплик)", script.size());
            } catch (Exception failure) {
                target.scriptFailed();
                LOGGER.info("Mob Talk: тексты свадьбы не пришли, служба идёт на запасных ({})", String.valueOf(failure.getMessage()));
            } finally { Thread.interrupted(); }
        });
    }

    // ---- Клятва игрока. ----

    /** Пришла очередь клятвы: собеседником становится пара, игрок видит, как сказать клятву голосом или написать её. */
    private void promptVow(WeddingController.Scene scene) {
        Wedding.Beat beat = ceremony.current();
        if (beat == null || !beat.vow() || promptedVow == beat.seq()) return;
        promptedVow = beat.seq();
        MobEntity spouse = MobSpeechSound.find(owner.world, scene.spouse());
        if (spouse != null && !spouse.getUuid().equals(owner.selected)) owner.select(spouse);
        String key = owner.talkKey.getBoundKeyLocalizedText().getString();
        owner.client.inGameHud.getChatHud().addMessage(Text.literal("§d[Mob Talk] Ваша клятва: удерживайте " + key + " и говорите (до " + owner.config().weddingVowSeconds + " с) ")
                .append(Text.literal("[или напишите]").styled(style -> style.withColor(Formatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/mobtalk say ")))));
    }
    /** Распознанная (или написанная) клятва: в чат, решатель смотрит, не отказ ли это, служба идёт дальше; пара готовит свою клятву
     * в ответ на эти слова. */
    void vowHeard(String text) {
        Wedding.Ceremony target = ceremony;
        WeddingScript.Input request = input;
        if (target == null || !target.awaitingVow()) return;
        Wedding.Beat beat = target.current();
        if (beat == null || beat.seq() == judgedVow) return; // Эту клятву уже судят: второй раз не считается.
        judgedVow = beat.seq();
        vowOpen = false;
        owner.chat("Вы: " + text);
        Decider decider = owner.decider();
        Supplier<ConversationEngine> engines = owner.engineSupplier();
        String question = target.said(Wedding.Key.ASK_PLAYER);
        work.execute(() -> {
            boolean consents;
            try { consents = decider.consents(question, text); } catch (RuntimeException failure) { consents = !Wedding.refuses(text); }
            target.vow(text, !consents, System.currentTimeMillis());
            if (!consents) return;
            try {
                ConversationEngine engine = engines.get();
                String vow = engine == null ? "" : engine.weddingVow(request, text);
                if (!vow.isBlank()) target.spouseVow(vow);
            } catch (Exception failure) { LOGGER.info("Mob Talk: клятва пары по словам игрока не пришла ({})", String.valueOf(failure.getMessage())); }
            finally { Thread.interrupted(); }
        });
    }
    /** Клятву не удалось распознать (Whisper упал, тишина): подсказка написать её. Служба ждёт дальше. */
    void vowUnheard() {
        if (ceremony == null || !ceremony.awaitingVow()) return;
        owner.client.inGameHud.getChatHud().addMessage(Text.literal("§e[Mob Talk] Клятву не расслышали. Скажите ещё раз или ")
                .append(Text.literal("[напишите её]").styled(style -> style.withColor(Formatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/mobtalk say ")))));
    }

    // ---- Реплики. ----

    private void voice() {
        long now = System.currentTimeMillis();
        if (line != null) { advance(now); return; }
        if (owner.busyTalking() || owner.speaking()) return; // Игрок говорит или кто-то договаривает: служба ждёт свою очередь.
        Wedding.Line next = ceremony.claim(now);
        if (next == null) { prefetch(ceremony.upcoming(2)); return; }
        line = next;
        segments = split(next.text());
        segment = 0;
        owner.chat("§d" + (next.name().isBlank() ? "" : next.name() + ": ") + "§f" + next.text());
        begin(now);
    }
    private void begin(long now) {
        String text = segments.get(segment);
        audio = prefetched.remove(key(line.voiceId(), text));
        if (audio == null) audio = fetch(line.voiceId(), text);
        audioDeadline = now + VOICE_WAIT_MILLIS; textUntil = -1; playing = false;
        if (segment + 1 < segments.size()) prefetchText(line.voiceId(), segments.get(segment + 1));
        else prefetch(ceremony.upcoming(2));
    }
    private void advance(long now) {
        if (playing) { if (!owner.speaking()) nextSegment(now); return; }
        if (textUntil > 0) { if (now >= textUntil) nextSegment(now); return; }
        if (!audio.isDone() && now < audioDeadline) return;
        SpeechStream stream = null;
        if (audio.isDone()) { try { stream = audio.get(); } catch (Exception failed) { stream = null; } }
        else { audio.cancel(true); closeLater(audio); }
        MobEntity speaker = MobSpeechSound.find(owner.world, line.speaker());
        if (stream != null && speaker != null && owner.client.player.squaredDistanceTo(speaker) <= RANGE * RANGE) {
            owner.playVoice(line.speaker(), stream, RANGE);
            playing = true; voiced++;
        } else {
            if (stream != null) stream.close();
            textUntil = now + Wedding.speakMillis(segments.get(segment)); // Без голоса: столько, сколько заняла бы речь.
        }
    }
    private void nextSegment(long now) {
        segment++;
        if (segment < segments.size()) { begin(now); return; }
        ceremony.lineDone(line.seq(), now);
        line = null; audio = null; playing = false; textUntil = -1;
    }

    private void prefetch(List<Wedding.Line> upcoming) {
        for (Wedding.Line next : upcoming) {
            if (next.key() == Wedding.Key.SPOUSE_VOW || next.text().isBlank()) continue; // Клятва пары ещё может смениться.
            prefetchText(next.voiceId(), split(next.text()).getFirst());
        }
    }
    private void prefetchText(String voiceId, String text) {
        String key = key(voiceId, text);
        if (prefetched.containsKey(key)) return;
        while (prefetched.size() >= 4) { Iterator<Future<SpeechStream>> it = prefetched.values().iterator(); closeLater(it.next()); it.remove(); }
        prefetched.put(key, fetch(voiceId, text));
    }
    private Future<SpeechStream> fetch(String voiceId, String text) {
        Supplier<ConversationEngine> engines = owner.engineSupplier();
        return work.submit(() -> {
            try {
                ConversationEngine engine = engines.get();
                return engine == null ? null : engine.voice(voiceId, text);
            } finally { Thread.interrupted(); }
        });
    }
    private static String key(String voiceId, String text) { return voiceId + "\n" + text; }
    private void closeLater(Future<SpeechStream> future) {
        if (future == null) return;
        work.execute(() -> { try { SpeechStream s = future.get(15, TimeUnit.SECONDS); if (s != null) s.close(); } catch (Exception ignored) { } });
    }

    /** Фразы не длиннее {@link #SEGMENT_CHARS} по границам предложений; пустой текст — одна пустая фраза. */
    static List<String> split(String text) {
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

    /** Мир сменился или свадьба кончилась: недоигранное и заказанное заранее закрывается. */
    void reset() {
        if (audio != null) { if (!audio.isDone()) audio.cancel(true); closeLater(audio); }
        for (Future<SpeechStream> future : prefetched.values()) closeLater(future);
        prefetched.clear();
        ceremony = null; input = null; line = null; audio = null; playing = false; textUntil = -1; promptedVow = -1; judgedVow = -1; vowOpen = false;
    }
}
