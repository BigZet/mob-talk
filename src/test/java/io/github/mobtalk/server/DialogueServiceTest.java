package io.github.mobtalk.server;

import io.github.mobtalk.core.ActionType;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.FakeProvider;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.RuleDecider;
import io.github.mobtalk.game.FakeGameBridge;
import io.github.mobtalk.net.MobSpeechS2C;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Раздел 16.2 плана, трек A: две сессии на фейковом мосту и фейковом OpenRouter — ответ собеседнику и соседу, занятый моб, бюджет,
 * отмена на полпути, выход игрока, очевидцы всем в радиусе. */
class DialogueServiceTest {
    @TempDir Path directory;
    private FakeProvider provider;
    private MobTalkConfig config;
    private ConversationStore memory;
    private FakeGameBridge bridge;
    private RecordingOutbox out;
    private DialogueService service;
    private final UUID villager = UUID.randomUUID(), cow = UUID.randomUUID(), dima = UUID.randomUUID(), vasya = UUID.randomUUID(), far = UUID.randomUUID();
    private final RefusingBudget budget = new RefusingBudget();

    static final class RefusingBudget implements PlayerBudget {
        volatile String refuse = "";
        @Override public Verdict allow(UUID player, Kind kind) { return refuse.isEmpty() ? Verdict.ok() : Verdict.no(refuse); }
        @Override public void charge(UUID player, Kind kind, double usd, int units) { }
        @Override public String report() { return ""; }
    }

    @BeforeEach void start() throws Exception {
        provider = new FakeProvider();
        config = FakeProvider.config();
        config.witnessReplies = 2;
        memory = ConversationStore.open(directory, config);
        bridge = new FakeGameBridge(memory, config);
        out = new RecordingOutbox();
        service = new DialogueService(bridge, directory, out, provider::engine, c -> new RuleDecider(), () -> budget, StubTranscription::new,
                new DialogueService.Options(false, false), 3);
        bridge.addMob(villager, 11, "Марфа", "minecraft:villager", 0, 64, 0);
        bridge.addMob(cow, 12, "Корова", "minecraft:cow", 3, 64, 0);
        bridge.addPlayer(dima, "Дима", 2, 64, 0);
        bridge.addPlayer(vasya, "Вася", -2, 64, 0);
        bridge.addPlayer(far, "Далёкий", 500, 64, 500);
        bridge.snapshots.put(villager, snapshot(villager, "minecraft:villager", "Марфа", dima));
        bridge.snapshots.put(cow, snapshot(cow, "minecraft:cow", "Корова", dima));
        service.join(dima, "Дима"); service.join(vasya, "Вася"); service.join(far, "Далёкий");
    }
    @AfterEach void stop() throws Exception {
        service.close();
        provider.close();
        ConversationStore.closeWorld(directory);
    }

    private static MobSnapshot snapshot(UUID mob, String type, String name, UUID player) {
        return new MobSnapshot(mob, type, name, 20, 20, "minecraft:overworld", "minecraft:plains", "день", "ясно", 2,
                "minecraft:stick", List.of(), List.of(), player, Map.of(), Map.of(), Map.of(), List.of(), List.of(ActionType.NONE), 0);
    }
    private boolean answered(UUID player) { return out.speechesTo(player).stream().anyMatch(s -> s.packet().kind().equals(MobSpeechS2C.ANSWER) && !s.packet().text().isBlank()); }

    @Test void replyGoesToTheAskerAndToTheNeighbourButNotFar() throws Exception {
        service.talkText(dima, 11, "Дай скидку, жадина", false, new byte[0]);
        assertTrue(RecordingOutbox.await(service, 10_000, () -> answered(dima) && answered(vasya)), "ответ дошёл обоим");
        MobSpeechS2C toDima = out.speechesTo(dima).getFirst().packet(), toVasya = out.speechesTo(vasya).getFirst().packet();
        assertTrue(toDima.toYou()); assertFalse(toVasya.toYou());
        assertEquals("Дай скидку, жадина", toDima.heard()); assertEquals("Дима", toVasya.asker());
        assertEquals("За такие слова и торговаться не стану.", toDima.text());
        assertEquals(11, toDima.mobId()); assertEquals(villager, toDima.mob());
        assertTrue(toDima.clipId() > 0, "звук обещан");
        assertTrue(RecordingOutbox.await(service, 5_000, () -> !out.clipsTo(dima).isEmpty() && !out.clipsTo(vasya).isEmpty()), "клип дошёл обоим");
        assertEquals(DialogueService.PCM_FORMAT, out.clipsTo(dima).getFirst().clip().format());
        assertArrayEquals(provider.pcm, out.clipsTo(dima).getFirst().clip().data());
        assertTrue(out.speechesTo(far).isEmpty(), "далёкий игрок ничего не слышит");
        assertEquals(1, memory.card(villager, dima).turns().size(), "ход записан в память");
        assertTrue(RecordingOutbox.await(service, 3_000, () -> service.holderOf(villager) == null), "замок моба снят после доставки");
        assertTrue(bridge.called("apply " + villager), "мир применил ответ");
    }

    @Test void secondPlayerToABusyMobIsToldWhoIsTalking() throws Exception {
        provider.chatDelayMillis = 800;
        service.talkText(dima, 11, "Привет", false, new byte[0]);
        assertTrue(RecordingOutbox.await(service, 3_000, () -> dima.equals(service.holderOf(villager))), "моб заперт за Димой");
        service.talkText(vasya, 11, "И мне ответь", false, new byte[0]);
        assertEquals("Марфа занят разговором с Дима", out.lastStatus(vasya));
        assertTrue(RecordingOutbox.await(service, 10_000, () -> answered(dima)));
        assertFalse(answered(vasya) && out.speechesTo(vasya).stream().anyMatch(s -> s.packet().toYou()), "Васе не ответили как собеседнику");
        assertEquals(1, provider.chatCalls.get(), "модель звали один раз");
    }

