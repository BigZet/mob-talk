package io.github.mobtalk.client;

import io.github.mobtalk.client.game.mixin.ChatScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.gui.screen.SleepingChatScreen;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import java.util.Comparator;

/** Трек J: разговор в кровати. Пока игрок лежит, ваниль держит открытым окно чата сна; V в нём при пустой строке — это
 * «говорить», а не буква в чат. Собеседник — житель, лежащий (или стоящий) ближе всех к кровати. */
final class BedTalk {
    private static final double REACH = 4.0;
    private final MobTalkClient owner;
    private SleepingChatScreen screen;
    private boolean keyHeld;

    BedTalk(MobTalkClient owner) { this.owner = owner; }

    void initialize(SleepingChatScreen opened) {
        screen = opened; keyHeld = false;
        ScreenKeyboardEvents.allowKeyPress(opened).register((ignored, key, scan, modifiers) -> {
            if (screen != opened || !owner.talkKey.matchesKey(key, scan) || !chatEmpty(opened)) return true;
            keyHeld = true;
            return false;
        });
        ScreenKeyboardEvents.allowKeyRelease(opened).register((ignored, key, scan, modifiers) -> {
            if (screen != opened || !owner.talkKey.matchesKey(key, scan) || !keyHeld) return true;
            keyHeld = false; clearChat(opened);
            return false;
        });
        // Буква клавиши приходит отдельным вводом символа: пока V зажата, строку чата держим пустой.
        ScreenEvents.afterTick(opened).register(ignored -> { if (keyHeld) clearChat(opened); });
        ScreenEvents.remove(opened).register(ignored -> { if (screen == opened) { screen = null; keyHeld = false; } });
    }

    boolean open() { return screen != null && owner.client.currentScreen == screen; }
    boolean keyHeld() { return open() && keyHeld; }

    /** Житель у кровати: сначала спящий рядом, иначе ближайший; null — никого. */
    MobEntity partner() {
        if (owner.client.player == null || owner.client.world == null) return null;
        var player = owner.client.player;
        return owner.client.world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(REACH), v -> v.isAlive() && !v.isBaby()).stream()
                .min(Comparator.<VillagerEntity>comparingInt(v -> v.isSleeping() ? 0 : 1).thenComparingDouble(v -> v.squaredDistanceTo(player)))
                .orElse(null);
    }

    private static boolean chatEmpty(SleepingChatScreen opened) {
        var field = ((ChatScreenAccessor) opened).mobtalk$chatField();
        return field == null || field.getText().isEmpty();
    }
    private static void clearChat(SleepingChatScreen opened) {
        var field = ((ChatScreenAccessor) opened).mobtalk$chatField();
        if (field != null && !field.getText().isEmpty()) field.setText("");
    }
}
