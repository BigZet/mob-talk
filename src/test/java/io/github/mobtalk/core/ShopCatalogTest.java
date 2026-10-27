package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShopCatalogTest {
    @Test void basketRoundsUpOnce() {
        // 3 bread for 1 and 2 more bread: 5/3 emeralds -> 2
        assertEquals(2, ShopCatalog.total(List.of(new ShopCatalog.Line(5, 3, 1))));
        // two half lots of different goods make one emerald, not two
        assertEquals(1, ShopCatalog.total(List.of(new ShopCatalog.Line(1, 2, 1), new ShopCatalog.Line(3, 6, 1))));
        assertEquals(0, ShopCatalog.total(List.of()));
        assertEquals(6, ShopCatalog.total(List.of(new ShopCatalog.Line(2, 1, 3))));
    }

    @Test void paysWithBlocksAndChange() {
        assertEquals(new ShopCatalog.Payment(3, 0, 0), ShopCatalog.pay(3, 10, 0));
        assertEquals(new ShopCatalog.Payment(2, 1, 6), ShopCatalog.pay(5, 2, 4));
        assertEquals(new ShopCatalog.Payment(0, 2, 4), ShopCatalog.pay(14, 0, 2));
        assertNull(ShopCatalog.pay(20, 1, 2));
    }

    @Test void scaledNeverFree() {
        assertEquals(1, ShopCatalog.scaled(1, 10));
        assertEquals(2, ShopCatalog.scaled(1, 200));
        assertEquals(3, ShopCatalog.scaled(3, 100));
    }

    @Test void pickIsSteadyAndInPlace() {
        for (long seed = 0; seed < 50; seed++) {
            ShopCatalog.Goods a = ShopCatalog.pick(ShopCatalog.Place.FREEZER, seed);
            assertEquals(ShopCatalog.Place.FREEZER, a.place());
            assertEquals(a, ShopCatalog.pick(ShopCatalog.Place.FREEZER, seed));
        }
        assertNotNull(ShopCatalog.of("minecraft:bread"));
    }
}
