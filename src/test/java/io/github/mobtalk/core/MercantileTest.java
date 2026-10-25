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
        GestureIntent again = Mercantile.gift(new GestureIntent(Gesture.GIFT, Gesture.Outcome.GOOD, 3, Mercantile.wish(r).item()), r, Mercantile.wish(r).item(), 1);
        GestureIntent fresh = Mercantile.gift(new GestureIntent(Gesture.GIFT, Gesture.Outcome.GOOD, 3, Mercantile.wish(r).item()), r, Mercantile.wish(r).item(), 0);
        assertTrue(again.hearts() < fresh.hearts(), "то же самое подряд — уже не желанное: " + again.hearts() + " < " + fresh.hearts());
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

    @Test void dinnerDateBillsAndMayDash() throws Exception {
        assertTrue(Mercantile.invites("Пойдём в ресторан?") && Mercantile.invites("давай поужинаем") && !Mercantile.invites("как дела"));
        assertTrue(Mercantile.pays("Я плачу") && Mercantile.pays("счёт, пожалуйста") && !Mercantile.pays("пойдём"));
        ConversationStore store = ConversationStore.open(directory, config);
        store.observe(SocialLayerTest.villager(villager, dima, 0, false, "minecraft:air"));
        ConversationStore.Profile p = store.state.profiles.get(villager.toString());
        p.social.gender = Gender.FEMALE.name(); p.social.persona = Persona.MERCANTILE.name();
        assertTrue(store.startDinner(villager, dima, "Дима", 2, config).contains("голодран"), "голодранца не берут в ресторан");
        assertEquals(0, store.dinnerBill(villager, dima));
        assertTrue(store.startDinner(villager, dima, "Дима", 30, config).contains("Счёт"));
        int bill = store.dinnerBill(villager, dima);
        assertTrue(bill >= 5, "счёт: " + bill);
        String facts = String.join(" ", store.profile(SocialLayerTest.villager(villager, dima, store.revision(villager), false, "minecraft:air")).socialTurn.facts());
        assertTrue(facts.contains("ресторан") && facts.contains(String.valueOf(bill)), facts);
        int before = p.relations.get(dima.toString()).score;
        assertTrue(store.endDinner(villager, dima, "Дима", true, true).contains("упорхнула"));
        assertEquals(before + Mercantile.DASH_HEARTS, p.relations.get(dima.toString()).score);
        assertEquals(0, store.dinnerBill(villager, dima), "ужин кончился");
        store.startDinner(villager, dima, "Дима", 30, config);
        int was = p.relations.get(dima.toString()).score;
        assertTrue(store.endDinner(villager, dima, "Дима", false, false).contains("ярост"));
        assertEquals(was + Mercantile.STIFFED_HEARTS, p.relations.get(dima.toString()).score);
    }

    private ConversationStore.Profile lady(ConversationStore store, UUID id, String village) {
        store.observe(SocialLayerTest.villager(id, dima, 0, false, "minecraft:air"));
        ConversationStore.Profile p = store.state.profiles.get(id.toString());
        p.social.gender = Gender.FEMALE.name(); p.social.persona = Persona.MERCANTILE.name(); p.social.traits.clear(); p.villageId = village;
        return p;
    }

    @Test void girlfriendsGossipAboutAStingyPlayer() throws Exception {
        ConversationStore store = ConversationStore.open(directory, config);
        ConversationStore.Profile first = lady(store, villager, "v1");
        UUID friend = UUID.randomUUID(), stranger = UUID.randomUUID();
        lady(store, friend, "v1"); lady(store, stranger, "v2");
        store.startDinner(villager, dima, "Дима", 30, config);
        store.endDinner(villager, dima, "Дима", false, false);
        Mercantile.Gossip g = store.state.mercGossip.get("v1|" + dima);
        assertNotNull(g, "сбежал от счёта — подружки знают");
        assertEquals(Mercantile.Talk.STINGY, g.kind());
        assertNull(Mercantile.heard(store.state, first, dima, config, System.currentTimeMillis()), "своя сплетня — не новость");
        ConversationStore.Profile seen = store.profile(SocialLayerTest.villager(friend, dima, store.revision(friend), false, "minecraft:air"));
        String facts = String.join(" ", seen.socialTurn.facts());
        assertTrue(facts.contains("жмот") && facts.contains("Подружка"), facts);
        assertTrue(facts.contains("голодранец"), "кошелёк на 20 изумрудов, но для неё — голодранец по слухам: " + facts);
        String far = String.join(" ", store.profile(SocialLayerTest.villager(stranger, dima, store.revision(stranger), false, "minecraft:air")).socialTurn.facts());
        assertFalse(far.contains("Подружка"), "в другой деревне не слышали");
        assertEquals(Mercantile.Purse.RICH, Mercantile.rumoured(Mercantile.Purse.MODEST, new Mercantile.Gossip("GENEROUS", "", "", "", "", "", 0)));
    }

    @Test void sheBegsAndRemembersTheAnniversary() {
        ConversationStore.Relation rel = new ConversationStore.Relation();
        rel.score = 80; rel.rapport = new Rapport(); rel.rapport.courtship(Courtship.DATING);
        long start = 1_000_000L, hour = config.mercAnniversaryMinutes * 60_000L;
        rel.rapport.datingAt = start;
        String first = Mercantile.beg(rel, config, start + 60_000L);
        assertNotNull(first, "давно не дарил — клянчит");
        assertFalse(first.contains("годовщ"));
        assertNull(Mercantile.beg(rel, config, start + 120_000L), "не чаще раза в " + config.mercBegMinutes + " мин");
        String anniversary = Mercantile.beg(rel, config, start + hour + 1);
        assertTrue(anniversary != null && anniversary.contains("годовщина"), anniversary);
        assertEquals(1, rel.rapport.anniversaries);
        assertFalse(Mercantile.forgotAnniversary(rel, start + hour + 60_000L), "ещё есть время");
        assertTrue(Mercantile.forgotAnniversary(rel, start + hour + Mercantile.ANNIVERSARY_WAIT_MILLIS + 1));
        assertEquals(80 + Mercantile.ANNIVERSARY_HEARTS, rel.score);
        Mercantile.paid(rel.rapport, "minecraft:diamond", config, start + 2 * hour + 1);
        rel.rapport.begAt = -1;
        String next = Mercantile.beg(rel, config, start + 2 * hour + 2);
        assertTrue(next != null && next.contains("годовщина"));
        Mercantile.paid(rel.rapport, "minecraft:diamond", config, start + 2 * hour + 3);
        assertFalse(Mercantile.forgotAnniversary(rel, start + 3 * hour), "подарок к годовщине успел");
        ConversationStore.Relation stranger = new ConversationStore.Relation(); stranger.score = 5; stranger.rapport = new Rapport();
        assertNull(Mercantile.beg(stranger, config, start), "у чужого не клянчит");
    }

    @Test void sheLeavesForARichVillager() throws Exception {
        ConversationStore store = ConversationStore.open(directory, config);
        ConversationStore.Profile p = lady(store, villager, "v1");
        ConversationStore.Relation rel = p.relations.get(dima.toString());
        if (rel.rapport == null) rel.rapport = new Rapport();
        rel.score = 90; rel.rapport.courtship(Courtship.ENGAGED); rel.rapport.ring = "mobtalk:engagement_ring";
        long now = System.currentTimeMillis();
        rel.rapport.datingAt = now - 10 * 60_000L;
        assertFalse(store.mercantileTired(villager, config), "ещё терпит");
        rel.rapport.paidAt = now - (config.mercLeaveMinutes + 1) * 60_000L;
        assertTrue(store.mercantileTired(villager, config));
        UUID merchant = UUID.randomUUID();
        Map<UUID, String> notices = store.mercantileLeave(villager, merchant, "Купец Пётр", "мастер-оружейник", config);
        assertTrue(notices.get(dima).contains("мастер-оружейник") && notices.get(dima).contains("Кольцо"), String.valueOf(notices));
        assertEquals(Courtship.NONE, rel.rapport.courtship());
        assertEquals(merchant, store.spouseOf(villager), "вышла за богатого");
        assertEquals(90 + Mercantile.LEFT_HEARTS, rel.score);
        assertEquals(Mercantile.Talk.STINGY, store.state.mercGossip.get("v1|" + dima).kind());
        assertFalse(store.mercantileTired(villager, config), "замужем — больше не уходит");
        assertTrue(store.mercantileLeave(villager, UUID.randomUUID(), "Другой", "", config).isEmpty());
    }
}
