package io.github.mobtalk.core;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.github.mobtalk.core.BargainMeter.Kind.*;
import static io.github.mobtalk.core.BargainMeter.Verdict.*;
import static org.junit.jupiter.api.Assertions.*;

class BargainMeterTest {
    private static MobState.View state(int mood, int calm, int interest, int trust) { return new MobState.View(mood, calm, interest, trust, ""); }
    private static BargainMeter.Move move(BargainMeter.Kind kind, int price, int current, int minimum, int asked) { return new BargainMeter.Move(kind, price, current, minimum, asked); }

    // The mob's move decides most of it: an offer leans, a refusal is against, silence is unsure — at neutral feelings and standing.
    @Test void theMoveSetsTheTone() {
        MobState.View neutral = MobState.View.NEUTRAL;
        assertEquals(LEANING, BargainMeter.read(move(OFFER, 18, 22, 11, -1), neutral, 0).verdict());
        assertEquals(LEANING, BargainMeter.read(move(ACCEPT, -1, 22, 11, -1), neutral, 0).verdict());
        assertEquals(AGAINST, BargainMeter.read(move(DECLINE, -1, 22, 11, -1), neutral, 0).verdict());
        assertEquals(UNSURE, BargainMeter.read(BargainMeter.Move.NONE, neutral, 0).verdict());
        assertEquals(UNSURE, BargainMeter.read(null, null, 0).verdict());
    }

    // Feelings and standing tilt the verdict: a distrustful or hostile mob offers reluctantly, a trusting friend refuses gently.
    @Test void trustMoodAndRelationshipTiltIt() {
        // 35 for the offer, 15 for going to the floor, trust -10, mood -8, relationship -9 → 23 → unsure (trust 30 still deals).
        assertEquals(UNSURE, BargainMeter.read(move(OFFER, 11, 22, 11, -1), state(10, 50, 50, 30), -36).verdict());
        // -35 for the refusal, trust +20, mood +8, relationship +20, generous +10 → 23: not yet leaning, but no longer against.
        assertEquals(UNSURE, BargainMeter.read(move(DECLINE, -1, 22, 11, -1), state(90, 50, 50, 90), 80).verdict());
        assertEquals(LEANING, BargainMeter.read(BargainMeter.Move.NONE, state(90, 50, 50, 90), 80).verdict(), "a cheerful friend leans before any move");
        assertEquals(AGAINST, BargainMeter.read(BargainMeter.Move.NONE, state(20, 50, 50, 30), -60).verdict(), "a sullen, wary mob is against before any move");
        // Too shaken or distrustful to deal, or hating the player: against, whatever was said.
        assertEquals(AGAINST, BargainMeter.read(move(OFFER, 11, 22, 11, -1), state(50, MobState.WARY_CALM - 1, 50, 50), 50).verdict());
        assertEquals(AGAINST, BargainMeter.read(move(OFFER, 11, 22, 11, -1), state(50, 50, 50, MobState.WARY_TRUST - 1), 50).verdict());
        assertEquals(AGAINST, BargainMeter.read(move(OFFER, 11, 22, 11, -1), MobState.View.NEUTRAL, CombatRules.HATRED).verdict());
    }

    // The player's own number matters: asking under the floor pushes the mob away, being met pulls it closer.
    @Test void thePlayersNumberCounts() {
        MobState.View neutral = MobState.View.NEUTRAL;
        int plain = BargainMeter.read(move(OFFER, 18, 22, 11, -1), neutral, 0).score();
        assertTrue(BargainMeter.read(move(OFFER, 18, 22, 11, 5), neutral, 0).score() < plain, "asked for less than the floor");
        assertTrue(BargainMeter.read(move(OFFER, 18, 22, 11, 18), neutral, 0).score() > plain, "the mob met the ask");
        assertTrue(BargainMeter.read(move(OFFER, 18, 22, 11, 15), neutral, 0).score() > plain, "within reach");
        assertEquals(AGAINST, BargainMeter.read(move(DECLINE, -1, 22, 11, 3), state(50, 50, 50, 40), -10).verdict());
        assertEquals(-1, BargainMeter.asked("дай подешевле")); assertEquals(15, BargainMeter.asked("давай за пятнадцать")); assertEquals(8, BargainMeter.asked("10 много, 8 возьмёшь?"));
    }

