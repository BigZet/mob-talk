package io.github.mobtalk.server;

import io.github.mobtalk.net.MobLabelS2C;
import io.github.mobtalk.net.MobSpeechS2C;
import io.github.mobtalk.net.MobStateS2C;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Запись всего, что сервер послал бы игрокам (трек A, тесты): по игроку, с ожиданием условия. */
final class RecordingOutbox implements DialogueService.Outbox {
    record Status(UUID player, String status, boolean busy, String error) { }
    record Speech(UUID player, MobSpeechS2C packet) { }
    record Clip(UUID player, SpeechService.Clip clip) { }
    record Chat(UUID player, String line) { }
    record Screen(UUID player, String screen, String json) { }
    record State(UUID player, MobStateS2C packet) { }
    record Label(UUID player, MobLabelS2C packet) { }
    final List<Status> statuses = new ArrayList<>();
    final List<Speech> speeches = new ArrayList<>();
    final List<Clip> clips = new ArrayList<>();
    final List<Chat> chats = new ArrayList<>();
    final List<Screen> screens = new ArrayList<>();
    final List<State> states = new ArrayList<>();
    final List<Label> labels = new ArrayList<>();

    @Override public synchronized void status(UUID player, String status, boolean busy, String error) { statuses.add(new Status(player, status, busy, error)); }
    @Override public synchronized void speech(UUID player, MobSpeechS2C packet) { speeches.add(new Speech(player, packet)); }
    @Override public synchronized void clip(UUID player, SpeechService.Clip clip) { clips.add(new Clip(player, clip)); }
    @Override public synchronized void chat(UUID player, String line) { chats.add(new Chat(player, line)); }
    @Override public synchronized void screen(UUID player, String screen, String json) { screens.add(new Screen(player, screen, json)); }
    @Override public synchronized void state(UUID player, MobStateS2C packet) { states.add(new State(player, packet)); }
    @Override public synchronized void label(UUID player, MobLabelS2C packet) { labels.add(new Label(player, packet)); }

    synchronized List<Speech> speechesTo(UUID player) { return speeches.stream().filter(s -> s.player.equals(player)).toList(); }
    synchronized List<Speech> speechesOfKind(String kind) { return speeches.stream().filter(s -> s.packet.kind().equals(kind)).toList(); }
    synchronized List<Clip> clipsTo(UUID player) { return clips.stream().filter(c -> c.player.equals(player)).toList(); }
    synchronized List<State> statesTo(UUID player) { return states.stream().filter(s -> s.player.equals(player)).toList(); }
    synchronized List<Label> labelsTo(UUID player) { return labels.stream().filter(s -> s.player.equals(player)).toList(); }
    synchronized String lastError(UUID player) {
        for (int i = statuses.size() - 1; i >= 0; i--) if (statuses.get(i).player.equals(player) && !statuses.get(i).error.isEmpty()) return statuses.get(i).error;
        return "";
    }
    synchronized String lastStatus(UUID player) {
        for (int i = statuses.size() - 1; i >= 0; i--) if (statuses.get(i).player.equals(player)) return statuses.get(i).status;
        return "";
    }

    /** Ждёт условие, прокручивая тик службы (эфир, очереди очевидцев); false по таймауту. */
    static boolean await(DialogueService service, long millis, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            service.tick();
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }
}
