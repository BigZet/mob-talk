package io.github.mobtalk.game;

import io.github.mobtalk.core.ActionIntent;
import io.github.mobtalk.core.BargainIntent;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.DealIntent;
import io.github.mobtalk.core.FriendActions;
import io.github.mobtalk.core.GestureIntent;
import io.github.mobtalk.core.Initiative;
import io.github.mobtalk.core.MobSnapshot;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.QuestIntent;
import io.github.mobtalk.core.SocialIntent;
import io.github.mobtalk.core.VillageChatter;
import io.github.mobtalk.core.VillageNotices;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Что серверная логика диалога (трек A, {@code DialogueService}) просит у мира. {@link MobGameBridge} — настоящий мост к
 * серверу Minecraft; в тестах — фейк без Minecraft (раздел 16 плана, {@code FakeGameBridge}). Все методы безопасны с любого потока:
 * работа в мире идёт через {@link #onServer}. Сигнатуры только из ядра: ни одного класса Minecraft.
 * Трек A добавил к контракту S1 взгляды на моба и игрока ({@link MobView}, {@link PlayerView}), соседей, внимание, торговца,
 * слова семьи и свадьбы и очереди поводов (инициатива, болтовня, объявления). */
public interface GameBridge {
    /** Итог действия: строка для чата и ревизия карточки моба после фиксации. */
    record Outcome(String message, long revision) { }
    /** Живой моб в мире: сетевой номер для пакетов, имя (данное мира, иначе игровое), вид и позиция. */
    record MobView(int entityId, UUID id, String name, String type, String dimension, double x, double y, double z) {
        public double distanceTo(PlayerView player) { return Math.sqrt(squaredDistanceTo(player)); }
        public double squaredDistanceTo(PlayerView player) {
            double dx = x - player.x(), dy = y - player.y(), dz = z - player.z(); return dx * dx + dy * dy + dz * dz;
        }
    }
    /** Игрок на сервере: ник, мир, позиция, жив ли, моб его открытого торгового окна (null — окна нет) и время суток его мира (тики). */
    record PlayerView(UUID id, String name, String dimension, double x, double y, double z, boolean alive, UUID merchant, long timeOfDay) {
        /** Игровой день мира игрока. */
        public long day() { return Math.floorDiv(timeOfDay, 24_000L); }
    }

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

    // ---- Трек A: взгляды на мир для DialogueService. ----

    /** Живой моб с этим сетевым номером в мире игрока; падает, если его нет, он мёртв или игрок не в сети. */
    CompletableFuture<MobView> mobByEntityId(UUID playerId, int entityId);
    /** Живой моб по UUID в этом измерении; падает, если его нет или он мёртв. */
    CompletableFuture<MobView> mob(UUID mobId, String dimension);
    /** Игрок в сети; падает, если вышел. */
    CompletableFuture<PlayerView> player(UUID playerId);
    /** Живые игроки не дальше {@code range} блоков от моба в этом измерении; пусто, если моба нет. */
    CompletableFuture<List<UUID>> playersNear(UUID mobId, String dimension, double range);
    /** Живые знакомые мобы (с историей или именем в памяти) не дальше {@code range} от игрока. */
    CompletableFuture<List<UUID>> knownMobsNear(UUID playerId, double range);
    /** Моб стоит и смотрит на игрока ещё {@code ticks} тиков. */
    CompletableFuture<Void> attend(UUID mobId, UUID playerId, int ticks);
    /** Моб за открытым торговым окном игрока (свои сделки по B или профессия жителя); null — окна нет; падает, если игрок вышел. */
    CompletableFuture<UUID> currentMerchant(UUID playerId);
    /** Что сказанное значит для семьи и свадьбы (кольцо принято, «идём на свадьбу», «будь свидетелем», «пойдём спать», «сюрприз»):
     * строки для чата через перевод строки, пусто — ничего. Выключается серверным флагом семьи у вызывающего. */
    CompletableFuture<String> afterWords(UUID mobId, UUID playerId, String said, GestureIntent gesture);
    /** /mobtalk stop_all: все мобы перестают следовать; строка для чата. */
    CompletableFuture<String> stopAllFollowers(UUID playerId);
    /** /mobtalk village forgive: деревня вокруг игрока прощает его; строка для чата. */
    CompletableFuture<String> forgiveVillage(UUID playerId);
    /** Сколько предметов {@code itemId} у игрока в основном инвентаре и второй руке (для строки услуги в HUD). */
    CompletableFuture<Integer> countInHand(UUID playerId, String itemId);
    /** Аренды STAY/GUARD/SPARE моба для /mobtalk state; с любого потока. */
    List<FriendActions.Status> friendStatus(UUID mobId);

    // ---- Трек A: очереди поводов, которые мир поднимает сам. ----

    /** Следующий повод заговорить первым; при занятом эфире — только подарок или семейный повод для моба не из {@code inFlight}. */
    Initiative.Cue pollInitiative(boolean floorBusy, Set<UUID> inFlight);
    /** Моб промолчал или ушёл: минута мира возвращается. */
    void refundInitiative(Initiative.Cue cue);
    /** Сколько поводов ждут. */
    int pendingInitiatives();
    /** Эфир занят (запись, ответ, голос): мир не поднимает обычных поводов. */
    void floorBusy(boolean busy);
    /** Следующая пара жителей для болтовни; null — нет. */
    VillageChatter.Cue pollTalk();
    void refundTalk(VillageChatter.Cue cue);
    boolean talkWaiting();
    /** Болтовня пишется или звучит: мир не ищет новую пару. */
    void talking(boolean on);
    /** Следующее объявление жителя в чат; null — нет. */
    VillageNotices.Cue pollNotice();
    /** Идёт свадьба у колокола: посторонние реплики молчат. */
    boolean weddingActive();
    /** Идущая свадьба для серверного ведущего ({@code WeddingHost}): служба, тексты, жених-игрок (null — женятся жители) и измерение;
     * null — свадьбы нет. */
    record WeddingView(io.github.mobtalk.core.Wedding.Ceremony ceremony, io.github.mobtalk.core.WeddingScript.Input input, UUID player, String dimension) {
        public UUID spouse() { return ceremony.cast().spouse().id(); }
    }
    default WeddingView wedding() { return null; }
    /** Тексты свадьбы просить у модели: один раз за свадьбу, иначе null. */
    default io.github.mobtalk.core.WeddingScript.Input takeWeddingScript() { return null; }
}
