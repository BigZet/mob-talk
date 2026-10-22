package io.github.mobtalk.server;

import io.github.mobtalk.core.ActionType;
import io.github.mobtalk.core.BargainMeter;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.game.FakeGameBridge;
import io.github.mobtalk.net.MobStateS2C;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Раздел 16.2 плана, трек A: {@code MobStateS2C} уходит только игрокам, знающим моба, не чаще раза в секунду и только при изменении. */
class MobStateSyncTest {
    @TempDir Path directory;
    private ConversationStore memory;
    private FakeGameBridge bridge;
    private RecordingOutbox out;
    private MobStateSync sync;
    private final UUID villager = UUID.randomUUID(), stranger = UUID.randomUUID(), dima = UUID.randomUUID(), far = UUID.randomUUID();
    private final MobStateSync.Tracker tracker = new MobStateSync.Tracker(), farTracker = new MobStateSync.Tracker();

    @BeforeEach void start() throws Exception {
        MobTalkConfig config = new MobTalkConfig();
        memory = ConversationStore.open(directory, config);
        bridge = new FakeGameBridge(memory, config);
        out = new RecordingOutbox();
        sync = new MobStateSync(bridge, out);
        bridge.addMob(villager, 11, "Марфа", "minecraft:villager", 0, 64, 0);
        bridge.addMob(stranger, 12, "Корова", "minecraft:cow", 1, 64, 0);
        bridge.addPlayer(dima, "Дима", 2, 64, 0);
        bridge.addPlayer(far, "Далёкий", 500, 64, 500);
        // Знакомый моб: один ход в памяти.
        MobSnapshot snapshot = new MobSnapshot(villager, "minecraft:villager", "Марфа", 20, 20, "minecraft:overworld", "minecraft:plains", "день", "ясно", 2,
                "", List.of(), List.of(), dima, Map.of(), Map.of(), Map.of(), List.of(), List.of(ActionType.NONE), 0);
        io.github.mobtalk.core.FakeProvider.meet(memory, snapshot, "Привет", "И тебе привет");
    }
    @AfterEach void stop() throws Exception { ConversationStore.closeWorld(directory); }

    @Test void knownMobNearIsSentOnceAndOnlyAgainWhenItChanges() {
        assertTrue(memory.acquainted(villager));
        sync.tick(dima, null, tracker, Map.of());
        assertEquals(1, out.statesTo(dima).size(), "состояние знакомого моба");
        assertEquals(1, out.labelsTo(dima).size(), "подпись знакомого моба");
        MobStateS2C state = out.statesTo(dima).getFirst().packet();
        assertEquals(11, state.mobId()); assertEquals(villager, state.mob());
        assertTrue(state.label().startsWith("отношение"));
        assertFalse(out.labelsTo(dima).getFirst().packet().name().isBlank(), "имя из памяти мира (житель получает данное имя)");
        assertTrue(out.statesTo(dima).stream().noneMatch(s -> s.packet().mob().equals(stranger)), "незнакомец без состояния");
        sync.tick(dima, null, tracker, Map.of());
        assertEquals(1, out.statesTo(dima).size(), "без изменений повтора нет");
        memory.recordDamage(villager, "minecraft:villager", "Марфа", dima, "Дима", System.currentTimeMillis());
        sync.tick(dima, null, tracker, Map.of());
        assertEquals(2, out.statesTo(dima).size(), "отношение изменилось — новый пакет");
        assertTrue(out.statesTo(dima).get(1).packet().relationship() < state.relationship());
    }

    @Test void farPlayerGetsNothingAndSelectedStrangerGetsAStateOnceMet() {
        sync.tick(far, null, farTracker, Map.of());
        assertTrue(out.statesTo(far).isEmpty()); assertTrue(out.labelsTo(far).isEmpty());
        // Собеседник без карточки: подписи нет, состояния нет.
        sync.tick(dima, stranger, tracker, Map.of());
        assertTrue(out.statesTo(dima).stream().noneMatch(s -> s.packet().mob().equals(stranger)));
    }

    @Test void bargainVerdictRidesAlong() {
        BargainMeter.Move move = new BargainMeter.Move(BargainMeter.Kind.OFFER, 8, 10, 5, 7);
        sync.tick(dima, villager, tracker, Map.of(villager, move));
        MobStateS2C state = out.statesTo(dima).getFirst().packet();
        assertFalse(state.bargain().isEmpty(), "ключ вердикта торга");
    }

    @Test void mobLeavingTheCircleGetsAnEmptyLabel() {
        sync.tick(dima, null, tracker, Map.of());
        assertEquals(1, out.labelsTo(dima).size());
        bridge.mobs.put(villager, bridge.mobs.get(villager)); // на месте
        bridge.addMob(villager, 11, "Марфа", "minecraft:villager", 100, 64, 100); // ушёл далеко
        sync.tick(dima, null, tracker, Map.of());
        assertEquals(2, out.labelsTo(dima).size());
        assertEquals("", out.labelsTo(dima).get(1).packet().name(), "пустая подпись снимает старую");
    }
}
