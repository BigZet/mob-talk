package io.github.mobtalk.core;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The civil registry (track «чиновники»): the book of acts follows the family tree, the windows offer the right papers, a repeated
 * certificate costs the fee, the papers print the form's wording. */
class CivilRegistryTest {
    private final UUID player = UUID.randomUUID(), wife = UUID.randomUUID(), son = UUID.randomUUID(), granny = UUID.randomUUID();

    private FamilyTree family() {
        FamilyTree tree = new FamilyTree();
        tree.marry(player, "Дима", Gender.MALE, true, wife, "Аксинья", Gender.FEMALE, false);
        tree.addChild(son, "Ваня", Gender.MALE, wife, player);
        tree.addChild(wife, "Аксинья", Gender.FEMALE, granny, null);
        tree.node(granny, "Марфа", Gender.FEMALE, false);
        return tree;
    }

    @Test void theBookFollowsTheTreeOnce() {
        FamilyTree tree = family();
        CivilRegistry book = new CivilRegistry();
        List<CivilRegistry.Act> first = book.sync(tree, 5, "", id -> null);
        assertEquals(3, first.size()); // two births and the marriage
        assertTrue(book.sync(tree, 6, "", id -> null).isEmpty());
        tree.died(granny);
        List<CivilRegistry.Act> death = book.sync(tree, 7, "", id -> null);
        assertEquals(1, death.size());
        assertEquals(CivilRegistry.Kind.DEATH, death.get(0).kind());
        assertEquals(7, death.get(0).day());
        tree.divorce(player);
        List<CivilRegistry.Act> divorce = book.sync(tree, 9, "", id -> null);
        assertEquals(CivilRegistry.Kind.DIVORCE, divorce.get(0).kind());
        assertFalse(book.current(player, wife));
        tree.marry(player, "Дима", Gender.MALE, true, wife, "Аксинья", Gender.FEMALE, false);
        assertEquals(CivilRegistry.Kind.MARRIAGE, book.sync(tree, 12, "", id -> null).get(0).kind());
        assertTrue(book.current(player, wife));
    }

    @Test void eachWindowOffersItsPapers() {
        FamilyTree tree = family();
        CivilRegistry book = new CivilRegistry();
        book.sync(tree, 5, "", id -> null);
        tree.died(granny);
        book.sync(tree, 6, "", id -> null);
        List<CivilRegistry.Paper> clerk = book.papers(player, tree, Officialdom.Rank.CLERK, false).stream().map(CivilRegistry.Offer::paper).toList();
        assertTrue(clerk.contains(CivilRegistry.Paper.MARRIAGE_CERTIFICATE));
        assertTrue(clerk.contains(CivilRegistry.Paper.BIRTH_CERTIFICATE)); // the son's
        assertTrue(clerk.contains(CivilRegistry.Paper.NO_RECORD)); // the player's own birth is on no record
        assertFalse(clerk.contains(CivilRegistry.Paper.NOT_MARRIED));
        assertFalse(clerk.contains(CivilRegistry.Paper.DEATH_CERTIFICATE)); // granny is the wife's kin, not his
        assertTrue(book.papers(wife, tree, Officialdom.Rank.CLERK, false).stream().anyMatch(o -> o.paper() == CivilRegistry.Paper.DEATH_CERTIFICATE));
        assertEquals(List.of(CivilRegistry.Paper.REFERENCE, CivilRegistry.Paper.HOUSEHOLD, CivilRegistry.Paper.ALIVE),
                book.papers(player, tree, Officialdom.Rank.HEAD, false).stream().map(CivilRegistry.Offer::paper).toList());
        assertTrue(book.papers(player, tree, Officialdom.Rank.INSPECTOR, false).stream().anyMatch(o -> o.paper() == CivilRegistry.Paper.NO_ORDERS));
        assertFalse(book.papers(player, tree, Officialdom.Rank.INSPECTOR, true).stream().anyMatch(o -> o.paper() == CivilRegistry.Paper.NO_ORDERS));
        assertTrue(new CivilRegistry().papers(player, new FamilyTree(), Officialdom.Rank.CLERK, false).stream().anyMatch(o -> o.paper() == CivilRegistry.Paper.NOT_MARRIED));
    }

    @Test void aRepeatedCertificateCostsTheFee() {
        CivilRegistry book = new CivilRegistry();
        book.sync(family(), 5, "", id -> null);
        int act = book.acts().stream().filter(a -> a.kind() == CivilRegistry.Kind.MARRIAGE).findFirst().orElseThrow().number();
        CivilRegistry.Paper paper = CivilRegistry.Paper.MARRIAGE_CERTIFICATE;
        assertFalse(book.repeated(paper, act, player));
        assertEquals(0, paper.fee(false, 3));
        assertTrue(book.issue(paper, act, player).matches("[IVX]+-[А-Я]{2} № \\d{6}"));
        assertTrue(book.repeated(paper, act, player));
        assertEquals(3, paper.fee(true, 3));
        assertEquals(3, CivilRegistry.Paper.BUILD_PERMIT.fee(false, 3));
        assertTrue(book.issue(CivilRegistry.Paper.FAMILY, 0, player).startsWith("№ "));
    }

    @Test void thePapersPrintTheFormsWording() {
        CivilRegistry book = new CivilRegistry();
        book.sync(family(), 5, "", id -> null);
        CivilRegistry.Act birth = book.acts().stream().filter(a -> a.kind() == CivilRegistry.Kind.BIRTH && son.equals(a.id(0))).findFirst().orElseThrow();
        CivilRegistry.Facts facts = new CivilRegistry.Facts("Дима", "Ольховка", 8, -50, List.of("супруга — Аксинья"), List.of(), false);
        List<String> lines = CivilRegistry.lines(CivilRegistry.Paper.BIRTH_CERTIFICATE, birth, facts);
        assertEquals("Ваня родился в 5-й день", lines.get(0)); assertEquals("Мать: Аксинья", lines.get(1)); assertEquals("Отец: Дима", lines.get(2));
        assertTrue(String.join(" ", CivilRegistry.lines(CivilRegistry.Paper.NO_RECORD, null, facts)).contains("не рождался"));
        assertTrue(String.join(" ", CivilRegistry.lines(CivilRegistry.Paper.REFERENCE, null, facts)).contains("отрицательно"));
        assertTrue(CivilRegistry.lines(CivilRegistry.Paper.FAMILY, null, facts).contains("супруга — Аксинья"));
        assertTrue(String.join(" ", CivilRegistry.lines(CivilRegistry.Paper.BUILD_PERMIT, null, facts)).contains("до 15-го дня"));
        assertTrue(CivilRegistry.lines(CivilRegistry.Paper.DEATH_CERTIFICATE, null, facts).isEmpty());
        assertTrue(Officialdom.prompt(Officialdom.Rank.INSPECTOR, UUID.randomUUID(), 0, 20, 100, null).contains("разрешение на строительство"));
    }
}