    @Test void budgetRefusalReachesThePlayerWithoutCallingTheModel() throws Exception {
        budget.refuse = "Лимит на сегодня исчерпан";
        service.talkText(dima, 11, "Привет", false, new byte[0]);
        assertTrue(RecordingOutbox.await(service, 2_000, () -> !out.lastError(dima).isEmpty()));
        assertEquals("Лимит на сегодня исчерпан", out.lastError(dima));
        Thread.sleep(200);
        assertEquals(0, provider.chatCalls.get(), "LLM не вызван");
        assertTrue(out.speechesTo(dima).isEmpty());
    }

    @Test void cancelMidwayDropsTheReply() throws Exception {
        provider.chatDelayMillis = 700;
        service.talkText(dima, 11, "Привет", false, new byte[0]);
        assertTrue(RecordingOutbox.await(service, 3_000, () -> provider.chatCalls.get() == 1), "модель думает");
        service.cancel(dima, "Мир сменился");
        assertEquals("Мир сменился", service.status(dima));
        assertNull(service.holderOf(villager), "замок снят отменой");
        assertFalse(RecordingOutbox.await(service, 1_500, () -> answered(dima)), "ответ не доставлен");
        assertTrue(memory.card(villager, dima) == null || memory.card(villager, dima).turns().isEmpty(), "ничего не записано");
    }

    @Test void leavingPlayerClosesTheSessionAndFreesTheMob() throws Exception {
        provider.chatDelayMillis = 700;
        service.talkText(dima, 11, "Привет", false, new byte[0]);
        assertTrue(RecordingOutbox.await(service, 3_000, () -> provider.chatCalls.get() == 1));
        service.leave(dima);
        assertNull(service.holderOf(villager));
        assertFalse(RecordingOutbox.await(service, 1_500, () -> answered(dima)));
        // Вася тем временем может говорить с тем же жителем.
        provider.chatDelayMillis = 0;
        bridge.snapshots.put(villager, snapshot(villager, "minecraft:villager", "Марфа", vasya));
        service.talkText(vasya, 11, "А мне?", false, new byte[0]);
        assertTrue(RecordingOutbox.await(service, 10_000, () -> answered(vasya)));
    }

    @Test void deadMobFailsWithAPlainReason() throws Exception {
        bridge.mobs.remove(villager);
        service.talkText(dima, 11, "Привет", false, new byte[0]);
        assertTrue(RecordingOutbox.await(service, 2_000, () -> service.status(dima).contains("погиб")), service.status(dima));
        assertEquals(0, provider.chatCalls.get());
    }

    @Test void witnessesHearTheExchangeAndAnswerToEveryoneInRange() throws Exception {
        bridge.witnesses.put(villager, List.of(cow));
        bridge.snapshots.put(cow, snapshot(cow, "minecraft:cow", "Корова", dima));
        service.talkText(dima, 11, "Дай скидку, жадина", false, new byte[0]);
        assertTrue(RecordingOutbox.await(service, 10_000, () -> answered(dima)));
        // Оскорбление — повод для очевидца всегда; его реплика ждёт свободного эфира и приходит обоим игрокам рядом.
        assertTrue(RecordingOutbox.await(service, 15_000, () -> out.speechesOfKind(MobSpeechS2C.WITNESS).size() >= 2), "очевидец заговорил для обоих");
        List<RecordingOutbox.Speech> witness = out.speechesOfKind(MobSpeechS2C.WITNESS);
        assertTrue(witness.stream().anyMatch(s -> s.player().equals(dima) && s.packet().toYou()));
        assertTrue(witness.stream().anyMatch(s -> s.player().equals(vasya) && !s.packet().toYou()));
        assertEquals(cow, witness.getFirst().packet().mob()); assertEquals(12, witness.getFirst().packet().mobId());
        assertTrue(witness.stream().noneMatch(s -> s.player().equals(far)));
        // Порядок: ответ собеседнику раньше очевидца.
        int answerAt = out.speeches.indexOf(out.speechesTo(dima).getFirst());
        int witnessAt = out.speeches.indexOf(witness.getFirst());
        assertTrue(answerAt < witnessAt);
        assertEquals(2, provider.chatCalls.get(), "ответ и очевидец");
    }

    @Test void giftAndScreenRequestsGoThroughTheBridge() throws Exception {
        service.gift(dima, 11);
        assertTrue(RecordingOutbox.await(service, 2_000, () -> out.chats.stream().anyMatch(c -> c.line().contains("Подарок принят"))));
        service.screen(dima, "info", 11);
        assertTrue(RecordingOutbox.await(service, 2_000, () -> out.screens.stream().anyMatch(s -> s.screen().equals("info") && s.json().contains("info"))));
        service.screen(dima, "village", 0);
        assertTrue(RecordingOutbox.await(service, 2_000, () -> out.screens.stream().anyMatch(s -> s.screen().equals("village"))));
        service.action(dima, 11, "{\"type\":\"STOP\",\"target\":\"player\"}");
        assertTrue(RecordingOutbox.await(service, 2_000, () -> bridge.called("apply " + villager + " STOP")));
    }
}
