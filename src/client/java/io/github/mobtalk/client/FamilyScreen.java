package io.github.mobtalk.client;

import io.github.mobtalk.game.MobGameBridge;
import io.github.mobtalk.core.ChildLedger;
import io.github.mobtalk.core.Gender;
import io.github.mobtalk.core.Pregnancy;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Экран семьи (трек N): карточки супруга и детей — сердечки, настроение, где сейчас, ревность, срок беременности и что хочется
 * сегодня, стадия и возраст детей, последние события; внизу дерево семьи. Только чтение мира: сводку собирает сервер, экран рисует. */
final class FamilyScreen extends Screen {
    private static final int CARD = 0xC0101018, EDGE = 0xFF6A4A8A, PINK = 0xFFE7A6E0, MUTED = 0xFFA0A0A8, WHITE = 0xFFFFFFFF, BAR = 0xFF3A3A44;
    private final Screen parent;
    private final MobGameBridge game;
    private final UUID player;
    private MobGameBridge.FamilyPage page;
    private String error = "";
    private double scroll;
    private int contentHeight;

    FamilyScreen(Screen parent, MobGameBridge game, UUID player) {
        super(Text.literal("Семья"));
        this.parent = parent; this.game = game; this.player = player;
    }

    @Override protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Обновить"), b -> load()).dimensions(width / 2 - 104, height - 26, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Закрыть"), b -> close()).dimensions(width / 2 + 4, height - 26, 100, 20).build());
        if (page == null) load();
    }
    private void load() {
        game.family(player).whenComplete((result, failure) -> client.execute(() -> {
            if (failure != null) error = "Не удалось прочитать семью: " + failure.getMessage();
            else { page = result; error = ""; }
        }));
    }
    @Override public void close() { client.setScreen(parent); }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        scroll = Math.max(0, Math.min(Math.max(0, contentHeight - (height - 70)), scroll - vertical * 14));
        return true;
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, WHITE);
        int left = Math.max(8, width / 2 - 170), right = Math.min(width - 8, width / 2 + 170);
        context.enableScissor(0, 28, width, height - 32);
        int y = 32 - (int) scroll, top = y;
        if (!error.isEmpty()) y = line(context, error, left, y, 0xFFFF8080);
        else if (page == null) y = line(context, "Читаю память мира…", left, y, MUTED);
        else if (page.overview().empty()) y = paragraph(context, "Семьи пока нет. Женитесь на жителе (кольцо и «идём на свадьбу»), потом скажите «давай заведём ребёнка» — "
                + "и устройте особый вечер: «у меня для тебя сюрприз».", left, right, y, MUTED);
        else {
            for (ChildLedger.Spouse spouse : page.overview().spouses()) y = spouse(context, spouse, left, right, y) + 6;
            if (!page.overview().children().isEmpty()) y = line(context, "Дети", left, y + 2, PINK) + 2;
            for (ChildLedger.Child child : page.overview().children()) y = child(context, child, left, right, y) + 6;
            if (!page.overview().tree().isEmpty()) y = paragraph(context, "Дерево семьи: " + String.join(", ", page.overview().tree()), left, right, y + 2, MUTED);
        }
        contentHeight = y - top;
        context.disableScissor();
    }

    private int spouse(DrawContext context, ChildLedger.Spouse s, int left, int right, int y) {
        int start = y;
        y += 5;
        String role = s.gender() == Gender.FEMALE ? "жена" : "муж";
        if (!"женаты".equals(s.courtship())) role = s.courtship();
        y = line(context, "♥ " + s.name() + " — " + role + ", " + s.persona(), left + 6, y, PINK);
        y = bar(context, "Сердечки " + s.hearts(), left + 6, right - 6, y, (s.hearts() + 100) / 200f, 0xFFE05A8A);
        String job = page.profession().getOrDefault(s.id(), "");
        y = paragraph(context, "Где: " + page.where().getOrDefault(s.id(), "?") + (job.isBlank() ? "" : " · " + job), left + 6, right - 6, y, WHITE);
        String mood = page.mood().get(s.id());
        if (mood != null) y = paragraph(context, "Настроение: " + mood, left + 6, right - 6, y, WHITE);
        if (s.jealousy() > 0) y = paragraph(context, "Ревность " + s.jealousy() + " — гасится разговором, подарком и ночью вместе", left + 6, right - 6, y, 0xFFFFB070);
        if (!s.pregnancy().isBlank()) {
            String who = s.carrier() == Pregnancy.Carrier.PLAYER ? "Вы ждёте малыша (носите вы)" : "Ждёт малыша";
            y = bar(context, who + ": " + s.pregnancy(), left + 6, right - 6, y, s.days() <= 0 ? 0 : s.day() / (float) s.days(), 0xFFC98AE8);
            if (!s.craving().isBlank()) y = paragraph(context, "Что нужно сейчас: ей хочется " + s.craving() + " — принесите и подарите (G)", left + 6, right - 6, y, 0xFFFFE08A);
        } else if (s.trying()) y = paragraph(context, "Вы решили завести малыша. Устройте особый вечер: «у меня для тебя сюрприз», свечи, цветы, пластинка, торт — и ночь вместе.",
                left + 6, right - 6, y, 0xFFFFE08A);
        if (!s.recent().isEmpty()) y = paragraph(context, "Недавно: " + s.recent().getLast(), left + 6, right - 6, y, MUTED);
        frame(context, left, start, right, y + 3);
        return y + 3;
    }
    private int child(DrawContext context, ChildLedger.Child c, int left, int right, int y) {
        int start = y;
        y += 5;
        String age = c.ageTicks() < 0 ? "" : ", " + days(c.ageTicks()) + " дн.";
        y = line(context, (c.name().isBlank() ? "Малыш" : c.name()) + " — " + c.word() + (c.stage().isBlank() ? "" : ", " + c.stage()) + age + (c.dead() ? " †" : ""), left + 6, y, WHITE);
        if (c.nextTicks() > 0) y = line(context, "До следующей стадии: " + days(c.nextTicks()) + " дн.", left + 6, y, MUTED);
        String where = page.where().get(c.id());
        if (where != null && !c.dead()) y = paragraph(context, "Где: " + where, left + 6, right - 6, y, MUTED);
        if (!c.traits().isEmpty()) y = paragraph(context, "Черты: " + String.join(", ", c.traits()), left + 6, right - 6, y, MUTED);
        if (!c.recent().isEmpty()) y = paragraph(context, "Недавно: " + c.recent().getLast(), left + 6, right - 6, y, MUTED);
        frame(context, left, start, right, y + 3);
        return y + 3;
    }
    private static String days(long ticks) { double d = ticks / 24_000.0; return d < 10 ? String.format(java.util.Locale.ROOT, "%.1f", d) : String.valueOf(Math.round(d)); }

    private void frame(DrawContext context, int left, int top, int right, int bottom) {
        // Под текстом: фон рисуется после текста, но полупрозрачный и только по краям — рамка карточки.
        context.fill(left, top, right, top + 1, EDGE); context.fill(left, bottom - 1, right, bottom, EDGE);
        context.fill(left, top, left + 1, bottom, EDGE); context.fill(right - 1, top, right, bottom, EDGE);
    }
    private int line(DrawContext context, String text, int x, int y, int color) { context.drawTextWithShadow(textRenderer, text, x, y, color); return y + 11; }
    private int paragraph(DrawContext context, String text, int left, int right, int y, int color) {
        for (var row : textRenderer.wrapLines(Text.literal(text), Math.max(40, right - left))) { context.drawTextWithShadow(textRenderer, row, left, y, color); y += 10; }
        return y + 1;
    }
    private int bar(DrawContext context, String label, int left, int right, int y, float fraction, int color) {
        y = line(context, label, left, y, WHITE);
        int width = Math.max(10, right - left);
        context.fill(left, y, left + width, y + 4, BAR);
        context.fill(left, y, left + Math.round(width * Math.max(0, Math.min(1, fraction))), y + 4, color);
        return y + 8;
    }
    @Override public boolean shouldPause() { return false; }
    /** Для самотеста: сводка, которую экран показывает (null — ещё читается). */
    MobGameBridge.FamilyPage page() { return page; }
}
