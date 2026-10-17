package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BargainingStoreTest {
    @TempDir Path directory;

    // Closing/reopening the world or talking to somebody else must not reset a granted price/cooldown or share it.
    @Test void priceAndCooldownSurviveReloadWithoutLeakingToAnotherPlayerOrVillager() throws Exception {
        UUID villager = UUID.randomUUID(), player = UUID.randomUUID(), otherVillager = UUID.randomUUID(), otherPlayer = UUID.randomUUID();
        var config = new MobTalkConfig();
        var offer = new Bargaining.Offer(2, "sticks-for-emerald", 32, 32, 64, true);
        var granted = Bargaining.negotiate(null, offer, 60, new BargainIntent(BargainIntent.Type.OFFER, 2, 3), 100).session();
        var store = ConversationStore.open(directory, config);
        store.setBargain(villager, player, granted);
        ConversationStore.closeWorld(directory);
        store = ConversationStore.open(directory, config);
        try {
            assertEquals(granted, store.bargain(villager, player));
            assertNull(store.bargain(otherVillager, player));
            assertNull(store.bargain(villager, otherPlayer));
            // Paying keeps the price: the stored session stays ACCEPTED and the villager may end the talk without revoking it.
            var ended = Bargaining.negotiate(store.bargain(villager, player), offer, 60,
                    new BargainIntent(BargainIntent.Type.DECLINE, 2, 0), 200).session();
            store.setBargain(villager, player, ended);
        } finally { ConversationStore.closeWorld(directory); }
        store = ConversationStore.open(directory, config);
        try {
            var kept = store.bargain(villager, player);
            assertEquals(Bargaining.Status.DECLINED, kept.status());
            assertEquals(29, Bargaining.acceptedPrice(kept, offer, 1_000_000));
            assertFalse(Bargaining.negotiate(kept, offer, 60, new BargainIntent(BargainIntent.Type.OFFER, 2, 5), 300).changed());
            assertEquals(24100, kept.cooldownUntil());
            assertEquals(27, Bargaining.negotiate(kept, offer, 60, new BargainIntent(BargainIntent.Type.OFFER, 2, 5), 24100).session().price());
        } finally { ConversationStore.closeWorld(directory); }
    }
}