    // Exactly at the thresholds: 25 leans, 24 is unsure, -25 is against.
    @Test void thresholdsAreInclusive() {
        assertEquals(LEANING, BargainMeter.Verdict.of(BargainMeter.THRESHOLD)); assertEquals(UNSURE, BargainMeter.Verdict.of(BargainMeter.THRESHOLD - 1));
        assertEquals(AGAINST, BargainMeter.Verdict.of(-BargainMeter.THRESHOLD)); assertEquals(UNSURE, BargainMeter.Verdict.of(1 - BargainMeter.THRESHOLD));
        assertEquals("склоняется", LEANING.word); assertEquals("against", AGAINST.key); assertEquals(0, UNSURE.sign);
    }

    // The move is read off the reply and the snapshot: the open window row, a spoken promise, or the mob's own deal.
    @Test void readsTheMoveFromTheSnapshot() {
        Map<String, Object> open = Map.ofEntries(Map.entry("open", true), Map.entry("selectedIndex", 1), Map.entry("vanillaCount", 32), Map.entry("minimumPrice", 16),
                Map.entry("allowedPrices", List.of(31, 30)), Map.entry("state", Map.of("status", "NONE")));
        MobSnapshot window = villager(Map.of("bargaining", open));
        BargainMeter.Move offered = BargainMeter.move(new BargainIntent(BargainIntent.Type.OFFER, 1, 2), DealIntent.none(), window, "за тридцать отдашь?");
        assertEquals(move(OFFER, 30, 32, 16, 30), offered);
        assertNull(BargainMeter.move(BargainIntent.none(), DealIntent.none(), window, "привет"));
        Map<String, Object> row = SpokenOffer.row(0, "k", Map.of("name", "Морковь", "count", 22), Map.of("count", 0), Map.of("name", "Изумруд", "count", 1), 22, List.of(21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11), false);
        MobSnapshot shut = villager(Map.of("bargaining", Map.of("open", false, "spoken", SpokenOffer.block(List.of(row), null, 0, ""))));
        assertEquals(move(OFFER, 15, 22, 11, -1), BargainMeter.move(new BargainIntent(BargainIntent.Type.OFFER, 0, 7), DealIntent.none(), shut, "скинь"));
        assertEquals(move(DECLINE, -1, 22, 11, 5), BargainMeter.move(new BargainIntent(BargainIntent.Type.DECLINE, 0, 0), DealIntent.none(), shut, "по 5"));
        Map<String, Object> deal = Map.of("id", "milk", "currentPrice", 6, "minPrice", 3, "allowedPrices", List.of(5, 4, 3), "canOffer", true);
        MobSnapshot cow = new MobSnapshot(UUID.randomUUID(), "minecraft:cow", "Корова", 10, 10, "minecraft:overworld", "plains", "day", "clear", 2, "minecraft:stick",
                List.of(), List.of(), UUID.randomUUID(), Map.of(), Map.of("trading", Map.of("deals", List.of(deal))), Map.of(), List.of(), List.of(ActionType.NONE), 0);
        assertEquals(move(OFFER, 4, 6, 3, 4), BargainMeter.move(BargainIntent.none(), new DealIntent(DealIntent.Type.OFFER, "milk", 4), cow, "четыре"));
    }

    private static MobSnapshot villager(Map<String, Object> details) {
        return new MobSnapshot(UUID.randomUUID(), "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "plains", "day", "clear", 2, "minecraft:stick",
                List.of(), List.of(), UUID.randomUUID(), Map.of(), details, Map.of(), List.of(), List.of(ActionType.NONE), 0);
    }
}
