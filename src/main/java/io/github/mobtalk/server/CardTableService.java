package io.github.mobtalk.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mobtalk.core.CardLedger;
import io.github.mobtalk.core.CardLines;
import io.github.mobtalk.core.ConversationStore;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.cards.Action;
import io.github.mobtalk.core.cards.CardTable;
import io.github.mobtalk.core.cards.DealerStyle;
import io.github.mobtalk.core.cards.TableEvent;
import io.github.mobtalk.core.cards.Variant;
import io.github.mobtalk.net.CardTableActionC2S;
import io.github.mobtalk.net.CardTableOpenS2C;
import io.github.mobtalk.net.CardTableStateS2C;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/** Игра в 21 на сервере (docs/cards-21.md, этап 2): столы по UUID жителя-банкира, места и зрители, залог ставок в памяти мира
 * ({@link CardLedger}), таймеры хода, окно «Ловлю!», проверки и отмена раунда с возвратом ставок. Правила раунда — {@link CardTable};
 * мир — {@link World}, пакеты — {@link Outbox}: ни одного класса Minecraft, в тестах — фейки. Всё на потоке сервера.
 *
 * <p>Изумруды: ставка сразу уходит из инвентаря в залог стола ({@link CardLedger#staked}); после раунда выплата идёт в инвентарь,
 * залог снимается. Игрок вышел, сервер остановился, житель погиб, ушёл, его ударил чужой или моб, рейд — раунд отменяется, ставки
 * возвращаются (вышедшему — при следующем входе, {@link CardLedger#owe}). Сам встал посреди раунда или сам ударил жителя — ставка
 * остаётся банку. */
public final class CardTableService {
    /** Ближе скольких блоков садятся и держатся за столом. */
    public static final double SIT_RANGE = 6, STAY_RANGE = 8;
    /** Окно «Ловлю!» после расчёта и ожидание остальных, когда кто-то уже нажал «Раздать». */
    public static final long CATCH_MILLIS = 3_000, DEAL_WAIT_MILLIS = 15_000;
    /** Отношение, при котором и ниже житель не садится играть; нажатий в секунду с одного игрока. */
    public static final int MIN_RELATION = -40, ACTIONS_PER_SECOND = 10;
    private static final Gson GSON = new Gson();

