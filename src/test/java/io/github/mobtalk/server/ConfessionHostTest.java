package io.github.mobtalk.server;

import io.github.mobtalk.core.Confession;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.DivineLink;
import io.github.mobtalk.core.FakeProvider;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.RuleDecider;
import io.github.mobtalk.game.FakeGameBridge;
import io.github.mobtalk.game.GameBridge;
import io.github.mobtalk.net.MobSpeechS2C;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Б4, ведущий исповеди на фейковом мосту, фейковом OpenRouter и фейковом боге: слова кающегося перехватываются до разговора и не
 * попадают в память жителя, реплики батюшки идут только исповеднику, соседу — лишь «Идёт исповедь» и разрешительная молитва, бог
 * получает только названное, «хочу исповедаться» иерею — не разговор. */
class ConfessionHostTest {
    @TempDir Path directory;
    private FakeProvider provider;
    private ConversationStore memory;
    private FakeGameBridge bridge;
    private RecordingOutbox out;
    private DialogueService service;
    private final UUID priest = UUID.randomUUID(), dima = UUID.randomUUID(), vasya = UUID.randomUUID();
    private final List<String> divineRequests = new ArrayList<>();

    @BeforeEach void start() throws Exception {
        provider = new FakeProvider();
        MobTalkConfig config = FakeProvider.config();
        memory = ConversationStore.open(directory, config);
        bridge = new FakeGameBridge(memory, config);
        out = new RecordingOutbox();
        service = new DialogueService(bridge, directory, out, provider::engine, c -> new RuleDecider(), () -> new DialogueServiceTest.RefusingBudget(),
                StubTranscription::new, new DialogueService.Options(false, false), 3);
        bridge.addMob(priest, 21, "отец Сергий", "minecraft:villager", 0, 64, 0);
        bridge.addPlayer(dima, "Дима", 1, 64, 0);
        bridge.addPlayer(vasya, "Вася", -3, 64, 0);
        service.join(dima, "Дима"); service.join(vasya, "Вася");
        DivineLink.set(request -> {
            synchronized (divineRequests) { divineRequests.add(request); }
            if (request.contains("\"absolve\"")) return "{\"ok\":true,\"absolved\":[{\"id\":1,\"kind\":\"blasphemy\",\"text\":\"хулил бога бранью\",\"heavy\":false,"
                    + "\"percent\":75,\"restored\":6,\"remaining\":0}],\"deferred\":[],\"concealed\":[2],\"refused\":false,\"curses\":0,\"mercyBefore\":-23,\"mercyAfter\":-17}";
            return "{\"ok\":false,\"error\":\"не к тесту\"}";
        });
    }
    @AfterEach void stop() throws Exception {
        DivineLink.set(null);
        service.close();
        provider.close();
        ConversationStore.closeWorld(directory);
    }

    private Confession.Rite rite() {
        List<Confession.Deed> deeds = List.of(new Confession.Deed(1, "blasphemy", "хулил бога бранью, сегодня", 8, false, false, false),
                new Confession.Deed(2, "villager_killed", "убил жителя, сегодня", 15, true, false, false));
        return new Confession.Rite(priest, "отец Сергий", dima, "Дима", Confession.Place.VILLAGE, false, deeds, false, 3, true, Set.of(), System.currentTimeMillis());
    }

