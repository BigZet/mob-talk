package io.github.mobtalk.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Подарки подряд дают одну реакцию, а жители не сводят разговор к кроватям и колоколу. */
class GiftReactionTest {
    private static Initiative.Cue gift(UUID mob, String reason, long at) { return new Initiative.Cue(mob, "Тимофей", Initiative.Trigger.GIFT, reason, List.of(), at); }
    private static String thrown(String item) {
        return "игрок только что бросил тебе под ноги подарок — " + item + "; ты его подобрал и рад: хороший подарок. Это уже случилось: отреагируй одной репликой по характеру, обращаясь к игроку";
    }

    @Test void giftsInARowBecomeOneReactionThatKeepsTheLastFew() {
        UUID mob = UUID.randomUUID();
        Initiative.Cue merged = gift(mob, thrown("minecraft:emerald"), 100);
        assertFalse(Initiative.merged(merged));
        for (String item : List.of("minecraft:diamond", "minecraft:gold_ingot", "minecraft:gold_ingot", "minecraft:diamond", "minecraft:bread"))
            merged = Initiative.merge(merged, gift(mob, thrown(item), 200));
        assertTrue(Initiative.merged(merged));
        assertEquals(100, merged.createdMillis(), "the reaction keeps the moment of the first gift");
        assertEquals(Initiative.MERGED_KEEP, merged.reason().split("; потом ").length, merged.reason());
        assertFalse(merged.reason().contains("emerald"), "the oldest gifts are dropped");
        assertTrue(merged.reason().endsWith("minecraft:bread; ты его подобрал и рад: хороший подарок. Всё это уже случилось; обращайся к игроку"), merged.reason());
        assertFalse(merged.reason().contains("Это уже случилось: отреагируй одной репликой"), "one closing rule, not one per gift");
        String text = Initiative.cueText(merged, new Initiative.Verdict(true, Initiative.Reaction.REMARK, 2));
        assertTrue(text.contains("одна-две короткие фразы"), text);
    }

    @Test void villagersNoLongerWantBedsAndTheBell() {
        SpeciesCatalog.Species villager = SpeciesCatalog.get("minecraft:villager");
        for (String text : List.of(villager.interests(), villager.desire()))
            assertFalse(ProfileDefaults.FIXTURE_WISH.matcher(text).find(), text);
        assertTrue(PromptTexts.VILLAGE_FIXTURES.contains("сам о них речь не заводи"));
    }

    @Test void oldVillagerProfilesLoseTheBedAndBellFixation() {
        ConversationStore.Profile p = new ConversationStore.Profile();
        p.type = "minecraft:villager"; p.name = "Житель"; p.preference = "колокол на площади"; p.fear = "молния";
        p.traits = new ArrayList<>(List.of("Особенно ценит: колокол на площади", "Лично тревожит: молния"));
        p.wish = "Ты хочешь, чтобы на площади звонил колокол, а кроватей в деревне было больше, чем жителей.";
        assertTrue(ProfileDefaults.refresh(p, new HashMap<>(), new MobTalkConfig()));
        assertFalse(ProfileDefaults.RETIRED_VILLAGER_INTERESTS.contains(p.preference), p.preference);
        assertFalse(p.preference.isBlank());
        assertEquals("", p.wish);
        assertEquals(List.of("Особенно ценит: " + p.preference, "Лично тревожит: молния"), p.traits);
        assertFalse(ProfileDefaults.refresh(p, new HashMap<>(), new MobTalkConfig()), "the fix is applied once");

        ConversationStore.Profile cat = new ConversationStore.Profile();
        cat.type = "minecraft:cat"; cat.name = "Кошка"; cat.wish = "Хочу спать на кровати игрока.";
        ProfileDefaults.refresh(cat, new HashMap<>(), new MobTalkConfig());
        assertEquals("Хочу спать на кровати игрока.", cat.wish, "only villagers are touched");
    }
}
