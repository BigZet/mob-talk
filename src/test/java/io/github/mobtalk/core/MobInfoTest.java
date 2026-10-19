package io.github.mobtalk.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** The «Моб и деревня» panel and the village status line: what they show and what they keep folded. */
class MobInfoTest {
    @TempDir Path directory;
    @AfterEach void close() throws Exception { ConversationStore.closeWorld(directory); Tuning.apply(null); }

    private static MobSnapshot villager(UUID id, UUID player) {
        return new MobSnapshot(id, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains",
                "day", "clear", 2, "minecraft:air", List.of(), List.of(), player, Map.of("name", "Стив"), Map.of(), Map.of(), List.of(), List.of(ActionType.NONE), 0);
    }

    @Test void standingShowsRankPointsAndConsequences() {
        VillageStanding.View exile = VillageStanding.of(List.of(-80, -80), 10, VillageRumors.Reputation.NONE);
        List<String> lines = MobInfo.standingLines(exile, "Ольховка");
        assertEquals("Ваш статус в «Ольховка»: изгнанник", lines.get(0));
        assertTrue(lines.get(1).contains("Очки: -160") && lines.get(1).contains("знают вас 2 из 10") && lines.get(1).contains("средний балл -80"), lines.get(1));
        assertTrue(lines.get(2).contains("торговать с вами отказываются") && lines.get(2).contains("голем следит"), lines.get(2));
        assertTrue(lines.get(3).startsWith("Как встречают: деревня его изгнала"));
        VillageStanding.View guest = VillageStanding.of(List.of(10), 10, VillageRumors.Reputation.NONE);
        assertEquals("Последствия: нет, торговля открыта", MobInfo.standingLines(guest, "Ольховка").get(2));
        assertEquals("деревня «Ольховка»: гость (10)", MobInfo.standingLine(guest, "Ольховка"));
        assertEquals(List.of("Рядом нет известной деревни."), MobInfo.standingLines(null, ""));
        assertEquals("", MobInfo.standingLine(guest, ""));
    }

    @Test void reportCoversMobAndVillageAndFoldsSecrets() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        UUID anna = UUID.randomUUID(), boris = UUID.randomUUID(), child = UUID.randomUUID(), player = UUID.randomUUID();
        ConversationStore.Village village = store.createVillage("minecraft:overworld", 100, 100, "Ольховка", "Деревня у реки.");
        store.observe(villager(anna, player)); store.observe(villager(boris, player)); store.observe(villager(child, player));
        store.setBackstory(anna, "Анна", "Родилась у мельницы.", "Открыть пекарню", "Боится темноты", village.id());
        store.setBackstory(boris, "Борис", "Кузнец.", "", "", village.id());
        store.setBackstory(child, "Ваня", "", "", "", village.id());
        store.recordHelp(anna, "minecraft:villager", "Житель", player, "Стив", 1000);
        synchronized (store) {
            store.state.family.marry(anna, "Анна", Gender.FEMALE, false, boris, "Борис", Gender.MALE, false);
            store.state.family.addChild(child, "Ваня", Gender.MALE, anna, boris);
        }
        long now = System.currentTimeMillis();
        store.addRumors(village.id(), List.of(new VillageRumors.View("r1", "игрок помог Анне", "Анна", 4, 1, now, 4)), now);

        String folded = MobInfo.report(store, anna, player, "minecraft:overworld", 110, 90, now, false, List.of("Ждёт здесь ещё 3 мин"),
                Map.of("summary", "Открытое место", "exits", List.of("восток"), "looksLike", "деревня", "looksLikeConfidence", 0.8));
        for (String expected : List.of("■ АННА", "Вид: ", "Родилась у мельницы.", "Желание и тайна свёрнуты", "■ ОТНОШЕНИЕ К ВАМ", "+20",
                "■ СОСТОЯНИЕ", "Настроение", "■ СВОИ", "Семья:", "Ждёт здесь ещё 3 мин", "■ ПАМЯТЬ", "Открытое место", "Выходы: восток",
                "Похоже на: деревня (80%)", "■ ДЕРЕВНЯ, ГДЕ ВЫ СТОИТЕ", "Ваш статус в «Ольховка»", "■ ДЕРЕВНЯ «ОЛЬХОВКА»", "Деревня у реки.",
                "— Анна: +20", "— Борис: +0", "Анна: игрок помог Анне", "Семьи:", "дети: Ваня"))
            assertTrue(folded.contains(expected), expected + "\n---\n" + folded);
        assertFalse(folded.contains("Открыть пекарню") || folded.contains("Боится темноты"), "the secrets stay folded");
        String open = MobInfo.report(store, anna, player, "minecraft:overworld", 110, 90, now, true, List.of(), Map.of());
        assertTrue(open.contains("Желание: Открыть пекарню") && open.contains("Тайна: Боится темноты"));
        assertTrue(open.contains("Не анализировалось"));

        String nobody = MobInfo.report(store, null, player, "minecraft:overworld", 5000, 5000, now, false, List.of(), Map.of());
        assertTrue(nobody.contains("Моб не выбран") && nobody.contains("Рядом нет известной деревни."), nobody);
    }

    @Test void formsOfAddressFollowTheSettingsInTheReport() throws Exception {
        ConversationStore store = ConversationStore.open(directory, new MobTalkConfig());
        UUID mob = UUID.randomUUID(), player = UUID.randomUUID();
        store.observe(villager(mob, player));
        store.recordDamage(mob, "minecraft:villager", "Житель", player, "Стив", 1000);
        MobTalkConfig names = new MobTalkConfig(); names.playerNamesHostile = "Димас"; names.playerNamesNeutral = "Дима";
        Tuning.apply(names);
        String report = MobInfo.report(store, mob, player, "minecraft:overworld", 0, 0, System.currentTimeMillis(), false, List.of(), Map.of());
        assertTrue(report.contains("обращается к вам: Димас"), report);
    }
}
