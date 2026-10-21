package io.github.mobtalk.core.place;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Vanilla structure pieces named from registry ids, and the few facts the prompt gets. */
class StructuresTest {
    private static Structures.Piece piece(String structure, String type, String template, int dx, int dy, int dz, boolean inside, int volume) {
        return new Structures.Piece(structure, type, template, dx, dy, dz, inside, true, volume);
    }

    @Test void namesComeFromTheRegistryIds() {
        assertEquals("равнинная деревня", Structures.structureName("minecraft:village_plains"));
        assertEquals("заброшенная шахта", Structures.structureName("minecraft:mineshaft_mesa"));
        assertEquals("разрушенный портал", Structures.structureName("minecraft:ruined_portal_mountain"));
        assertEquals("my mod castle", Structures.structureName("othermod:my_mod_castle"), "unknown structures keep their id");
        assertEquals("кузница", Structures.pieceName("minecraft:jigsaw", "minecraft:village/plains/houses/plains_weaponsmith_1"));
        assertEquals("кузница", Structures.pieceName("minecraft:jigsaw", "minecraft:village/desert/zombie/houses/desert_armorer_1"));
        assertEquals("церковь", Structures.pieceName("minecraft:jigsaw", "minecraft:village/taiga/houses/taiga_temple_1"));
        assertEquals("колодец", Structures.pieceName("minecraft:jigsaw", "minecraft:village/plains/town_centers/plains_fountain_01"));
        assertEquals("дом", Structures.pieceName("minecraft:jigsaw", "minecraft:village/snowy/houses/snowy_small_house_3"));
        assertEquals("загон хоглинов", Structures.pieceName("minecraft:jigsaw", "minecraft:bastion/hoglin_stable/walls/wall_base"));
        assertEquals("сторожевая башня", Structures.pieceName("minecraft:jigsaw", "minecraft:pillager_outpost/watchtower"));
        assertEquals("коридор", Structures.pieceName("minecraft:mscorridor", ""));
        assertEquals("библиотека", Structures.pieceName("minecraft:shli", ""));
        assertEquals("иглу", Structures.pieceName("minecraft:iglu", "minecraft:igloo/top"));
        assertNull(Structures.pieceName("minecraft:something", ""));
    }

    @Test void ctovVillagesAndPiecesAreNamedLikeVanillaOnes() {
        assertEquals("равнинная деревня", Structures.structureName("ctov:medium/village_plains"));
        assertEquals("большая деревня в оазисе", Structures.structureName("ctov:large/village_desert_oasis"));
        assertEquals("маленькая укреплённая таёжная деревня", Structures.structureName("ctov:small/village_taiga_fortified"));
        assertEquals("деревня", Structures.structureName("ctov:medium/village_new_kind"), "an unknown variant is still a village");
        assertEquals("аванпост разбойников", Structures.structureName("ctov:pillager_outpost_mesa"));
        assertEquals("big castle", Structures.structureName("othermod:castles/big_castle"));
        assertTrue(Structures.village("ctov:large/village_swamp") && Structures.village("minecraft:village_snowy"));
        assertFalse(Structures.village("ctov:pillager_outpost_plains") || Structures.village("minecraft:mineshaft"));
        assertEquals("дом", Structures.pieceName("minecraft:jigsaw", "ctov:village/plains/house/small_1"));
        assertEquals("дом", Structures.pieceName("minecraft:jigsaw", "ctov:village/halloween/house/big_2"), "the biome folder 'halloween' is no well");
        assertEquals("таверна", Structures.pieceName("minecraft:jigsaw", "ctov:village/plains/jobsite/tavern"));
        assertEquals("кузница", Structures.pieceName("minecraft:jigsaw", "ctov:village/taiga/jobsite/smith"));
        assertEquals("дом рыбака", Structures.pieceName("minecraft:jigsaw", "ctov:village/beach/jobsite/fishing_hut"));
        assertEquals("мастерская", Structures.pieceName("minecraft:jigsaw", "ctov:village/plains/jobsite/pokecenter"));
        assertEquals("площадь", Structures.pieceName("minecraft:jigsaw", "ctov:village/plains/town_center"));
        assertEquals("улица", Structures.pieceName("minecraft:jigsaw", "ctov:village/plains/roads/t_2"));
        assertEquals("фонарь", Structures.pieceName("minecraft:jigsaw", "ctov:village/plains/decos/lamp_3"));
        assertEquals("украшение", Structures.pieceName("minecraft:jigsaw", "ctov:village/plains/deco/picnic_mat"));
        assertEquals("кладбище", Structures.pieceName("minecraft:jigsaw", "ctov:village/halloween/decos/cemetary"));
        assertEquals("крепостная стена", Structures.pieceName("minecraft:jigsaw", "ctov:village/underground/wall/large_wall"));
        assertEquals("башня", Structures.pieceName("minecraft:jigsaw", "ctov:pillager_outpost/mesa/tower"));
        assertEquals(List.of("Ты внутри: таверна — большая равнинная деревня.", "Рядом: дом (большая равнинная деревня) в 7 блоках к западу."),
                Structures.facts(List.of(
                        piece("ctov:large/village_plains", "minecraft:jigsaw", "ctov:village/plains/roads/straight", 0, 0, 0, true, 300),
                        piece("ctov:large/village_plains", "minecraft:jigsaw", "ctov:village/plains/jobsite/tavern", 0, 0, 0, true, 900),
                        piece("ctov:large/village_plains", "minecraft:jigsaw", "ctov:village/plains/house/med_1", -7, 0, 1, false, 500),
                        piece("ctov:large/village_plains", "minecraft:jigsaw", "ctov:village/plains/decos/lamp_1", 2, 0, 0, false, 10))));
    }

