package io.github.mobtalk.game;

import io.github.mobtalk.core.ActionIntent;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.MobTalkConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/** Раздел 16.2 плана: {@link FakeGameBridge} и {@link MobGameBridge} реализуют один интерфейс; фейк отдаёт заданные снимки и итоги. */
class GameBridgeContractTest {
    @TempDir Path dir;

    @Test void realBridgeImplementsTheSameInterface() throws Exception {
        assertTrue(GameBridge.class.isAssignableFrom(MobGameBridge.class));
        assertTrue(GameBridge.class.isAssignableFrom(FakeGameBridge.class));
        // Ни одного типа Minecraft в контракте: серверную логику можно гонять без игры.
        for (Method method : GameBridge.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            assertFalse(method.getReturnType().getName().startsWith("net.minecraft"), method.getName());
            for (Class<?> parameter : method.getParameterTypes()) assertFalse(parameter.getName().startsWith("net.minecraft"), method.getName());
        }
    }

    @Test void fakeReturnsWhatItWasGiven() throws Exception {
        ConversationStore memory = ConversationStore.open(dir, new MobTalkConfig());
        try {
            FakeGameBridge fake = new FakeGameBridge(memory, new MobTalkConfig());
            UUID cow = UUID.randomUUID(), player = UUID.randomUUID();
            MobSnapshot snapshot = new MobSnapshot(cow, "minecraft:cow", "Бурёнка", 10, 10, "minecraft:overworld", "plains", "day", "clear", 2, "", List.of(), List.of());
            fake.snapshots.put(cow, snapshot);
            fake.outcomes.put(cow, new GameBridge.Outcome("Стоит на месте", 5));
            fake.witnesses.put(cow, List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
            assertSame(snapshot, fake.capture(cow, player, "minecraft:overworld").get());
            assertEquals("Стоит на месте", fake.apply(cow, player, "minecraft:overworld", ActionIntent.none(), snapshot, 4, () -> true).get());
            assertEquals(5, fake.applyWithRevision(cow, player, "minecraft:overworld", ActionIntent.none(), null, null, "", null, null, null, snapshot, 4, () -> true).get().revision());
            assertEquals(2, fake.witnesses(cow, player, "minecraft:overworld", 2).get().size());
            assertEquals("Подарок принят", fake.giftFromHand(cow, player).get());
            assertNull(fake.villageAt(player, "minecraft:overworld", 0, 0));
            assertEquals(7, fake.onServer(() -> 7).get());
            assertSame(memory, fake.memory());
            ExecutionException missing = assertThrows(ExecutionException.class, () -> fake.capture(UUID.randomUUID(), player, "minecraft:overworld").get());
            assertTrue(missing.getCause().getMessage().contains("недоступен"));
            fake.failure = "Мир сменился";
            ExecutionException gone = assertThrows(ExecutionException.class, () -> fake.capture(cow, player, "minecraft:overworld").get());
            assertEquals("Мир сменился", gone.getCause().getMessage());
            assertTrue(fake.calls.stream().anyMatch(call -> call.startsWith("apply " + cow)));
        } finally { ConversationStore.closeWorld(dir); }
    }
}
