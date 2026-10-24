package io.github.mobtalk.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Б4: слова исповеди, тексты Требника, выбор епитимьи, запасное сопоставление названного, порядок исповеди (полный, возвращение,
 * глумление, молчание, сторож), разбор классификатора и епитимья в памяти мира (поклоны, срок, HUD) без слов исповеди. */
class ConfessionTest {
    @TempDir Path directory;
    private final UUID priest = UUID.randomUUID(), player = UUID.randomUUID();
    private static final List<Confession.Deed> DEEDS = List.of(
            new Confession.Deed(1, "blasphemy", "хулил бога бранью, сегодня", 8, false, false, false),
            new Confession.Deed(2, "villager_killed", "убил жителя, сегодня", 15, true, false, false));

    @Test void wordsOfThePenitent() {
        assertTrue(Confession.asks("Батюшка, хочу исповедаться"));
        assertTrue(Confession.asks("исповедуй меня, отче"));
        assertFalse(Confession.asks("Привет, как дела?"));
        assertTrue(Confession.returns("Я исполнил епитимью"));
        assertTrue(Confession.done("Всё"));
        assertTrue(Confession.done("больше ничего, батюшка"));
        assertTrue(Confession.done("Нет."));
        assertFalse(Confession.done("я всё украл у соседа и не вернул"), "«всё» внутри исповеди — не конец");
        assertTrue(Confession.mocks("да пошёл ты нахер"));
        assertTrue(Confession.mocks("Бога нет, отстань"));
        assertFalse(Confession.mocks("Каюсь, ругался на бога"), "признаться в хуле — не хулить");
        assertEquals(2, Confession.sincerity("Каюсь, виноват"));
        assertEquals(1, Confession.sincerity("Убил жителя"));
        assertEquals(0, Confession.sincerity("иди ты"));
    }

    @Test void trebnikTexts() {
        assertTrue(Confession.EXHORTATION.get(0).startsWith("Се, чадо, Христос невидимо стоит, приемля исповедание твое"));
        assertTrue(Confession.EXHORTATION.get(1).contains("аз же точию свидетель есмь"));
        String prayer = Confession.absolution("Дмитрий");
        assertTrue(prayer.startsWith("Господь и Бог наш Иисус Христос, благодатию и щедротами Своего человеколюбия, да простит ти, чадо Дмитрий, вся согрешения твоя"));
        assertTrue(prayer.contains("и аз, недостойный иерей, властию Его мне данною, прощаю и разрешаю тя от всех грехов твоих"));
        assertTrue(prayer.endsWith("во имя Отца, и Сына, и Святаго Духа. Аминь."));
        assertTrue(Confession.absolutionRu("Дмитрий").contains("прощаю и разрешаю тебя"));
    }

    @Test void thePenanceFitsTheSinAndSkipsTheOnesSwitchedOff() {
        assertEquals(Confession.Penance.CANDLE, Confession.choose(DEEDS, Set.of()), "убийство жителя — свеча за упокой");
        assertEquals(Confession.Penance.BOWS, Confession.choose(List.of(DEEDS.get(0)), Set.of()), "хула — поклоны");
        assertEquals(Confession.Penance.BOWS, Confession.choose(DEEDS, Set.of("candle")), "свеча выключена — следующая по порядку");
        assertEquals(Confession.Penance.FAST, Confession.choose(List.of(new Confession.Deed(3, "slaughter", "", 1, false, false, false)), Set.of()));
        assertEquals(Confession.Penance.LABOR, Confession.choose(List.of(new Confession.Deed(4, "desecration", "", 12, true, false, false)), Set.of()));
        assertEquals(Set.of("candle", "fast"), Confession.off(" candle, FAST ,чушь"));
        assertTrue(Confession.withPenance("Иди с миром, чадо.", Confession.Penance.CANDLE, true).contains("Епитимья тебе: поставь свечу"), "модель забыла епитимью — дописана");
        String said = "Поставь свечку у аналоя, чадо, и приходи.";
        assertEquals(said, Confession.withPenance(said, Confession.Penance.CANDLE, true), "назвала — не повторяется");
    }