    /** Что службе нужно от мира. Все вызовы с потока сервера. */
    public interface World {
        /** Живой житель под этим сетевым номером в мире игрока; null — нет такого. */
        Villager villager(UUID player, int entityId);
        /** Живой загруженный житель по UUID в любом измерении; null — погиб, выгрузился. */
        Villager villager(UUID id);
        /** Ближайший к игроку взрослый житель не дальше {@code range} (для команды); null — нет. */
        Villager nearest(UUID player, double range);
        /** Игрок в сети; null — вышел. */
        Person player(UUID id);
        /** Изумруды в инвентаре игрока. */
        int emeralds(UUID player);
        /** Забрать изумруды из инвентаря; false — не хватило (ничего не взято). */
        boolean take(UUID player, int emeralds);
        /** Отдать изумруды в инвентарь, лишнее — под ноги. */
        void give(UUID player, int emeralds);
        /** Житель стоит и смотрит на игрока ещё {@code ticks} тиков (STAY за столом). */
        void hold(UUID villager, UUID player, int ticks);
        /** Игровой день основного мира. */
        long day();
        /** Предмет в главной руке игрока: id, количество и простой ли он (без зачарований, имени и прочих данных); null — пусто. */
        default Held held(UUID player) { return null; }
        /** Забрать из главной руки {@code count} предметов {@code item}; false — в руке уже другое. */
        default boolean takeHeld(UUID player, String item, int count) { return false; }
        /** Отдать предметы в инвентарь, лишнее — под ноги. */
        default void giveItem(UUID player, String item, int count) { }
        /** Выигрыш скидки на кону: одноразовая строка со скидкой {@code percent}% в окне торговли жителя; описание или "" — нечего. */
        default String prize(UUID villager, UUID player, int percent) { return ""; }
        /** Игроки в сети (для приглашений). */
        default List<UUID> online() { return List.of(); }
        /** Время суток основного мира, тики 0..23999. */
        default long timeOfDay() { return 6000; }
        /** Игрок сейчас разговаривает с мобом (приглашение подождёт). */
        default boolean busy(UUID player) { return false; }
        /** Житель за столом или встал: подпись «играет в 21» над ним и игроками. */
        default void playing(UUID villager, List<UUID> players, boolean on) { }
    }
    /** Житель в мире: сетевой номер, имя, где стоит, взрослый ли, спит, в панике, рейд у деревни, профессия ({@code minecraft:cleric}) и уровень. */
    public record Villager(UUID id, int entityId, String name, String dimension, double x, double y, double z,
                           boolean adult, boolean sleeping, boolean panicking, boolean raid, String profession, int level) {
        public double distanceTo(Person p) {
            double dx = x - p.x(), dy = y - p.y(), dz = z - p.z();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
    public record Person(UUID id, String name, String dimension, double x, double y, double z, boolean alive) { }
    public record Held(String item, int count, boolean plain) { }
    /** Куда уходят пакеты; игрок без мода на клиенте пропускается. */
    public interface Outbox {
        void open(UUID player, CardTableOpenS2C packet);
        void state(UUID player, CardTableStateS2C packet);
        void chat(UUID player, String line);
        /** Житель зовёт сыграть: строка в чат с кнопкой «сесть за стол». */
        default void invite(UUID player, String villager, UUID villagerId, String line) {
            chat(player, villager + ": «" + line + "» — ПКМ колодой по жителю, чтобы сесть за стол");
        }
        /** Игрок встал из-за стола: сессия для «Истории» админки. */
        default void session(CardLedger.Session session, int purse, String style) { }
    }
    /** Кто пишет реплики жителя: модель на фоне с заготовкой при опоздании ({@link CardLines}). {@code done} — на потоке сервера. */
    public interface Talk {
        void line(UUID villager, UUID player, CardLines.Input input, java.util.function.Consumer<CardLines.Line> done);
        /** Без модели: сразу заготовка (тесты, выключенный ИИ). */
        Talk FALLBACK = (villager, player, input, done) -> done.accept(CardLines.fallback(input.event(), input.style(), System.nanoTime()));
    }
    /** Крупная ставка: от неё перебор, выигрыш и проигрыш — повод для реплики. */
    public static final int BIG_STAKE = 6;
    /** Раздач без слов, после которых житель скажет что-нибудь сам. */
    public static final int QUIET_ROUNDS = 3;

    private final World world;
    private final Outbox out;
    private final ConversationStore memory;
    private final Supplier<MobTalkConfig> config;
    private final LongSupplier clock;
    private final Map<UUID, Table> tables = new LinkedHashMap<>();
    /** Игрок → житель, за чьим столом он сидит или смотрит. */
    private final Map<UUID, UUID> where = new HashMap<>();
    private final Map<UUID, Deque<Long>> presses = new HashMap<>();
    private Long nextSeed;
    private List<io.github.mobtalk.core.cards.Card> nextOrder;
    private DealerStyle nextCheater;
    private long ticks;
    private volatile Talk talk = Talk.FALLBACK;

    public CardTableService(World world, Outbox out, ConversationStore memory, Supplier<MobTalkConfig> config, LongSupplier clock) {
        this.world = world; this.out = out; this.memory = memory; this.config = config; this.clock = clock;
    }
    /** Подключить модель для реплик (игра подставляет фоновый запрос с заготовкой при опоздании). */
    public void setTalk(Talk talk) { this.talk = talk == null ? Talk.FALLBACK : talk; }

    private CardLedger ledger() { return memory.cards(); }
    private MobTalkConfig cfg() { return config.get(); }
    /** Личные настройки стола игрока (F7, {@code table*}): приглашения и реплики жителя. */
    private java.util.function.Function<UUID, MobTalkConfig> personal = player -> PlayerSettingsStore.of(player).applyTo(cfg());
    void personal(java.util.function.Function<UUID, MobTalkConfig> personal) { this.personal = personal; }
    private String lines(UUID player) { return player == null ? "key" : personal.apply(player).tableLines; }

    /** Не изумрудная часть ставки места на эту раздачу: предметы, услуга, игра на интерес, скидка жителя на кону. */
    static final class Extras {
        final List<CardLedger.ItemStake> items = new ArrayList<>();
        int itemValue, discount;
        boolean favor, interest;
        int virtual() { return itemValue + (favor ? CardLedger.FAVOR_VALUE : 0) + (interest ? 1 : 0); }
        boolean any() { return !items.isEmpty() || favor || interest || discount > 0; }
        void clear() { items.clear(); itemValue = 0; discount = 0; favor = false; interest = false; }
    }

    /** Один стол: житель, правила, места (UUID игрока по номеру места), зрители, таймеры и итоги сессии каждого игрока. */
    final class Table {
        final UUID villager;
        Villager last;
        final CardTable game;
        final UUID[] seats;
        final String[] names;
        final Set<UUID> watchers = new LinkedHashSet<>();
        final boolean[] ready;
        final List<TableEvent> events = new ArrayList<>();
        final Map<UUID, int[]> sessions = new LinkedHashMap<>(); // игрок → {раундов, итог}
        final Extras[] extras;
        int version, settledRound, turnSeat = -1, turnHand = -1;
        long turnDeadline, dealDeadline, catchUntil;
        String line = "", emotion = "";
        /** Реплика — ответ на «Сказать…»: её видят и те, у кого житель молчит. */
        boolean lineSaid;
        /** В этой раздаче житель уже говорил; раздач подряд без слов. */
        boolean spoke;
        int quiet;
        long lastSaid;
        Table(Villager villager, CardTable game) {
            this.villager = villager.id(); this.last = villager; this.game = game;
            this.seats = new UUID[game.limits().seats()]; this.names = new String[seats.length]; this.ready = new boolean[seats.length];
            this.extras = new Extras[seats.length];
            for (int i = 0; i < extras.length; i++) extras[i] = new Extras();
        }
        int seatOf(UUID player) { for (int i = 0; i < seats.length; i++) if (player.equals(seats[i])) return i; return -1; }
        List<UUID> players() { List<UUID> list = new ArrayList<>(); for (UUID id : seats) if (id != null) list.add(id); return list; }
        List<UUID> audience() { List<UUID> list = players(); list.addAll(watchers); return list; }
        boolean empty() { return game.occupied() == 0 && watchers.isEmpty(); }
    }

    // ---- Жизненный цикл. ----

    /** Мир открыт: ставки, что лежали на столах при падении сервера, становятся долгом игрокам. */
    public void start() {
        int players = ledger().recover();
        if (players > 0) org.slf4j.LoggerFactory.getLogger("mobtalk").info("Mob Talk: игра в 21 — ставки {} игроков со столов вернутся при входе", players);
    }
    /** Сервер останавливается: все раунды отменены, ставки — долгом до следующего входа (инвентарь уже может быть сохранён). */
    public void close() {
        for (Table table : List.copyOf(tables.values())) shut(table, "Сервер останавливается", true);
    }
    /** Игрок вошёл: вернуть, что осталось на столах. */
    public void join(UUID player) {
        List<CardLedger.ItemStake> items = ledger().takeOwedItems(player);
        for (CardLedger.ItemStake item : items) world.giveItem(player, item.item(), item.count());
        if (!items.isEmpty()) out.chat(player, "[Mob Talk] Со стола вернулись ваши предметы");
        int owed = ledger().takeOwed(player);
        if (owed <= 0) return;
        world.give(player, owed);
        out.chat(player, "[Mob Talk] Со стола вернулись ваши ставки: " + owed + " изумр.");
    }
    /** Игрок вышел: его ставки вернутся при входе; стол без людей закрывается. */
    public void leave(UUID player) {
        presses.remove(player);
        Table table = tableOf(player);
        if (table == null) return;
        int seat = table.seatOf(player);
        if (seat >= 0) {
            int virtual = table.extras[seat].virtual();
            int staked = table.game.staked(seat);
            int back = table.game.leave(seat, true);
            ledger().staked(player, -(staked - virtual));
            ledger().owe(player, Math.max(0, back - virtual));
            releaseExtras(table, seat, player, true, true);
            unseat(table, seat, "вышел из игры");
        } else table.watchers.remove(player);
        where.remove(player);
        if (table.empty()) shut(table, "", true);
        else afterChange(table);
    }
    /** Самотест и {@code /mobtalk cards deck <сид>}: следующий новый стол тасует этим сидом. */
    public void deck(long seed) { nextSeed = seed; }
    /** Самотест: следующий новый стол сдаёт первую раздачу ровно в этом порядке (дальше — тасовка). */
    public void stack(List<io.github.mobtalk.core.cards.Card> order) { nextOrder = order == null ? null : List.copyOf(order); }
    /** Самотест «Ловлю!»: следующий новый стол с этим порядком, житель играет стилем {@code cheater} и тянет из рукава при первой возможности. */
    public void stackCheat(List<io.github.mobtalk.core.cards.Card> order, DealerStyle cheater) { stack(order); nextCheater = cheater; }

    // ---- Открыть стол. ----

    /** ПКМ колодой по жителю, {@code game: OFFER} или команда: сесть за стол жителя (или смотреть, если мест нет). Возвращает отказ
     * или "" — стол открыт. {@code variant} пусто — правила сервера. */
    public String open(UUID player, int entityId, String variant) {
        return open(player, world.villager(player, entityId), variant);
    }
    /** Команда без цели: ближайший взрослый житель. */
    public String openNearest(UUID player, String variant) {
        return open(player, world.nearest(player, SIT_RANGE), variant);
    }
    private String open(UUID player, Villager villager, String variant) {
        String refusal = refusal(player, villager);
        if (refusal != null) { out.chat(player, "[Mob Talk] " + refusal); return refusal; }
        Table table = tables.get(villager.id());
        Table mine = tableOf(player);
        if (mine != null && mine != table) {
            String text = "Вы уже за столом у " + mine.last.name();
            out.chat(player, "[Mob Talk] " + text);
            return text;
        }
        Person person = world.player(player);
        if (table == null) {
            MobTalkConfig c = cfg();
            int purse = ledger().purse(villager.id(), villager.level(), world.day(), c.cardsPurseStart, c.cardsPursePerDay);
            if (purse < c.cardsMinBet) { String text = villager.name() + " на мели: приходите завтра"; out.chat(player, "[Mob Talk] " + text); return text; }
            Variant rules = variant == null || variant.isBlank() ? Variant.of(c.cardsVariant) : Variant.of(variant);
            RandomGenerator random = nextSeed != null ? new SplittableRandom(nextSeed) : new SecureRandom();
            if (nextCheater != null) {
                // Рукав срабатывает всегда: доля «случайности» жульничества — ноль.
                SplittableRandom base = new SplittableRandom(21);
                random = new RandomGenerator() {
                    @Override public long nextLong() { return base.nextLong(); }
                    @Override public double nextDouble() { return 0; }
                };
            }
            nextSeed = null;
            io.github.mobtalk.core.cards.Shoe shoe = nextOrder == null ? null : io.github.mobtalk.core.cards.Shoe.stacked(rules, random, nextOrder);
            nextOrder = null;
            DealerStyle style = nextCheater != null ? nextCheater : ledger().style(villager.id());
            nextCheater = null;
            CardTable game = new CardTable(rules, style, random, shoe, new CardTable.Limits(c.cardsSeats, c.cardsMinBet, c.cardsMaxBet, c.cardsCheating), purse);
            table = new Table(villager, game);
            tables.put(villager.id(), table);
        }
        table.last = villager;
        int seat = table.seatOf(player);
        if (seat < 0 && !table.watchers.contains(player)) {
            seat = table.game.freeSeat();
            if (seat >= 0) { sit(table, seat, player, person == null ? "" : person.name()); speak(table, CardLines.Event.START, player, "", "", true); }
            else table.watchers.add(player);
        }
        where.put(player, villager.id());
        world.playing(villager.id(), table.players(), true);
        out.open(player, new CardTableOpenS2C(villager.entityId(), villager.name(), seat, table.game.variant().name(),
                table.game.limits().minBet(), table.game.limits().maxBet(), table.seats.length));
        afterChange(table);
        return "";
    }
    /** Почему этот житель не сядет с этим игроком; null — сядет. Отказ всегда объясняется словами. */
    String refusal(UUID player, Villager villager) {
        MobTalkConfig c = cfg();
        if (!c.enabled || !c.cards) return "Игра в 21 выключена на сервере";
        if (villager == null) return "Рядом нет жителя, с кем сыграть";
        Table table = tables.get(villager.id());
        if (table != null && (table.seatOf(player) >= 0 || table.watchers.contains(player))) return null; // Уже за этим столом: просто открыть.
        if (!villager.adult()) return "Дети в карты не играют";
        if ("minecraft:cleric".equals(villager.profession())) return villager.name() + ": «Азарт — грех, чадо. Карт в руки не возьму, а за тебя помолюсь»";
        if (villager.sleeping()) return villager.name() + " спит";
        if (villager.panicking()) return villager.name() + " сейчас не до карт";
        if (villager.raid()) return "На деревню напали — не до карт";
        Person person = world.player(player);
        if (person == null || !person.alive()) return "Игрок недоступен";
        if (!person.dimension().equals(villager.dimension()) || villager.distanceTo(person) > SIT_RANGE)
            return "Подойдите к жителю ближе (до " + (int) SIT_RANGE + " блоков)";
        if (memory.relationship(villager.id(), player) <= MIN_RELATION) return villager.name() + ": «С тобой я за стол не сяду»";
        return null;
    }

    private void sit(Table table, int seat, UUID player, String name) {
        table.game.sit(seat, name);
        table.seats[seat] = player;
        table.names[seat] = name;
        table.ready[seat] = false;
        table.watchers.remove(player);
        table.sessions.computeIfAbsent(player, id -> new int[2]);
    }
    /** Место освободилось: запись сессии этого игрока в историю. */
    private void unseat(Table table, int seat, String note) {
        UUID player = table.seats[seat];
        table.seats[seat] = null;
        table.ready[seat] = false;
        if (player == null) return;
        int[] session = table.sessions.remove(player);
        if (session != null && session[0] > 0) {
            long now = clock.getAsLong();
            int wonBefore = ledger().wonToday(table.villager, player, now);
            CardLedger.Session record = new CardLedger.Session(now, player.toString(), table.names[seat], table.villager.toString(), table.last.name(),
                    table.game.variant().name(), session[0], session[1], note);
            ledger().record(record);
            out.session(record, table.game.purse(), table.game.style().name());
            ledger().afterSession(table.villager, "minecraft:villager", table.last.name(), player, table.names[seat], session[0], session[1],
                    wonBefore + Math.max(0, session[1]), Math.max(1, cfg().cardsPurseStart + 4 * (Math.max(1, table.last.level()) - 1)), now);
            if (!note.contains("ударил")) speak(table, CardLines.Event.END, player, "сыграли " + session[0] + " раздач, итог игрока " + session[1], "", true);
        }
    }

    // ---- Нажатия. ----

    /** Пакет {@link CardTableActionC2S}: проверки сервера (тот ли стол, чей ход, хватает ли изумрудов, частота) и ход в игре. */
    public void action(UUID player, CardTableActionC2S packet) {
        if (!allowPress(player)) return;
        Table table = tableOf(player);
        if (table == null || table.last.entityId() != packet.entityId()) return;
        String action = packet.action() == null ? "" : packet.action().toLowerCase(Locale.ROOT);
        int seat = table.seatOf(player);
        String refusal = switch (action) {
            case "bet" -> seat < 0 ? "Вы смотрите: сядьте за стол" : bet(table, seat, player, packet.amount());
            case "unbet" -> seat < 0 ? null : unbet(table, seat, player, packet.amount());
            case "deal" -> seat < 0 ? null : ready(table, seat);
            case "hit" -> press(table, seat, player, Action.HIT);
            case "stand" -> press(table, seat, player, Action.STAND);
            case "double" -> press(table, seat, player, Action.DOUBLE);
            case "split" -> press(table, seat, player, Action.SPLIT);
            case "vabank" -> press(table, seat, player, Action.VA_BANK);
            case "catch" -> seat < 0 ? null : accuse(table, seat, player);
            case "sit" -> sitDown(table, player);
            case "watch" -> watch(table, seat, player);
            case "leave" -> { getUp(table, seat, player); yield null; }
            case "say" -> say(table, player, packet.text());
            case "item" -> seat < 0 ? null : stakeItem(table, seat, player);
            case "favor" -> seat < 0 ? null : stakeFavor(table, seat, player);
            case "interest" -> seat < 0 ? null : stakeInterest(table, seat, player);
            case "discount" -> seat < 0 ? null : askDiscount(table, seat, player);
            default -> null;
        };
        if (refusal != null && !refusal.isEmpty()) notice(table, player, refusal);
        if (tables.get(table.villager) == table) afterChange(table);
    }

    private String bet(Table table, int seat, UUID player, int amount) {
        if (amount <= 0) return null;
        if (table.game.phase() != CardTable.Phase.BETTING) return "Ставки уже сделаны: ждите следующей раздачи";
        int limit = cfg().cardsDailyLoss;
        if (limit > 0) {
            int lost = Math.max(0, -ledger().net(player, world.day()));
            if (lost + table.game.staked(seat) + amount > limit) return "Хватит на сегодня: проиграть за день можно до " + limit + " изумр.";
        }
        if (table.extras[seat].interest) return "Вы играете на интерес: снимите ставку, чтобы поставить изумруды";
        if (ledger().married(table.villager, player)) return table.last.name() + ": «С тобой — только на интерес, родная душа»";
        if (world.emeralds(player) < amount) return "Не хватает изумрудов";
        CardTable.Refusal refused = table.game.bet(seat, amount);
        if (refused != CardTable.Refusal.NONE) return refusal(table, refused);
        if (!world.take(player, amount)) { table.game.unbet(seat, amount); return "Не хватает изумрудов"; }
        ledger().staked(player, amount);
        table.ready[seat] = false;
        return null;
    }
    private String unbet(Table table, int seat, UUID player, int amount) {
        Extras x = table.extras[seat];
        if (x.any() && table.game.phase() == CardTable.Phase.BETTING) {
            // Снять всё: предметы в руки, услуга и интерес отменены, изумрудная часть — в инвентарь.
            int all = table.game.unbet(seat, Integer.MAX_VALUE);
            int real = Math.max(0, all - x.virtual());
            for (CardLedger.ItemStake item : ledger().releaseItems(player)) world.giveItem(player, item.item(), item.count());
            if (real > 0) { world.give(player, real); ledger().staked(player, -real); }
            x.clear();
            table.ready[seat] = false;
            return null;
        }
        int back = table.game.unbet(seat, amount <= 0 ? Integer.MAX_VALUE : amount);
        if (back <= 0) return null;
        world.give(player, back);
        ledger().staked(player, -back);
        table.ready[seat] = false;
        return null;
    }
    // ---- Ставки не изумрудами (этап 5). ----

    private String stakeCheck(Table table, int seat, UUID player) {
        if (table.game.phase() != CardTable.Phase.BETTING) return "Ставки уже сделаны: ждите следующей раздачи";
        if (table.extras[seat].interest) return "Вы играете на интерес";
        if (ledger().married(table.villager, player)) return table.last.name() + ": «С тобой — только на интерес, родная душа»";
        if (!cfg().cardsItemStakes) return "Ставки предметами и услугами на сервере выключены";
        return null;
    }
    /** Предмет из главной руки на кон: житель оценивает его в изумрудах ({@link CardLedger#itemValue}); берёт столько штук, сколько
     * помещается в ставку на руку. */
    private String stakeItem(Table table, int seat, UUID player) {
        String refusal = stakeCheck(table, seat, player);
        if (refusal != null) return refusal;
        Held held = world.held(player);
        if (held == null || held.count() <= 0) return "Возьмите в руку то, что ставите";
        int value = CardLedger.itemValue(held.item());
        if (value <= 0 || !held.plain()) return table.last.name() + ": «Такое я на кон не возьму»";
        int room = table.game.limits().maxBet() - table.game.view().seats().get(seat).pendingBet();
        int count = Math.min(held.count(), room / value);
        if (count <= 0) return "Ставка на руку — до " + table.game.limits().maxBet() + " изумр.: " + held.item().substring(held.item().indexOf(':') + 1) + " стоит " + value;
        CardTable.Refusal refused = table.game.bet(seat, count * value);
        if (refused != CardTable.Refusal.NONE) return refusal(table, refused);
        if (!world.takeHeld(player, held.item(), count)) { table.game.unbet(seat, count * value); return "Предмет выпал из рук"; }
        CardLedger.ItemStake stake = new CardLedger.ItemStake(held.item(), count);
        ledger().stakeItem(player, stake);
        Extras x = table.extras[seat];
        x.items.add(stake);
        x.itemValue += count * value;
        table.ready[seat] = false;
        return null;
    }
    /** Услуга на кону: проиграл — принесёшь жителю то, что он попросит («Карточный долг»). */
    private String stakeFavor(Table table, int seat, UUID player) {
        String refusal = stakeCheck(table, seat, player);
        if (refusal != null) return refusal;
        Extras x = table.extras[seat];
        if (x.favor) return "Услуга уже на кону";
        if (ledger().favorOpen(table.villager, player)) return table.last.name() + ": «Ты мне и так услугу должен»";
        CardTable.Refusal refused = table.game.bet(seat, CardLedger.FAVOR_VALUE);
        if (refused != CardTable.Refusal.NONE) return refusal(table, refused);
        x.favor = true;
        table.ready[seat] = false;
        return null;
    }
    /** На интерес: ставка в один изумруд понарошку, ничего не платится; житель теплеет (+1, до 5 раз в день). */
    private String stakeInterest(Table table, int seat, UUID player) {
        if (table.game.phase() != CardTable.Phase.BETTING) return "Ставки уже сделаны: ждите следующей раздачи";
        Extras x = table.extras[seat];
        if (x.interest) return null;
        if (table.game.view().seats().get(seat).pendingBet() > 0) return "Сначала снимите ставку";
        CardTable.Refusal refused = table.game.bet(seat, 1);
        if (refused != CardTable.Refusal.NONE) return refusal(table, refused);
        x.interest = true;
        table.ready[seat] = false;
        return null;
    }
    /** Попросить скидку на кон: житель (отношение от 15) ставит скидку 10–30% на один свой товар — выиграешь раздачу, она твоя. Раз в день. */
    private String askDiscount(Table table, int seat, UUID player) {
        if (table.game.phase() != CardTable.Phase.BETTING) return "Ставки уже сделаны: ждите следующей раздачи";
        Extras x = table.extras[seat];
        if (x.discount > 0 || x.interest) return null;
        if (!cfg().cardsItemStakes) return "Ставки сделками на сервере выключены";
        if (memory.relationship(table.villager, player) < CardLedger.DISCOUNT_RELATION) return table.last.name() + ": «Скидку? Мы не настолько знакомы»";
        if (!ledger().discountToday(table.villager, player, world.day())) return table.last.name() + ": «Скидку я сегодня уже ставил»";
        x.discount = CardLedger.DISCOUNT_MIN;
        return null;
    }
    /** Предметы и прочее места после раздачи, отмены или ухода: {@code back} — вернуть (в инвентарь или долгом при {@code owe}). */
    private void releaseExtras(Table table, int seat, UUID player, boolean back, boolean owe) {
        Extras x = table.extras[seat];
        List<CardLedger.ItemStake> items = ledger().releaseItems(player);
        if (back) {
            if (owe || world.player(player) == null) ledger().oweItems(player, items);
            else for (CardLedger.ItemStake item : items) world.giveItem(player, item.item(), item.count());
        }
        x.clear();
    }

    /** «Раздать»: раздача, когда готовы все, кто поставил; иначе остальным ещё {@link #DEAL_WAIT_MILLIS}. */
    private String ready(Table table, int seat) {
        if (table.game.phase() != CardTable.Phase.BETTING) return null;
        if (table.game.view().seats().get(seat).pendingBet() < table.game.limits().minBet()) return "Сначала поставьте: от " + table.game.limits().minBet() + " изумр.";
        table.ready[seat] = true;
        if (allReady(table)) return dealNow(table);
        if (table.dealDeadline == 0) table.dealDeadline = clock.getAsLong() + DEAL_WAIT_MILLIS;
        return null;
    }
    private boolean allReady(Table table) {
        List<CardTable.SeatView> seats = table.game.view().seats();
        boolean any = false;
        for (int i = 0; i < table.seats.length; i++) {
            if (table.seats[i] == null) continue;
            // Сидящий без ставки ещё думает: сразу не раздаём, ему — {@link #DEAL_WAIT_MILLIS}; кто не поставил к сроку, пропускает раздачу.
            boolean staked = seats.get(i).pendingBet() >= table.game.limits().minBet();
            if (!table.ready[i]) return false;
            any |= staked;
        }
        return any;
    }
    private String dealNow(Table table) {
        table.dealDeadline = 0;
        table.spoke = false; // Новая раздача — житель снова может сказать слово.
        java.util.Arrays.fill(table.ready, false);
        CardTable.Refusal refused = table.game.deal();
        return refused == CardTable.Refusal.NONE ? null : refusal(table, refused);
    }
    private String press(Table table, int seat, UUID player, Action action) {
        if (seat < 0) return null;
        int wallet = world.emeralds(player);
        CardTable.Outcome outcome = table.game.act(seat, action, wallet);
        if (!outcome.ok()) return refusal(table, outcome.refusal());
        if (action == Action.VA_BANK) speak(table, CardLines.Event.VA_BANK, player, "ставка выросла до " + table.game.staked(seat), "", true);
        if (outcome.paid() > 0) {
            if (world.take(player, outcome.paid())) ledger().staked(player, outcome.paid());
            // Инвентарь проверен этим же тиком; если изумруды всё же пропали, ставка остаётся — проигрыш спишется с залога как обычно.
        }
        return null;
    }
    private String accuse(Table table, int seat, UUID player) {
        if (table.game.phase() != CardTable.Phase.SETTLED || clock.getAsLong() > table.catchUntil) return "Поздно: ловят сразу после раздачи";
        CardTable.Accusation accusation = table.game.accuse(seat);
        if (accusation.refusal() != CardTable.Refusal.NONE) return accusation.refusal() == CardTable.Refusal.NOT_ALLOWED ? "Вы уже ловили в этой раздаче" : null;
        long now = clock.getAsLong();
        if (accusation.caught()) {
            ledger().caught(table.villager, "minecraft:villager", table.last.name(), player, table.names[seat], now);
            speak(table, CardLines.Event.CAUGHT, player, "игрок крикнул «Ловлю!» и увидел карту из рукава", "", true);
        } else {
            ledger().falselyAccused(table.villager, "minecraft:villager", table.last.name(), player, table.names[seat], now);
            speak(table, CardLines.Event.FALSE_ACCUSE, player, "игрок крикнул «Ловлю!», но ты играл честно", "", true);
        }
        if (accusation.caught() && accusation.extra() > 0) {
            world.give(player, accusation.extra());
            ledger().addNet(player, world.day(), accusation.extra());
            int[] session = table.sessions.get(player);
            if (session != null) session[1] += accusation.extra();
            ledger().setPurse(table.villager, table.game.purse());
        }
        return null;
    }
    private String sitDown(Table table, UUID player) {
        if (table.seatOf(player) >= 0) return null;
        int seat = table.game.freeSeat();
        if (seat < 0) return "Мест нет: смотрите";
        Person person = world.player(player);
        sit(table, seat, player, person == null ? "" : person.name());
        world.playing(table.villager, table.players(), true);
        return null;
    }
    private String watch(Table table, int seat, UUID player) {
        if (seat < 0) return null;
        if (table.game.staked(seat) > 0) return "Сначала доиграйте раздачу или заберите ставку";
        table.game.leave(seat, true);
        unseat(table, seat, "смотрит");
        table.watchers.add(player);
        return null;
    }
    /** Игрок уходит сам (или сам ударил жителя): до раздачи всё возвращается, посреди раунда ставка на руках остаётся банку — изумруды
     * и предметы; услуга и интерес просто отменяются (банк от них не растёт). Возвращает проигранные изумруды (и цену предметов). */
    private int forfeit(Table table, int seat, UUID player) {
        Extras x = table.extras[seat];
        boolean betting = table.game.phase() == CardTable.Phase.BETTING || table.game.phase() == CardTable.Phase.SETTLED;
        int virtual = x.virtual(), unreal = virtual - x.itemValue;
        int staked = table.game.staked(seat);
        int back = table.game.leave(seat, false);
        int real = Math.max(0, betting ? back - virtual : back);
        if (real > 0) world.give(player, real);
        ledger().staked(player, -(staked - virtual));
        if (!betting && unreal > 0) table.game.adjust(-unreal);
        releaseExtras(table, seat, player, betting, false);
        return betting ? 0 : Math.max(0, staked - unreal - real);
    }
    /** «Встать»: посреди раунда ставки на руках остаются банку, отложенная ставка возвращается. */
    private void getUp(Table table, int seat, UUID player) {
        if (seat >= 0) {
            int back = forfeit(table, seat, player);
            int lost = back;
            if (lost > 0) {
                ledger().addNet(player, world.day(), -lost);
                int[] session = table.sessions.get(player);
                if (session != null) session[1] -= lost;
                ledger().setPurse(table.villager, table.game.purse());
            }
            unseat(table, seat, lost > 0 ? "встал посреди раздачи" : "");
        } else table.watchers.remove(player);
        where.remove(player);
        out.state(player, new CardTableStateS2C(table.last.entityId(), ++table.version, "{}", "Вы встали из-за стола"));
        if (table.empty()) shut(table, "", false);
        else world.playing(table.villager, table.players(), true);
    }

    // ---- Мир вмешался. ----

    /** Жителя ударили: стол закрывается. Ударил игрок за этим столом — его ставка остаётся банку; остальным всё возвращается. */
    public void villagerHurt(UUID villager, UUID attacker) {
        Table table = tables.get(villager);
        if (table == null) return;
        int seat = attacker == null ? -1 : table.seatOf(attacker);
        if (seat >= 0) {
            int lost = forfeit(table, seat, attacker);
            if (lost > 0) ledger().addNet(attacker, world.day(), -lost);
            ledger().setPurse(villager, table.game.purse());
            unseat(table, seat, "ударил жителя");
        }
        shut(table, table.last.name() + " больше не играет: его ударили", false);
    }
    /** Житель погиб или выгрузился. */
    public void villagerGone(UUID villager) {
        Table table = tables.get(villager);
        if (table != null) shut(table, table.last.name() + " ушёл из-за стола", false);
    }

    /** Закрыть стол: раунд отменяется, ставки на руках возвращаются (в инвентарь, а при {@code owe} или игроку не в сети — долгом). */
    private void shut(Table table, String reason, boolean owe) {
        int[] virtual = new int[table.seats.length];
        for (int seat = 0; seat < virtual.length; seat++) virtual[seat] = table.extras[seat].virtual();
        for (CardTable.Settlement back : table.game.cancel()) {
            UUID player = table.seats[back.seat()];
            if (player == null) continue;
            int real = Math.max(0, back.returned() - virtual[back.seat()]);
            ledger().staked(player, -(back.staked() - virtual[back.seat()]));
            if (owe || world.player(player) == null) ledger().owe(player, real);
            else world.give(player, real);
        }
        for (int seat = 0; seat < table.seats.length; seat++) if (table.seats[seat] != null) releaseExtras(table, seat, table.seats[seat], true, owe);
        table.game.drainEvents();
        for (int seat = 0; seat < table.seats.length; seat++) if (table.seats[seat] != null) unseat(table, seat, reason);
        ledger().setPurse(table.villager, table.game.purse());
        for (UUID player : new ArrayList<>(where.keySet())) {
            if (!table.villager.equals(where.get(player))) continue;
            where.remove(player);
            out.state(player, new CardTableStateS2C(table.last.entityId(), ++table.version, "{}", reason.isEmpty() ? "Стол закрыт" : reason));
        }
        tables.remove(table.villager);
        world.playing(table.villager, List.of(), false);
    }

    // ---- Тик. ----

    /** Раз в тик: таймеры хода, раздачи и «Ловлю!»; раз в секунду — проверки мира (житель на месте, игроки рядом) и взгляд жителя. */
    public void tick() {
        ticks++;
        long now = clock.getAsLong();
        if (ticks % INVITE_TICKS == 0) invitations();
        for (Table table : List.copyOf(tables.values())) {
            if (ticks % 20 == 0 && !check(table)) continue;
            CardTable game = table.game;
            boolean changed = false;
            if (game.phase() == CardTable.Phase.BETTING && table.dealDeadline > 0 && now >= table.dealDeadline) {
                dealNow(table);
                changed = true;
            } else if (game.phase() == CardTable.Phase.PLAYERS && table.turnDeadline > 0 && now >= table.turnDeadline) {
                game.act(game.turnSeat(), Action.STAND, 0);
                changed = true;
            } else if (game.phase() == CardTable.Phase.SETTLED && now >= table.catchUntil) {
                game.nextRound();
                changed = true;
                if (game.purse() < game.limits().minBet()) {
                    UUID someone = table.players().isEmpty() ? null : table.players().getFirst();
                    speak(table, CardLines.Event.RUINED, someone, "банк пуст", "", true);
                    shut(table, table.last.name() + " проигрался в пух: банк пуст", false);
                    continue;
                }
            }
            if (changed) afterChange(table);
        }
    }
    /** Житель на месте и может играть, игроки рядом; false — стол закрыт. */
    private boolean check(Table table) {
        Villager villager = world.villager(table.villager);
        if (villager == null) { shut(table, table.last.name() + " ушёл из-за стола", false); return false; }
        table.last = villager;
        if (villager.sleeping()) { shut(table, villager.name() + " уснул за столом", false); return false; }
        if (villager.panicking() || villager.raid()) { shut(table, villager.name() + " вскочил: не до карт", false); return false; }
        boolean changed = false;
        for (int seat = 0; seat < table.seats.length; seat++) {
            UUID player = table.seats[seat];
            if (player == null) continue;
            Person person = world.player(player);
            if (person != null && person.alive() && person.dimension().equals(villager.dimension()) && villager.distanceTo(person) <= STAY_RANGE) continue;
            // Отошёл (оттолкнули, умер): не его выбор — ставки возвращаются.
            int virtual = table.extras[seat].virtual();
            int staked = table.game.staked(seat);
            int back = Math.max(0, table.game.leave(seat, true) - virtual);
            ledger().staked(player, -(staked - virtual));
            if (person == null) ledger().owe(player, back); else world.give(player, back);
            releaseExtras(table, seat, player, true, person == null);
            unseat(table, seat, "отошёл от стола");
            where.remove(player);
            out.state(player, new CardTableStateS2C(villager.entityId(), ++table.version, "{}", "Вы отошли от стола"));
            changed = true;
        }
        for (UUID watcher : List.copyOf(table.watchers)) {
            Person person = world.player(watcher);
            if (person != null && person.dimension().equals(villager.dimension()) && villager.distanceTo(person) <= STAY_RANGE * 2) continue;
            table.watchers.remove(watcher);
            where.remove(watcher);
        }
        if (table.empty()) { shut(table, "", false); return false; }
        List<UUID> players = table.players();
        UUID look = players.isEmpty() ? table.watchers.iterator().next() : players.get((int) (ticks / 100 % players.size()));
        world.hold(table.villager, look, 40);
        if (changed) afterChange(table);
        return true;
    }

    // ---- После каждого изменения. ----

    /** Расчёт закончившейся раздачи, таймер хода, рассылка состояния. */
    private void afterChange(Table table) {
        CardTable game = table.game;
        List<TableEvent> fresh = game.drainEvents();
        table.events.addAll(fresh);
        if (game.phase() == CardTable.Phase.SETTLED && table.settledRound != game.round()) settle(table);
        if (game.phase() == CardTable.Phase.PLAYERS) {
            if (game.turnSeat() != table.turnSeat || game.turnHand() != table.turnHand) {
                table.turnSeat = game.turnSeat(); table.turnHand = game.turnHand();
                table.turnDeadline = clock.getAsLong() + cfg().cardsTurnSeconds * 1000L;
            }
        } else { table.turnSeat = -1; table.turnHand = -1; table.turnDeadline = 0; }
        if (game.phase() != CardTable.Phase.BETTING) table.dealDeadline = 0;
        broadcast(table, null, "");
        table.events.clear();
    }
    private void settle(Table table) {
        CardTable game = table.game;
        table.settledRound = game.round();
        // Окно «Ловлю!» открывается, когда игрок увидел карты жителя: клиент показывает их с паузами до 1,2 с на карту.
        table.catchUntil = clock.getAsLong() + CATCH_MILLIS + 1200L * game.dealerCards().size() + 800;
        long day = world.day();
        long now = clock.getAsLong();
        for (CardTable.Settlement s : game.settlements()) {
            UUID player = table.seats[s.seat()];
            if (player == null) continue;
            Extras x = table.extras[s.seat()];
            int virtual = x.virtual();
            boolean online = world.player(player) != null;
            ledger().staked(player, -(s.staked() - virtual));
            int net;
            if (x.interest) {
                // На интерес: никто ничего не платит, банк как был; житель теплеет.
                game.adjust(s.net());
                net = 0;
                ledger().interest(table.villager, "minecraft:villager", table.last.name(), player, day);
            } else if (s.returned() >= s.staked()) {
                // Выигрыш или ничья: предметы назад, услуга не нужна, изумруды — сверх цены предметов.
                int emeralds = s.returned() - virtual;
                if (emeralds > 0) { if (online) world.give(player, emeralds); else ledger().owe(player, emeralds); }
                releaseExtras(table, s.seat(), player, true, !online);
                net = s.net();
                if (x.discount > 0 && s.net() > 0) {
                    int percent = Math.min(CardLedger.DISCOUNT_MAX, CardLedger.DISCOUNT_MIN + 2 * (s.staked() - (x.interest ? 1 : 0)));
                    String prize = world.prize(table.villager, player, percent);
                    if (!prize.isBlank()) out.chat(player, "[Mob Talk] Скидка с кона: " + prize);
                }
            } else {
                // Проигрыш: предметы остаются жителю (банк растёт на их цену), услуга становится карточным долгом.
                if (s.returned() > 0) { int emeralds = Math.max(0, s.returned()); if (online) world.give(player, emeralds); else ledger().owe(player, emeralds); }
                if (x.favor) {
                    game.adjust(-CardLedger.FAVOR_VALUE);
                    io.github.mobtalk.core.FavorItems.Candidate debt = io.github.mobtalk.core.FavorItems.draw(1).getFirst();
                    if (ledger().cardDebt(table.villager, "minecraft:villager", table.last.name(), player, debt.item(), debt.count(), now))
                        out.chat(player, "[Mob Talk] Карточный долг: " + table.last.name() + " ждёт от вас " + debt.count() + "× " + debt.item().substring(debt.item().indexOf(':') + 1));
                }
                releaseExtras(table, s.seat(), player, false, false);
                net = s.net() + (x.favor ? CardLedger.FAVOR_VALUE : 0);
                UUID spouse = ledger().spouseOtherThan(table.villager, player);
                if (spouse != null && -net >= BIG_STAKE) ledger().grumble(spouse, player, table.names[s.seat()], table.last.name(), -net, now);
            }
            x.clear();
            ledger().addNet(player, day, net);
            int[] session = table.sessions.computeIfAbsent(player, id -> new int[2]);
            session[0]++; session[1] += net;
        }
        ledger().setPurse(table.villager, game.purse());
        // Слово жителя о раздаче: золотое очко, крупный перебор, выигрыш или проигрыш; иначе раз в несколько раздач — просто к игре.
        CardLines.Event event = null;
        UUID about = null;
        String details = "";
        for (TableEvent e : table.events) {
            if (e.kind() != TableEvent.Kind.NATURAL) continue;
            if (e.seat() == CardTable.DEALER) event = CardLines.Event.NATURAL_DEALER;
            else { event = CardLines.Event.NATURAL_PLAYER; about = table.seats[e.seat()]; }
        }
        if (event == null) for (CardTable.Settlement s : game.settlements()) {
            if (s.staked() < BIG_STAKE || table.seats[s.seat()] == null) continue;
            boolean bust = table.events.stream().anyMatch(e -> e.kind() == TableEvent.Kind.BUST && e.seat() == s.seat());
            about = table.seats[s.seat()];
            event = bust ? CardLines.Event.BUST_PLAYER : s.net() > 0 ? CardLines.Event.WIN_BIG : s.net() < 0 ? CardLines.Event.LOSE_BIG : null;
            details = "ставка " + s.staked() + ", итог игрока " + s.net();
            if (event != null) break;
        }
        if (event == null && table.events.stream().anyMatch(e -> e.kind() == TableEvent.Kind.BUST && e.seat() == CardTable.DEALER) && Math.random() < 0.4)
            event = CardLines.Event.BUST_DEALER;
        boolean chatty = table.players().stream().anyMatch(p -> "round".equals(lines(p)));
        if (event == null && (++table.quiet >= QUIET_ROUNDS || chatty)) event = CardLines.Event.QUIET;
        if (event != null) speak(table, event, about == null && !table.players().isEmpty() ? table.players().getFirst() : about, details, "", false);
    }

    // ---- Житель сам зовёт. ----

    /** Как часто житель может позвать (тики) и шанс за проверку; вечер — с 11 500 до 13 000 тиков суток. */
    public static final int INVITE_TICKS = 600;
    public static final double INVITE_CHANCE = 0.35;
    public static final double INVITE_RANGE = 8;
    private java.util.function.DoubleSupplier dice = Math::random;
    void dice(java.util.function.DoubleSupplier dice) { this.dice = dice; }

    /** Вечером знакомый взрослый житель рядом сам зовёт игрока сыграть — не чаще раза в игровой день, если игрок не за столом и
     * ни с кем не говорит, а житель к нему не враждебен. */
    void invitations() {
        MobTalkConfig c = cfg();
        long time = Math.floorMod(world.timeOfDay(), 24_000L);
        if (!c.enabled || !c.cards || time < 11_500 || time > 13_000) return;
        for (UUID player : world.online()) {
            if (where.containsKey(player) || world.busy(player) || !personal.apply(player).tableInvite || dice.getAsDouble() > INVITE_CHANCE) continue;
            Villager villager = world.nearest(player, INVITE_RANGE);
            if (villager == null || tables.containsKey(villager.id()) || refusal(player, villager) != null && !refusal(player, villager).contains("ближе")) continue;
            if (!memory.acquainted(villager.id()) || memory.relationship(villager.id(), player) < 0) continue;
            if (!ledger().invite(player, world.day())) continue;
            Person person = world.player(player);
            CardLines.Input input = new CardLines.Input(villager.name(), ledger().persona(villager.id()), ledger().style(villager.id()), CardLines.Event.INVITE,
                    "вечер, игрок стоит рядом", person == null ? "" : person.name(), memory.relationship(villager.id(), player),
                    ledger().remembered(villager.id(), player), "");
            talk.line(villager.id(), player, input, line -> out.invite(player, villager.name(), villager.id(), line.text()));
        }
    }

    // ---- Слова жителя. ----

    /** Житель говорит: не больше одной реплики за раздачу, кроме важного (вход и выход игрока, «Ловлю!», банк пуст, игрок сам что-то
     * сказал). Реплику пишет {@link Talk}; ушедший из-за стола игрок получает её в чат. */
    private void speak(Table table, CardLines.Event event, UUID player, String details, String said, boolean important) {
        if (!important && table.spoke) return;
        // Все за столом выбрали «житель молчит» — без обращения к модели; на «Сказать…» житель всё равно отвечает.
        if (event != CardLines.Event.SAID && !table.audience().isEmpty() && table.audience().stream().allMatch(p -> "never".equals(lines(p)))) return;
        table.spoke = true;
        table.quiet = 0;
        Person person = player == null ? null : world.player(player);
        CardLines.Input input = new CardLines.Input(table.last.name(), ledger().persona(table.villager), table.game.style(), event, details,
                person == null ? "" : person.name(), player == null ? 0 : memory.relationship(table.villager, player),
                player == null ? "" : ledger().remembered(table.villager, player), said);
        talk.line(table.villager, player, input, line -> {
            if (line == null || line.text().isBlank()) return;
            Table live = tables.get(table.villager);
            boolean gone = player != null && !table.villager.equals(where.get(player));
            if (gone || live != table) { if (player != null && !"never".equals(lines(player))) out.chat(player, table.last.name() + ": «" + line.text() + "»"); if (live != table) return; }
            table.line = line.text();
            table.lineSaid = event == CardLines.Event.SAID;
            table.emotion = line.emotion().name();
            broadcast(table, null, "");
        });
    }
    /** «Сказать…»: игрок обратился к жителю за столом; не чаще раза в 5 с. */
    private String say(Table table, UUID player, String text) {
        if (text == null || text.isBlank()) return null;
        long now = clock.getAsLong();
        if (now - table.lastSaid < 5_000) return "Подождите: житель ещё отвечает";
        table.lastSaid = now;
        speak(table, CardLines.Event.SAID, player, "", text.strip().length() > 300 ? text.strip().substring(0, 300) : text.strip(), true);
        return null;
    }

    private void notice(Table table, UUID player, String text) { broadcast(table, player, text); }

    /** Состояние каждому за столом (или одному {@code only} с подсказкой {@code notice}). */
    private void broadcast(Table table, UUID only, String notice) {
        CardTable.View view = table.game.view();
        JsonArray events = new JsonArray();
        if (only == null) for (TableEvent e : table.events) {
            JsonObject json = new JsonObject();
            json.addProperty("k", e.kind().name()); json.addProperty("s", e.seat()); json.addProperty("h", e.hand());
            json.addProperty("c", e.card() == null ? -1 : e.card().code()); json.addProperty("a", e.amount());
            events.add(json);
        }
        long now = clock.getAsLong();
        int version = ++table.version;
        JsonArray extras = new JsonArray();
        for (Extras x : table.extras) {
            JsonObject e = new JsonObject();
            JsonArray items = new JsonArray();
            for (CardLedger.ItemStake item : x.items) { JsonObject i = new JsonObject(); i.addProperty("id", item.item()); i.addProperty("count", item.count()); items.add(i); }
            e.add("items", items); e.addProperty("favor", x.favor); e.addProperty("interest", x.interest); e.addProperty("discount", x.discount); e.addProperty("virtual", x.virtual());
            extras.add(e);
        }
        for (UUID player : only == null ? table.audience() : List.of(only)) {
            JsonObject json = new JsonObject();
            json.add("view", GSON.toJsonTree(view));
            json.add("events", events);
            json.addProperty("you", table.seatOf(player));
            json.addProperty("wallet", world.emeralds(player));
            json.addProperty("turnLeft", table.turnDeadline > 0 ? Math.max(0, table.turnDeadline - now) : 0);
            json.addProperty("turnTotal", cfg().cardsTurnSeconds * 1000L);
            json.addProperty("dealLeft", table.dealDeadline > 0 ? Math.max(0, table.dealDeadline - now) : 0);
            json.addProperty("catchLeft", view.phase().equals("SETTLED") ? Math.max(0, table.catchUntil - now) : 0);
            JsonArray ready = new JsonArray();
            for (boolean r : table.ready) ready.add(r);
            json.add("ready", ready);
            json.addProperty("watchers", table.watchers.size());
            json.addProperty("line", table.lineSaid || !"never".equals(lines(player)) ? table.line : "");
            json.addProperty("emotion", table.emotion);
            json.addProperty("notice", notice);
            json.addProperty("style", table.game.style().word);
            json.add("extras", extras);
            json.addProperty("relation", memory.relationship(table.villager, player));
            out.state(player, new CardTableStateS2C(table.last.entityId(), version, json.toString(), ""));
        }
    }

    private String refusal(Table table, CardTable.Refusal refusal) {
        CardTable.Limits limits = table.game.limits();
        return switch (refusal) {
            case NONE -> null;
            case PHASE -> "Сейчас этого не сделать";
            case SEAT -> "Вы не за столом";
            case TURN -> "Не ваш ход";
            case LIMIT -> "Ставка на руку — от " + limits.minBet() + " до " + limits.maxBet() + " изумр.";
            case BANK -> "У " + table.last.name() + " в банке столько нет: " + table.game.purse() + " изумр.";
            case FUNDS -> "Не хватает изумрудов";
            case NOT_ALLOWED -> "Так сейчас нельзя";
        };
    }

    private boolean allowPress(UUID player) {
        long now = clock.getAsLong();
        Deque<Long> recent = presses.computeIfAbsent(player, id -> new ArrayDeque<>());
        while (!recent.isEmpty() && now - recent.peekFirst() >= 1000) recent.pollFirst();
        if (recent.size() >= ACTIONS_PER_SECOND) return false;
        recent.addLast(now);
        return true;
    }

    private Table tableOf(UUID player) { UUID villager = where.get(player); return villager == null ? null : tables.get(villager); }

    // ---- Для админки, самотеста и тестов. ----

    /** Что житель знает об игре в 21 для промпта (mobDetails.cardGame): включена ли, за столом ли он сейчас и с этим ли игроком,
     * сколько у него в кошельке, как он играет и что помнит о прошлых партиях с игроком. */
    public Map<String, Object> promptFacts(UUID villager, UUID player) {
        Map<String, Object> facts = new LinkedHashMap<>();
        MobTalkConfig c = cfg();
        facts.put("cardsEnabled", c.enabled && c.cards);
        Table table = tables.get(villager);
        facts.put("playingNow", table != null);
        facts.put("withThisPlayer", table != null && player != null && (table.seatOf(player) >= 0 || table.watchers.contains(player)));
        int purse = ledger().balance(villager);
        if (purse >= 0) facts.put("purse", purse);
        facts.put("canPlay", purse < 0 || purse >= c.cardsMinBet); // На мели житель за стол не сядет.
        facts.put("style", ledger().style(villager).word);
        String remembered = player == null ? "" : ledger().remembered(villager, player);
        if (!remembered.isBlank()) facts.put("pastGames", remembered);
        facts.put("rules", Variant.of(c.cardsVariant).title + ", ставка " + c.cardsMinBet + "–" + c.cardsMaxBet + " изумр.");
        return facts;
    }

    /** Житель за столом прямо сейчас. */
    public boolean playing(UUID villager) { return tables.containsKey(villager); }
    /** Стол, за которым игрок сидит или смотрит: житель или null. */
    public UUID tableAt(UUID player) { return where.get(player); }
    /** Короткая строка о столах для {@code /mobtalk cards}. */
    public String describe() {
        if (tables.isEmpty()) return "Столов нет";
        List<String> parts = new ArrayList<>();
        for (Table t : tables.values())
            parts.add(t.last.name() + " (" + t.game.variant().title + ", " + t.game.style().word + ", банк " + t.game.purse() + ", игроков " + t.game.occupied()
                    + ", смотрят " + t.watchers.size() + ", фаза " + t.game.phase() + ")");
        return String.join("; ", parts);
    }
    CardTable game(UUID villager) { Table t = tables.get(villager); return t == null ? null : t.game; }
}
