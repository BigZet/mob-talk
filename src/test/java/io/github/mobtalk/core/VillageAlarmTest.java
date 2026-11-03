package io.github.mobtalk.core;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The village's alarm: levels, the players it is against, how it ends and what the residents remember. */
class VillageAlarmTest {
    private static final VillageAlarm.Rules RULES = new VillageAlarm.Rules(1200, 3600, 5);
    private static final String VILLAGE = "minecraft:overworld|minecraft:village_plains@1,2";

    @Test void monstersRaiseAThreatAndACrowdAnAttack() {
        VillageAlarm alarms = new VillageAlarm();
        assertEquals(VillageAlarm.Change.NONE, alarms.monsters(VILLAGE, 0, "", 0, RULES), "nobody seen, nothing raised");
        assertNull(alarms.get(VILLAGE));
        assertEquals(VillageAlarm.Change.RAISED, alarms.monsters(VILLAGE, 2, "монстры у деревни: 2", 10, RULES));
        assertEquals(VillageAlarm.Level.THREAT, alarms.get(VILLAGE).level);
        assertEquals(VillageAlarm.Change.NONE, alarms.monsters(VILLAGE, 3, "монстры у деревни: 3", 30, RULES), "still a threat");
        assertEquals(VillageAlarm.Change.ESCALATED, alarms.monsters(VILLAGE, 5, "монстры у деревни: 5", 50, RULES));
        VillageAlarm.Alarm alarm = alarms.get(VILLAGE);
        assertTrue(alarm.attack());
        assertEquals("монстры у деревни: 5", alarm.cause);
        assertEquals(VillageAlarm.Change.NONE, alarms.monsters(VILLAGE, 1, "монстры у деревни: 1", 70, RULES), "an attack does not drop back");
        assertEquals(5, alarm.mostThreats);
        assertEquals(VillageAlarm.Change.NONE, alarms.attacked(VILLAGE, "рейд", 80), "already an attack");
    }

    @Test void aBlowTurnsTheVillageOnThePlayerAtOnce() {
        VillageAlarm alarms = new VillageAlarm();
        UUID dima = UUID.randomUUID(), guest = UUID.randomUUID();
        assertEquals(VillageAlarm.Change.RAISED, alarms.player(VILLAGE, dima, "Дима", VillageAlarm.Level.ATTACK, "ударил Петра", 0));
        VillageAlarm.Alarm alarm = alarms.get(VILLAGE);
        assertTrue(alarm.attack(), "a blow is an attack straight away, no warning");
        assertTrue(alarm.against(dima));
        assertFalse(alarm.against(guest), "a friend who walks in is not a target");
        assertEquals(VillageAlarm.Change.NONE, alarms.player(VILLAGE, dima, "Дима", VillageAlarm.Level.ATTACK, "ударил Марфу", 20));
        assertEquals(VillageAlarm.Change.NEW_TARGET, alarms.player(VILLAGE, guest, "Гость", VillageAlarm.Level.THREAT, "его тут ненавидят", 30));
        assertEquals(2, alarm.targets.size());
    }

    @Test void anExileOrAHatedPlayerIsAThreatUntilTheyStrike() {
        VillageAlarm alarms = new VillageAlarm();
        UUID dima = UUID.randomUUID();
        assertEquals(VillageAlarm.Change.RAISED, alarms.player(VILLAGE, dima, "Дима", VillageAlarm.Level.THREAT, "изгнанник в деревне", 0));
        assertFalse(alarms.get(VILLAGE).attack());
        assertEquals(VillageAlarm.Change.ESCALATED, alarms.player(VILLAGE, dima, "Дима", VillageAlarm.Level.ATTACK, "изгнанник не уходит", 600));
        assertEquals("изгнанник не уходит", alarms.get(VILLAGE).targets.get(dima).cause);
    }

    @Test void theAlarmEndsWhenTheVillageIsQuietAndThePlayerGone() {
        VillageAlarm alarms = new VillageAlarm();
        UUID dima = UUID.randomUUID();
        alarms.monsters(VILLAGE, 6, "монстры", 0, RULES);
        alarms.player(VILLAGE, dima, "Дима", VillageAlarm.Level.ATTACK, "ударил Петра", 0);
        alarms.killed(VILLAGE); alarms.killed(VILLAGE); alarms.lost(VILLAGE, "Пётр");
        assertTrue(alarms.tick("minecraft:overworld|", 1199, RULES).isEmpty());
        assertTrue(alarms.tick("minecraft:the_nether|", 5000, RULES).isEmpty(), "another world's clock does not touch it");
        assertNotNull(alarms.get(VILLAGE));
        alarms.seen(VILLAGE, dima, 2000);
        assertTrue(alarms.tick("minecraft:overworld|", 3500, RULES).isEmpty(), "the player is still about");
        List<VillageAlarm.Alarm> ended = alarms.tick("minecraft:overworld|", 2000 + 3600, RULES);
        assertEquals(1, ended.size(), "the player stayed away long enough and nothing else is about");
        assertNull(alarms.get(VILLAGE));
        String summary = ended.get(0).summary();
        assertTrue(summary.contains("Пётр"), summary);
        assertTrue(summary.contains("убито монстров: 2"), summary);
    }

    @Test void deathOrForgivenessReleasesThePlayer() {
        VillageAlarm alarms = new VillageAlarm();
        UUID dima = UUID.randomUUID();
        String other = "minecraft:overworld|bell@0,64,0";
        alarms.player(VILLAGE, dima, "Дима", VillageAlarm.Level.ATTACK, "ударил Петра", 0);
        alarms.player(other, dima, "Дима", VillageAlarm.Level.THREAT, "его тут ненавидят", 0);
        assertTrue(alarms.release(other, dima));
        assertFalse(alarms.release(other, dima));
        assertEquals(List.of(VILLAGE), alarms.release(dima));
        assertFalse(alarms.get(VILLAGE).against(dima));
        assertEquals(2, alarms.tick("minecraft:overworld|", 1200, RULES).size(), "with the player released only the quiet clock is left");
    }

    @Test void rulesComeFromTheSettings() {
        MobTalkConfig config = new MobTalkConfig();
        VillageAlarm.Rules rules = VillageAlarm.Rules.from(config);
        assertEquals(60 * 20L, rules.clearTicks());
        assertEquals(3 * 1200L, rules.playerClearTicks());
        assertEquals(5, rules.attackThreats());
    }

    @Test void theCommandEndsAnAlarmAtOnceAndOnlyThatOne() {
        VillageAlarm alarms = new VillageAlarm();
        String other = "minecraft:overworld|minecraft:village_plains@9,9";
        alarms.monsters(VILLAGE, 6, "монстры у деревни: 6", 0, RULES);
        alarms.monsters(other, 1, "монстры у деревни: 1", 0, RULES);
        assertNotNull(alarms.end(VILLAGE));
        assertNull(alarms.get(VILLAGE));
        assertNull(alarms.end(VILLAGE), "nothing left to end");
        assertNotNull(alarms.get(other), "the other village keeps its alarm");
        assertEquals(VillageAlarm.Change.RAISED, alarms.monsters(VILLAGE, 1, "монстры у деревни: 1", 40, RULES), "a new threat raises a new alarm");
    }
}