    @Test void namedByWordsWhenTheClassifierIsSilent() {
        assertEquals(Set.of(1L), Confession.namedByWords("Каюсь, хулил бога", DEEDS));
        assertEquals(Set.of(2L), Confession.namedByWords("я убил человека", DEEDS));
        assertEquals(Set.of(), Confession.namedByWords("ленился и спал допоздна", DEEDS));
        ConfessionScript.Named named = ConfessionScript.parseNamed("{\"named\":[2,99,\"x\"],\"sincerity\":7}", DEEDS);
        assertEquals(Set.of(2L), named.ids(), "чужие id отброшены");
        assertEquals(2, named.sincerity(), "искренность в 0..2");
    }

    private Confession.Rite rite(boolean returning, boolean punished) {
        return new Confession.Rite(priest, "отец Сергий", player, "Дмитрий", Confession.Place.TEMPLE, returning, DEEDS, punished, 3, true, Set.of(), 0);
    }
    /** Договорить все реплики очереди; возвращает их. */
    private static List<Confession.Line> speak(Confession.Rite rite, long now) {
        List<Confession.Line> said = new java.util.ArrayList<>();
        for (Confession.Line line; (line = rite.claim()) != null; ) { said.add(line); rite.lineDone(line.seq(), now); }
        return said;
    }

    @Test void aWholeConfession() {
        Confession.Rite rite = rite(false, false);
        assertEquals("Подойди, чадо, к аналою.", speak(rite, 1).getFirst().text());
        rite.gathered(2);
        List<Confession.Line> exhortation = speak(rite, 3);
        assertEquals(2, exhortation.size());
        assertFalse(exhortation.get(0).translation().isBlank(), "перевод строкой");
        assertTrue(rite.listening());
        rite.heard("Убил жителя, каюсь", 4);
        assertEquals(0, rite.takeClassify());
        assertEquals(Confession.ASK_MORE, speak(rite, 5).getFirst().text());
        rite.heard("всё", 6);
        assertEquals(Confession.Rite.Phase.THINK, rite.phase());
        assertFalse(rite.takeCounsel(7), "ждёт классификатора");
        rite.classified(Set.of(2L, 77L), 2);
        assertTrue(rite.takeCounsel(8));
        assertEquals(Confession.Penance.CANDLE, rite.plannedPenance());
        rite.counselled("Тяжкий грех, чадо, но Бог милостив.", 9);
        assertEquals(Confession.Penance.CANDLE, rite.penance());
        assertTrue(rite.needsReturn());
        List<Confession.Line> counsel = speak(rite, 10);
        assertTrue(counsel.get(0).text().contains("Епитимья тебе: поставь свечу"));
        assertTrue(counsel.stream().anyMatch(l -> l.text().equals(Confession.BOW)));
        assertTrue(rite.wantsBow());
        rite.bowed(11);
        assertTrue(rite.takeAbsolve());
        assertFalse(rite.takeAbsolve(), "отпущение просят один раз");
        rite.absolvedNow(true, 12);
        List<Confession.Line> prayer = speak(rite, 13);
        assertTrue(prayer.get(0).action() && prayer.get(0).text().contains("епитрахилью"));
        assertTrue(prayer.get(1).audible() && prayer.get(1).text().contains("чадо Дмитрий"), "молитву слышат все рядом");
        assertTrue(prayer.get(2).action() && prayer.get(2).text().contains("целует Крест и Евангелие"));
        assertEquals(Confession.Rite.Phase.DONE, rite.phase());
        assertEquals(Set.of(2L), rite.named(), "чужой id не засчитан");
        assertEquals(2, rite.sincerity());
    }

