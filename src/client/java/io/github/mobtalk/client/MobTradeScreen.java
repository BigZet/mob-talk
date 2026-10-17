package io.github.mobtalk.client;

import io.github.mobtalk.client.game.MobGameBridge;
import io.github.mobtalk.core.TradeDeal;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** The selected mob's own deals (trades.json). Goods change hands only by the explicit «Купить» click here, verified on the server. */
final class MobTradeScreen extends Screen {
    interface Controller {
        CompletableFuture<MobGameBridge.TradeOffers> load();
        CompletableFuture<String> buy(String dealId);
        /** The selected mob is still alive and within conversation range. */
        boolean available();
        boolean isTradeKey(int key, int scan);
    }
    private static final int ROW = 26, LIST_TOP = 28, BUTTON = 72;
    private final Controller controller;
    private MobGameBridge.TradeOffers offers;
    private DealList list;
    private Text status = Text.translatable("mobtalk.trade.loading");
    private boolean error, busy, disposed;
    private int left, contentWidth;

    MobTradeScreen(Text mobName, Controller controller) { super(mobName); this.controller = controller; }

    @Override protected void init() {
        contentWidth = Math.min(440, width - 40); left = (width - contentWidth) / 2;
        list = new DealList(LIST_TOP, height - 62);
        if (offers != null) {
            for (MobGameBridge.TradeRow row : offers.deals()) list.add(new DealRow(row));
            for (String hint : offers.hints()) list.add(new NoteRow(Text.translatable("mobtalk.trade.locked", hint), 0x8A8A8A));
            if (offers.deals().isEmpty() && offers.hints().isEmpty()) list.add(new NoteRow(Text.translatable("mobtalk.trade.empty"), 0xBBBBBB));
        }
        addDrawableChild(list);
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, ignored -> close()).dimensions((width - 120) / 2, height - 26, 120, 20).build());
        if (offers == null && !busy) refresh(Text.empty());
    }

    private void refresh(Text after) {
        busy = true; status = Text.translatable("mobtalk.trade.loading"); error = false;
        controller.load().whenComplete((loaded, failure) -> client.execute(() -> {
            if (disposed) return;
            busy = false;
            if (failure != null) { fail(failure); return; }
            offers = loaded; status = after;
            double scroll = list.getScrollAmount();
            clearAndInit();
            list.setScrollAmount(scroll);
        }));
    }
    private void buy(String dealId) {
        if (busy) return;
        busy = true; status = Text.translatable("mobtalk.trade.buying"); error = false;
        controller.buy(dealId).whenComplete((message, failure) -> client.execute(() -> {
            if (disposed) return;
            busy = false;
            if (failure != null) { fail(failure); return; }
            refresh(Text.literal(message == null ? "" : message));
        }));
    }
    private void fail(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        String message = MobTalkSettingsScreen.cleanMessage(cause.getMessage());
        status = message.isBlank() ? Text.translatable("mobtalk.trade.failed") : Text.literal(message);
        error = true;
    }

    @Override public void tick() { if (!controller.available()) close(); }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (controller.isTradeKey(key, scan)) { close(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean shouldPause() { return false; }
    @Override public void removed() { disposed = true; }
    @Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 10, 0xFFFFFF);
        Text quest = offers == null ? Text.empty() : offers.quest() == null ? Text.translatable("mobtalk.trade.quest.none")
                : Text.translatable("mobtalk.trade.quest", offers.quest().title(), offers.quest().progress(), offers.quest().count());
        context.drawTextWithShadow(textRenderer, clipped(quest.getString(), contentWidth), left, height - 56, 0xFFD37F);
        if (!status.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, clipped(status.getString(), contentWidth), width / 2, height - 42, error ? 0xFF6060 : 0xBBBBBB);
            if (mouseY >= height - 45 && mouseY < height - 30) setTooltip(textRenderer.wrapLines(status, Math.min(360, width - 24)));
        }
    }
    private Text clipped(String value, int max) { return textRenderer.getWidth(value) <= max ? Text.literal(value) : Text.literal(textRenderer.trimToWidth(value, Math.max(1, max - 12)) + "…"); }
    private static ItemStack stack(TradeDeal.Item item, int count) {
        Identifier id = Identifier.tryParse(item.id());
        Item known = id == null || !Registries.ITEM.containsId(id) ? Items.AIR : Registries.ITEM.get(id);
        return new ItemStack(known, Math.max(1, count));
    }
    private static Text amount(TradeDeal.Item item, int count) {
        ItemStack stack = stack(item, count);
        return Text.translatable("mobtalk.trade.item", count, stack.isEmpty() ? item.name() : stack.getName().getString());
    }

    private final class DealList extends ElementListWidget<Row> {
        DealList(int top, int bottom) { super(MobTradeScreen.this.client, MobTradeScreen.this.width, Math.max(ROW, bottom - top), top, ROW); }
        void add(Row row) { addEntry(row); }
        @Override public int getRowWidth() { return contentWidth; }
        @Override protected int getScrollbarX() { return getRowRight() + 6; }
    }
    private abstract static class Row extends ElementListWidget.Entry<Row> { }

    private final class NoteRow extends Row {
        private final Text text; private final int color;
        NoteRow(Text text, int color) { this.text = text; this.color = color; }
        @Override public List<? extends Element> children() { return List.of(); }
        @Override public List<? extends Selectable> selectableChildren() { return List.of(); }
        @Override public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            context.drawTextWithShadow(textRenderer, clipped(text.getString(), entryWidth), x, y + (entryHeight - 8) / 2, color);
            if (hovered && textRenderer.getWidth(text) > entryWidth) setTooltip(textRenderer.wrapLines(text, Math.min(360, width - 24)));
        }
    }

    /** Icon and goods on the first line; price, former price and daily stock on the second; «Купить» explains itself when disabled. */
    private final class DealRow extends Row {
        private final MobGameBridge.TradeRow row;
        private final ButtonWidget button;
        DealRow(MobGameBridge.TradeRow row) {
            this.row = row;
            button = ButtonWidget.builder(Text.translatable("mobtalk.trade.buy"), ignored -> buy(row.deal().id())).size(BUTTON, 20).build();
            Text reason = row.stockLeft() == 0 ? Text.translatable("mobtalk.trade.soldOut")
                    : row.owned() < row.currentPrice() ? Text.translatable("mobtalk.trade.short", row.currentPrice(), stack(row.deal().price(), 1).getName().getString(), row.owned())
                    : null;
            button.active = reason == null && !busy;
            if (reason != null) button.setTooltip(Tooltip.of(reason));
        }
        @Override public List<? extends Element> children() { return List.of(button); }
        @Override public List<? extends Selectable> selectableChildren() { return List.of(button); }
        @Override public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float delta) {
            TradeDeal deal = row.deal();
            int textWidth = entryWidth - BUTTON - 26;
            context.drawItem(stack(deal.sell(), deal.sell().count()), x, y + 5);
            context.drawTextWithShadow(textRenderer, clipped(amount(deal.sell(), deal.sell().count()).getString(), textWidth), x + 20, y + 4, 0xFFFFFF);
            StringBuilder details = new StringBuilder(Text.translatable("mobtalk.trade.price", amount(deal.price(), row.currentPrice())).getString());
            if (row.currentPrice() < deal.price().count()) details.append(" · ").append(Text.translatable("mobtalk.trade.was", deal.price().count()).getString());
            if (row.stockLeft() >= 0) details.append(" · ").append(Text.translatable("mobtalk.trade.stock", row.stockLeft()).getString());
            context.drawTextWithShadow(textRenderer, clipped(details.toString(), textWidth), x + 20, y + 15, row.currentPrice() < deal.price().count() ? 0x80E080 : 0xBBBBBB);
            button.setX(x + entryWidth - BUTTON); button.setY(y + 3);
            button.render(context, mouseX, mouseY, delta);
        }
    }
}
