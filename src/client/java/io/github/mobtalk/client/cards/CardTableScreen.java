package io.github.mobtalk.client.cards;

import io.github.mobtalk.core.cards.Card;
import io.github.mobtalk.core.cards.CardTable;
import io.github.mobtalk.core.cards.Variant;
import io.github.mobtalk.net.CardTableOpenS2C;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Стол игры в 21 (docs/cards-21.md): мир размыт, над столом лампа, сверху портрет жителя в резной рамке с живой моделью, табличка
 * с именем и стилем, пузырь реплики, банк жителя; на сукне его карты, колода, места игроков с кругами ставок и стопками фишек-изумрудов,
 * своя рука снизу по центру с бейджем счёта и кольцом таймера; под столом кнопки хода и фишки ставки. Всё решает сервер: экран
 * показывает последнее состояние по порядку ({@link TableTimeline}) и шлёт нажатия. Раскладка мест — та же, что на текстуре стола
 * (tools/cards_art.py). */
public final class CardTableScreen extends Screen implements TableTimeline.Places {
    private static final int GOLD = 0xF6CE6B, TEXT = 0xE4E8EF, MUTED = 0xAFC0D5, RED = 0xFF6A5A, GREEN = 0x8CF0A8;
    // Раскладка на столе: доли ширины и высоты текстуры стола (как SEAT_ZONES в tools/cards_art.py).
    private static final float[] DEALER = {0.50f, 0.335f};
    private static final float[] SHOE = {0.86f, 0.29f};
    private static final float[][] SLOT_CARDS = {{0.50f, 0.745f}, {0.785f, 0.655f}, {0.215f, 0.655f}, {0.165f, 0.300f}};
    private static final float[][] SLOT_BET = {{0.50f, 0.555f}, {0.665f, 0.500f}, {0.335f, 0.500f}, {0.165f, 0.470f}};
    private static final float[] SLOT_SIZE = {0.30f, 0.20f, 0.20f, 0.20f};

    private final TableFx fx = new TableFx();
    private final TableTimeline timeline = new TableTimeline(fx, this);
    private final List<TableButton> chipButtons = new ArrayList<>();
    private TableButton hit, stand, extra, split, accuse, deal, unbet, leave;
    private float u = 1, cardScale = 1, tx, ty, tw, th, pw, ph, px;
    private double gui = 2;
    private TableButton sit;
    private long closedAt, lastFrame, lineAt;
    private float walletX;
    private String line = "", emotion = "";
    private net.minecraft.client.gui.widget.TextFieldWidget sayField;
    private TableButton say, more, item, favor, interest, discount, back;
    private boolean moreStakes;
    private boolean confirmLeave;

    public CardTableScreen() { super(Text.literal("Игра в 21")); }

    // ---- Раскладка. ----

    /** Портрет — от высоты экрана, стол — всё, что осталось между ним и строкой кнопок (по ширине растягивается до 1,3 раза: на
     * широком экране сукно не сжимается в середине). Масштаб карт кратен пикселю окна: пиксель-арт без полос. */
    private void layout() {
        gui = client == null ? 2 : client.getWindow().getScaleFactor();
        // Левая колонка жителя: портрет-бюст, табличка, банк, реплика. Стол справа — по высоте экрана (на широком экране та ширина,
        // на которую раньше растягивалось сукно, отдана портрету).
        pw = Math.max(84, Math.min(230, Math.min(width * 0.25f, (height - 40) * 0.44f)));
        ph = pw * 144f / 120f;
        px = 6;
        ty = 6;
        th = height - 30 - ty;
        float natural = th / 0.5625f;
        float room = width - (px + pw + 8) - 6;
        tw = Math.min(room, natural * 1.15f);
        if (tw < natural) th = Math.min(th, tw * 0.5625f * 1.2f); // Узкое место: сукно чуть вытягивается вверх, а не оставляет пустую полосу.
        tx = px + pw + 8 + (room - tw) / 2f;
        u = Math.min(tw / 1024f, th / 576f);
        float target = 0.27f * th / 48f;
        cardScale = (float) (Math.max(1, Math.floor(target * gui)) / gui);
    }
    /** Размер крупной надписи, кратный пикселю окна (чёткие буквы). */
    private float crisp(float size) { return (float) (Math.max(1, Math.round(size * gui)) / gui); }
    private float x(float fraction) { return tx + fraction * tw; }
    /** Середина стола по x. */
    private int mid() { return Math.round(tx + tw / 2); }
    private float y(float fraction) { return ty + fraction * th; }
    /** Место за столом → ячейка раскладки: своё — внизу по центру, остальные по кругу; у зрителя места как есть. */
    private int slot(int seat, int you) {
        if (you < 0) return seat;
        int seats = Math.max(1, CardTableClient.table() == null ? 4 : CardTableClient.table().seats());
        int rel = ((seat - you) % seats + seats) % seats;
        // Следующий по кругу — справа, предыдущий — слева, напротив — сверху слева: вдвоём соседи сидят сбоку, а не у жителя.
        return rel == 0 ? 0 : rel == 1 ? 1 : rel == seats - 1 ? 2 : 3;
    }
    private int you() { CardTableClient.State s = CardTableClient.state(); return s == null ? -1 : s.you(); }