    @Test void theReturnAfterThePenanceIsOnlyThePrayer() {
        Confession.Rite rite = rite(true, false);
        speak(rite, 1);
        rite.gathered(2);
        assertEquals(Confession.BOW, speak(rite, 3).getFirst().text());
        rite.bowed(4);
        assertTrue(rite.takeAbsolve());
        rite.absolvedNow(true, 5);
        speak(rite, 6);
        assertEquals(Confession.Rite.Phase.DONE, rite.phase());
    }

    @Test void mockerySilenceAndTheWatchdog() {
        Confession.Rite mocking = rite(false, false);
        speak(mocking, 1); mocking.gathered(2); speak(mocking, 3);
        mocking.heard("Бога нет, это всё", 4);
        assertTrue(mocking.mocking());
        mocking.classified(Set.of(1L), 1);
        mocking.counselled("", 5);
        assertTrue(mocking.refused());
        assertEquals(Confession.REFUSAL, speak(mocking, 6).getFirst().text());
        assertEquals(Confession.Rite.Phase.DONE, mocking.phase(), "отказ — исповедь была, но без молитвы");

        Confession.Rite silent = rite(false, false);
        speak(silent, 1); silent.gathered(2); speak(silent, 3);
        silent.tick(3 + Confession.Rite.SILENCE_MILLIS + 1);
        assertEquals(Confession.SILENT, speak(silent, 40_000).getFirst().text());
        assertEquals(Confession.Rite.Phase.CANCELLED, silent.phase(), "молчание — исповеди не было, откат не тратится");

        Confession.Rite stuck = rite(false, false);
        stuck.tick(Confession.Rite.WATCHDOG_MILLIS + 1);
        assertEquals(Confession.Rite.Phase.CANCELLED, stuck.phase());

        Confession.Rite punished = rite(false, true);
        speak(punished, 1); punished.gathered(2); speak(punished, 3);
        punished.heard("хулил бога, всё", 4);
        punished.classified(Set.of(1L), 1);
        assertTrue(punished.needsReturn(), "под идущей карой и лёгкое ждёт епитимьи");
    }

    @Test void thePenanceInTheWorldMemoryHasNoWordsOfTheConfession() throws Exception {
        MobTalkConfig config = new MobTalkConfig();
        ConversationStore store = ConversationStore.open(directory, config);
        try {
            ConfessionLedger ledger = store.confession();
            ledger.confessed(player, "Дмитрий", priest, "отец Сергий", "minecraft:villager", 1000, Confession.Penance.BOWS, 0);
            assertEquals(1000, ledger.lastFull(player));
            ledger.assign(player, Confession.Penance.BOWS, List.of(2L), priest, "отец Сергий", Confession.Place.TEMPLE, "minecraft:overworld", 10, 64, 0, true, 1000, 1000 + 48_000, 0);
            ConfessionLedger.Penance penance = ledger.penance(player);
            assertTrue(penance.hud(1000).startsWith("Епитимья: поклоны перед аналоем 0/12"), penance.hud(1000));
            for (int i = 0; i < 11; i++) ledger.progress(player, 1);
            assertFalse(ledger.penance(player).done());
            ledger.progress(player, 1);
            assertTrue(ledger.penance(player).done());
            assertEquals("Епитимья исполнена — вернись к батюшке (отец Сергий)", ledger.penance(player).hud(2000));
            assertEquals(List.of(2L), ledger.clear(player));
            assertNull(ledger.penance(player));
            List<String> events = store.events(priest);
            assertTrue(events.stream().anyMatch(e -> e.contains("Принял исповедь игрока Дмитрий") && e.contains("поклоны")), "батюшка помнит факт и епитимью");
            assertTrue(events.stream().allMatch(e -> !e.contains("убил") && !e.contains("хулил")), "слов исповеди в памяти нет");
        } finally { ConversationStore.closeWorld(directory); }
    }
}
