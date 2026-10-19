package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The village's view of the player: ranks from the residents' relationships and the rumours, and what each rank means. */
class VillageStandingTest {
    @TempDir Path directory;
    @AfterEach void close() throws Exception { ConversationStore.closeWorld(directory); }

    private static VillageStanding.Rank rank(List<Integer> scores, int residents) { return VillageStanding.of(scores, residents, VillageRumors.Reputation.NONE).rank(); }

    @Test void ranksFollowPointsAndPeople() {
        assertEquals(VillageStanding.Rank.STRANGER, rank(List.of(), 10));
        assertEquals(VillageStanding.Rank.GUEST, rank(List.of(5), 10));
        assertEquals(VillageStanding.Rank.FRIEND, rank(List.of(40, 40, 45), 10));
        assertEquals(VillageStanding.Rank.GUEST, rank(List.of(130), 10), "one friend is not the village");
        assertEquals(VillageStanding.Rank.GUARD, rank(List.of(60, 60, 60, 60, 70), 10));
        assertEquals(VillageStanding.Rank.ELDER, rank(List.of(80, 80, 80, 80, 80, 80, 80, 80), 12));
        assertEquals(VillageStanding.Rank.GUARD, rank(List.of(80, 80, 80, 80, 80, 80, 80, 80), 8), "a hamlet has no head");
        assertEquals(VillageStanding.Rank.SUSPECT, rank(List.of(-20), 10));
        assertEquals(VillageStanding.Rank.EXILE, rank(List.of(-80, -40), 10));
        assertEquals(VillageStanding.Rank.EXILE, rank(List.of(-80, -80, 10), 10), "a sum of −150 exiles");
    }

    @Test void rumoursTipTheBalance() {
        VillageStanding.View quiet = VillageStanding.of(List.of(40, 40, 30), 10, VillageRumors.Reputation.NONE);
        VillageStanding.View praised = VillageStanding.of(List.of(40, 40, 30), 10, new VillageRumors.Reputation(1, 6));
        VillageStanding.View cursed = VillageStanding.of(List.of(10), 10, new VillageRumors.Reputation(-1, 8));
        assertEquals(VillageStanding.Rank.GUEST, quiet.rank());
        assertEquals(VillageStanding.Rank.FRIEND, praised.rank());
        assertEquals(VillageStanding.Rank.SUSPECT, cursed.rank());
        assertEquals(-70, cursed.points());
        assertEquals(10 - VillageStanding.RUMOR_CAP, VillageStanding.of(List.of(10), 10, new VillageRumors.Reputation(-1, 20)).points(), "rumours are capped");
    }

    @Test void consequencesByRank() {
        VillageStanding.View exile = VillageStanding.of(List.of(-90, -90), 5, VillageRumors.Reputation.NONE);
        assertTrue(exile.refusesTrade() && exile.golemAlert() && exile.banished() && exile.warns());
        VillageStanding.View suspect = VillageStanding.of(List.of(-20), 5, VillageRumors.Reputation.NONE);
        assertFalse(suspect.refusesTrade()); assertTrue(suspect.golemAlert() && suspect.warns()); assertFalse(suspect.banished());
        VillageStanding.View guest = VillageStanding.of(List.of(20), 5, VillageRumors.Reputation.NONE);
        assertFalse(guest.refusesTrade() || guest.golemAlert() || guest.warns());
        assertTrue(VillageStanding.describe(exile).contains("убирается"));
        assertEquals("", VillageStanding.describe(VillageStanding.View.NONE));
    }

    // The store sums the residents of one village only, and the villager's prompt says how the village sees the player.
    @Test void theStoreAggregatesOneVillage() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        UUID player = UUID.randomUUID();
        ConversationStore.Village village = store.createVillage("minecraft:overworld", 0, 0, "Ольховка", "У реки.");
        UUID[] residents = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        for (UUID id : residents) {
            store.observe(SocialLayerTest.villager(id, player, 0, false, "minecraft:air"));
            store.setBackstory(id, "", "Ты живёшь в Ольховке.", "", "", village.id());
            store.recordDamage(id, "minecraft:villager", "Житель", player, "Стив", 1000);
        }
        UUID outsider = UUID.randomUUID();
        store.observe(SocialLayerTest.villager(outsider, player, 0, false, "minecraft:air"));
        VillageStanding.View view = store.social().standing(village.id(), player, System.currentTimeMillis());
        assertEquals(3, view.residents()); assertEquals(3, view.acquainted());
        assertEquals(VillageStanding.Rank.EXILE, view.rank());
        assertEquals(VillageStanding.Rank.EXILE, store.social().standingAt("minecraft:overworld", 20, 20, player, System.currentTimeMillis()).rank());
        assertEquals(VillageStanding.Rank.STRANGER, store.social().standingAt("minecraft:overworld", 5000, 5000, player, System.currentTimeMillis()).rank());
        ConversationStore.Profile p = store.profile(SocialLayerTest.villager(residents[0], player, store.revision(residents[0]), false, "minecraft:air"));
        String prompt = new MobPrompt(new MobTalkConfig()).system(SpeciesCatalog.get("minecraft:villager"), p, SocialLayerTest.villager(residents[0], player, 0, false, "minecraft:air"));
        assertTrue(prompt.contains("деревня его изгнала"), prompt);
    }
}