    @Override public float[] shoe() { return new float[]{x(SHOE[0]), y(SHOE[1])}; }
    @Override public float[] wallet() { return new float[]{walletX, height - 14}; }
    @Override public float[] purse() { return new float[]{px + pw / 2, ph + 40}; }
    @Override public float[] bet(int seat) { float[] b = SLOT_BET[Math.min(3, slot(seat, you()))]; return new float[]{x(b[0]), y(b[1]) + 6 * u}; }
    @Override public float[] hand(int seat, int hand) {
        if (seat == CardTable.DEALER) return new float[]{x(DEALER[0]), y(DEALER[1])};
        float[] c = SLOT_CARDS[Math.min(3, slot(seat, you()))];
        CardTableClient.State s = CardTableClient.state();
        int hands = s == null || s.seat(seat) == null ? 1 : Math.max(1, s.seat(seat).hands().size());
        float shift = hands > 1 ? (hand - (hands - 1) / 2f) * (CardTable.DEALER == seat ? 0 : 60 * cardScale) : 0;
        return new float[]{x(c[0]) + shift, y(c[1])};
    }
    @Override public float scale() { return cardScale; }
    private float chipScale() { return (float) (Math.max(1, Math.round(cardScale * 1.35f * gui)) / gui); }
    /** Самотест: житель сейчас лезет в рукав (рывок портрета). */
    public static boolean cheatShowing() {
        return net.minecraft.client.MinecraftClient.getInstance().currentScreen instanceof CardTableScreen screen && screen.timeline.cheatAt >= 0
                && System.currentTimeMillis() > screen.timeline.cheatAt + 80 && screen.timeline.tell(System.currentTimeMillis()) != 0;
    }
    /** Самотест: реплика жителя, которую экран сейчас показывает. */
    public static String shownLine() {
        return net.minecraft.client.MinecraftClient.getInstance().currentScreen instanceof CardTableScreen screen ? screen.line : "";
    }
    /** Самотест: сколько миллисекунд назад показана текущая реплика. */
    public static long lineAge() {
        return net.minecraft.client.MinecraftClient.getInstance().currentScreen instanceof CardTableScreen screen ? System.currentTimeMillis() - screen.lineAt : 0;
    }
    /** Самотест: стопка выигрыша сейчас едет к игроку. */
    public static boolean winChipsFlying() {
        if (!(net.minecraft.client.MinecraftClient.getInstance().currentScreen instanceof CardTableScreen screen)) return false;
        long now = System.currentTimeMillis();
        return screen.timeline.moves().stream().anyMatch(m -> m.sparkle() && now > m.start() + m.life() / 4 && now < m.start() + m.life() * 3 / 4);
    }

    @Override protected void init() {
        layout();
        timeline.speed = fx.speed = CardTableClient.speedFactor();
        chipButtons.clear();
        hit = button("Ещё", "hit", 0, () -> CardTableClient.send("hit", 0, ""));
        stand = button("Хватит", "stand", 0, () -> CardTableClient.send("stand", 0, ""));
        extra = button(blackjack() ? "Удвоить" : "Ва-банк", blackjack() ? "double" : "vabank", 0, () -> CardTableClient.send(blackjack() ? "double" : "vabank", 0, ""));
        split = button("Разделить", "split", 0, () -> CardTableClient.send("split", 0, ""));
        accuse = button("Ловлю!", "catch", 0, () -> CardTableClient.send("catch", 0, ""));
        accuse.alarm = true;
        for (int amount : new int[]{1, 5, 9}) {
            int value = amount;
            chipButtons.add(button("+" + amount, null, amount, () -> CardTableClient.send("bet", value, "")));
        }
        unbet = button("Снять", "unbet", 0, () -> CardTableClient.send("unbet", 0, ""));
        deal = button("Раздать", "deal", 0, () -> CardTableClient.send("deal", 0, ""));
        sit = button("Сесть", "sit", 0, () -> CardTableClient.send("sit", 0, ""));
        leave = button("Встать", "leave", 0, this::askLeave);
        say = button("Сказать", "say", 0, this::toggleSay);
        more = button("Ещё ставки", "vabank", 0, () -> moreStakes = true);
        item = button("Предмет из руки", "deal", 0, () -> CardTableClient.send("item", 0, ""));
        favor = button("Услуга", "unbet", 0, () -> CardTableClient.send("favor", 0, ""));
        interest = button("На интерес", "sit", 0, () -> CardTableClient.send("interest", 0, ""));
        discount = button("Скидку на кон", "catch", 0, () -> CardTableClient.send("discount", 0, ""));
        back = button("Назад", "leave", 0, () -> moreStakes = false);
        sayField = new net.minecraft.client.gui.widget.TextFieldWidget(textRenderer, width / 2 - 140, height - 50, 280, 18, Text.literal("Сказать"));
        sayField.setMaxLength(200);
        sayField.setPlaceholder(Text.literal("Что сказать жителю? Enter — сказать, Esc — отмена"));
        sayField.visible = false;
        addDrawableChild(sayField);
        refreshButtons();
    }
    /** Одна строка кнопок под столом — только то, что можно в этой фазе; по центру, слева место под «Твои изумруды». */
    private void arrange(List<TableButton> shown) {
        for (TableButton b : List.of(hit, stand, extra, split, accuse, unbet, deal, sit, leave, say, more, item, favor, interest, discount, back)) b.visible = shown.contains(b);
        for (TableButton b : chipButtons) b.visible = shown.contains(b);
        int gap = 4, wallet = 64, total = 0;
        int chips = (int) shown.stream().filter(chipButtons::contains).count();
        // Узкий экран: фишки и кнопки ужимаются, чтобы ряд влез целиком (подписи короче иконок не бывают).
        int chipW = width < 520 ? 30 : 38;
        int each = Math.max(36, Math.min(96, (width - 12 - wallet - gap * shown.size() - chips * chipW) / Math.max(1, shown.size() - chips)));
        for (TableButton b : shown) total += (chipButtons.contains(b) ? chipW : each) + gap;
        int x = Math.max(wallet + 8, width / 2 - (total - gap) / 2 + wallet / 2), y = height - 24;
        walletX = x - wallet / 2f - 4;
        for (TableButton b : shown) {
            int w = chipButtons.contains(b) ? chipW : each;
            b.setX(x); b.setY(y); b.setWidth(w);
            x += w + gap;
        }
    }    private TableButton button(String label, String icon, int chip, Runnable press) {
        return addDrawableChild(new TableButton(0, height - 24, 60, 20, label, icon, chip, b -> press.run()));
    }
    @Override public void resize(net.minecraft.client.MinecraftClient client, int width, int height) { super.resize(client, width, height); fx.clear(); }

