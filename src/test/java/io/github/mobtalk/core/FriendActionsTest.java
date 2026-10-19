package io.github.mobtalk.core;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FriendActionsTest {
    private static final List<ActionType> ALL = List.of(ActionType.NONE, ActionType.STOP, ActionType.FOLLOW, ActionType.FLEE,
            ActionType.ATTACK, ActionType.STAY, ActionType.GUARD, ActionType.SPARE);

    private static MobSnapshot snapshot(String type, Map<String, Object> details, List<ActionType> available) {
        return new MobSnapshot(UUID.randomUUID(), type, "Моб", 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 3,
                "", List.of(), List.of(), UUID.randomUUID(), Map.of(), details, Map.of(),
                List.of(new ObservedEntity("e1", UUID.randomUUID(), "minecraft:cow", "Корова", 4, "north", true, Map.of()),
                        new ObservedEntity("e2", UUID.randomUUID(), "minecraft:sheep", "Овца", 6, "south", false, Map.of())),
                available, 0);
    }
    private static ActionIntent validate(String json, MobSnapshot snapshot) {
        return AnswerValidator.validateAction(JsonParser.parseString(json).getAsJsonObject(), snapshot);
    }

    // A waiting dog: STAY always means "here", whatever the model put in target.
    @Test void stayAlwaysTargetsTheSpotItIsOn() {
        MobSnapshot wolf = snapshot("minecraft:wolf", Map.of("fighter", true), ALL);
        for (String target : List.of("here", "", "self", "player", "e1"))
            assertEquals(new ActionIntent(ActionType.STAY, "here"), validate("{\"type\":\"STAY\",\"target\":\"" + target + "\"}", wolf));
        // In a pit waiting is pointless but harmless: it stays allowed.
        MobSnapshot trapped = snapshot("minecraft:wolf", Map.of("fighter", true, "trapped", true), ALL);
        assertEquals(new ActionIntent(ActionType.STAY, "here"), validate("{\"type\":\"STAY\",\"target\":\"here\"}", trapped));
    }

    @Test void guardTakesThePlayerOrThePlaceAndOnlyFromAFighter() {
        MobSnapshot golem = snapshot("minecraft:iron_golem", Map.of("fighter", true), ALL);
        assertEquals(new ActionIntent(ActionType.GUARD, "player"), validate("{\"type\":\"GUARD\",\"target\":\"player\"}", golem));
        assertEquals(new ActionIntent(ActionType.GUARD, "here"), validate("{\"type\":\"GUARD\",\"target\":\"here\"}", golem));
        assertEquals(new ActionIntent(ActionType.GUARD, "player"), validate("{\"type\":\"GUARD\",\"target\":\"\"}", golem));
        assertEquals(ActionIntent.none(), validate("{\"type\":\"GUARD\",\"target\":\"e1\"}", golem));
        MobSnapshot villager = snapshot("minecraft:villager", Map.of("fighter", false), ALL);
        assertEquals(ActionIntent.none(), validate("{\"type\":\"GUARD\",\"target\":\"player\"}", villager));
    }

    @Test void spareNeedsAVisibleTargetUnderATruceOrThePlayerBeforeOne() {
        MobSnapshot inTruce = snapshot("minecraft:zombie", Map.of("fighter", true, "truce", true), ALL);
        assertEquals(new ActionIntent(ActionType.SPARE, "e1"), validate("{\"type\":\"SPARE\",\"target\":\"e1\"}", inTruce));
        assertEquals(ActionIntent.none(), validate("{\"type\":\"SPARE\",\"target\":\"e2\"}", inTruce)); // hidden
        assertEquals(ActionIntent.none(), validate("{\"type\":\"SPARE\",\"target\":\"e9\"}", inTruce)); // invented
        assertEquals(ActionIntent.none(), validate("{\"type\":\"SPARE\",\"target\":\"player\"}", inTruce)); // already safe
        MobSnapshot enemy = snapshot("minecraft:zombie", Map.of("fighter", true, "truce", false), ALL);
        assertEquals(new ActionIntent(ActionType.SPARE, "player"), validate("{\"type\":\"SPARE\",\"target\":\"player\"}", enemy));
        assertEquals(ActionIntent.none(), validate("{\"type\":\"SPARE\",\"target\":\"e1\"}", enemy));
        assertEquals(ActionIntent.none(), validate("{\"type\":\"SPARE\",\"target\":\"here\"}", enemy));
    }

    @Test void friendActionsOutsideTheCapturedPolicyAreDropped() {
        MobSnapshot cow = snapshot("minecraft:cow", Map.of("fighter", false, "truce", true),
                List.of(ActionType.NONE, ActionType.STOP, ActionType.FOLLOW, ActionType.FLEE));
        for (String json : List.of("{\"type\":\"STAY\",\"target\":\"here\"}", "{\"type\":\"GUARD\",\"target\":\"player\"}",
                "{\"type\":\"SPARE\",\"target\":\"e1\"}"))
            assertEquals(ActionIntent.none(), validate(json, cow));
    }

    // Who may be asked what: by relationship, ownership and species.
    @Test void availabilityFollowsRelationshipOwnershipAndSpecies() {
        assertTrue(FriendActions.mayStay(CombatRules.TRUCE, false));
        assertTrue(FriendActions.mayStay(-50, true)); // a tamed wolf waits even after a quarrel
        assertFalse(FriendActions.mayStay(CombatRules.TRUCE - 1, false)); // a stranger, or a hostile without a truce
        assertTrue(FriendActions.mayGuard(true, CombatRules.ALLY, false));
        assertFalse(FriendActions.mayGuard(true, CombatRules.ALLY - 1, false)); // golem below ALLY
        assertTrue(FriendActions.mayGuard(true, 0, true));
        assertFalse(FriendActions.mayGuard(false, 100, true)); // not a fighter
        assertTrue(FriendActions.maySpare(true));
        assertFalse(FriendActions.maySpare(false));
        assertTrue(FriendActions.maySpareTarget(true, false));
        assertTrue(FriendActions.maySpareTarget(false, true));
        assertFalse(FriendActions.maySpareTarget(true, true));
        assertFalse(FriendActions.maySpareTarget(false, false));
    }

    @Test void leaseEndsOnTimeDistanceOrAMissingTarget() {
        FriendActions.Lease stay = new FriendActions.Lease(ActionType.STAY, "here", 1000);
        assertEquals(FriendActions.End.NONE, stay.check(1000 + FriendActions.LEASE_TICKS - 1, 10, true));
        assertEquals(FriendActions.End.EXPIRED, stay.check(1000 + FriendActions.LEASE_TICKS, 10, true));
        assertEquals(FriendActions.End.LEFT, stay.check(2000, FriendActions.LEAVE_RANGE + 0.5, true));
        assertEquals(FriendActions.End.LEFT, stay.check(2000, Double.NaN, true)); // player gone from the world
        FriendActions.Lease spare = new FriendActions.Lease(ActionType.SPARE, UUID.randomUUID().toString(), 1000);
        assertEquals(FriendActions.End.NONE, spare.check(2000, Double.NaN, true)); // the player's whereabouts do not matter
        assertEquals(FriendActions.End.TARGET_GONE, spare.check(2000, Double.NaN, false));
    }

    @Test void repeatingTheRequestRefreshesAndOtherIntentsReplaceOrKeep() {
        FriendActions.Lease guard = new FriendActions.Lease(ActionType.GUARD, "player", 0);
        assertEquals(FriendActions.Change.REFRESH, FriendActions.change(guard, new ActionIntent(ActionType.GUARD, "player")));
        guard.refresh(5000);
        assertEquals(5000 + FriendActions.LEASE_TICKS, guard.expires());
        assertEquals(FriendActions.LEASE_TICKS - 100, guard.remaining(5100));
        assertEquals(FriendActions.Change.KEEP, FriendActions.change(guard, ActionIntent.none()));
        assertEquals(FriendActions.Change.KEEP, FriendActions.change(guard, new ActionIntent(ActionType.SPARE, "e1")));
        assertEquals(FriendActions.Change.REPLACE, FriendActions.change(guard, new ActionIntent(ActionType.GUARD, "here")));
        assertEquals(FriendActions.Change.REPLACE, FriendActions.change(guard, new ActionIntent(ActionType.FOLLOW, "player")));
        assertEquals(FriendActions.Change.REPLACE, FriendActions.change(guard, new ActionIntent(ActionType.STOP, "")));
        assertEquals(FriendActions.Change.KEEP, FriendActions.change(null, new ActionIntent(ActionType.STOP, "")));
    }

    @Test void guardHoldsARingAndBreaksOffLongChases() {
        assertFalse(FriendActions.approach(false, 5)); // inside the ring: hold still
        assertTrue(FriendActions.approach(false, 6.5)); // too far: come back
        assertTrue(FriendActions.approach(true, 4)); // coming back goes all the way to NEAR
        assertFalse(FriendActions.approach(true, 2.9));
        assertTrue(FriendActions.threatens(7.9));
        assertFalse(FriendActions.threatens(8.1));
        assertFalse(FriendActions.breakOff(15));
        assertTrue(FriendActions.breakOff(16.5));
    }

    @Test void schemaAndPromptOfferTheNewActions() {
        MobSnapshot wolf = snapshot("minecraft:wolf", Map.of("fighter", true), ALL);
        String schema = ReplySchema.replyFormat(wolf).toString();
        for (String type : List.of("STAY", "GUARD", "SPARE")) {
            assertTrue(schema.contains("\"" + type + "\""), type);
            assertTrue(PromptTexts.ANSWER_FIELDS.contains(type), type);
        }
        assertTrue(PromptTexts.ANSWER_FIELDS.contains("постою здесь"));
    }
}
