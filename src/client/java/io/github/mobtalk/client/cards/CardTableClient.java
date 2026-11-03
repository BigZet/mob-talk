package io.github.mobtalk.client.cards;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mobtalk.core.cards.CardTable;
import io.github.mobtalk.net.CardTableActionC2S;
import io.github.mobtalk.net.CardTableOpenS2C;
import io.github.mobtalk.net.CardTableStateS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import java.util.ArrayList;
import java.util.List;

/** Игра в 21 на клиенте: что сервер сказал о столе ({@link CardTableOpenS2C}, {@link CardTableStateS2C}) и нажатия обратно
 * ({@link CardTableActionC2S}). Клиент ничего не решает: только показывает открытые карты и шлёт кнопки. Поток клиента. */
public final class CardTableClient {
    private CardTableClient() { }
    private static final Gson GSON = new Gson();

    /** Одно событие стола для анимации: вид ({@code TableEvent.Kind}), место (−1 — житель), рука, карта (−1 — рубашкой), изумруды. */
    public record Event(String kind, int seat, int hand, int card, int amount) { }
    /** Не изумрудная часть ставки места: предметы (id, сколько), услуга, игра на интерес, скидка жителя на кону (%). */
    public record Extra(List<String> items, List<Integer> counts, boolean favor, boolean interest, int discount, int virtual) {
        public boolean any() { return !items.isEmpty() || favor || interest || discount > 0; }
    }
    /** Последнее состояние: открытые карты, моё место, мои изумруды, таймеры (мс), готовность мест, реплика жителя, подсказка мне. */
    public record State(CardTable.View view, int you, int wallet, long turnLeft, long turnTotal, long dealLeft, long catchLeft, List<Boolean> ready,
                        int watchers, String line, String emotion, String notice, String style, List<Extra> extras, int relation, long receivedAt) {
        public Extra extra(int index) { return extras == null || index < 0 || index >= extras.size() ? null : extras.get(index); }
        public CardTable.SeatView seat(int index) { return view == null || index < 0 || index >= view.seats().size() ? null : view.seats().get(index); }
    }

    private static CardTableOpenS2C open;
    private static State state;
    private static int version;
    private static String closed = "";
    private static final List<Event> events = new ArrayList<>();

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(CardTableOpenS2C.ID, (payload, context) -> onOpen(payload));
        ClientPlayNetworking.registerGlobalReceiver(CardTableStateS2C.ID, (payload, context) -> onState(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    static void reset() { open = null; state = null; version = 0; closed = ""; events.clear(); }

    private static void onOpen(CardTableOpenS2C packet) {
        // Каждое открытие — новая сессия стола: версии сервера начинаются заново.
        open = packet;
        state = null; version = 0; events.clear();
        closed = "";
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(client.currentScreen instanceof CardTableScreen)) client.setScreen(new CardTableScreen());
        io.github.mobtalk.client.SelfTestServer.cardOpen(packet.seat(), packet.variant());
    }
    private static void onState(CardTableStateS2C packet) {
        if (open == null || packet.entityId() != open.entityId() || packet.version() <= version) return;
        version = packet.version();
        if (!packet.closed().isEmpty()) { closed = packet.closed(); io.github.mobtalk.client.SelfTestServer.cardClosed(closed); return; }
        JsonObject json = JsonParser.parseString(packet.json()).getAsJsonObject();
        if (!json.has("view")) return;
        CardTable.View view = GSON.fromJson(json.get("view"), CardTable.View.class);
        List<Boolean> ready = new ArrayList<>();
        for (JsonElement r : json.getAsJsonArray("ready")) ready.add(r.getAsBoolean());
        State next = new State(view, json.get("you").getAsInt(), json.get("wallet").getAsInt(), json.get("turnLeft").getAsLong(), json.has("turnTotal") ? json.get("turnTotal").getAsLong() : 30_000,
                json.get("dealLeft").getAsLong(), json.get("catchLeft").getAsLong(), ready, json.get("watchers").getAsInt(),
                text(json, "line"), text(json, "emotion"), text(json, "notice"), text(json, "style"), extras(json),
                json.has("relation") ? json.get("relation").getAsInt() : 0, System.currentTimeMillis());
        // Подсказка приходит одному игроку без событий: стол прежний, только строка.
        state = next;
        io.github.mobtalk.client.SelfTestServer.cardState(view, next.you(), next.watchers(), next.wallet(), next.notice());
        JsonArray list = json.getAsJsonArray("events");
        if (list != null) for (JsonElement e : list) {
            JsonObject o = e.getAsJsonObject();
            events.add(new Event(o.get("k").getAsString(), o.get("s").getAsInt(), o.get("h").getAsInt(), o.get("c").getAsInt(), o.get("a").getAsInt()));
        }
    }
    private static List<Extra> extras(JsonObject json) {
        List<Extra> out = new ArrayList<>();
        if (!json.has("extras")) return out;
        for (JsonElement e : json.getAsJsonArray("extras")) {
            JsonObject o = e.getAsJsonObject();
            List<String> items = new ArrayList<>(); List<Integer> counts = new ArrayList<>();
            for (JsonElement i : o.getAsJsonArray("items")) { items.add(i.getAsJsonObject().get("id").getAsString()); counts.add(i.getAsJsonObject().get("count").getAsInt()); }
            out.add(new Extra(items, counts, o.get("favor").getAsBoolean(), o.get("interest").getAsBoolean(), o.get("discount").getAsInt(),
                    o.has("virtual") ? o.get("virtual").getAsInt() : 0));
        }
        return out;
    }
    private static String text(JsonObject json, String key) { return json.has(key) ? json.get(key).getAsString() : ""; }

    /** Личные настройки стола из F7: скорость анимаций (normal, fast, off) и подсказка хода; громкость — в {@link TableSounds}. */
    static String speed = "normal";
    static boolean hint;
    public static void settings(io.github.mobtalk.core.MobTalkConfig config) {
        speed = config.tableSpeed; hint = config.tableHint; TableSounds.volume = config.tableVolume / 100f;
    }
    static float speedFactor() { return "off".equals(speed) ? 0 : "fast".equals(speed) ? 2 : 1; }

    public static CardTableOpenS2C table() { return open; }
    public static State state() { return state; }
    /** Стол закрыт сервером: причина, иначе "". */
    public static String closed() { return closed; }
    /** Новые события по порядку (и забыть их). */
    public static List<Event> takeEvents() { List<Event> out = List.copyOf(events); events.clear(); return out; }
    /** Экран закрыт игроком или сервером: забыть стол. */
    static void forget() { reset(); }

    public static void send(String action, int amount, String text) {
        if (open == null || !ClientPlayNetworking.canSend(CardTableActionC2S.ID)) return;
        ClientPlayNetworking.send(new CardTableActionC2S(open.entityId(), action, amount, text == null ? "" : text));
    }
}
