package io.github.divineblock.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Confession's numbers: only the named is forgiven, by the F9 percents (church and village, light and heavy, sincerity, a running
 * punishment), a heavy sin's rest after the penance, a hidden sin marked for the god, mockery refused, a deed confessed once, a
 * relapse heavier and always heavy, the curse rule, old saves and the protocol's answers. */
class AbsolutionTest {
    private final DivineConfig config = new DivineConfig();

    private static Righteousness soul() {
        Righteousness r = new Righteousness();
        r.record(Righteousness.Deed.BLASPHEMY, 10, "");       // light: 8
        r.record(Righteousness.Deed.VILLAGER_KILLED, 10, ""); // heavy: 15
        return r;
    }
    private static Righteousness.Memory of(Righteousness r, Righteousness.Deed deed) {
        return r.memories.stream().filter(m -> m.deed == deed).findFirst().orElseThrow();
    }

    @Test void onlyTheNamedIsForgivenAndTheHiddenIsMarked() {
        Righteousness r = soul();
        Righteousness.Memory hula = of(r, Righteousness.Deed.BLASPHEMY), murder = of(r, Righteousness.Deed.VILLAGER_KILLED);
        assertEquals(-23, r.score, 0.01);
        Absolution.Result result = Absolution.absolve(r, 10, Set.of(hula.id), 1, false, false, config);
        assertEquals(1, result.absolved().size());
        assertEquals(75, result.absolved().getFirst().percent(), "a light sin by a village priest: three quarters");
        assertEquals(6, result.absolved().getFirst().restored(), 0.01);
        assertTrue(hula.settled, "the light sin is settled; its lost quarter of mercy stays lost");
        assertEquals(List.of(murder.id), result.concealed());
        assertTrue(murder.concealed && murder.open(), "the murder kept back stays on the soul, marked");
        assertEquals(-17, r.score, 0.01);
        assertEquals(1, Absolution.open(r, 10, config).size(), "the settled sin is no longer to confess");
        assertTrue(r.conscience(2).contains(murder), "the god holds the hidden sin against him");
    }

    @Test void aHeavySinGoesInPartAndTheRestAfterThePenance() {
        Righteousness r = soul();
        Righteousness.Memory murder = of(r, Righteousness.Deed.VILLAGER_KILLED);
        Absolution.Result temple = Absolution.absolve(r, 10, Set.of(murder.id), 1, true, false, config);
        assertEquals(1, temple.deferred().size());
        assertEquals(50, temple.deferred().getFirst().percent(), "a heavy sin in a church: half now");
        assertEquals(7.5, temple.deferred().getFirst().remaining(), 0.01);
        assertTrue(murder.open() && !murder.settled && murder.awaiting > 0);
        assertTrue(Absolution.open(r, 10, config).stream().noneMatch(o -> o.id() == murder.id), "a deed is confessed once: its rest waits for the penance");
        Absolution.Result again = Absolution.absolve(r, 10, Set.of(murder.id), 2, true, false, config);
        assertTrue(again.absolved().isEmpty() && again.deferred().isEmpty(), "naming it again forgives nothing more");
        Absolution.Result rest = Absolution.absolveRest(r, 11, List.of(murder.id));
        assertEquals(1, rest.absolved().size());
        assertTrue(murder.settled);
        assertEquals(0, murder.effective(), 0.01, "the whole sin forgiven");
        assertTrue(Absolution.absolveRest(r, 11, List.of(murder.id)).absolved().isEmpty(), "the rest only once");
    }

    @Test void villagePriestSincerityAndARunningPunishment() {
        Righteousness r = soul();
        Righteousness.Memory murder = of(r, Righteousness.Deed.VILLAGER_KILLED);
        Absolution.Result sincere = Absolution.absolve(r, 10, Set.of(murder.id), 2, false, false, config);
        assertEquals(65, sincere.deferred().getFirst().percent(), "40 % by a village priest, +25 for sincerity");
        Righteousness s = soul();
        Righteousness.Memory hula = of(s, Righteousness.Deed.BLASPHEMY);
        Absolution.Result punished = Absolution.absolve(s, 10, Set.of(hula.id), 2, true, true, config);
        assertEquals(50, punished.deferred().getFirst().percent(), "under a running punishment no more than half before the penance");
        assertFalse(hula.settled, "its rest waits for the penance, even for a light sin");
        Righteousness t = soul();
        Absolution.Result full = Absolution.absolve(t, 10, Set.of(of(t, Righteousness.Deed.BLASPHEMY).id), 2, true, false, config);
        assertEquals(100, full.absolved().getFirst().percent(), "never more than the whole sin");
    }

