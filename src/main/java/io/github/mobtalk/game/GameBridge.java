package io.github.mobtalk.game;

import io.github.mobtalk.core.ActionIntent;
import io.github.mobtalk.core.BargainIntent;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.DealIntent;
import io.github.mobtalk.core.GestureIntent;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.QuestIntent;
import io.github.mobtalk.core.SocialIntent;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Что серверная логика диалога (трек A, {@code DialogueService}) просит у мира. {@link MobGameBridge} — настоящий мост к
 * серверу Minecraft; в тестах — фейк без Minecraft (раздел 16 плана, {@code FakeGameBridge}). Все методы безопасны с любого потока:
 * работа в мире идёт через {@link #onServer}. Сигнатуры только из ядра: ни одного класса Minecraft. */
public interface GameBridge {
    /** Итог действия: строка для чата и ревизия карточки моба после фиксации. */
    record Outcome(String message, long revision) { }

    /** Память мира (карточки, слухи, деревни). */
    ConversationStore memory();
    /** Серверный снимок настроек, по которому живёт мост. */
    MobTalkConfig config();

    /** Снимок моба и места вокруг него для промпта; исключение — понятная игроку причина («Подойдите к мобу ближе»). */
    CompletableFuture<MobSnapshot> capture(UUID mobId, UUID playerId, String dimension);
    /** Короткая форма {@link #applyWithRevision}: только намерение действия, без торга и жестов. */
    CompletableFuture<String> apply(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            MobSnapshot observed, long expectedRevision, BooleanSupplier valid);
    /** Применить решения ответа к миру и памяти: действие, торг, сделку, разблокировку, поручение, слова и жест. */
    CompletableFuture<Outcome> applyWithRevision(UUID mobId, UUID playerId, String dimension, ActionIntent intent,
            BargainIntent bargain, DealIntent deal, String unlock, QuestIntent quest, SocialIntent social, GestureIntent gesture,
            MobSnapshot observed, long expectedRevision, BooleanSupplier valid);
    /** Подарок из руки игрока (клавиша G); строка для чата. */
    CompletableFuture<String> giftFromHand(UUID mobId, UUID playerId);
    /** Открыть окно сделок моба у игрока. */
    CompletableFuture<Void> openTrade(UUID mobId, UUID playerId, String dimension);
    /** Знакомые мобы, слышавшие говорящего, не больше {@code limit}. */
    CompletableFuture<List<UUID>> witnesses(UUID speakerId, UUID playerId, String dimension, int limit);
    /** Деревня, в которой стоит игрок (по постройке или по старому правилу радиуса); null вне деревень. Потокобезопасно. */
    ConversationStore.Village villageAt(UUID playerId, String dimension, int x, int z);
    /** Панель «Моб и деревня» для /mobtalk info. */
    CompletableFuture<String> info(UUID mobId, UUID playerId, String dimension, int x, int z, boolean secrets);
    /** Запись в дневник моба («The player met this mob»); моб ищется по памяти, незнакомец пропускается. */
    void event(UUID mobId, String text);
    /** Выполнить на потоке сервера; будущее падает, если мир сменился. */
    <T> CompletableFuture<T> onServer(Supplier<T> work);
}