    private static boolean blackjack() { CardTableOpenS2C t = CardTableClient.table(); return t != null && "BLACKJACK".equals(t.variant()); }

    // ---- Ввод. ----

    private void askLeave() {
        CardTableClient.State s = CardTableClient.state();
        boolean midRound = s != null && s.view() != null && ("PLAYERS".equals(s.view().phase()) || "DEALER".equals(s.view().phase()))
                && s.seat(s.you()) != null && !s.seat(s.you()).hands().isEmpty();
        if (midRound && !confirmLeave) { confirmLeave = true; leave.setMessage(Text.literal("Точно?")); return; }
        CardTableClient.send("leave", 0, "");
        CardTableClient.forget();
        super.close();
    }
    @Override public void close() {
        if (sayField != null && sayField.visible) { toggleSay(); return; }
        askLeave();
    }
    private void toggleSay() {
        sayField.visible = !sayField.visible;
        sayField.setText("");
        setFocused(sayField.visible ? sayField : null);
        sayField.setFocused(sayField.visible);
    }
    @Override public boolean shouldPause() { return false; }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (sayField != null && sayField.visible) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                if (!sayField.getText().isBlank()) CardTableClient.send("say", 0, sayField.getText());
                toggleSay();
                return true;
            }
            return super.keyPressed(key, scan, modifiers);
        }
        if (key == GLFW.GLFW_KEY_SPACE) { if (hit.active) hit.onPress(); return true; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { if (stand.active) stand.onPress(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public void tick() {
        CardTableClient.State s = CardTableClient.state();
        List<CardTableClient.Event> events = CardTableClient.takeEvents();
        long now = System.currentTimeMillis();
        if (!events.isEmpty()) timeline.accept(events, s == null ? null : s.view(), now);
        timeline.tick(now);
        // Реплика ждёт, пока доиграет показ раздачи: житель не комментирует карты, которых игрок ещё не видел.
        if (s != null && !s.line().equals(line) && !timeline.busy(now)) { line = s.line(); emotion = s.emotion(); lineAt = now; }
        if (!CardTableClient.closed().isEmpty()) {
            if (closedAt == 0) closedAt = now;
            else if (now - closedAt > 2500) { CardTableClient.forget(); super.close(); return; }
        }
        refreshButtons();
    }

    private void refreshButtons() {
        if (hit == null) return;
        CardTableClient.State s = CardTableClient.state();
        CardTable.View v = s == null ? null : s.view();
        long now = System.currentTimeMillis();
        boolean seated = s != null && s.you() >= 0;
        boolean shown = !timeline.busy(now);
        boolean myTurn = v != null && seated && "PLAYERS".equals(v.phase()) && v.turnSeat() == s.you();
        boolean betting = v != null && seated && "BETTING".equals(v.phase());
        CardTable.HandView hand = myTurn ? s.seat(s.you()).hands().get(v.turnHand()) : null;
        hit.active = myTurn && !hand.doubled();
        stand.active = myTurn;
        hit.urgent = stand.urgent = myTurn && shown;
        extra.active = myTurn && (blackjack() ? hand.cards().size() == 2 && !hand.doubled() : hand.cards().size() == 1 && !hand.vaBank());
        split.active = myTurn && hand.cards().size() == 2 && s.seat(s.you()).hands().size() == 1 && sameValue(hand.cards().get(0), hand.cards().get(1));
        accuse.active = v != null && seated && "SETTLED".equals(v.phase()) && left(s, s.catchLeft()) > 0 && !s.seat(s.you()).hands().isEmpty();
        accuse.urgent = accuse.active;
        for (TableButton chip : chipButtons) chip.active = betting;
        unbet.active = betting && (s.seat(s.you()).pendingBet() > 0 || s.extra(s.you()) != null && s.extra(s.you()).any());
        more.active = betting;
        if (!betting) moreStakes = false;
        deal.active = betting && s.seat(s.you()).pendingBet() >= v.minBet() && !(s.ready().size() > s.you() && s.ready().get(s.you()));
        deal.urgent = deal.active;
        if (!"Точно?".equals(leave.getMessage().getString())) confirmLeave = false;
        if (confirmLeave && v != null && "BETTING".equals(v.phase())) { confirmLeave = false; leave.setMessage(Text.literal("Встать")); }
        sit.active = s != null && s.you() < 0 && v != null && v.seats().contains(null);
        List<TableButton> row = new ArrayList<>();
        String phase = v == null ? "" : v.phase();
        if (!seated) row.add(sit);
        else if ("BETTING".equals(phase) && moreStakes) {
            item.active = favor.active = interest.active = true;
            discount.active = s.relation() >= 15;
            row.add(item); row.add(favor); row.add(interest); row.add(discount); row.add(back);
            arrange(row);
            return;
        }
        else if ("BETTING".equals(phase)) { row.addAll(chipButtons); row.add(more); row.add(unbet); row.add(deal); }
        else if ("PLAYERS".equals(phase)) { row.add(hit); row.add(stand); row.add(extra); if (blackjack()) row.add(split); }
        else if ("SETTLED".equals(phase) || "DEALER".equals(phase)) row.add(accuse);
        if (seated && !"BETTING".equals(phase)) row.add(say);
        row.add(leave);
        arrange(row);    }
    private static boolean sameValue(int a, int b) {
        Card x = Card.of(a), y = Card.of(b);
        return x != null && y != null && Variant.BLACKJACK.value(x.rank()) == Variant.BLACKJACK.value(y.rank());
    }
    private static long left(CardTableClient.State s, long millis) { return millis - (System.currentTimeMillis() - s.receivedAt()); }

    // ---- Рисование. ----

    /** Фон — только размытый мир, без затемнения; на нём лампа, стол и всё на столе. Кнопки рисует {@link Screen#render} поверх. */
    @Override public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        applyBlur(delta);
        layout();
        TableDraw.filters();
        long now = System.currentTimeMillis();
        CardTableClient.State s = CardTableClient.state();
        CardTable.View v = s == null ? null : s.view();
        // Тень стола и сам стол.
        context.fill(Math.round(tx + 10 * u), Math.round(ty + th - 6 * u), Math.round(tx + tw - 10 * u), Math.round(ty + th + 5 * u), 0x40000000);
        TableDraw.image(context, TableDraw.TABLE, tx, ty, tw, th, 0xFFFFFFFF);
        if (v != null) {
            shoe(context, v);
            fx.renderUnder(context, textRenderer, now);
            dealerCards(context, v, now);
            for (int seat = 0; seat < v.seats().size(); seat++) seat(context, s, v, seat, now);
            chipMoves(context, now);
        }
        portrait(context, s, mouseX, mouseY, now);
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = System.currentTimeMillis();
        float frame = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        super.render(context, mouseX, mouseY, delta);
        CardTableClient.State s = CardTableClient.state();
        fx.gui = (float) gui;
        fx.render(context, textRenderer, now, frame);
        bubble(context, now);
        wallet(context, s);
        status(context, s, now);
        String closed = CardTableClient.closed();
        if (!closed.isEmpty()) {
            int w = textRenderer.getWidth(closed) + 24;
            TableDraw.badge(context, mid() - w / 2, Math.round(y(0.47f)) - 9, mid() + w / 2, Math.round(y(0.47f)) + 11, GOLD, 1);
            context.drawCenteredTextWithShadow(textRenderer, closed, mid(), Math.round(y(0.47f)) - 3, 0xFF000000 | GOLD);
        } else if (s == null) context.drawCenteredTextWithShadow(textRenderer, "Житель садится за стол…", mid(), Math.round(y(0.47f)), 0xFF000000 | MUTED);
    }

    private void shoe(DrawContext context, CardTable.View v) {
        float s = cardScale * 0.9f, cx = x(SHOE[0]), cy = y(SHOE[1]);
        int layers = Math.max(1, Math.min(4, v.shoe() / 8 + 1));
        for (int i = layers - 1; i >= 0; i--) {
            float px = cx - TableDraw.CARD_W * s / 2 - i * 1.5f * s, py = cy - TableDraw.CARD_H * s / 2 + i * 1.5f * s;
            if (i == layers - 1) TableDraw.cardShadow(context, px, py, s, 0, 0.8f);
            TableDraw.card(context, -1, px, py, s, 0, 1, 0xFFFFFFFF);
        }
        String count = String.valueOf(v.shoe());
        int w = textRenderer.getWidth(count) + 10;
        int by = Math.round(cy + TableDraw.CARD_H * s / 2 + 5);
        TableDraw.badge(context, Math.round(cx) - w / 2, by, Math.round(cx) + w / 2, by + 12, 0xAFC0D5, 0);
        context.drawCenteredTextWithShadow(textRenderer, count, Math.round(cx), by + 2, 0xFFE4E8EF);
    }

    /** Ряд карт с тенью, веером; возвращает правый край ряда. {@code codes} — что видно сейчас по таймлайну. */
    private float row(DrawContext context, int seat, int hand, List<Integer> codes, float cx, float cy, float s, float shake, boolean dealer, long now) {
        int n = codes.size();
        float step = 15 * s, width = TableDraw.CARD_W * s + Math.max(0, n - 1) * step;
        float left = cx - width / 2 + shake;
        for (int i = 0; i < n; i++) {
            // Карта из рукава вылетает из портрета жителя, остальные — из колоды.
            float[] from = dealer && i == timeline.cheatCard ? new float[]{px + pw / 2, ph * 0.55f} : shoe();
            float flight = timeline.flight(seat, hand, i, now);
            if (flight < 0) continue;
            float mid = (n - 1) / 2f;
            float angle = dealer ? (i - mid) * 0.02f : (i - mid) * 0.055f;
            float px = left + i * step, py = cy - TableDraw.CARD_H * s / 2 + (float) Math.pow(Math.abs(i - mid), 2) * 0.9f * s * (dealer ? 0.5f : 1);
            if (flight < 1) {
                // Полёт из колоды по дуге, с вращением.
                float t = 1 - (1 - flight) * (1 - flight);
                float sx = from[0] - TableDraw.CARD_W * s / 2, sy = from[1] - TableDraw.CARD_H * s / 2;
                px = sx + (px - sx) * t;
                py = sy + (py - sy) * t - (float) Math.sin(t * Math.PI) * 40 * u;
                angle = angle + (1 - t) * 1.2f;
            }
            int code = codes.get(i);
            float squeeze = 1;
            boolean faceDown = code < 0;
            if (dealer) {
                float flip = timeline.flip(i, now);
                if (flip < 0) faceDown = true;
                else if (flip < 1) { squeeze = Math.abs((float) Math.cos(flip * Math.PI)); faceDown = flip < 0.5f; }
            }
            TableDraw.cardShadow(context, px, py, s, angle, 0.85f * (flight < 1 ? 0.6f : 1));
            int tint = timeline.busted(seat, hand, now) ? 0xFFB09090 : 0xFFFFFFFF;
            TableDraw.card(context, faceDown ? -1 : code, px, py, s, angle, squeeze, tint);
            if (dealer && timeline.blink(i, now)) TableDraw.sprite(context, TableDraw.Sprite.GLOW, px + 16 * s, py + 24 * s, 60 * s, 0xFFFFFF, 0.8f, 0);
            if (dealer) {
                float flip = timeline.flip(i, now);
                if (flip >= 0.5f && flip < 1) {
                    // Блик по карте сразу после переворота.
                    float g = (flip - 0.5f) * 2;
                    TableDraw.sprite(context, TableDraw.Sprite.BEAM, px + 16 * s * squeeze + (g - 0.5f) * 30 * s, py + 24 * s, 10 * s, 70 * s, 0xFFFFFF, 0.7f * (1 - g), 0.5f);
                }
            }
        }
        return left + width;
    }
    /** Видимые сейчас коды карт руки (ещё не долетевшие не считаются) — для счёта. */
    private List<Card> visible(int seat, int hand, List<Integer> codes, boolean dealer, long now) {
        List<Card> out = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            if (timeline.flight(seat, hand, i, now) < 1) continue;
            if (dealer && timeline.flip(i, now) < 0.5f) continue;
            Card card = Card.of(codes.get(i));
            if (card != null) out.add(card);
        }
        return out;
    }

    private void dealerCards(DrawContext context, CardTable.View v, long now) {
        if (v.dealer().isEmpty()) return;
        float cx = x(DEALER[0]), cy = y(DEALER[1]);
        float right = row(context, CardTable.DEALER, 0, v.dealer(), cx, cy, cardScale, timeline.shake(CardTable.DEALER, 0, now), true, now);
        List<Card> shown = visible(CardTable.DEALER, 0, v.dealer(), true, now);
        if (shown.isEmpty()) return;
        Variant variant = Variant.of(v.variant());
        Variant.Score score = variant.score(shown);
        boolean hidden = shown.size() < v.dealer().size();
        boolean bust = timeline.busted(CardTable.DEALER, 0, now), natural = timeline.natural(CardTable.DEALER, 0, now);
        scoreBadge(context, right + 8, cy, (hidden ? "≥" : "") + score.total(), bust ? RED : natural ? 0xFFD24A : GOLD, bust || natural ? 1 : 0.4f, now);
    }

    private void seat(DrawContext context, CardTableClient.State s, CardTable.View v, int seat, long now) {
        CardTable.SeatView view = v.seats().get(seat);
        int slot = Math.min(3, slot(seat, s.you()));
        boolean mine = seat == s.you();
        float scale = mine ? cardScale : cardScale * (v.seats().size() > 2 ? 0.8f : 0.9f);
        float[] c = SLOT_CARDS[slot], b = SLOT_BET[slot];
        float cx = x(c[0]), cy = y(c[1]);
        boolean turn = "PLAYERS".equals(v.phase()) && v.turnSeat() == seat;
        if (turn) {
            // Пульсирующая золотая подсветка зоны того, кто ходит.
            float pulse = 0.5f + 0.5f * (float) Math.sin(now / 260.0);
            int hw = Math.round(SLOT_SIZE[slot] * tw / 2), hh = Math.round(0.11f * th);
            for (int k = 0; k < 3; k++) outline(context, Math.round(cx) - hw - k, Math.round(cy) - hh - k, Math.round(cx) + hw + k, Math.round(cy) + hh + k, TableDraw.argb(GOLD, (0.75f - k * 0.25f) * (0.5f + 0.5f * pulse)));
        }
        if (view == null) {
            if (!mine) context.drawCenteredTextWithShadow(textRenderer, "свободно", Math.round(cx), Math.round(cy) - 4, 0x60C8D8C8);
            return;
        }
        // Ставка на круге: отложенная или на руках (до расчёта).
        int stake = view.pendingBet();
        if (!"BETTING".equals(v.phase()) && timeline.stakeShown(seat, now)) for (CardTable.HandView h : view.hands()) stake += h.bet();
        stake -= timeline.inFlight(seat);
        CardTableClient.Extra virtualPart = s.extra(seat);
        if (virtualPart != null && timeline.stakeShown(seat, now)) stake -= virtualPart.virtual(); // Фишки — только изумруды; предметы и прочее рядом значками.
        TableDraw.stacks(context, stake, x(b[0]), y(b[1]) + 6 * u, chipScale(), 1);
        CardTableClient.Extra extraStake = s.extra(seat);
        if (extraStake != null && extraStake.any() && timeline.stakeShown(seat, now)) extras(context, extraStake, x(b[0]) + 14 * chipScale(), y(b[1]) - 10 * chipScale());
        // Имя над зоной (у своего места — «Вы»), готовность к раздаче.
        if (!mine) {
            String label = view.name() + (s.ready().size() > seat && s.ready().get(seat) && "BETTING".equals(v.phase()) ? " · готов" : "");
            int lw = textRenderer.getWidth(label) + 12;
            int ly = Math.round(cy - 0.115f * th);
            // Нижнее место (у зрителя): подпись в левом углу зоны, чтобы не лечь на стопку фишек над картами.
            int lx = slot == 0 ? Math.round(cx - SLOT_SIZE[0] * tw / 2 + lw / 2f + 4) : Math.round(cx);
            TableDraw.badge(context, lx - lw / 2, ly - 6, lx + lw / 2, ly + 6, turn ? GOLD : 0x8A9AB0, turn ? 0.8f : 0);
            context.drawCenteredTextWithShadow(textRenderer, label, lx, ly - 4, turn ? 0xFFFFF1C6 : 0xFFE4E8EF);
        }
        // Руки.
        List<CardTable.HandView> hands = view.hands();
        for (int h = 0; h < hands.size(); h++) {
            CardTable.HandView hand = hands.get(h);
            float[] center = hand(seat, h);
            float right = row(context, seat, h, hand.cards(), center[0], center[1], scale, timeline.shake(seat, h, now), false, now);
            float flash = timeline.bustFlash(seat, h, now);
            if (flash > 0) {
                float half = (right - center[0]);
                TableDraw.sprite(context, TableDraw.Sprite.GLOW, center[0], center[1], half * 3.2f, TableDraw.CARD_H * scale * 2.4f, 0xFF1010, flash * 0.9f, 0);
                context.fill(Math.round(2 * center[0] - right - 2), Math.round(center[1] - TableDraw.CARD_H * scale / 2 - 2), Math.round(right + 2),
                        Math.round(center[1] + TableDraw.CARD_H * scale / 2 + 2), TableDraw.argb(0xC01818, flash * 0.45f));
            }
            List<Card> shown = visible(seat, h, hand.cards(), false, now);
            if (shown.isEmpty()) continue;
            Variant.Score score = Variant.of(v.variant()).score(shown);
            boolean bust = timeline.busted(seat, h, now), natural = timeline.natural(seat, h, now);
            String text = score.total() + (score.soft() ? "/" + (score.total() - 10) : "") + (hand.vaBank() ? " ва-банк" : hand.doubled() ? " ×2" : "");
            scoreBadge(context, right + 8, center[1], text, bust ? RED : natural ? 0xFFD24A : score.total() == 21 ? GREEN : GOLD, bust || natural ? 1 : turn && h == v.turnHand() ? 0.7f : 0.3f, now);
            if (mine && turn && h == v.turnHand()) timer(context, s, center[0] - (TableDraw.CARD_W * scale + Math.max(0, hand.cards().size() - 1) * 15 * scale) / 2 - 16, center[1]);
            if (mine && turn && h == v.turnHand() && CardTableClient.hint && !bust) {
                List<Card> up = visible(CardTable.DEALER, 0, v.dealer(), true, now);
                String advice = advice(score, up.isEmpty() ? 0 : Variant.of(v.variant()).score(up.subList(0, 1)).total());
                context.drawTextWithShadow(textRenderer, advice, Math.round(right + 8), Math.round(center[1]) + 10, 0xFFB8C4D4);
            }
        }
    }

    /** Значки не изумрудной ставки у круга: предметы, свиток услуги, сердце интереса, бейдж скидки жителя. */
    private void extras(DrawContext context, CardTableClient.Extra e, float x, float y) {
        int ix = Math.round(x), iy = Math.round(y);
        for (int i = 0; i < e.items().size(); i++) {
            net.minecraft.item.Item type = net.minecraft.registry.Registries.ITEM.get(net.minecraft.util.Identifier.of(e.items().get(i)));
            net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(type, e.counts().get(i));
            context.drawItem(stack, ix, iy);
            context.drawItemInSlot(textRenderer, stack, ix, iy);
            ix += 17;
        }
        if (e.favor()) { context.drawItem(new net.minecraft.item.ItemStack(net.minecraft.item.Items.WRITABLE_BOOK), ix, iy); ix += 17; }
        if (e.interest()) {
            TableDraw.badge(context, ix, iy + 2, ix + 54, iy + 14, 0xFF8AB0, 0.4f);
            context.drawTextWithShadow(textRenderer, "интерес", ix + 6, iy + 4, 0xFFFFD0E0);
            ix += 58;
        }
        if (e.discount() > 0) {
            String text = "скидка на кону";
            int w = textRenderer.getWidth(text) + 12;
            TableDraw.badge(context, ix, iy + 2, ix + w, iy + 14, GOLD, 0.7f);
            context.drawTextWithShadow(textRenderer, text, ix + 6, iy + 4, 0xFFFFE9B0);
        }
    }

    private static void outline(DrawContext context, int x0, int y0, int x1, int y1, int argb) {
        context.fill(x0 + 3, y0, x1 - 3, y0 + 1, argb); context.fill(x0 + 3, y1 - 1, x1 - 3, y1, argb);
        context.fill(x0, y0 + 3, x0 + 1, y1 - 3, argb); context.fill(x1 - 1, y0 + 3, x1, y1 - 3, argb);
        context.fill(x0 + 1, y0 + 1, x0 + 3, y0 + 2, argb); context.fill(x1 - 3, y0 + 1, x1 - 1, y0 + 2, argb);
        context.fill(x0 + 1, y1 - 2, x0 + 3, y1 - 1, argb); context.fill(x1 - 3, y1 - 2, x1 - 1, y1 - 1, argb);
        context.fill(x0 + 1, y0 + 2, x0 + 2, y0 + 3, argb); context.fill(x1 - 2, y0 + 2, x1 - 1, y0 + 3, argb);
        context.fill(x0 + 1, y1 - 3, x0 + 2, y1 - 2, argb); context.fill(x1 - 2, y1 - 3, x1 - 1, y1 - 2, argb);
    }

    /** Подсказка хода (F7): до 11 и мягкие до 17 — брать; от 17 — хватит; между — по открытой карте сдающего (от 7 — брать). */
    static String advice(Variant.Score score, int dealerUp) {
        int total = score.total();
        boolean more = total <= 11 || score.soft() && total <= 17 || total < 17 && (dealerUp == 0 ? total < 15 : dealerUp >= 7);
        return more ? "совет: ещё" : "совет: хватит";
    }

    private void scoreBadge(DrawContext context, float x, float cy, String text, int color, float glow, long now) {
        int w = textRenderer.getWidth(text) + 12;
        int y0 = Math.round(cy) - 7;
        float pulse = glow >= 1 ? 0.75f + 0.25f * (float) Math.sin(now / 150.0) : glow;
        TableDraw.badge(context, Math.round(x), y0, Math.round(x) + w, y0 + 14, color, pulse);
        context.drawTextWithShadow(textRenderer, text, Math.round(x) + 6, y0 + 3, 0xFF000000 | color);
    }

    /** Кольцо таймера хода у своей руки: золото, к концу — красное, секунды в центре. */
    private void timer(DrawContext context, CardTableClient.State s, float cx, float cy) {
        long left = Math.max(0, left(s, s.turnLeft()));
        float fraction = Math.max(0, Math.min(1, left / (float) Math.max(1000, s.turnTotal())));
        int color = left < 8000 ? 0xFFFF6A5A : 0xFFF6CE6B;
        TableDraw.round(context, Math.round(cx) - 10, Math.round(cy) - 10, Math.round(cx) + 10, Math.round(cy) + 10, 10, 0xD0141820);
        TableDraw.ring(context, cx, cy, 9, 2, fraction, color, 0x40FFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, String.valueOf((left + 999) / 1000), Math.round(cx), Math.round(cy) - 3, color);
    }

    private void chipMoves(DrawContext context, long now) {
        for (TableTimeline.Move m : timeline.moves()) {
            if (now < m.start()) continue;
            float t = Math.min(1, (now - m.start()) / (float) m.life());
            float e = t * t * (3 - 2 * t);
            float[] a = point(m.from(), m.fromSeat()), b = point(m.to(), m.toSeat());
            float px = a[0] + (b[0] - a[0]) * e, py = a[1] + (b[1] - a[1]) * e - (float) Math.sin(e * Math.PI) * 18 * u;
            TableDraw.stacks(context, m.amount(), px, py, chipScale(), m.to() == TableTimeline.Point.WALLET && t > 0.8f ? (1 - t) * 5 : 1);
            if (m.sparkle()) {
                TableDraw.sprite(context, TableDraw.Sprite.GLOW, px, py - 8 * chipScale(), 70 * chipScale(), 0x8CFFB0, 0.6f * (1 - t * 0.5f), 0);
                if (Math.random() < 0.6) fx.add(TableDraw.Sprite.SPARK, px + (float) (Math.random() - 0.5) * 20 * chipScale(), py - 10 * chipScale(), 0, 20, 7, 0xB8FFD0, 1, now, 400);
            }
        }
    }
    private float[] point(TableTimeline.Point point, int seat) {
        return switch (point) {
            case SHOE -> shoe();
            case WALLET -> wallet();
            case PURSE -> purse();
            case BET -> bet(seat);
        };
    }

    // ---- Житель: портрет, табличка, банк, реплика. ----

    /** Левая колонка: портрет-бюст в резной рамке, под ним табличка с именем и стилем, банк жителя; реплика — ниже ({@link #bubble}). */
    private void portrait(DrawContext context, CardTableClient.State s, int mouseX, int mouseY, long now) {
        CardTableOpenS2C table = CardTableClient.table();
        float fx = px + timeline.tell(now) * Math.max(1, pw / 60f), fy = 6;
        float inset = 16 * pw / 120f;
        float wx0 = fx + inset, wy0 = fy + inset, wx1 = fx + pw - inset, wy1 = fy + ph - inset;
        context.fillGradient(Math.round(wx0), Math.round(wy0), Math.round(wx1), Math.round(wy1), 0xFF3A3424, 0xFF161A24); // тёплый отсвет лампы сверху
        Entity entity = table == null || client == null || client.world == null ? null : client.world.getEntityById(table.entityId());
        if (entity instanceof LivingEntity living) bust(context, living, wx0, wy0, wx1, wy1, mouseX);
        else TableDraw.atlas(context, (wx0 + wx1) / 2 - 16, (wy0 + wy1) / 2 - 16, 32, 32, 32, 304, 32, 32, 0xFFFFFFFF, 0);
        TableDraw.image(context, TableDraw.PORTRAIT, fx, fy, pw, ph, 0xFFFFFFFF);
        // Табличка: имя и стиль.
        String name = table == null ? "" : table.villager();
        String style = s == null || s.style().isEmpty() ? "" : "играет: " + s.style();
        int nw = Math.max(Math.round(pw), Math.max(textRenderer.getWidth(name), textRenderer.getWidth(style)) + 26), nh = style.isEmpty() ? 18 : 27;
        int nx = Math.max(2, Math.round(px + pw / 2 - nw / 2f)), ny = Math.round(fy + ph + 2);
        TableDraw.image(context, TableDraw.NAMEPLATE, nx, ny, nw, nh, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, name, nx + (nw - textRenderer.getWidth(name)) / 2, ny + 5, 0xFFFFE9B0);
        if (!style.isEmpty()) context.drawTextWithShadow(textRenderer, style, nx + (nw - textRenderer.getWidth(style)) / 2, ny + 15, 0xFFE0C080);
        int below = ny + nh + 4;
        // Банк.
        if (s != null && s.view() != null) {
            String bank = "Банк " + s.view().purse() + (s.watchers() > 0 ? " · смотрят " + s.watchers() : "");
            int bw = textRenderer.getWidth(bank) + 28;
            int bx0 = Math.max(2, Math.round(px + pw / 2 - bw / 2f));
            TableDraw.badge(context, bx0, below, bx0 + bw, below + 16, 0x5CE08A, 0.5f);
            TableDraw.emerald(context, bx0 + 6, below + 3, 1);
            context.drawTextWithShadow(textRenderer, bank, bx0 + 19, below + 4, 0xFFB8F5C8);
            below += 22;
        }
        bubbleTop = below + 2; bubbleLeft = Math.round(px);
    }
    /** Житель бюстом: голова с запасом сверху, плечи по грудь. Масштаб и сдвиг — от настоящей высоты модели (у человека-жителя и
     * ванильного она разная), окно режет ножницами только рамка; смотрит на стол, чуть ведя головой за мышью. */
    private void bust(DrawContext context, LivingEntity entity, float x0, float y0, float x1, float y1, int mouseX) {
        float window = y1 - y0;
        float height = Math.max(0.5f, entity.getHeight()), scale = entity.getScale();
        // В окне — верхние 55% модели и воздух над головой.
        float size = window / (height * 0.72f);
        float cx = (x0 + x1) / 2, cy = y0 + (0.17f + 0.5f) * height * size; // 17% воздуха: над шляпой фермера и ушами человека-жителя
        float turn = (float) Math.atan((cx - mouseX) / 160.0), tilt = (float) Math.atan(-0.35);
        org.joml.Quaternionf body = new org.joml.Quaternionf().rotateZ((float) Math.PI);
        org.joml.Quaternionf camera = new org.joml.Quaternionf().rotateX(tilt * 20 * 0.017453292f);
        body.mul(camera);
        float bodyYaw = entity.bodyYaw, yaw = entity.getYaw(), pitch = entity.getPitch(), prevHead = entity.prevHeadYaw, head = entity.headYaw;
        entity.bodyYaw = 180 + turn * 20; entity.setYaw(180 + turn * 40); entity.setPitch(-tilt * 20);
        entity.headYaw = entity.getYaw(); entity.prevHeadYaw = entity.getYaw();
        context.enableScissor(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1));
        InventoryScreen.drawEntity(context, cx, cy, size / scale, new org.joml.Vector3f(0, height / 2 / scale, 0), body, camera, entity);
        context.disableScissor();
        entity.bodyYaw = bodyYaw; entity.setYaw(yaw); entity.setPitch(pitch); entity.prevHeadYaw = prevHead; entity.headYaw = head;
    }    private int bubbleTop, bubbleLeft;

    /** Пузырь реплики под табличкой: светлый, с хвостиком к портрету, текст печатается. */
    private void bubble(DrawContext context, long now) {
        if (line.isEmpty()) return;
        int chars = (int) Math.min(line.length() + 2, (now - lineAt) / 18 + 1);
        if (chars <= 0) return;
        int x0 = bubbleLeft, maxW = Math.max(90, Math.round(Math.max(pw, 120)));
        List<net.minecraft.text.OrderedText> rows = textRenderer.wrapLines(Text.literal("«" + line + "»"), maxW - 14);
        int h = rows.size() * 10 + 8, w = 14;
        for (var r : rows) w = Math.max(w, textRenderer.getWidth(r) + 14);
        int y0 = bubbleTop;
        int edge = switch (emotion) { case "HAPPY" -> 0xFFD8A020; case "SMUG" -> 0xFF8A50C0; case "ANGRY" -> 0xFFC03028; case "SAD" -> 0xFF4A70B0; case "SURPRISED" -> 0xFFE07820; case "NERVOUS" -> 0xFF6A8A60; default -> 0xFF3A2A1A; };
        TableDraw.round(context, x0 - 2, y0 - 2, x0 + w + 2, y0 + h + 2, 7, edge);
        TableDraw.round(context, x0, y0, x0 + w, y0 + h, 5, 0xF8FBF6EA);
        for (int i = 0; i < 5; i++) context.fill(x0 + 12 + i, y0 - 5 + i, x0 + 22 - i, y0 + 1 - i + 5, i == 0 ? edge : 0xF8FBF6EA); // хвостик вверх, к портрету
        int shown = 0, yy = y0 + 4;
        for (var r : rows) {
            StringBuilder plain = new StringBuilder();
            r.accept((index, style, cp) -> { plain.appendCodePoint(cp); return true; });
            String text = plain.toString();
            if (shown >= chars) break;
            context.drawText(textRenderer, text.substring(0, Math.min(text.length(), chars - shown)), x0 + 7, yy, 0xFF2A2018, false);
            shown += text.length();
            yy += 10;
        }
    }
    private void wallet(DrawContext context, CardTableClient.State s) {
        if (s == null || s.you() < 0) return;
        String text = "Твои " + s.wallet();
        int w = textRenderer.getWidth(text) + 28;
        int y0 = height - 22, x0 = Math.round(walletX - w / 2f), x1 = x0 + w;
        TableDraw.badge(context, x0, y0, x1, y0 + 16, 0x5CE08A, 0.4f);
        TableDraw.emerald(context, x0 + 6, y0 + 3, 1);
        context.drawTextWithShadow(textRenderer, text, x0 + 20, y0 + 4, 0xFFB8F5C8);
    }

    /** Строка хода посередине сукна и подсказка сервера. */
    private void status(DrawContext context, CardTableClient.State s, long now) {
        if (s == null || s.view() == null || !CardTableClient.closed().isEmpty()) return;
        CardTable.View v = s.view();
        String text = switch (v.phase()) {
            case "BETTING" -> left(s, s.dealLeft()) > 0 ? "Раздача через " + (left(s, s.dealLeft()) + 999) / 1000 + " с"
                    : s.you() < 0 ? "Делают ставки" : "Ставка от " + v.minBet() + " до " + v.maxBet() + " · «Раздать», когда готовы";
            case "PLAYERS" -> v.turnSeat() == s.you() ? "Ваш ход: «Ещё» — пробел, «Хватит» — Enter" : "Ходит " + name(v, v.turnSeat());
            case "DEALER" -> "Житель добирает";
            default -> timeline.busy(now) ? "" : left(s, s.catchLeft()) > 0 && s.you() >= 0 ? "Заметили рукав? «Ловлю!» — ещё " + (left(s, s.catchLeft()) + 999) / 1000 + " с" : "Раздача окончена";
        };
        if (!text.isEmpty()) {
            int w = textRenderer.getWidth(text) + 16;
            int cy = Math.round(Math.min(y(0.865f) + 16 * u, ty + th - 24 * u));
            boolean mine = "PLAYERS".equals(v.phase()) && v.turnSeat() == s.you();
            TableDraw.badge(context, mid() - w / 2, cy - 7, mid() + w / 2, cy + 7, GOLD, mine ? 0.6f : 0);
            context.drawCenteredTextWithShadow(textRenderer, text, mid(), cy - 4, 0xFFFFE9B0);
        }        if (!s.notice().isEmpty() && System.currentTimeMillis() - s.receivedAt() < 4000) {
            int w = textRenderer.getWidth(s.notice()) + 16;
            int cy = Math.round(y(0.45f));
            TableDraw.badge(context, mid() - w / 2, cy - 8, mid() + w / 2, cy + 8, RED, 0.8f);
            context.drawCenteredTextWithShadow(textRenderer, s.notice(), mid(), cy - 4, 0xFFFFD0C8);
        }
    }
    private static String name(CardTable.View v, int seat) { CardTable.SeatView s = seat >= 0 && seat < v.seats().size() ? v.seats().get(seat) : null; return s == null ? "?" : s.name(); }
}