    @Test void insideTheSmallestBuildingAndNeighboursNearby() {
        List<Structures.Piece> pieces = List.of(
                piece("minecraft:village_plains", "minecraft:jigsaw", "minecraft:village/plains/streets/straight_01", 0, 0, 0, true, 300),
                piece("minecraft:village_plains", "minecraft:jigsaw", "minecraft:village/plains/houses/plains_weaponsmith_1", 0, 0, 0, true, 900),
                piece("minecraft:village_plains", "minecraft:jigsaw", "minecraft:village/plains/houses/plains_temple_3", 0, 1, -9, false, 1200),
                piece("minecraft:village_plains", "minecraft:jigsaw", "minecraft:village/plains/houses/plains_small_house_1", 6, 0, 0, false, 400),
                piece("minecraft:village_plains", "minecraft:jigsaw", "minecraft:village/plains/houses/plains_small_house_2", 12, 0, 3, false, 400),
                piece("minecraft:village_plains", "minecraft:jigsaw", "minecraft:village/plains/plains_lamp_1", 2, 0, 0, false, 10));
        List<String> facts = Structures.facts(pieces);
        assertEquals("Ты внутри: кузница — равнинная деревня.", facts.get(0), facts.toString());
        assertEquals("Рядом: дом (равнинная деревня) в 6 блоках к востоку; церковь (равнинная деревня) в 9 блоках к северу.", facts.get(1));
        assertEquals(2, facts.size(), "one line per kind: the second house and the lamp are not repeated");
    }

    @Test void onTheStreetOfAVillageAndInAMineshaft() {
        assertEquals(List.of("Ты на территории: снежная деревня, улица."), Structures.facts(List.of(
                piece("minecraft:village_snowy", "minecraft:jigsaw", "minecraft:village/snowy/streets/square_01", 0, 0, 0, true, 50))));
        assertEquals(List.of("Ты внутри: коридор — заброшенная шахта.", "Рядом: перекрёсток (заброшенная шахта) в 5 блоках к западу."), Structures.facts(List.of(
                piece("minecraft:mineshaft", "minecraft:mscorridor", "", 0, 0, 0, true, 60),
                piece("minecraft:mineshaft", "minecraft:mscrossing", "", -5, 0, 1, false, 40))));
        assertEquals(List.of("Ты на территории: пустынная деревня."), Structures.facts(List.of(
                new Structures.Piece("minecraft:village_desert", "minecraft:jigsaw", "minecraft:village/desert/streets/x", 30, 0, 0, false, true, 40))));
        assertTrue(Structures.facts(List.of()).isEmpty());
    }
}
