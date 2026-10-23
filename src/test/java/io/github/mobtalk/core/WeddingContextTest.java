package io.github.mobtalk.core;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Жители знают об идущей свадьбе: кто женится, что идёт и кто они на ней — строка доходит до промпта. */
class WeddingContextTest {
    private final UUID bride = UUID.randomUUID(), priest = UUID.randomUUID(), guest = UUID.randomUUID(), stranger = UUID.randomUUID();
    private final Wedding.Cast cast = new Wedding.Cast(
            new Wedding.Person(bride, "Валентина", Gender.FEMALE, "minecraft:farmer", "", "", 80, false, 0), null,
            new Wedding.Person(priest, "Пётр", Gender.MALE, "minecraft:cleric", "", "", 30, false, 0), null, null,
            List.of(new Wedding.Person(guest, "Фома", Gender.MALE, "minecraft:mason", "", "", 10, false, 0)));
    private final Wedding.Names names = new Wedding.Names("Дима", Gender.MALE, "Берёзовка");

    @Test void eachVillagerKnowsItsPartInTheWedding() {
        assertTrue(Wedding.contextLine(cast, names, Wedding.Ceremony.Phase.SERVICE, guest).contains("женятся Валентина и Дима; у колокола идёт служба. Ты среди гостей."));
        assertTrue(Wedding.contextLine(cast, names, Wedding.Ceremony.Phase.SERVICE, priest).contains("Ты ведёшь службу."));
        assertTrue(Wedding.contextLine(cast, names, Wedding.Ceremony.Phase.GATHER, stranger).contains("Тебя на церемонии нет"));
        assertEquals("", Wedding.contextLine(cast, names, Wedding.Ceremony.Phase.CANCELLED, guest));
    }

    @Test void theLineReachesThePrompt() {
        MobSnapshot snapshot = new MobSnapshot(guest, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains",
                "day", "clear", 2, "minecraft:air", List.of(), List.of(), UUID.randomUUID(), Map.of(), Map.of(),
                Map.of("wedding", "Сейчас в деревне свадьба: женятся Валентина и Дима."), List.of(), List.of(ActionType.NONE), 0);
        assertEquals("\n\nСейчас в деревне свадьба: женятся Валентина и Дима.", MobPrompt.wedding(snapshot));
    }
}