    @Test void mockeryForgivesNothingAndCountsAsAnInsult() {
        Righteousness r = soul();
        double before = r.score;
        Absolution.Result result = Absolution.absolve(r, 10, Set.of(of(r, Righteousness.Deed.BLASPHEMY).id), 0, true, false, config);
        assertTrue(result.refused());
        assertTrue(result.absolved().isEmpty() && result.deferred().isEmpty());
        assertTrue(r.score < before, "a small insult to the god");
        assertTrue(r.memories.stream().anyMatch(m -> m.deed == Righteousness.Deed.RUDE_PRAYER && "на исповеди".equals(m.where)));
    }

    @Test void theSameSinSoonAfterIsARelapseHeavierAndAlwaysHeavy() {
        Righteousness r = new Righteousness();
        r.record(Righteousness.Deed.BLASPHEMY, 10, "", 2);
        Righteousness.Memory first = r.memories.getFirst();
        Absolution.absolve(r, 10, Set.of(first.id), 1, true, false, config);
        assertTrue(first.settled);
        double before = r.score;
        r.record(Righteousness.Deed.BLASPHEMY, 11, "", 2);
        Righteousness.Memory relapse = r.memories.getLast();
        assertNotEquals(first.id, relapse.id, "a new memory: the settled one is not reopened");
        assertTrue(relapse.relapse);
        assertEquals(-16, r.score - before, 0.01, "twice the weight");
        assertTrue(Absolution.heavy(relapse, config), "a relapse is always heavy");
        r.record(Righteousness.Deed.BLASPHEMY, 14, "", 2);
        assertFalse(r.memories.getLast().relapse, "after two game days it is a plain sin again");
    }

    @Test void aCurseEndsOnceEverySinOfItsDayIsSettled() {
        Righteousness r = soul();
        Righteousness.Memory hula = of(r, Righteousness.Deed.BLASPHEMY), murder = of(r, Righteousness.Deed.VILLAGER_KILLED);
        assertFalse(Absolution.clearUpTo(r, 10));
        Absolution.absolve(r, 10, Set.of(hula.id, murder.id), 1, false, false, config);
        assertFalse(Absolution.clearUpTo(r, 10), "the murder's rest still waits for the penance");
        r.record(Righteousness.Deed.SLAUGHTER, 12, "");
        Absolution.absolveRest(r, 12, List.of(murder.id));
        assertTrue(Absolution.clearUpTo(r, 10), "a later sin does not hold a curse laid before it");
        assertFalse(Absolution.clearUpTo(r, 12));
    }

    @Test void oldSavesGetIdsAndTheProtocolAnswers() {
        String old = "{\"souls\":{\"00000000-0000-0000-0000-000000000001\":{\"righteousness\":{\"score\":-15,\"memories\":[{\"deed\":\"VILLAGER_KILLED\",\"day\":3,\"count\":1,\"total\":-15}]}}}}";
        GodLedger ledger = GodLedger.fromJson(old);
        Righteousness r = ledger.souls.values().iterator().next().righteousness;
        assertTrue(r.memories.getFirst().id > 0, "an old deed gets its id");
        r.record(Righteousness.Deed.GOLEM_KILLED, 3, "");
        assertNotEquals(r.memories.get(0).id, r.memories.get(1).id);
        JsonObject who = new JsonObject(); who.addProperty("uuid", "x"); who.addProperty("name", "Тест2");
        JsonObject deeds = AdminProtocol.deedsJson(who, r, 3, false, config);
        assertEquals(2, deeds.getAsJsonArray("deeds").size());
        JsonObject murder = deeds.getAsJsonArray("deeds").get(0).getAsJsonObject();
        assertTrue(murder.get("heavy").getAsBoolean());
        assertEquals("villager_killed", murder.get("kind").getAsString());
        Absolution.Result result = Absolution.absolve(r, 3, Set.of(murder.get("id").getAsLong()), 1, false, false, config);
        JsonObject answer = JsonParser.parseString(AdminProtocol.resultJson(result, 1).toString()).getAsJsonObject();
        assertEquals(1, answer.getAsJsonArray("deferred").size());
        assertEquals(1, answer.getAsJsonArray("concealed").size());
        assertEquals(1, answer.get("curses").getAsInt());
        String line = AdminProtocol.confessionLine("отец Сергий", false, result, 1, 1, 0);
        assertTrue(line.contains("отец Сергий") && line.contains("скрыто 1"), line);
        JsonObject request = JsonParser.parseString("{\"named\":[5,\"x\",7]}").getAsJsonObject();
        assertEquals(Set.of(5L, 7L), AdminProtocol.ids(request, "named"));
    }
}
