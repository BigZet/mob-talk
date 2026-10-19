package io.github.mobtalk.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The place block reaches the system prompt as facts, the fight estimate names what shuts the creature in, and the JSON
 * does not repeat it. */
class PlacePromptTest {
    private static MobSnapshot snapshot(Map<String, Object> place, boolean trapped) {
        return new MobSnapshot(UUID.randomUUID(), "minecraft:cow", "Марфа", 10, 10, "minecraft:overworld", "minecraft:plains", "day", "clear", 3,
                "minecraft:stick", List.of(), List.of(), UUID.randomUUID(), Map.of("name", "Стив", "health", 20, "maxHealth", 20),
                Map.of("fighter", false, "attackDamage", 0, "trapped", trapped), place == null ? Map.of() : Map.of("place", place), List.of(),
                List.of(ActionType.NONE, ActionType.FLEE), 0);
    }
    private static final Map<String, Object> PIT = Map.of("kind", "PIT", "trapped", true, "enclosed", true, "exits", List.of(),
            "summary", "Ты на дне ямы 3×3 глубиной 4; стены из бедрок (не прокопать), наверху небо. Выхода нет: ты заперт.",
            "escape", "яма 3×3 глубиной 4 из бедрок, не прокопать",
            "objects", List.of("стена из бедрок 4×3 вплотную к северу: не прокопать", "вода под ногами, стоячая"),
            "looksLike", "TRAP", "looksLikeConfidence", 0.87);

    @Test void blockCarriesFactsObjectsAndGuess() {
        String block = PlacePrompt.block(snapshot(PIT, true));
        assertTrue(block.startsWith("\n\nМесто, где ты стоишь. Это факты о месте, они верны"), block);
        assertTrue(block.contains("Ты на дне ямы 3×3 глубиной 4") && block.contains("Что вокруг: стена из бедрок 4×3 вплотную к северу: не прокопать; вода под ногами, стоячая."), block);
        assertTrue(block.contains("Похоже на: ловушка (уверенность 87 %)") && block.contains("факты выше точнее"), block);
        assertEquals("", PlacePrompt.block(snapshot(null, false)), "no analysis, no block");
        assertEquals("", PlacePrompt.block(snapshot(Map.of("kind", "OPEN"), false)), "no summary, no block");
    }

    @Test void registryFactsAndTreesFollowTheSummary() {
        Map<String, Object> village = new java.util.HashMap<>(PIT);
        village.put("structures", List.of("Ты внутри: кузница — равнинная деревня.", "Рядом: церковь (равнинная деревня) в 9 блоках к северу."));
        village.put("trees", List.of("Над тобой крона: дуба."));
        String block = PlacePrompt.block(snapshot(village, false));
        int summary = block.indexOf("Выхода нет: ты заперт."), smithy = block.indexOf("\nТы внутри: кузница — равнинная деревня."),
                oak = block.indexOf("\nНад тобой крона: дуба."), around = block.indexOf("Что вокруг:");
        assertTrue(summary > 0 && smithy > summary && block.contains("\nРядом: церковь") && oak > smithy && around > oak, block);
        assertTrue(block.contains("пользуйся ими, а не пересказывай"), "the use-don't-retell rule stays");
    }

    @Test void fightEstimateNamesWhatShutsItIn() {
        String text = Situation.of(snapshot(PIT, true), null).describe();
        assertTrue(text.contains("Бежать некуда: яма 3×3 глубиной 4 из бедрок, не прокопать — ты заперт."), text);
        String plain = Situation.of(snapshot(null, true), null).describe();
        assertTrue(plain.contains("Бежать некуда: ты заперт, путь наружу закрыт."), plain);
        assertFalse(Situation.of(snapshot(PIT, false), null).describe().contains("Бежать некуда"), "not trapped, no line");
    }

    @Test void jsonDropsThePlace() {
        JsonObject tree = PlacePrompt.withoutPlace(new Gson().toJsonTree(snapshot(PIT, true))).getAsJsonObject();
        assertFalse(tree.getAsJsonObject("environmentDetails").has("place"));
        assertTrue(tree.getAsJsonObject("mobDetails").get("trapped").getAsBoolean(), "the validator's flag stays");
    }
}
