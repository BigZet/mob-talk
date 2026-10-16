package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BargainingStoreTest {
    @TempDir Path directory;

    // Closing/reopening the world or talking to somebody else must not reset a quote/cooldown or share it.
    @Test void quoteAndCooldownSurviveReloadWithoutLeakingToAnotherPlayerOrVillager() throws Exception {
        UUID villager = UUID.randomUUID(), player = UUID.randomUUID(), otherVillager = UUID.randomUUID(), otherPlayer = UUID.randomUUID();
        var config = new MobTalkConfig();
        var offer = new Bargaining.Offer(2, "sticks-for-emerald", 32, 32, 64, true);
        var quote = Bargaining.negotiate(null, offer, 60, new BargainIntent(BargainIntent.Type.OFFER, 2, 3), 100).session();
        var store = ConversationStore.open(directory, config);
        store.setBargain(villager, player, quote);
        ConversationStore.closeWorld(directory);
        store = ConversationStore.open(directory, config);
        try {
            assertEquals(quote, store.bargain(villager, player));
            assertNull(store.bargain(otherVillager, player));
            assertNull(store.bargain(villager, otherPlayer));
            var accepted = Bargaining.negotiate(store.bargain(villager, player), offer, 60,
                    new BargainIntent(BargainIntent.Type.ACCEPT, 2, 0), 200).session();
            store.setBargain(villager, player, Bargaining.consumed(accepted));
        } finally { ConversationStore.closeWorld(directory); }
        store = ConversationStore.open(directory, config);
        try {
            var used = store.bargain(villager, player);
            assertEquals(Bargaining.Status.CONSUMED, used.status());
            assertFalse(Bargaining.negotiate(used, offer, 60, new BargainIntent(BargainIntent.Type.OFFER, 2, 3), 300).changed());
            assertEquals(24100, used.cooldownUntil());
        } finally { ConversationStore.closeWorld(directory); }
    }
}
