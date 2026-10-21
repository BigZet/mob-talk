package io.github.mobtalk.human;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class HumanGenesTest {
    private static final UUID A = UUID.fromString("0b6a3c1e-1f2d-4c5b-9a8e-7d6c5b4a3f21"), B = UUID.fromString("6f1e2d3c-4b5a-4968-8776-655443322110");

    @Test void sameVillagerAlwaysLooksTheSame() {
        assertEquals(HumanGenes.of(A, 0.8f), HumanGenes.of(A, 0.8f));
        assertNotEquals(HumanGenes.of(A, 0.8f), HumanGenes.of(B, 0.8f));
    }

    @Test void hotVillagesAreDarkerOnAverage() {
        double desert = 0, snow = 0;
        for (int i = 0; i < 400; i++) {
            UUID id = new UUID(i * 7919L, i * 104729L);
            desert += HumanGenes.of(id, HumanGenes.temperature("desert")).melanin();
            snow += HumanGenes.of(id, HumanGenes.temperature("snow")).melanin();
        }
        assertTrue(desert > snow + 100, desert + " vs " + snow);
    }

    @Test void childTakesAfterParents() {
        HumanGenes mother = HumanGenes.of(A, 0.0f), father = HumanGenes.of(B, 2.0f);
        UUID child = UUID.fromString("11111111-2222-3333-4444-555555555555");
        HumanGenes c = HumanGenes.child(child, mother, father);
        assertEquals(c, HumanGenes.child(child, mother, father));
        float lo = Math.min(mother.melanin(), father.melanin()) - 0.1f, hi = Math.max(mother.melanin(), father.melanin()) + 0.1f;
        assertTrue(c.melanin() >= lo && c.melanin() <= hi);
        assertEquals(HumanGenes.of(child, 0.8f).clothes(), c.clothes());
    }

    @Test void picksStayInsideThePool() {
        assertEquals("a", HumanGenes.pick(List.of("a", "b"), 0f));
        assertEquals("b", HumanGenes.pick(List.of("a", "b"), 1f));
        assertNull(HumanGenes.pick(List.of(), 0.5f));
    }
}
