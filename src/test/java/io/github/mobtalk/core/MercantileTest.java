package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Меркантильные горожанки: кошелёк, аппетит, мелочь не берёт, желанное — в восторге, остывание, голодранцу — от ворот поворот,
 * у соперников впереди богатый; характер не выпадает наугад. Память мира, без Minecraft. */
class MercantileTest {
    @TempDir Path directory;
    @AfterEach void close() throws Exception { ConversationStore.closeWorld(directory); }
    private final UUID villager = UUID.randomUUID(), dima = UUID.randomUUID();
    private final MobTalkConfig config = new MobTalkConfig();

    @Test void neverPickedAtRandom() {
        Random random = new Random(1);
        for (int i = 0; i < 2000; i++) assertNotEquals(Persona.MERCANTILE, Persona.pick(random, false));
    }

    @Test void walletCountsMoneyAndHalfTheWornGear() {
        assertEquals(9 + 3 * 4 + 5, Mercantile.wealth(Map.of("minecraft:emerald_block", 1, "minecraft:diamond", 3, "minecraft:emerald", 5, "minecraft:bread", 64), List.of()));
        assertEquals(16, Mercantile.wealth(Map.of(), List.of("minecraft:diamond_chestplate")));
        assertEquals(Mercantile.Purse.BROKE, Mercantile.purse(3, config));
        assertEquals(Mercantile.Purse.MODEST, Mercantile.purse(20, config));
        assertEquals(Mercantile.Purse.RICH, Mercantile.purse(64, config));
        assertEquals(Mercantile.Purse.MODEST, Mercantile.purse(-1, config), "кошелька не видно — не судит");
    }

    @Test void citiesHaveMoreOfThem() {
        int city = 0, village = 0;
        for (int i = 0; i < 2000; i++) {
            UUID id = new UUID(i * 7919L, i * 104729L);
            if (Mercantile.chosen(id, true, config)) city++;
            if (Mercantile.chosen(id, false, config)) village++;
        }
        assertTrue(city > 600 && city < 1000, "около 40% в городе: " + city);
        assertTrue(village > 40 && village < 180, "около 5% в деревне: " + village);
    }

    @Test void cheapGiftIsSpurnedAndAppetiteGrows() {
        Rapport r = new Rapport();
        GestureIntent poppy = Mercantile.gift(new GestureIntent(Gesture.GIFT, Gesture.Outcome.GOOD, 2, "minecraft:poppy"), r, "minecraft:poppy");
        assertEquals(Gesture.Outcome.REFUSED, poppy.outcome());
        assertFalse(poppy.takesItem(), "ромашки не берёт");
        GestureIntent wished = Mercantile.gift(new GestureIntent(Gesture.GIFT, Gesture.Outcome.GOOD, 3, "minecraft:emerald"), r, "minecraft:emerald");
        assertEquals(Gesture.Outcome.BEST, wished.outcome(), "первое желание — изумруд");
        Mercantile.paid(r, "minecraft:diamond_block", config, 1000);
        assertTrue(Mercantile.appetite(r) > 10, "после алмазного блока аппетит вырос: " + Mercantile.appetite(r));
        GestureIntent emerald = Mercantile.gift(new GestureIntent(Gesture.GIFT, Gesture.Outcome.GOOD, 3, "minecraft:emerald"), r, "minecraft:emerald");
        assertEquals(Gesture.Outcome.GOOD, emerald.outcome());
        assertEquals(1, emerald.hearts(), "изумруд теперь — «и это всё?»");
        assertTrue(Mercantile.price(Mercantile.wish(r).item()) >= Mercantile.appetite(r));
        GestureIntent allergy = new GestureIntent(Gesture.GIFT, Gesture.Outcome.REFUSED, -5, "minecraft:cake");
        assertSame(allergy, Mercantile.gift(allergy, r, "minecraft:cake"), "аллергия сильнее жадности");
    }

    @Test void coolsOffWithoutGifts() {
        ConversationStore.Relation rel = new ConversationStore.Relation();
        rel.score = 50; rel.rapport = new Rapport();
        Mercantile.paid(rel.rapport, "minecraft:diamond", config, 0);
        assertEquals(0, Mercantile.cool(rel, config, 10 * 60_000L));
        assertEquals(2 * config.mercCoolHearts, Mercantile.cool(rel, config, 31 * 60_000L));
        assertEquals(50 - 2 * config.mercCoolHearts, rel.score);
        assertTrue(Mercantile.neglected(rel.rapport, config, 31 * 60_000L));
    }

    @Test void brokeSuitorIsShunned() {
        Map<Gesture, GestureIntent> rolled = new LinkedHashMap<>();
        rolled.put(Gesture.KISS, new GestureIntent(Gesture.KISS, Gesture.Outcome.SUCCESS, 5, ""));
        rolled.put(Gesture.DATE, new GestureIntent(Gesture.DATE, Gesture.Outcome.ACCEPTED, 5, "minecraft:poppy"));
        rolled.put(Gesture.PROPOSE, new GestureIntent(Gesture.PROPOSE, Gesture.Outcome.ACCEPTED, 10, "minecraft:gold_ingot"));
        Mercantile.gestures(rolled, Mercantile.Purse.BROKE);
        assertFalse(rolled.get(Gesture.KISS).outcome().warm());
        assertEquals(Gesture.Outcome.REFUSED, rolled.get(Gesture.DATE).outcome());
        assertEquals(Gesture.Outcome.REFUSED, rolled.get(Gesture.PROPOSE).outcome(), "золото — не кольцо");
        Map<Gesture, GestureIntent> rich = new LinkedHashMap<>();
        rich.put(Gesture.FLIRT, new GestureIntent(Gesture.FLIRT, Gesture.Outcome.FAIL, -2, ""));
        Mercantile.gestures(rich, Mercantile.Purse.RICH);
        assertTrue(rich.get(Gesture.FLIRT).outcome().warm(), "богатому флирт заходит");
        assertTrue(Mercantile.rivalBonus(500, 0, config) > Mercantile.rivalBonus(2, 0, config) + 10);
    }

    @Test void promptSpeaksOfThePurseAndTheWish() throws Exception {
        ConversationStore store = ConversationStore.open(directory, config);
        store.observe(SocialLayerTest.villager(villager, dima, 0, false, "minecraft:air"));
        ConversationStore.Profile p = store.state.profiles.get(villager.toString());
        p.social.gender = Gender.FEMALE.name(); p.social.persona = Persona.MERCANTILE.name(); p.social.traits.clear();
        ConversationStore.Profile seen = store.profile(SocialLayerTest.villager(villager, dima, store.revision(villager), false, "minecraft:poppy"));
        String facts = String.join(" ", seen.socialTurn.facts());
        assertTrue(facts.contains("меркантильная") && facts.contains("изумруд"), facts);
        assertEquals(Gesture.Outcome.REFUSED, seen.socialTurn.intent(Gesture.GIFT).outcome(), "мак ей не подарок");
        assertTrue(SocialPrompt.persona(seen).contains("зайка"), "приторная речь");
    }
}
