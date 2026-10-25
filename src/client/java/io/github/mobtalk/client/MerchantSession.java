package io.github.mobtalk.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.text.Text;
import java.util.UUID;

/** Речь по удержанию внутри ванильного окна торговли: чьё это окно, зажата ли в нём клавиша и когда окно перестаёт быть окном
 * разговора. Кто торговец за окном, отвечает сервер ({@code ScreenRequestC2S("deal", syncId)} → {@code ScreenDataS2C("deal")}). */
final class MerchantSession {
    /** Торговец, для которого начата запись; ответ применяется, пока открыто то же окно. */
    record Context(UUID mob, UUID player, int syncId, long epoch) { }

    private final MobTalkClient owner;
    private MerchantScreen screen;
    private Context context, work;
    private long epoch;
    private boolean keyHeld, lookupPending;
    private long lookupSince;
    /** Моб, чьё окно сделок игрок попросил по B: это окно не прерывает идущий разговор. */
    UUID ownTradeRequested;

    MerchantSession(MobTalkClient owner) { this.owner = owner; }

    Context context() { return context; }
    Context current() { return owner.client.currentScreen == screen ? context : null; }
    Context work() { return work; }
    void setWork(Context value) { work = value; }
    boolean windowOpen() { return screen != null && owner.client.currentScreen == screen; }
    boolean keyHeld() { return keyHeld; }
    void clearKeyHeld() { keyHeld = false; }

    void initialize(MerchantScreen opened) {
        if (screen != opened) {
            boolean own = ownTradeRequested != null;
            ownTradeRequested = null;
            end("Окно торговли изменилось: разговор отменён");
            if (!own && owner.busyTalking()) owner.cancelWork("Открыта торговля: разговор отменён");
            owner.updateWorld();
            screen = opened;
            epoch++;
            keyHeld = false;
            owner.keyHeld = false;
            owner.status = "Определяем торговца…";
            resolve(opened);
        }
        ScreenKeyboardEvents.allowKeyPress(opened).register((ignored, key, scan, modifiers) -> {
            if (screen == opened && context != null && owner.tradeKey.matchesKey(key, scan)) { opened.close(); return false; }
            if (!inputEnabled(opened) || !owner.talkKey.matchesKey(key, scan)) return true;
            keyHeld = true;
            return false;
        });
        ScreenKeyboardEvents.allowKeyRelease(opened).register((ignored, key, scan, modifiers) -> {
            if (screen != opened || !owner.talkKey.matchesKey(key, scan)) return true;
            boolean consumed = keyHeld || owner.enabled();
            keyHeld = false;
            return !consumed;
        });
        ScreenMouseEvents.allowMouseClick(opened).register((ignored, x, y, button) -> {
            if (!inputEnabled(opened) || !owner.talkKey.matchesMouse(button)) return true;
            keyHeld = true;
            return false;
        });
        ScreenMouseEvents.allowMouseRelease(opened).register((ignored, x, y, button) -> {
            if (screen != opened || !owner.talkKey.matchesMouse(button)) return true;
            boolean consumed = keyHeld || owner.enabled();
            keyHeld = false;
            return !consumed;
        });
        ScreenEvents.remove(opened).register(ignored -> { if (screen == opened) end("Торговля закрыта: разговор отменён"); });
        ScreenEvents.afterRender(opened).register((ignored, draw, mouseX, mouseY, delta) -> {
            if (!owner.enabled() || screen != opened) return;
            String hint = context == null ? "Mob Talk · " + owner.status
                    : "Mob Talk · " + owner.talkKey.getBoundKeyLocalizedText().getString() + ": удерживайте для речи · " + owner.status;
            String line = owner.client.textRenderer.trimToWidth(hint, Math.max(40, opened.width - 12));
            int textWidth = owner.client.textRenderer.getWidth(line);
            draw.fill((opened.width - textWidth) / 2 - 3, 3, (opened.width + textWidth) / 2 + 3, 17, 0xB0000000);
            draw.drawCenteredTextWithShadow(owner.client.textRenderer, Text.literal(line), opened.width / 2, 6, 0xFFFFFF);
        });
    }

