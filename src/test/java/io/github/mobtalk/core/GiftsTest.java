package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Gifts: values, bonuses, saturation, hearts thresholds, and the two ways a gift arrives (in a reply, thrown). */
class GiftsTest {
    @TempDir Path directory;
    @AfterEach void close() throws Exception { ConversationStore.closeWorld(directory); }
    private static final GiftCatalog.Receiver PLAIN = new GiftCatalog.Receiver(Persona.PEACEFUL, Set.of(), "minecraft:none", false);

    // The spec's example: a diamond again and again gives +8, +5, +3, 0, then "you gave me that already" and −1.
    @Test void saturationWearsTheSameGiftOut() {
        int[] hearts = new int[5]; Gesture.Outcome[] tiers = new Gesture.Outcome[5];
        for (int times = 0; times < 5; times++) { GiftCatalog.Value value = GiftCatalog.weigh("minecraft:diamond", PLAIN, times); hearts[times] = value.hearts(); tiers[times] = value.tier(); }
        assertArrayEquals(new int[]{8, 5, 3, 0, -1}, hearts);
        assertEquals(Gesture.Outcome.BEST, tiers[0]); assertEquals(Gesture.Outcome.ALREADY, tiers[4]);
    }

    @Test void bonusesAndAllergiesDecide() {
        assertEquals(Gesture.Outcome.GOOD, GiftCatalog.weigh("minecraft:bread", PLAIN, 0).tier());
        GiftCatalog.Value farmer = GiftCatalog.weigh("minecraft:wooden_hoe", new GiftCatalog.Receiver(Persona.PEACEFUL, Set.of(), "minecraft:farmer", false), 0);
        assertEquals(25, farmer.before());
        // Cake is sweet (20) for most, but an allergen first: the refusal wins over the best category.
        assertEquals(20, GiftCatalog.weigh("minecraft:cake", PLAIN, 0).before());
        GiftCatalog.Value lactose = GiftCatalog.weigh("minecraft:cake", new GiftCatalog.Receiver(Persona.PEACEFUL, Set.of(VillagerTrait.LACTOSE), "", false), 0);
        assertEquals(Gesture.Outcome.REFUSED, lactose.tier()); assertTrue(lactose.offends()); assertEquals(-9, lactose.hearts());
        GiftCatalog.Value greedy = GiftCatalog.weigh("minecraft:emerald", new GiftCatalog.Receiver(Persona.GREEDY, Set.of(), "", false), 0);
        assertEquals(35, greedy.before());
        GiftCatalog.Value weapon = GiftCatalog.weigh("minecraft:iron_sword", new GiftCatalog.Receiver(Persona.SENSITIVE, Set.of(), "", false), 0);
        assertEquals(Gesture.Outcome.REFUSED, weapon.tier());
        GiftCatalog.Value unknown = GiftCatalog.weigh("minecraft:dirt", PLAIN, 0);
        assertFalse(unknown.known()); assertEquals(0, unknown.hearts()); assertEquals(Gesture.Outcome.REFUSED, unknown.tier());
    }

    @Test void everyGiftIdLooksVanilla() {
        Pattern id = Pattern.compile("minecraft:[a-z0-9_]+");
        for (GiftCatalog.Category category : GiftCatalog.categories()) {
            assertFalse(category.word().isBlank());
            for (String item : category.items().keySet()) assertTrue(id.matcher(item).matches(), item);
            for (String key : category.bonuses().keySet()) assertTrue(key.equals("child") || key.startsWith("profession:minecraft:")
                    || key.startsWith("trait:") && VillagerTrait.of(key.substring(6)) != null || key.startsWith("persona:") && Persona.of(key.substring(8)) != null, key);
        }
    }

    @Test void heartsAreTheRelationshipItself() {
        assertEquals("", Hearts.symbols(39)); assertEquals("♥", Hearts.symbols(40)); assertEquals("♥♥", Hearts.symbols(70)); assertEquals("♥♥♥", Hearts.symbols(90));
        assertEquals("♡", Hearts.symbols(-1));
        assertEquals("можно свататься", Hearts.step(95));
    }

    @Test void giftsForgetOneAtATime() {
        Rapport rapport = new Rapport();
        rapport.gave("minecraft:diamond", 0); rapport.gave("minecraft:diamond", 1000); rapport.gave("minecraft:poppy", 2000);
        assertEquals(2, rapport.given("minecraft:diamond", 3000));
        assertEquals(1, rapport.given("minecraft:diamond", Rapport.GIFT_FORGET_MILLIS + 10));
        assertEquals(0, rapport.given("minecraft:diamond", 2 * Rapport.GIFT_FORGET_MILLIS + 10));
        assertEquals(1, rapport.given("minecraft:poppy", 2 * Rapport.GIFT_FORGET_MILLIS + 10));
    }

    // A thrown diamond lands at once: hearts, memory, the queue; a second and third in a row are worth less.
    @Test void aThrownGiftIsWeighedAndRemembered() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        store.observe(SocialLayerTest.villager(mob, player, 0, false, "minecraft:air"));
        long now = System.currentTimeMillis();
        GestureIntent first = store.social().giftThrown(mob, "minecraft:villager", "Житель", player, "Стив", "minecraft:diamond", "minecraft:none", false, now);
        assertTrue(first.takesItem());
        int afterFirst = store.relationship(mob, player);
        assertEquals(first.hearts(), afterFirst);
        GestureIntent second = store.social().giftThrown(mob, "minecraft:villager", "Житель", player, "Стив", "minecraft:diamond", "minecraft:none", false, now + 1);
        assertTrue(second.hearts() < first.hearts());
        assertTrue(store.events(mob).stream().anyMatch(e -> e.contains("подарил тебе minecraft:diamond")));
        assertNull(store.social().giftThrown(UUID.randomUUID(), "minecraft:cow", "Корова", player, "Стив", "minecraft:wheat", "", false, now));
        assertTrue(SocialLedger.giftCue(first).contains("minecraft:diamond"));
    }

    // Holding an item, the reply's outcomes include the gift: its tier is known before the request.
    @Test void aHeldItemIsOfferedAsAGift() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        ConversationStore.Profile p = store.profile(SocialLayerTest.villager(mob, player, 0, false, "minecraft:poppy"));
        GestureIntent gift = p.socialTurn.intent(Gesture.GIFT);
        assertEquals("minecraft:poppy", gift.item());
        assertTrue(p.socialTurn.schemaValues().contains("GIFT"));
        String block = SocialPrompt.gestures(p);
        assertTrue(block.contains("minecraft:poppy"), block);
        assertFalse(store.profile(SocialLayerTest.villager(mob, player, 0, false, "minecraft:air")).socialTurn.schemaValues().contains("GIFT"));
    }
}
