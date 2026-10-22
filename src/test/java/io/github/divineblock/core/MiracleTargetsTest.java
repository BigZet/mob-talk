package io.github.divineblock.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Who suffers each act on a server with two mortals: the one who prayed alone for a personal act, both for a world act. */
class MiracleTargetsTest {
    private record Mortal(String name) { }
    private static final Mortal ASKER = new Mortal("Дима"), NEIGHBOUR = new Mortal("Петя");
    private static final List<Mortal> ONLINE = List.of(ASKER, NEIGHBOUR);

    @Test void everyActHitsTheAskerAloneOrEveryone() {
        for (Miracle miracle : Miracle.values()) {
            List<Mortal> hit = MiracleScope.targets(miracle, ASKER, ONLINE);
            assertTrue(hit.contains(ASKER), miracle.id() + " must reach the one who prayed");
            if (MiracleScope.of(miracle) == MiracleScope.PERSONAL) assertEquals(List.of(ASKER), hit, miracle.id() + " is personal");
            else assertEquals(ONLINE, hit, miracle.id() + " falls on the world");
        }
    }

    @Test void blessingsBansAndCursesAreTheAskersOwn() {
        for (Miracle miracle : List.of(Miracle.HEAL, Miracle.BUFF, Miracle.BLESSING, Miracle.GIFT_ITEM, Miracle.ORE_VEIN, Miracle.TOTEM, Miracle.SAFE_HOME,
                Miracle.CURSE_MINOR, Miracle.CURSE_TOOL, Miracle.CAIN, Miracle.JUDGEMENT, Miracle.MEMORY_WIPE, Miracle.ETERNAL_BAN, Miracle.HELL_EXILE))
            assertEquals(List.of(ASKER), MiracleScope.targets(miracle, ASKER, ONLINE), miracle.id());
        assertFalse(MiracleScope.targets(Miracle.ETERNAL_BAN, ASKER, ONLINE).contains(NEIGHBOUR), "the ban never falls on a bystander");
    }

    @Test void floodsFireAndPlaguesFallOnEveryone() {
        for (Miracle miracle : List.of(Miracle.FLOOD, Miracle.SODOM, Miracle.METEOR, Miracle.METEOR_SHOWER, Miracle.PLAGUES, Miracle.TORNADO, Miracle.ACID_RAIN,
                Miracle.WEATHER_CLEAR, Miracle.TIME_RUSH))
            assertEquals(ONLINE, MiracleScope.targets(miracle, ASKER, ONLINE), miracle.id());
    }

    @Test void theWayHomeLeadsToTheAskersOwnRespawn() {
        var own = MiracleScope.home("кровать Димы", "nether", "overworld", "спавн мира");
        assertTrue(own.own());
        assertEquals("кровать Димы", own.pos());
        assertEquals("nether", own.world(), "his respawn's own dimension");
        var none = MiracleScope.home(null, null, "overworld", "спавн мира");
        assertFalse(none.own());
        assertEquals("спавн мира", none.pos());
        var gone = MiracleScope.home("кровать", null, "overworld", "спавн мира");
        assertEquals("спавн мира", gone.pos(), "a respawn in a world that no longer exists leads to the world spawn");
    }

    /** docs/punishments.md marks every act personal or world exactly as the code does. */
    @Test void theCatalogueMarksEveryAct() throws Exception {
        String doc = Files.readString(Path.of("docs/punishments.md"), StandardCharsets.UTF_8);
        for (Miracle miracle : Miracle.values()) {
            String row = doc.lines().filter(line -> line.startsWith("| `" + miracle.id() + "` |")).findFirst().orElse(null);
            assertNotNull(row, miracle.id() + " is missing from the table in docs/punishments.md");
            assertTrue(row.toLowerCase(Locale.ROOT).contains("| " + MiracleScope.of(miracle).label + " |"), row);
        }
    }
}