    private boolean inputEnabled(MerchantScreen opened) {
        return screen == opened && owner.client.currentScreen == opened && owner.enabled() && !owner.reloading && owner.client.isWindowFocused();
    }

    /** Спросить сервер, чьё это окно; ответ придёт в {@link #resolved}. */
    private void resolve(MerchantScreen opened) {
        if (lookupPending && System.currentTimeMillis() - lookupSince < 5_000 || owner.client.player == null) return;
        lookupPending = true; lookupSince = System.currentTimeMillis();
        owner.requestDeal(opened.getScreenHandler().syncId);
    }
    /** {@code ScreenDataS2C("deal")}: {"mob": uuid|"", "syncId": n, "entityId": n, "name": ...}. */
    void resolved(String json) {
        lookupPending = false;
        MerchantScreen opened = screen;
        if (opened == null || owner.client.currentScreen != opened || owner.client.player == null) return;
        JsonObject data;
        try { data = JsonParser.parseString(json).getAsJsonObject(); } catch (RuntimeException bad) { return; }
        int syncId = data.has("syncId") ? data.get("syncId").getAsInt() : -1;
        if (syncId != opened.getScreenHandler().syncId || owner.client.player.currentScreenHandler != opened.getScreenHandler()) return;
        String mobText = data.has("mob") ? data.get("mob").getAsString() : "";
        if (mobText.isEmpty()) {
            if (context != null) end("Торговец недоступен: разговор отменён");
            else if (!barMenu(opened)) owner.status = "Нет доступного торговца для Mob Talk";
            return;
        }
        UUID id;
        try { id = UUID.fromString(mobText); } catch (IllegalArgumentException bad) { return; }
        UUID player = owner.client.player.getUuid();
        if (context != null) { if (!context.mob.equals(id) || context.syncId != syncId) end("Торговец сменился: разговор отменён"); return; }
        MobEntity mob = MobSpeechSound.find(owner.world, id);
        if (mob == null || !mob.isAlive() || mob.isRemoved()) { owner.status = "Торговец ещё загружается"; return; }
        owner.select(mob);
        context = new Context(id, player, syncId, epoch);
        owner.status = owner.readyStatus();
    }

    /** The bartender's drinks menu (BarMerchant) is a plain shop window, not a conversation: nothing to report about it. */
    private static boolean barMenu(MerchantScreen opened) {
        return opened.getTitle().getContent() instanceof net.minecraft.text.TranslatableTextContent title && title.getKey().equals("mobtalk.bar.menu");
    }

    void end(String reason) {
        screen = null;
        context = null;
        epoch++;
        lookupPending = false;
        keyHeld = false;
        owner.keyHeld = owner.talkKey != null && owner.talkKey.isPressed();
        if (work != null) owner.cancelWork(reason);
    }

    /** Запись разрешена без окна, или в своём торговом окне, пока оно то же. */
    boolean allowedRecordingScreen() {
        return owner.client.currentScreen == null || context != null && owner.client.currentScreen == screen
                && owner.client.player != null && owner.client.player.currentScreenHandler == screen.getScreenHandler()
                && context.player.equals(owner.client.player.getUuid()) && context.mob.equals(owner.selected)
                && context.syncId == screen.getScreenHandler().syncId;
    }

    /** Раз в тик: окно, сменившееся под нами, снимается; торговец переспрашивается раз в секунду. */
    void tick(long ticks) {
        if (screen == null) return;
        if (owner.client.currentScreen != screen || owner.client.player == null || owner.client.player.currentScreenHandler != screen.getScreenHandler())
            end("Окно торговли изменилось: разговор отменён");
        else if (ticks % 20 == 0) resolve(screen);
    }
}
