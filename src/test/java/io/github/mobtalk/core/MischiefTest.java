package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MischiefTest {
    @TempDir Path directory;

    // Every misdeed costs less than a blow seen (10); the work block is the worst of them; the text names the block when known.
    @Test void penaltiesStayBelowABlow() {
        for (Mischief.Kind kind : Mischief.Kind.values()) assertTrue(kind.penalty < 0 && kind.penalty > -10, kind + " " + kind.penalty);
        assertEquals(-3, Mischief.Kind.CONTAINER.penalty); assertEquals(-3, Mischief.Kind.CROP.penalty);
        assertEquals(-8, Mischief.Kind.WORKSTATION.penalty); assertEquals(-5, Mischief.Kind.DOOR.penalty); assertEquals(-5, Mischief.Kind.BED.penalty);
        assertEquals("сломал рабочий блок жителя (стол картографа)", Mischief.seen(Mischief.Kind.WORKSTATION, "стол картографа"));
        assertEquals("открыл чужой сундук в деревне", Mischief.seen(Mischief.Kind.CONTAINER, ""));
    }

    // One kind counts once per twenty seconds per player; another kind or another player counts at once.
    @Test void countsOncePerCooldownPerKindAndPlayer() {
        Mischief.Ledger ledger = new Mischief.Ledger();
        UUID steve = UUID.randomUUID(), alex = UUID.randomUUID();
        assertTrue(ledger.count(steve, Mischief.Kind.CONTAINER, 1000));
        assertFalse(ledger.count(steve, Mischief.Kind.CONTAINER, 1000 + Mischief.COOLDOWN_MILLIS - 1), "the same chest opened again");
        assertTrue(ledger.count(steve, Mischief.Kind.CROP, 1500), "another kind counts");
        assertTrue(ledger.count(alex, Mischief.Kind.CONTAINER, 2000), "another player counts");
        assertTrue(ledger.count(steve, Mischief.Kind.CONTAINER, 1000 + Mischief.COOLDOWN_MILLIS));
        assertTrue(ledger.count(steve, Mischief.Kind.BED, 500), "a clock running backwards counts as no time");
        assertFalse(ledger.count(steve, Mischief.Kind.BED, 400));
    }

    // The player's own blocks are theirs: the one they placed, and the other half of a door or bed placed through it.
    @Test void ownBlocksAreExempt() {
        Mischief.Ledger ledger = new Mischief.Ledger();
        ledger.placed("minecraft:overworld", 10, 64, 10);
        assertTrue(ledger.ownBlock("minecraft:overworld", 10, 64, 10));
        assertTrue(ledger.ownBlock("minecraft:overworld", 10, 65, 10), "the door's upper half");
        assertTrue(ledger.ownBlock("minecraft:overworld", 11, 64, 10), "the bed's head");
        assertFalse(ledger.ownBlock("minecraft:overworld", 12, 64, 10));
        assertFalse(ledger.ownBlock("minecraft:the_nether", 10, 64, 10), "another dimension");
        ledger.clear();
        assertFalse(ledger.ownBlock("minecraft:overworld", 10, 64, 10));
    }

    // A villager that sees a misdeed remembers it as a stranger too, trusts less and is on edge; the memory names the deed.
    @Test void aVillagerWitnessGetsAProfileAndGrowsSuspicious() throws Exception {
        UUID villager = UUID.randomUUID(), player = UUID.randomUUID(), cow = UUID.randomUUID();
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        try {
            assertFalse(store.known(villager));
            long now = System.currentTimeMillis(); // The card reads the state at the real clock: the shift must be fresh.
            store.recordWitnessed(villager, "minecraft:villager", "Житель", player, "Стив", Mischief.seen(Mischief.Kind.CONTAINER, ""), Mischief.Kind.CONTAINER.penalty, MobState.Event.MISCHIEF, now);
            assertTrue(store.known(villager), "a village remembers even a stranger's misdeed");
            MobCard card = store.card(villager, player);
            assertEquals(-3, card.relationship());
            assertTrue(card.events().stream().anyMatch(event -> event.equals("Видел, как игрок Стив открыл чужой сундук в деревне; отношение -3")), card.events().toString());
            // Against the villager's own rest point (its temperament and the new -3 standing): suspicion, not panic.
            MobState.Baseline rest = MobState.Baseline.of(card.calm(), card.confidence(), card.curiosity(), card.sociability(), -3);
            assertTrue(rest.trust() - card.state().trust() >= 14 && rest.calm() - card.state().calm() >= 9 && card.state().interest() - rest.interest() >= 9,
                    card.state() + " vs " + rest);
            // The old entry point still notices nothing for a stranger of another species.
            store.recordWitnessed(cow, player, "Стив", "ударил Зорьку (корова)", -10, 2000);
            assertFalse(store.known(cow));
            // A kept spoken bargain warms the villager and is remembered.
            store.recordTradeDone(villager, player, "Стив", "1× Изумруд за 15× Морковь", now + 70_000);
            assertEquals(2, store.card(villager, player).relationship());
            assertTrue(store.card(villager, player).events().stream().anyMatch(event -> event.contains("купил по договорённости 1× Изумруд за 15× Морковь")));
        } finally { ConversationStore.closeWorld(directory); }
    }

    // The new event is suspicion, not fear: milder than a blow seen on every axis but interest.
    @Test void mischiefIsMilderThanAWitnessedBlow() {
        MobState.Baseline rest = MobState.Baseline.of(50, 50, 50, 50, 0);
        MobState chest = new MobState(), blow = new MobState();
        chest.shift(MobState.Event.MISCHIEF, rest, 0); blow.shift(MobState.Event.WITNESSED_HIT, rest, 0);
        MobState.View seen = chest.view(rest, 0), hit = blow.view(rest, 0);
        assertTrue(seen.trust() < rest.trust() && seen.trust() >= hit.trust());
        assertTrue(seen.calm() < rest.calm() && seen.calm() > hit.calm());
        assertTrue(seen.interest() > hit.interest());
        assertEquals(List.of(), List.of()); // keeps the import honest when the assertions above change
        assertEquals(Map.of(), Map.of());
    }
}
