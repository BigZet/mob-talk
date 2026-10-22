package io.github.mobtalk.server;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.server.Permissions.Level;
import io.github.mobtalk.server.PlayerBudget.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Расходы и лимиты по игрокам (трек C): по умолчанию без ограничений, окна «в минуту» и «в день», сутки по часам сервера, множитель
 * стримера, админ без лимита, правила поверх настроек, места для одновременных ответов, запись в {@code budget.json}. */
class PlayerBudgetTest {
    @TempDir Path world;

    /** Часы, которые двигает тест. */
    private static final class Ticking extends Clock {
        Instant now; final ZoneId zone;
        Ticking(Instant start, ZoneId zone) { now = start; this.zone = zone; }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new Ticking(now, zone); }
        @Override public Instant instant() { return now; }
        void plus(long millis) { now = now.plusMillis(millis); }
    }

    private final MobTalkConfig config = new MobTalkConfig();
    private final Map<UUID, Level> levels = new HashMap<>();
    private final Ticking clock = new Ticking(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.ofHours(3));
    private final UUID dima = UUID.randomUUID(), test2 = UUID.randomUUID(), star = UUID.randomUUID(), boss = UUID.randomUUID();
    private PlayerBudgetImpl budget() {
        levels.put(star, Level.STREAMER); levels.put(boss, Level.ADMIN);
        return new PlayerBudgetImpl(() -> config, id -> levels.getOrDefault(id, Level.PLAYER), clock);
    }

    @Test void noLimitsByDefaultButCostsAreCounted() {
        PlayerBudgetImpl budget = budget();
        for (int i = 0; i < 100; i++) assertTrue(budget.allow(dima, Kind.DIALOGUE).allowed());
        budget.charge(dima, Kind.DIALOGUE, 0.25, 1);
        budget.charge(dima, Kind.SPEECH, 0.05, 300);
        assertEquals(0.30, budget.spentTodayUsd(dima), 1e-9);
        assertTrue(budget.report().contains("диалог") && budget.report().contains("озвучка"), budget.report());
    }

    @Test void perMinuteWindowRefusesPolitelyAndReopens() {
        config.budgetPlayerPerMinute = 3;
        PlayerBudgetImpl budget = budget();
        for (int i = 0; i < 3; i++) assertTrue(budget.allow(test2, Kind.DIALOGUE).allowed());
        PlayerBudget.Verdict fourth = budget.allow(test2, Kind.DIALOGUE);
        assertFalse(fourth.allowed());
        assertTrue(fourth.reason().contains("3 запросов в минуту"), fourth.reason());
        assertTrue(budget.allow(dima, Kind.DIALOGUE).allowed(), "another player has their own window");
        assertTrue(budget.allow(test2, Kind.SPEECH).allowed(), "the per-minute limit of the settings is for dialogue only");
        clock.plus(60_000);
        assertTrue(budget.allow(test2, Kind.DIALOGUE).allowed(), "the window moved on");
    }

    @Test void dailyLimitResetsAtTheServersMidnight() {
        config.budgetPlayerDailyUsd = 0.10;
        PlayerBudgetImpl budget = budget();
        budget.charge(dima, Kind.DIALOGUE, 0.06, 1);
        assertTrue(budget.allow(dima, Kind.DIALOGUE).allowed());
        budget.charge(dima, Kind.TRANSCRIPTION, 0.05, 4);
        PlayerBudget.Verdict out = budget.allow(dima, Kind.DIALOGUE);
        assertFalse(out.allowed());
        assertTrue(out.reason().contains("дневной лимит"), out.reason());
        clock.now = Instant.parse("2026-10-22T20:59:00Z"); // 23:59 by the server's clock (+3)
        assertFalse(budget.allow(dima, Kind.DIALOGUE).allowed());
        clock.now = Instant.parse("2026-10-22T21:00:01Z"); // 00:00 the next day
        assertTrue(budget.allow(dima, Kind.DIALOGUE).allowed());
        assertEquals(0, budget.spentTodayUsd(dima), 1e-9);
        assertEquals(0.11, budget.totalUsd(dima), 1e-9, "the total survives the day");
        assertTrue(budget.costsJson().contains("2026-10-22"), "yesterday is in the history");
    }

    @Test void streamerMultiplierAndAdminWithoutLimits() {
        config.budgetPlayerPerMinute = 2; config.budgetStreamerMultiplier = 2;
        PlayerBudgetImpl budget = budget();
        for (int i = 0; i < 4; i++) assertTrue(budget.allow(star, Kind.DIALOGUE).allowed(), "streamer " + i);
        assertFalse(budget.allow(star, Kind.DIALOGUE).allowed());
        for (int i = 0; i < 50; i++) assertTrue(budget.allow(boss, Kind.DIALOGUE).allowed());
        config.budgetServerDailyUsd = 0.01;
        budget.charge(dima, Kind.DIALOGUE, 1, 1);
        assertFalse(budget.allow(test2, Kind.DIALOGUE).allowed(), "the server's day is spent");
        assertTrue(budget.allow(boss, Kind.DIALOGUE).allowed(), "the admin is never limited");
    }

    @Test void serverPerMinuteCountsEveryone() {
        config.budgetServerPerMinute = 3;
        PlayerBudgetImpl budget = budget();
        assertTrue(budget.allow(dima, Kind.DIALOGUE).allowed());
        assertTrue(budget.allow(test2, Kind.DIALOGUE).allowed());
        assertTrue(budget.allow(null, Kind.VILLAGE_TALK).allowed());
        PlayerBudget.Verdict busy = budget.allow(dima, Kind.DIALOGUE);
        assertFalse(busy.allowed());
        assertTrue(busy.reason().contains("перегружен"), busy.reason());
    }

    @Test void rulesOverTheSettings() {
        PlayerBudgetImpl budget = budget();
        assertTrue(AdminActions.budgetSet(budget, test2.toString(), "Тест2", "диалог", 1, 0).startsWith("Лимит: Тест2, диалог — 1 в минуту"));
        assertTrue(budget.allow(test2, Kind.DIALOGUE).allowed());
        assertFalse(budget.allow(test2, Kind.DIALOGUE).allowed());
        assertTrue(budget.allow(dima, Kind.DIALOGUE).allowed(), "the rule is Тест2's only");
        AdminActions.budgetSet(budget, "all", "все", "speech", 0, 0.01);
        budget.charge(dima, Kind.SPEECH, 0.02, 10);
        assertFalse(budget.allow(dima, Kind.SPEECH).allowed(), "a kind's daily rule counts that kind");
        assertTrue(budget.allow(dima, Kind.DIALOGUE).allowed(), "other kinds are free");
        assertTrue(AdminActions.budgetSet(budget, "all", "все", "speech", 0, 0).startsWith("Правило снято"));
        assertTrue(budget.allow(dima, Kind.SPEECH).allowed());
        assertTrue(AdminActions.budgetSet(budget, "all", "все", "танцы", 1, 1).startsWith("Нет такого вида"));
    }

    @Test void twoAnswersPerPlayerAndTheServersLimit() {
        config.maxConcurrentDialogues = 3;
        PlayerBudgetImpl budget = budget();
        PlayerBudget.Slot a = budget.enter(dima), b = budget.enter(dima), c = budget.enter(dima);
        assertTrue(a.granted() && b.granted());
        assertFalse(c.granted());
        assertTrue(c.reason().contains("два ответа"));
        PlayerBudget.Slot d = budget.enter(test2), e = budget.enter(test2);
        assertTrue(d.granted());
        assertFalse(e.granted(), "three answers on the server at most");
        assertTrue(e.reason().contains("Сервер занят"));
        a.close(); a.close();
        assertEquals(1, budget.active(dima), "closing twice frees one place");
        assertTrue(budget.enter(test2).granted());
    }

    @Test void budgetFileSurvivesARestartAndBadFilesAreIgnored() throws Exception {
        PlayerBudgetImpl budget = budget();
        budget.open(world);
        budget.remember(dima, "Дима");
        budget.charge(dima, Kind.DIALOGUE, 0.5, 1);
        budget.setLimit("all", Kind.DIALOGUE, 5, 1);
        budget.close();
        Path file = world.resolve("data/mobtalk/budget.json");
        assertTrue(Files.exists(file));
        PlayerBudgetImpl again = budget();
        again.open(world);
        assertEquals(0.5, again.spentTodayUsd(dima), 1e-9);
        assertEquals("Дима", again.name(dima));
        assertEquals(new PlayerBudgetImpl.Limit(5, 1), again.limits().get("all:DIALOGUE"));
        again.close();
        Files.writeString(file, "{не json");
        PlayerBudgetImpl broken = budget();
        broken.open(world);
        assertEquals(0, broken.spentTodayUsd(dima), 1e-9);
        broken.close();
    }
}
