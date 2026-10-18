package io.github.mobtalk.client;

import io.github.mobtalk.client.game.MobGameBridge;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.text.Text;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Push-to-talk inside the native trading window: which merchant it belongs to, whether the key is held there, and when the
 * window stops being the conversation's. The only GUI that accepts speech; everything else runs through {@link MobTalkClient}. */
final class MerchantSession {
    /** The merchant a recording was started for; a reply is applied only while the same window is still open. */
    record Context(UUID mob, UUID player, int syncId, long epoch) { }

    private final MobTalkClient owner;
    private MerchantScreen screen;
    private Context context, work;
    private long epoch;
    private boolean keyHeld, lookupPending;
    /** The mob whose own deal window the player asked for with B; that window does not interrupt a running conversation. */
    UUID ownTradeRequested;

    MerchantSession(MobTalkClient owner) { this.owner = owner; }

    Context context() { return context; }
    /** The context a new request should carry: only while the trading window is the current screen. */
    Context current() { return owner.client.currentScreen == screen ? context : null; }
    Context work() { return work; }
    void setWork(Context value) { work = value; }
    boolean windowOpen() { return screen != null && owner.client.currentScreen == screen; }
    boolean keyHeld() { return keyHeld; }
    void clearKeyHeld() { keyHeld = false; }

    /** Only the native trading screen accepts push-to-talk while a GUI is open. */
    void initialize(MerchantScreen opened) {
        if (screen != opened) {
            // The mob's own window (B) is part of the conversation; only a villager's profession window interrupts it.
            boolean own = ownTradeRequested != null;
            ownTradeRequested = null;
            end("Окно торговли изменилось: разговор отменён");
            if (!own && owner.busyTalking()) owner.cancelWork("Открыта торговля: разговор отменён");
            owner.updateWorld();
            owner.ensureGameBridge();
            screen = opened;
            epoch++;
            keyHeld = false;
            owner.keyHeld = false;
            owner.status = "Определяем торговца…";
            resolve(opened, epoch);
        }
        ScreenKeyboardEvents.allowKeyPress(opened).register((ignored, key, scan, modifiers) -> {
            if (screen == opened && context != null && owner.tradeKey.matchesKey(key, scan)) {
                opened.close(); // The deals key toggles the window, as the old screen did.
                return false;
            }
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

    private void resolve(MerchantScreen opened, long expected) {
        if (lookupPending || owner.gameReady == null || owner.client.player == null) return;
        lookupPending = true;
        UUID player = owner.client.player.getUuid();
        int syncId = opened.getScreenHandler().syncId;
        CompletableFuture<MobGameBridge> ready = owner.gameReady;
        ready.thenCompose(game -> game.selectMerchant(player, syncId)).whenComplete((id, error) -> owner.client.execute(() -> {
            if (screen != opened || epoch != expected) return;
            lookupPending = false;
            if (owner.client.currentScreen != opened || owner.client.player == null || !player.equals(owner.client.player.getUuid())
                    || owner.client.player.currentScreenHandler != opened.getScreenHandler()) {
                end("Окно торговли изменилось: разговор отменён");
                return;
            }
            if (error != null) {
                if (context != null) end("Торговец недоступен: разговор отменён");
                else owner.status = "Нет доступного торговца для Mob Talk";
                return;
            }
            if (context != null) {
                if (!context.mob.equals(id) || context.syncId != syncId) end("Торговец сменился: разговор отменён");
                return;
            }
            MobEntity mob = MobSpeechSound.find(owner.world, id);
            if (mob == null || !mob.isAlive() || mob.isRemoved()) {
                owner.status = "Торговец ещё загружается";
                return;
            }
            owner.select(mob);
            context = new Context(id, player, syncId, expected);
            owner.status = owner.readyStatus();
        }));
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

    /** Recording is allowed with no GUI, or inside this session's own trading window while it still matches. */
    boolean allowedRecordingScreen() {
        return owner.client.currentScreen == null || context != null && owner.client.currentScreen == screen
                && owner.client.player != null && owner.client.player.currentScreenHandler == screen.getScreenHandler()
                && context.player.equals(owner.client.player.getUuid()) && context.mob.equals(owner.selected)
                && context.syncId == screen.getScreenHandler().syncId;
    }

    /** Once a tick: drop a window that changed under us, re-resolve the merchant every second. */
    void tick(long ticks) {
        if (screen == null) return;
        if (owner.client.currentScreen != screen || owner.client.player == null
                || owner.client.player.currentScreenHandler != screen.getScreenHandler())
            end("Окно торговли изменилось: разговор отменён");
        else if (ticks % 20 == 0) resolve(screen, epoch);
    }
}
