package io.github.mobtalk.server;

import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.FakeProvider;
import io.github.mobtalk.core.Gender;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.RuleDecider;
import io.github.mobtalk.core.Wedding;
import io.github.mobtalk.core.WeddingScript;
import io.github.mobtalk.game.FakeGameBridge;
import io.github.mobtalk.game.GameBridge;
import io.github.mobtalk.net.MobSpeechS2C;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Ведущий свадьбы на выделенном сервере: реплики службы слышат жених и гость рядом, клятва жениха текстом уходит службе, а не
 * в разговор, и служба идёт дальше. */
class WeddingHostTest {
    @TempDir Path directory;
    private FakeProvider provider;
    private ConversationStore memory;
    private FakeGameBridge bridge;
    private RecordingOutbox out;
    private DialogueService service;
    private final UUID bride = UUID.randomUUID(), cleric = UUID.randomUUID(), groom = UUID.randomUUID(), guest = UUID.randomUUID(), far = UUID.randomUUID();

    @BeforeEach void start() throws Exception {
        provider = new FakeProvider();
        MobTalkConfig config = FakeProvider.config();
        memory = ConversationStore.open(directory, config);
        bridge = new FakeGameBridge(memory, config);
        out = new RecordingOutbox();
        service = new DialogueService(bridge, directory, out, provider::engine, c -> new RuleDecider(), DialogueServiceTest.RefusingBudget::new,
                StubTranscription::new, new DialogueService.Options(true, false, true), 3);
        bridge.addMob(bride, 21, "Валентина", "minecraft:villager", 0, 64, 0);
        bridge.addMob(cleric, 22, "Отец Пётр", "minecraft:villager", 0, 64, 2);
        bridge.addPlayer(groom, "Дима", 1, 64, 0);
        bridge.addPlayer(guest, "Вася", 8, 64, 8);
        bridge.addPlayer(far, "Далёкий", 500, 64, 500);
        service.join(groom, "Дима"); service.join(guest, "Вася"); service.join(far, "Далёкий");
    }
    @AfterEach void stop() throws Exception {
        service.close();
        provider.close();
        ConversationStore.closeWorld(directory);
    }

    private Wedding.Ceremony serviceUnderWay() {
        Wedding.Person spouse = new Wedding.Person(bride, "Валентина", Gender.FEMALE, "minecraft:farmer", "", "LocalDeliveryVoice00002", 80, false, 0);
        Wedding.Person priest = new Wedding.Person(cleric, "Отец Пётр", Gender.MALE, "minecraft:cleric", "", "LocalDeliveryVoice00001", 30, false, 0);
        Wedding.Cast cast = new Wedding.Cast(spouse, null, priest, null, null, List.of());
        Wedding.Names names = new Wedding.Names("Дима", Gender.MALE, "Берёзовка");
        long now = System.currentTimeMillis();
        Wedding.Ceremony ceremony = new Wedding.Ceremony(cast, names, 5_000, 60_000, 0, now);
        ceremony.scriptFailed();
        assertTrue(ceremony.gatherDone(true, now), "служба началась");
        bridge.weddingView = new GameBridge.WeddingView(ceremony, new WeddingScript.Input(cast, names, "", "", 0), groom, "minecraft:overworld");
        return ceremony;
    }
    private List<RecordingOutbox.Speech> wedding(UUID player) {
        return out.speechesTo(player).stream().filter(s -> MobSpeechS2C.WEDDING.equals(s.packet().kind())).toList();
    }

    @Test void guestHearsTheServiceAndTheGroomsVowMovesItOn() throws Exception {
        Wedding.Ceremony ceremony = serviceUnderWay();
        assertTrue(RecordingOutbox.await(service, 30_000, ceremony::awaitingVow), "ведущий дошёл до клятвы: " + ceremony.current() + ", реплик гостю " + wedding(guest).size() + ", клипов " + out.clipsTo(guest).size());
        assertFalse(wedding(guest).isEmpty(), "гость слышит службу");
        assertTrue(wedding(groom).stream().anyMatch(s -> s.packet().toYou() && s.packet().mob().equals(cleric)), "жених слышит ведущего");
        assertTrue(wedding(far).isEmpty(), "далёкий игрок не слышит");
        assertTrue(RecordingOutbox.await(service, 2_000, () -> out.screens.stream().anyMatch(s -> s.player().equals(groom) && s.screen().equals("wedding_vow")
                && s.json().contains("\"open\":true") && s.json().contains("\"entityId\":21"))), "клиенту жениха открыта клятва с невестой");

        service.talkText(groom, 21, "Да, клянусь быть с тобой всегда", false, new byte[0]);
        assertTrue(RecordingOutbox.await(service, 5_000, () -> !ceremony.awaitingVow()), "клятва принята службой");
        assertEquals("Да, клянусь быть с тобой всегда", ceremony.vowText());
        assertFalse(ceremony.jilted());
        assertTrue(RecordingOutbox.await(service, 2_000, () -> wedding(guest).stream().anyMatch(s -> s.packet().heard().contains("клянусь") && s.packet().asker().equals("Дима"))),
                "гость видит клятву жениха");
        Thread.sleep(300);
        assertTrue(out.speechesTo(groom).stream().noneMatch(s -> MobSpeechS2C.ANSWER.equals(s.packet().kind())), "клятва не ушла в обычный разговор");
        assertTrue(RecordingOutbox.await(service, 20_000, () -> ceremony.current() != null && ceremony.current().act() == Wedding.Act.RINGS), "служба дошла до колец");
    }
}
