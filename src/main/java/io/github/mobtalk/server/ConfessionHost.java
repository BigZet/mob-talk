package io.github.mobtalk.server;

import com.google.gson.JsonObject;
import io.github.mobtalk.core.Confession;
import io.github.mobtalk.core.ConfessionScript;
import io.github.mobtalk.core.ConversationEngine;
import io.github.mobtalk.core.DivineLink;
import io.github.mobtalk.core.SpeechStream;
import io.github.mobtalk.core.Wedding;
import io.github.mobtalk.game.GameBridge;
import io.github.mobtalk.game.GameBridge.ConfessionView;
import io.github.mobtalk.game.GameBridge.MobView;
import io.github.mobtalk.net.MobSpeechS2C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Ведущий исповеди на сервере (Б4) — в одиночке и на выделенном одинаково: реплики батюшки по очереди (голосом его, иначе текстом),
 * тайна — личными пакетами только исповеднику, остальным в радиусе тайны лишь «Идёт исповедь», разрешительная молитва слышна всем
 * рядом; слова кающегося перехватываются до разговора ({@link DialogueService#talkText}) и уходят дешёвому классификатору, наставление
 * пишет другой запрос по одному названному, отпущение считает Божественный блок. Ничего из сказанного не идёт в память жителя, слухи
 * и журнал (в журнал — только факт и числа, а слова — лишь с тумблером админа). Любой сбой — модель, бог, озвучка — не ломает исповедь:
 * запасные фразы, сопоставление по словам, текст без голоса. Поток сервера, кроме запросов (пул). */
final class ConfessionHost {
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk");
    static final String KIND = "confession", PUBLIC_KIND = "absolution";
    private static final long VOICE_WAIT_MILLIS = 12_000;
    private final DialogueService service;
    private final GameBridge bridge;

    /** Что ведущий помнит об одной исповеди: звучащую реплику, её звук, открыта ли запись, кого известили. */
    private static final class Track {
        ConfessionView view;
        Confession.Line line;
        CompletableFuture<byte[]> audio;
        long audioDeadline, speakingUntil = -1;
        boolean recording, resultAsked;
        final Set<UUID> told = new HashSet<>();
    }
    private final Map<Confession.Rite, Track> tracks = new LinkedHashMap<>();
    private final Map<UUID, String> huds = new HashMap<>();
    private long ticks;

    ConfessionHost(DialogueService service, GameBridge bridge) { this.service = service; this.bridge = bridge; }

    /** Игрок исповедуется: всё, что он говорит, — исповеди, не разговору (тайна). */
    boolean takes(UUID player) {
        for (Track track : tracks.values()) if (track.view.rite().player.equals(player) && !track.view.rite().over()) return true;
        return false;
    }

    /** Слова кающегося (голос или текст): ему самому — «Вы: …», исповеди — в заход. */
    void heard(UUID player, String text) {
        for (Track track : tracks.values()) {
            Confession.Rite rite = track.view.rite();
            if (!rite.player.equals(player) || rite.over()) continue;
            service.out().speech(player, new MobSpeechS2C(track.view.priestEntityId(), rite.priest, rite.priestName, "", true, 0, track.view.secrecy(),
                    MobSpeechS2C.ANSWER, 0, text, ""));
            if (track.view.open()) for (UUID other : listeners(track.view, false)) if (!other.equals(player))
                service.out().speech(other, new MobSpeechS2C(track.view.priestEntityId(), rite.priest, rite.priestName, "", false, 0, track.view.secrecy(), KIND, 0, text, rite.playerName));
            if (!rite.listening()) { service.out().status(player, "Батюшка ещё говорит — дослушайте", false, ""); return; }
            rite.heard(text, System.currentTimeMillis());
            service.out().status(player, rite.listening() ? "Батюшка слушает" : "Батюшка обдумывает…", false, "");
            return;
        }
    }

    /** Житель ответил на слова исповеди сам (матушка, «подожди, чадо», «ночь на дворе»): только спросившему. */
    void answered(UUID player, MobView mob, String said, GameBridge.ConfessionAnswer answer) {
        service.out().speech(player, new MobSpeechS2C(mob.entityId(), mob.id(), mob.name(), answer.line(), true, 0, 16, MobSpeechS2C.ANSWER, 0, said, ""));
        if (!answer.chat().isBlank()) service.out().chat(player, answer.chat());
        service.out().status(player, DialogueService.READY, false, "");
    }

    void tick() {
        ticks++;
        List<ConfessionView> now = bridge.confessions();
        Set<Confession.Rite> live = new HashSet<>();
        for (ConfessionView view : now) {
            live.add(view.rite());
            Track track = tracks.computeIfAbsent(view.rite(), r -> new Track());
            track.view = view;
            drive(track);
        }
        for (var it = tracks.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (live.contains(entry.getKey())) continue;
            Track gone = entry.getValue();
            if (gone.recording) service.out().screen(gone.view.rite().player, "confession_listen", "{\"open\":false}");
            it.remove();
        }
        if (ticks % 40 == 0) huds();
    }

    private void drive(Track track) {
        ConfessionView view = track.view;
        Confession.Rite rite = view.rite();
        long now = System.currentTimeMillis();
        tell(track);
        Integer round;
        while ((round = rite.takeClassify()) != null) classify(rite, round);
        if (rite.takeCounsel(now)) counsel(rite);
        if (rite.takeAbsolve()) absolve(track);
        if (rite.refused() && !rite.silent() && !track.resultAsked) refuse(track);
        boolean listening = rite.listening();
        if (listening != track.recording) {
            track.recording = listening;
            JsonObject json = new JsonObject();
            json.addProperty("open", listening); json.addProperty("seconds", service.config().confessionSpeakSeconds); json.addProperty("entityId", view.priestEntityId());
            service.out().screen(rite.player, "confession_listen", json.toString());
            if (listening && rite.rounds() == 0) service.out().chat(rite.player, "§7[Mob Talk] Говорите грехи: удерживайте клавишу речи (до " + service.config().confessionSpeakSeconds
                    + " с) или /mobtalk say …; закончив — «всё».");
        }
        voice(track, now);
    }

    /** Игрокам в радиусе тайны — один раз «Идёт исповедь» (без имён и слов). */
    private void tell(Track track) {
        ConfessionView view = track.view;
        if (ticks % 20 != 0) return;
        for (UUID other : listeners(view, false)) {
            if (other.equals(view.rite().player) || !track.told.add(other)) continue;
            service.out().chat(other, view.open() ? "§7[Mob Talk] Идёт исповедь " + view.rite().playerName + " — она слышна всем рядом." : "§7[Mob Talk] Идёт исповедь.");
        }
    }

    // ---- Модель и бог. ----

    private void classify(Confession.Rite rite, int round) {
        String words = rite.roundWords(round);
        service.pool().submit(DialoguePool.Priority.ANSWER, () -> {
            try {
                ConversationEngine engine = service.engineOrNull();
                if (engine == null || rite.deeds.isEmpty()) { rite.classifyFailed(round); return; }
                ConfessionScript.Named named = engine.confessionNamed(words, rite.deeds);
                rite.classified(named.ids(), Confession.mocks(words) ? 0 : named.sincerity());
            } catch (Exception failure) {
                rite.classifyFailed(round);
                LOGGER.info("Mob Talk: классификатор исповеди не ответил, сопоставление по словам ({})", String.valueOf(failure.getMessage()));
            } finally { Thread.interrupted(); }
        });
    }

    private void counsel(Confession.Rite rite) {
        if (rite.mocking()) { rite.counselled("", System.currentTimeMillis()); return; }
        Confession.Penance penance = rite.plannedPenance();
        List<Confession.Deed> named = rite.namedDeeds();
        ConfessionScript.Input input = new ConfessionScript.Input(rite.priestName, rite.place == Confession.Place.TEMPLE, rite.playerName,
                named.stream().map(Confession.Deed::text).toList(), named.stream().anyMatch(d -> d.relapse() || d.recent()), penance, rite.needsReturn());
        service.pool().submit(DialoguePool.Priority.ANSWER, () -> {
            String text = "";
            try {
                ConversationEngine engine = service.engineOrNull();
                if (engine != null) text = engine.confessionCounsel(input);
            } catch (Exception failure) { LOGGER.info("Mob Talk: наставление на исповеди не пришло, запасное ({})", String.valueOf(failure.getMessage())); }
            finally { Thread.interrupted(); }
            String counsel = text;
            service.onServer(() -> rite.counselled(counsel, System.currentTimeMillis()));
        });
    }

    /** Игрок преклонил главу: бог отпускает (или остаток после епитимьи), игра наказывает и благословляет, батюшка читает молитву. */
    private void absolve(Track track) {
        Confession.Rite rite = track.view.rite();
        track.resultAsked = true;
        java.util.Set<Long> named = rite.named();
        int sincerity = rite.sincerity();
        service.pool().submit(DialoguePool.Priority.ANSWER, () -> {
            DivineLink.Outcome outcome;
            try {
                outcome = rite.returning ? DivineLink.absolveRest(rite.player, rite.deeds.stream().map(Confession.Deed::id).toList(), rite.priestName)
                        : DivineLink.absolve(rite.player, named, sincerity, rite.place, rite.priestName);
            } catch (RuntimeException failure) { outcome = DivineLink.Outcome.NONE; }
            DivineLink.Outcome result = outcome;
            service.onServer(() -> {
                bridge.confessionResult(rite, result);
                rite.absolvedNow(result.lighter() || !result.reachable(), System.currentTimeMillis());
                journal(track, result);
            });
        });
    }
    /** Глумление: отпущения нет, но бог узнаёт (засчитывает хулу), и откат тратится. */
    private void refuse(Track track) {
        Confession.Rite rite = track.view.rite();
        track.resultAsked = true;
        java.util.Set<Long> named = rite.named();
        service.pool().submit(DialoguePool.Priority.ANSWER, () -> {
            DivineLink.Outcome outcome;
            try { outcome = DivineLink.absolve(rite.player, named, 0, rite.place, rite.priestName); } catch (RuntimeException failure) { outcome = DivineLink.Outcome.NONE; }
            DivineLink.Outcome result = outcome;
            service.onServer(() -> { bridge.confessionResult(rite, result); journal(track, result); });
        });
    }

    /** История админки: факт и числа; слова — только если админ включил запись текста. */
    private void journal(Track track, DivineLink.Outcome outcome) {
        Confession.Rite rite = track.view.rite();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("priest", rite.priestName); data.put("place", rite.place.id()); data.put("returning", rite.returning);
        data.put("deeds", rite.deeds.size()); data.put("named", rite.named().size()); data.put("sincerity", rite.sincerity());
        data.put("absolved", outcome.absolved().size()); data.put("deferred", outcome.deferred().size()); data.put("concealed", outcome.concealed().size());
        data.put("refused", rite.refused()); data.put("curses", outcome.curses()); data.put("mercyBefore", outcome.before()); data.put("mercyAfter", outcome.after());
        data.put("god", outcome.reachable());
        Confession.Penance penance = rite.penance();
        if (penance != null) data.put("penance", penance.id());
        if (service.config().confessionHistoryText) { data.put("words", rite.words()); data.put("counsel", rite.counsel()); }
        String text = rite.returning ? rite.playerName + " — после епитимьи у " + rite.priestName + ": отпущено " + outcome.absolved().size()
                : rite.playerName + " — исповедь у " + rite.priestName + (rite.place == Confession.Place.TEMPLE ? " (храм)" : " (село)")
                + (rite.refused() ? ": разрешения нет" : ": названо " + rite.named().size() + " из " + rite.deeds.size() + ", отпущено " + outcome.absolved().size()
                + ", до епитимьи " + outcome.deferred().size() + ", скрыто " + outcome.concealed().size() + (penance == null ? "" : ", епитимья «" + penance.title + "»"));
        service.journal().event("confession", rite.player, rite.playerName, rite.priest, rite.priestName, "minecraft:villager", text, data);
        LOGGER.info("Mob Talk: {}", text);
    }

    // ---- Реплики. ----

    private void voice(Track track, long now) {
        Confession.Rite rite = track.view.rite();
        if (track.line == null) {
            Confession.Line next = rite.claim();
            if (next == null) return;
            track.line = next;
            track.audio = next.action() ? CompletableFuture.completedFuture(new byte[0]) : fetch(track.view.voiceId(), next.text());
            track.audioDeadline = now + VOICE_WAIT_MILLIS * Math.max(1, WeddingHost.segments(next.text()).size());
            track.speakingUntil = -1;
            return;
        }
        if (track.speakingUntil > 0) {
            if (now < track.speakingUntil) return;
            rite.lineDone(track.line.seq(), now);
            track.line = null; track.audio = null; track.speakingUntil = -1;
            return;
        }
        if (!track.audio.isDone() && now < track.audioDeadline) return;
        byte[] pcm = track.audio.getNow(new byte[0]);
        int millis = pcm.length / DialogueService.PCM_BYTES_PER_MILLI;
        deliver(track, track.line, pcm, millis);
        track.speakingUntil = now + (track.line.action() ? 1_500 : pcm.length == 0 ? Wedding.speakMillis(track.line.text()) : millis + 400L);
    }
    private CompletableFuture<byte[]> fetch(String voiceId, String text) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        if (text == null || text.isBlank()) { result.complete(new byte[0]); return result; }
        service.pool().submit(DialoguePool.Priority.ANSWER, () -> {
            java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
            try {
                ConversationEngine engine = service.engineOrNull();
                for (String part : WeddingHost.segments(text)) {
                    SpeechStream stream = engine == null ? null : engine.voice(voiceId, part);
                    if (stream == null) { pcm.reset(); break; }
                    try { pcm.writeBytes(stream.awaitAll(TimeUnit.SECONDS.toNanos(60))); } finally { stream.close(); }
                }
            } catch (Exception failure) { pcm.reset(); }
            finally { Thread.interrupted(); result.complete(pcm.toByteArray()); }
        });
        return result;
    }
    /** Реплика исповеднику, а слышная всем (молитва, ремарки при ней) или исповедь стримера при тумблере — всем в радиусе тайны. */
    private void deliver(Track track, Confession.Line line, byte[] pcm, int millis) {
        ConfessionView view = track.view;
        Confession.Rite rite = view.rite();
        boolean everyone = line.audible() || view.open();
        List<UUID> listeners = everyone ? listeners(view, true) : List.of(rite.player);
        if (line.action()) {
            for (UUID listener : listeners) service.out().chat(listener, "§7§o" + line.text());
            return;
        }
        int clipId = pcm.length == 0 ? 0 : service.nextClipId();
        for (UUID listener : listeners) {
            boolean penitent = listener.equals(rite.player);
            service.out().speech(listener, new MobSpeechS2C(view.priestEntityId(), rite.priest, rite.priestName, line.text(), penitent, clipId, view.secrecy(),
                    line.audible() ? PUBLIC_KIND : KIND, millis, "", ""));
            if (!line.translation().isBlank()) service.out().chat(listener, "§8" + line.translation());
        }
        if (clipId == 0) return;
        SpeechService.Clip clip = new SpeechService.Clip(clipId, DialogueService.PCM_FORMAT, pcm, millis);
        for (UUID listener : listeners) service.out().clip(listener, clip);
    }
    /** Игроки в радиусе тайны от батюшки (кающийся — всегда, если {@code withPenitent}). */
    private List<UUID> listeners(ConfessionView view, boolean withPenitent) {
        List<UUID> out = new ArrayList<>();
        try { out.addAll(bridge.playersNear(view.rite().priest, view.dimension(), view.secrecy()).get(2, TimeUnit.SECONDS)); } catch (Exception none) { }
        if (withPenitent && !out.contains(view.rite().player)) out.add(view.rite().player);
        if (!withPenitent) out.remove(view.rite().player);
        return out;
    }

    // ---- Епитимья в HUD. ----

    private void huds() {
        for (DialogueService.Session session : service.sessions()) {
            String hud = bridge.penanceHud(session.player);
            String before = huds.put(session.player, hud);
            if (hud.equals(before)) continue;
            JsonObject json = new JsonObject(); json.addProperty("text", hud);
            service.out().screen(session.player, "penance", json.toString());
        }
    }
    void reset() { tracks.clear(); huds.clear(); }
}