    @Test void theWordsStaySecretAndOnlyTheNamedReachesTheGod() throws Exception {
        provider.answer = "{\"named\":[1],\"sincerity\":2,\"counsel\":\"Бог милостив, чадо: больше не хули Его, а молись.\"}";
        Confession.Rite rite = rite();
        bridge.confessionViews.add(new GameBridge.ConfessionView(rite, "minecraft:overworld", 21, false, 12, "LocalDeliveryVoice00001"));
        rite.gathered(System.currentTimeMillis());
        assertTrue(RecordingOutbox.await(service, 15_000, () -> rite.listening() && out.screens.stream().anyMatch(s -> s.player().equals(dima)
                && s.screen().equals("confession_listen") && s.json().contains("\"open\":true"))), "увещание прочитано, батюшка слушает, запись открыта");
        assertTrue(service.confession.takes(dima), "слова кающегося — исповеди");
        service.talkText(dima, 21, "Каюсь, батюшка: хулил бога, ругался на него", false, new byte[0]);
        assertTrue(RecordingOutbox.await(service, 10_000, rite::listening), "«нет ли ещё чего на душе?»");
        service.talkText(dima, 21, "Больше ничего", false, new byte[0]);
        assertTrue(RecordingOutbox.await(service, 15_000, rite::wantsBow), "наставление сказано, «преклони главу»");
        assertEquals(Set.of(1L), rite.named(), "названа только хула");
        assertEquals(2, rite.sincerity());
        rite.bowed(System.currentTimeMillis());
        assertTrue(RecordingOutbox.await(service, 15_000, rite::over), "молитва и целование Креста");
        assertEquals(Confession.Rite.Phase.DONE, rite.phase());
        // Бог получил только названное.
        String absolve = divineRequests.stream().filter(r -> r.contains("\"absolve\"")).findFirst().orElse("");
        assertTrue(absolve.contains("\"named\":[1]") && absolve.contains("\"sincerity\":2"), absolve);
        assertEquals(1, bridge.confessionResults.size());
        // Тайна: в память жителя ничего, обычного разговора не было.
        assertNull(memory.card(priest, dima), "исповедь не стала ходом разговора");
        assertTrue(memory.events(priest).stream().noneMatch(e -> e.contains("хулил")), "слов исповеди нет в памяти батюшки");
        assertTrue(provider.chatCalls.get() <= 3, "классификатор на два захода (один) и наставление: " + provider.chatCalls.get());
        // Соседу — ни одной реплики исповеди: только «Идёт исповедь» и разрешительная молитва.
        List<RecordingOutbox.Speech> toVasya = out.speechesTo(vasya);
        assertTrue(toVasya.stream().noneMatch(s -> s.packet().kind().equals(ConfessionHost.KIND)), "сосед не получил субтитров исповеди");
        assertTrue(toVasya.stream().noneMatch(s -> s.packet().heard().contains("хулил")), "сосед не видел слов кающегося");
        assertTrue(toVasya.stream().anyMatch(s -> s.packet().kind().equals(ConfessionHost.PUBLIC_KIND) && s.packet().text().contains("прощаю и разрешаю тя")),
                "разрешительную молитву слышат все рядом");
        assertTrue(out.chats.stream().anyMatch(c -> c.player().equals(vasya) && c.line().contains("Идёт исповедь")));
        // Исповеднику — всё.
        List<RecordingOutbox.Speech> toDima = out.speechesTo(dima);
        assertTrue(toDima.stream().anyMatch(s -> s.packet().text().startsWith("Се, чадо, Христос невидимо стоит")), "увещание по Требнику");
        assertTrue(toDima.stream().anyMatch(s -> s.packet().text().contains("не хули")), "наставление модели");
        assertTrue(toDima.stream().anyMatch(s -> s.packet().text().contains("чадо Дима")), "имя в молитве");
        assertTrue(out.screens.stream().anyMatch(s -> s.player().equals(dima) && s.screen().equals("confession_listen") && s.json().contains("\"open\":true")), "запись открыта");
    }

    @Test void askingAPriestIsNotAConversation() throws Exception {
        bridge.confessionAnswers = said -> Confession.asks(said) ? new GameBridge.ConfessionAnswer(true, Confession.BUSY, "") : GameBridge.ConfessionAnswer.NOT_MINE;
        service.talkText(dima, 21, "Батюшка, хочу исповедаться", false, new byte[0]);
        assertTrue(RecordingOutbox.await(service, 5_000, () -> out.speechesTo(dima).stream().anyMatch(s -> s.packet().text().equals(Confession.BUSY))));
        Thread.sleep(200);
        assertEquals(0, provider.chatCalls.get(), "модель не звали");
        assertNull(memory.card(priest, dima));
        assertTrue(out.speechesTo(vasya).isEmpty(), "ответ иерея только спросившему");
    }

    @Test void mockeryGetsNoAbsolutionButTheGodHearsOfIt() throws Exception {
        provider.answer = "{\"named\":[],\"sincerity\":1,\"counsel\":\"\"}";
        Confession.Rite rite = rite();
        bridge.confessionViews.add(new GameBridge.ConfessionView(rite, "minecraft:overworld", 21, false, 12, "LocalDeliveryVoice00001"));
        rite.gathered(System.currentTimeMillis());
        assertTrue(RecordingOutbox.await(service, 15_000, rite::listening));
        service.talkText(dima, 21, "Бога нет, чушь всё это. Это всё.", false, new byte[0]);
        assertTrue(RecordingOutbox.await(service, 15_000, rite::over));
        assertTrue(rite.refused());
        assertTrue(out.speechesTo(dima).stream().anyMatch(s -> s.packet().text().equals(Confession.REFUSAL)));
        assertTrue(out.speechesTo(dima).stream().noneMatch(s -> s.packet().text().contains("прощаю и разрешаю")), "молитвы нет");
        assertTrue(RecordingOutbox.await(service, 5_000, () -> divineRequests.stream().anyMatch(r -> r.contains("\"absolve\"") && r.contains("\"sincerity\":0"))),
                "бог узнал о глумлении");
    }
}
