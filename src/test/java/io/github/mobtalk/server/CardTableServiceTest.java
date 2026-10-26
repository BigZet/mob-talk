package io.github.mobtalk.server;

import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.cards.CardTable;
import io.github.mobtalk.net.CardTableActionC2S;
import io.github.mobtalk.net.CardTableOpenS2C;
import io.github.mobtalk.net.CardTableStateS2C;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Игра в 21 на сервере: кто садится за стол и кому отказ, ставка уходит в залог и возвращается выплатой, выход и падение сервера
 * возвращают ставки, удар по жителю закрывает стол, таймер хода говорит «хватит», и ни один изумруд не пропадает и не появляется
 * из ниоткуда: инвентарь + залог + долг + банк жителя постоянны. */
class CardTableServiceTest {
    @TempDir Path directory;
    private ConversationStore memory;
    private MobTalkConfig config;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final FakeWorld world = new FakeWorld();
    private final Recorder out = new Recorder();
    private CardTableService service;
    private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), farmer = UUID.randomUUID();

    @BeforeEach void start() throws Exception {
        config = new MobTalkConfig();
        memory = ConversationStore.open(directory, config);
        service = new CardTableService(world, out, memory, () -> config, now::get);
        world.villagers.put(farmer, villager(farmer, 7, "Фрол", "minecraft:farmer", 0, true));
        world.people.put(alice, new CardTableService.Person(alice, "Alice", "minecraft:overworld", 2, 64, 0, true));
        world.people.put(bob, new CardTableService.Person(bob, "Bob", "minecraft:overworld", -2, 64, 0, true));
        world.emeralds.put(alice, 40);
        world.emeralds.put(bob, 40);
    }
    @AfterEach void stop() throws Exception { ConversationStore.closeWorld(directory); }

    private static CardTableService.Villager villager(UUID id, int entityId, String name, String profession, double x, boolean adult) {
        return new CardTableService.Villager(id, entityId, name, "minecraft:overworld", x, 64, 0, adult, false, false, false, profession, 1);
    }
    private void press(UUID player, String action, int amount) { service.action(player, new CardTableActionC2S(7, action, amount, "")); now.addAndGet(150); }
    private int total(UUID... players) {
        int sum = Math.max(0, memory.cards().balance(farmer));
        for (UUID p : players) sum += world.emeralds.getOrDefault(p, 0) + memory.cards().stakedBy(p) + world.owedPeek(memory, p);
        return sum;
    }

    // A grown farmer sits down: the player gets the table, the purse starts at 8 for a novice, a stake goes into escrow.
    @Test void stakeGoesIntoEscrowAndComesBackAsPayout() {
        assertEquals("", service.open(alice, 7, ""));
        CardTableOpenS2C open = out.opens.get(alice);
        assertNotNull(open);
        assertEquals(0, open.seat());
        assertEquals("OCHKO", open.variant());
        assertEquals(8, memory.cards().balance(farmer));
        int before = total(alice);
        press(alice, "bet", 5);
        assertEquals(35, world.emeralds.get(alice));
        assertEquals(5, memory.cards().stakedBy(alice));
        press(alice, "deal", 0);
        CardTable game = service.game(farmer);
        assertEquals(CardTable.Phase.PLAYERS, game.phase());
        press(alice, "stand", 0);
        assertEquals(CardTable.Phase.SETTLED, game.phase());
        assertEquals(0, memory.cards().stakedBy(alice), "the escrow is released after the settlement");
        assertEquals(before, total(alice), "no emerald appears or disappears");
        assertTrue(out.lastJson(alice).contains("\"SETTLE\""), "the settlement goes to the client as an event");
    }

    // Who will not sit down: a child, a priest (who preaches instead), someone too far away; a sleeping villager.
    @Test void refusalsAreSpokenNotSilent() {
        UUID child = UUID.randomUUID(), priest = UUID.randomUUID(), far = UUID.randomUUID();
        world.villagers.put(child, villager(child, 8, "Ваня", "minecraft:none", 0, false));
        world.villagers.put(priest, villager(priest, 9, "Отец Илья", "minecraft:cleric", 0, true));
        world.villagers.put(far, villager(far, 10, "Дальний", "minecraft:farmer", 30, true));
        assertTrue(service.open(alice, 8, "").contains("Дети"));
        assertTrue(service.open(alice, 9, "").contains("грех"));
        assertTrue(service.open(alice, 10, "").contains("ближе"));
        assertEquals(3, out.chats.get(alice).size(), "every refusal is said to the player");
        config.cards = false;
        assertTrue(service.open(alice, 7, "").contains("выключена"));
    }

    // A player who drops out mid-deal gets his stake back on the next join; a crash turns escrow into a debt.
    @Test void disconnectAndCrashGiveStakesBack() {
        service.open(alice, 7, "");
        press(alice, "bet", 6);
        press(alice, "deal", 0);
        assertEquals(34, world.emeralds.get(alice));
        service.leave(alice);
        assertEquals(0, memory.cards().stakedBy(alice));
        assertFalse(service.playing(farmer), "a table without people closes");
        service.join(alice);
        assertEquals(40, world.emeralds.get(alice), "the stake came back on join");

        service.open(alice, 7, "");
        press(alice, "bet", 3);
        assertEquals(3, memory.cards().stakedBy(alice));
        // The server dies here: a new service on the same world memory finds the stake and owes it.
        CardTableService restarted = new CardTableService(world, out, memory, () -> config, now::get);
        restarted.start();
        assertEquals(0, memory.cards().stakedBy(alice));
        restarted.join(alice);
        assertEquals(40, world.emeralds.get(alice));
    }

    // Hitting the villager closes the table: the attacker's stake stays with the bank, everybody else is refunded.
    @Test void aBlowEndsTheGame() {
        service.open(alice, 7, "");
        service.open(bob, 7, "");
        assertEquals(1, out.opens.get(bob).seat());
        press(alice, "bet", 4);
        press(bob, "bet", 2);
        press(alice, "deal", 0);
        press(bob, "deal", 0);
        assertEquals(CardTable.Phase.PLAYERS, service.game(farmer).phase());
        int purse = service.game(farmer).purse();
        service.villagerHurt(farmer, alice);
        assertFalse(service.playing(farmer));
        assertEquals(36, world.emeralds.get(alice), "the one who hit loses his stake");
        assertEquals(40, world.emeralds.get(bob), "the other gets his back");
        assertEquals(purse + 4, memory.cards().balance(farmer));
        assertTrue(out.closed.get(bob).contains("ударили"));
    }

    // Thirty seconds without a decision count as «хватит»; after the catch window the next deal opens for stakes.
    @Test void turnTimerStandsForTheSlowPlayer() {
        service.open(alice, 7, "");
        press(alice, "bet", 2);
        press(alice, "deal", 0);
        CardTable game = service.game(farmer);
        assertEquals(CardTable.Phase.PLAYERS, game.phase());
        now.addAndGet(config.cardsTurnSeconds * 1000L + 10);
        for (int i = 0; i < 20; i++) service.tick();
        assertEquals(CardTable.Phase.SETTLED, game.phase());
        now.addAndGet(CardTableService.CATCH_MILLIS + 15_000);
        service.tick();
        assertEquals(CardTable.Phase.BETTING, game.phase());
    }

    // The daily loss limit: a player who already lost his day's share may not stake more.
    @Test void dailyLossLimit() {
        config.cardsDailyLoss = 10;
        memory.cards().addNet(alice, world.day(), -8);
        service.open(alice, 7, "");
        press(alice, "bet", 3);
        assertEquals(0, memory.cards().stakedBy(alice));
        assertTrue(out.lastJson(alice).contains("Хватит на сегодня"));
        press(alice, "bet", 2);
        assertEquals(2, memory.cards().stakedBy(alice));
    }

    // Two players, many deals with random presses: whatever happens, emeralds are only moved, never made or lost.
    @Test void emeraldsAreConservedOverManyDeals() {
        SplittableRandom random = new SplittableRandom(21);
        String[] actions = {"hit", "stand", "vabank", "hit", "stand", "catch"};
        for (int run = 0; run < 20; run++) {
            service.deck(run);
            config.cardsVariant = run % 2 == 0 ? "OCHKO" : "BLACKJACK";
            service.open(alice, 7, "");
            service.open(bob, 7, "");
            int before = total(alice, bob);
            for (int deal = 0; deal < 6 && service.playing(farmer); deal++) {
                press(alice, "bet", 1 + random.nextInt(3));
                press(bob, "bet", 1 + random.nextInt(3));
                press(alice, "deal", 0);
                press(bob, "deal", 0);
                for (int step = 0; step < 12; step++) {
                    UUID who = random.nextBoolean() ? alice : bob;
                    press(who, run % 2 == 0 ? actions[random.nextInt(actions.length)] : List.of("hit", "stand", "double", "split", "catch").get(random.nextInt(5)), 0);
                }
                now.addAndGet(config.cardsTurnSeconds * 1000L + CardTableService.CATCH_MILLIS + 100);
                for (int i = 0; i < 40; i++) service.tick();
                now.addAndGet(CardTableService.CATCH_MILLIS + 15_000);
                for (int i = 0; i < 40; i++) service.tick();
                assertEquals(before, total(alice, bob), "run " + run + " deal " + deal);
            }
            if (run % 3 == 0) service.villagerHurt(farmer, null);
            else { press(alice, "leave", 0); press(bob, "leave", 0); }
            service.join(alice); service.join(bob);
            assertEquals(before, total(alice, bob), "run " + run + " after the table");
            assertFalse(service.playing(farmer));
            world.emeralds.put(alice, 40); world.emeralds.put(bob, 40);
            memory.cards().setPurse(farmer, 24);
        }
    }

    // The villager speaks at the key moments; without a model the stand-in lines come at once and go to the table.
    @Test void villagerSpeaksWhenThePlayerSitsDown() {
        service.open(alice, 7, "");
        assertTrue(out.lastJson(alice).matches("(?s).*\"line\":\"[^\"]+\".*"), "a greeting line reaches the table");
    }

    // F7 «Реплики жителя: молчит» — no greeting, but an answer to «Сказать…» still comes.
    @Test void quietVillagerStillAnswersWhenSpokenTo() {
        MobTalkConfig quiet = config.copy(); quiet.tableLines = "never";
        service.personal(player -> quiet);
        service.open(alice, 7, "");
        assertTrue(out.lastJson(alice).contains("\"line\":\"\""), "no greeting");
        service.action(alice, new CardTableActionC2S(7, "say", 0, "Привет!"));
        assertTrue(out.lastJson(alice).matches("(?s).*\"line\":\"[^\"]+\".*"), "the answer reaches the table");
    }

    // «Ловлю!» for nothing costs 8 points of the relationship and leaves a grudge in memory; leaving writes the session down.
    @Test void falseAccusationHurtsAndTheSessionIsRemembered() {
        config.cardsCheating = false;
        service.open(alice, 7, "");
        press(alice, "bet", 2);
        press(alice, "deal", 0);
        press(alice, "stand", 0);
        assertEquals(CardTable.Phase.SETTLED, service.game(farmer).phase());
        int before = memory.relationship(farmer, alice);
        press(alice, "catch", 0);
        assertEquals(before + io.github.mobtalk.core.CardLedger.FALSE_ACCUSE_PENALTY, memory.relationship(farmer, alice));
        press(alice, "leave", 0);
        assertTrue(memory.facts(farmer).stream().anyMatch(f -> f.text().contains("зря обвинил")), "the grudge is remembered");
        assertTrue(memory.facts(farmer).stream().anyMatch(f -> f.text().startsWith("Играл в 21")), "one line per session");
        assertTrue(memory.cards().remembered(farmer, alice).contains("в 21"));
        assertEquals(1, memory.cards().history(10).size());
    }

    // A villager calls a player to play once per game day at most.
    @Test void invitationOncePerDay() {
        assertTrue(memory.cards().invite(alice, 3));
        assertFalse(memory.cards().invite(alice, 3));
        assertTrue(memory.cards().invite(alice, 4));
    }

    // What the model reads about the game: on or off, the purse, the style, the rules.
    @Test void promptFactsDescribeTheGame() {
        service.open(alice, 7, "");
        var facts = service.promptFacts(farmer, alice);
        assertEquals(true, facts.get("cardsEnabled"));
        assertEquals(true, facts.get("withThisPlayer"));
        assertEquals(8, facts.get("purse"));
        assertTrue(facts.get("rules").toString().contains("Очко"));
    }
    private static io.github.mobtalk.core.cards.Card card(io.github.mobtalk.core.cards.Rank rank, io.github.mobtalk.core.cards.Suit suit) { return new io.github.mobtalk.core.cards.Card(rank, suit); }
    private static final List<io.github.mobtalk.core.cards.Card> WIN = List.of(card(io.github.mobtalk.core.cards.Rank.TEN, io.github.mobtalk.core.cards.Suit.HEARTS),
            card(io.github.mobtalk.core.cards.Rank.NINE, io.github.mobtalk.core.cards.Suit.SPADES), card(io.github.mobtalk.core.cards.Rank.NINE, io.github.mobtalk.core.cards.Suit.CLUBS),
            card(io.github.mobtalk.core.cards.Rank.SEVEN, io.github.mobtalk.core.cards.Suit.DIAMONDS), card(io.github.mobtalk.core.cards.Rank.TEN, io.github.mobtalk.core.cards.Suit.SPADES));
    private static final List<io.github.mobtalk.core.cards.Card> LOSE = List.of(card(io.github.mobtalk.core.cards.Rank.TEN, io.github.mobtalk.core.cards.Suit.HEARTS),
            card(io.github.mobtalk.core.cards.Rank.NINE, io.github.mobtalk.core.cards.Suit.SPADES), card(io.github.mobtalk.core.cards.Rank.NINE, io.github.mobtalk.core.cards.Suit.CLUBS),
            card(io.github.mobtalk.core.cards.Rank.EIGHT, io.github.mobtalk.core.cards.Suit.DIAMONDS));
    /** Раздача по подложенной колоде: ставка уже сделана, игрок берёт карту и (если не перебрал) говорит «хватит». */
    private void playStacked() {
        press(alice, "deal", 0);
        press(alice, "hit", 0);
        if (service.game(farmer).phase() == CardTable.Phase.PLAYERS) press(alice, "hit", 0);
        if (service.game(farmer).phase() == CardTable.Phase.PLAYERS) press(alice, "stand", 0);
    }

    // Two diamonds on the stake are worth 16 emeralds: a win gives them back with 16 emeralds on top; a loss leaves them with the villager.
    @Test void itemsOnTheStake() {
        config.cardsPurseStart = 24;
        world.items.put(alice, new HashMap<>(Map.of("minecraft:diamond", 2)));
        world.hand.put(alice, "minecraft:diamond");
        service.stack(WIN);
        service.open(alice, 7, "");
        press(alice, "item", 0);
        assertEquals(0, world.count(alice, "minecraft:diamond"), "the diamonds went onto the table");
        assertEquals(16, service.game(farmer).view().seats().get(0).pendingBet());
        press(alice, "deal", 0);
        press(alice, "hit", 0);
        press(alice, "stand", 0);
        assertEquals(CardTable.Phase.SETTLED, service.game(farmer).phase());
        assertEquals(2, world.count(alice, "minecraft:diamond"), "a win gives the diamonds back");
        assertEquals(56, world.emeralds.get(alice), "and 16 emeralds of winnings");
        press(alice, "leave", 0);
        memory.cards().setPurse(farmer, 24); // После выигрыша у фермера 8: ставку в 16 он бы не принял.
        service.stack(LOSE);
        service.open(alice, 7, "");
        press(alice, "item", 0);
        assertEquals(16, service.game(farmer).view().seats().get(0).pendingBet());
        playStacked();
        assertEquals(0, world.count(alice, "minecraft:diamond"), "a loss leaves them with the villager");
        assertTrue(memory.cards().releaseItems(alice).isEmpty(), "nothing stays in escrow");
    }

    // For interest: no emerald moves, the bank stays, the villager warms up a little.
    @Test void forInterest() {
        service.stack(WIN);
        service.open(alice, 7, "");
        int purse = service.game(farmer).purse();
        press(alice, "interest", 0);
        press(alice, "bet", 3);
        assertEquals(1, service.game(farmer).view().seats().get(0).pendingBet(), "no emeralds on top of a game for interest");
        int relation = memory.relationship(farmer, alice);
        press(alice, "deal", 0);
        press(alice, "hit", 0);
        press(alice, "stand", 0);
        assertEquals(40, world.emeralds.get(alice));
        assertEquals(purse, service.game(farmer).purse());
        assertEquals(relation + 1, memory.relationship(farmer, alice));
    }

    // A favour on the stake: lost, it becomes a card debt — the villager waits for something to be brought.
    @Test void lostFavourBecomesADebt() {
        service.stack(LOSE);
        service.open(alice, 7, "");
        int purse = service.game(farmer).purse();
        press(alice, "favor", 0);
        assertEquals(io.github.mobtalk.core.CardLedger.FAVOR_VALUE, service.game(farmer).view().seats().get(0).pendingBet());
        playStacked();
        assertNotNull(memory.favor(farmer, alice), "the villager asks for his debt");
        assertEquals("Карточный долг", memory.favor(farmer, alice).title());
        assertEquals(purse, service.game(farmer).purse(), "a favour adds no emeralds to the bank");
        assertEquals(40, world.emeralds.get(alice));
    }

    // Items on a table when the server dies come back on the next join.
    @Test void itemsComeBackAfterACrash() {
        world.items.put(alice, new HashMap<>(Map.of("minecraft:gold_ingot", 3)));
        world.hand.put(alice, "minecraft:gold_ingot");
        service.open(alice, 7, "");
        press(alice, "item", 0);
        assertEquals(0, world.count(alice, "minecraft:gold_ingot"));
        CardTableService restarted = new CardTableService(world, out, memory, () -> config, now::get);
        restarted.start();
        restarted.join(alice);
        assertEquals(3, world.count(alice, "minecraft:gold_ingot"));
    }
    // «Раздать» of one player waits while another seated player has not bet yet; after the wait the deal goes without him.
    @Test void dealWaitsForEverySeatedPlayer() {
        service.open(alice, 7, "");
        service.open(bob, 7, "");
        press(alice, "bet", 2);
        press(alice, "deal", 0);
        assertEquals(CardTable.Phase.BETTING, service.game(farmer).phase(), "bob is still thinking");
        press(bob, "bet", 1);
        press(bob, "deal", 0);
        assertEquals(CardTable.Phase.PLAYERS, service.game(farmer).phase(), "both ready: the deal goes at once");
        assertEquals(1, service.game(farmer).hands(1).size(), "bob got his hand");
        now.addAndGet(60_000);
        for (int i = 0; i < 40; i++) service.tick();
        now.addAndGet(60_000);
        for (int i = 0; i < 40; i++) service.tick();
        now.addAndGet(60_000);
        for (int i = 0; i < 40; i++) service.tick();
        assertEquals(CardTable.Phase.BETTING, service.game(farmer).phase());
        press(alice, "bet", 2);
        press(alice, "deal", 0);
        now.addAndGet(CardTableService.DEAL_WAIT_MILLIS + 100);
        service.tick();
        assertEquals(CardTable.Phase.PLAYERS, service.game(farmer).phase(), "after the wait the deal goes without the one who did not bet");
        assertTrue(service.game(farmer).hands(1).isEmpty());
    }
    // ---- Фейки. ----

    static final class FakeWorld implements CardTableService.World {
        final Map<UUID, CardTableService.Villager> villagers = new HashMap<>();
        final Map<UUID, CardTableService.Person> people = new HashMap<>();
        final Map<UUID, Integer> emeralds = new HashMap<>();
        @Override public CardTableService.Villager villager(UUID player, int entityId) {
            return villagers.values().stream().filter(v -> v.entityId() == entityId).findFirst().orElse(null);
        }
        @Override public CardTableService.Villager villager(UUID id) { return villagers.get(id); }
        @Override public CardTableService.Villager nearest(UUID player, double range) { return villagers.values().stream().findFirst().orElse(null); }
        @Override public CardTableService.Person player(UUID id) { return people.get(id); }
        @Override public int emeralds(UUID player) { return emeralds.getOrDefault(player, 0); }
        @Override public boolean take(UUID player, int n) {
            int have = emeralds(player);
            if (have < n) return false;
            emeralds.put(player, have - n);
            return true;
        }
        @Override public void give(UUID player, int n) { emeralds.merge(player, n, Integer::sum); }
        @Override public void hold(UUID villager, UUID player, int ticks) { }
        @Override public long day() { return 3; }
        final Map<UUID, Map<String, Integer>> items = new HashMap<>();
        final Map<UUID, String> hand = new HashMap<>();
        @Override public CardTableService.Held held(UUID player) {
            String id = hand.get(player);
            int count = id == null ? 0 : items.getOrDefault(player, Map.of()).getOrDefault(id, 0);
            return count <= 0 ? null : new CardTableService.Held(id, count, true);
        }
        @Override public boolean takeHeld(UUID player, String item, int count) {
            Map<String, Integer> bag = items.computeIfAbsent(player, k -> new HashMap<>());
            if (!item.equals(hand.get(player)) || bag.getOrDefault(item, 0) < count) return false;
            bag.merge(item, -count, Integer::sum);
            return true;
        }
        @Override public void giveItem(UUID player, String item, int count) { items.computeIfAbsent(player, k -> new HashMap<>()).merge(item, count, Integer::sum); }
        int count(UUID player, String item) { return items.getOrDefault(player, Map.of()).getOrDefault(item, 0); }
        /** Долг, не забирая его. */
        int owedPeek(ConversationStore memory, UUID player) { int owed = memory.cards().takeOwed(player); memory.cards().owe(player, owed); return owed; }
    }

    static final class Recorder implements CardTableService.Outbox {
        final Map<UUID, CardTableOpenS2C> opens = new HashMap<>();
        final Map<UUID, List<CardTableStateS2C>> states = new HashMap<>();
        final Map<UUID, String> closed = new HashMap<>();
        final Map<UUID, List<String>> chats = new HashMap<>();
        @Override public void open(UUID player, CardTableOpenS2C packet) { opens.put(player, packet); }
        @Override public void state(UUID player, CardTableStateS2C packet) {
            states.computeIfAbsent(player, id -> new ArrayList<>()).add(packet);
            if (!packet.closed().isEmpty()) closed.put(player, packet.closed());
        }
        @Override public void chat(UUID player, String line) { chats.computeIfAbsent(player, id -> new ArrayList<>()).add(line); }
        String lastJson(UUID player) {
            StringBuilder all = new StringBuilder();
            for (CardTableStateS2C s : states.getOrDefault(player, List.of())) all.append(s.json());
            return all.toString();
        }
    }
}
