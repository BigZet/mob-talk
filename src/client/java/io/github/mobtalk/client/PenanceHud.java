package io.github.mobtalk.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/** Б4: строка епитимьи в углу экрана, как поручение: что сделать, сколько сделано, срок; исполнив — к кому вернуться. Текст
 * присылает сервер (экран {@code penance}), личная настройка {@code confessionHud} его прячет. */
final class PenanceHud {
    private static volatile String text = "";
    private static MobTalkClient owner;
    private PenanceHud() { }

    static void register(MobTalkClient client) {
        owner = client;
        HudRenderCallback.EVENT.register(PenanceHud::render);
    }
    /** {@code {"text":…}}; пустой текст — епитимьи нет. */
    static void text(String json) {
        try {
            var object = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            text = object.has("text") ? object.get("text").getAsString() : "";
        } catch (RuntimeException malformed) { text = ""; }
    }
    /** Для самотеста: что сейчас в строке. */
    static String current() { return text; }

    private static void render(DrawContext context, RenderTickCounter counter) {
        String line = text;
        MinecraftClient client = MinecraftClient.getInstance();
        if (line.isEmpty() || client.player == null || client.options.hudHidden || owner == null || !owner.config().confessionHud) return;
        int width = client.textRenderer.getWidth(line);
        int x = 6, y = 6;
        context.fill(x - 3, y - 3, x + width + 3, y + 11, 0x88000000);
        context.drawTextWithShadow(client.textRenderer, line, x, y, line.startsWith("Епитимья исполнена") ? 0x88FF88 : 0xFFE08A);
    }
}
