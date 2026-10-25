package io.github.mobtalk.client;

import io.github.mobtalk.core.MoodSpell;
import io.github.mobtalk.net.MobLabelS2C;
import io.github.mobtalk.net.MobStateS2C;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipBackgroundRenderer;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/** The mood card: while the key is held (Left Alt by default), a small card beside the crosshair tells how the villager in sight
 * feels — its face, its long mood and what would help, five bars (trust and interest are toward this player) and the strongest
 * reasons. Everything comes from the state packet ({@link MobStateS2C}); the card only draws. No darkening, vanilla tooltip frame. */
final class MoodCard {
    static final Identifier FONT = Identifier.of("mobtalk", "mood");
    private static final int BAR = 64, PAD = 6;
    private final MobTalkClient owner;
    KeyBinding key;
    /** Self-test: shows the card of this mob without the key. */
    UUID forced;

    MoodCard(MobTalkClient owner) { this.owner = owner; }

    /** The face glyph {@code face} (0–15) of the font {@code mobtalk:mood}; empty for none. */
    static Text face(int face) {
        if (face < 0 || face > 16) return Text.empty();
        return Text.literal(String.valueOf((char) (0xE000 + face))).setStyle(Style.EMPTY.withFont(FONT).withColor(Formatting.WHITE));
    }

    /** The word of a state ({@code mobtalk.state.<key>}), in the feminine for a woman when the language has one. */
    static String word(String key, boolean female) {
        if (key == null || key.isEmpty()) return "ровно";
        String base = Text.translatable("mobtalk.state." + key).getString();
        return female ? Text.translatableWithFallback("mobtalk.state." + key + ".f", base).getString() : base;
    }

    private UUID subject() {
        if (forced != null) return forced;
        if (key == null || !key.isPressed() || owner.client.currentScreen != null || !owner.config().labelsMoodFace) return null;
        MobEntity aimed = owner.aimedMob(MobLabels.RANGE);
        return aimed == null ? null : aimed.getUuid();
    }

    void render(DrawContext context, RenderTickCounter tick) {
        MinecraftClient client = owner.client;
        if (client.player == null || client.options.hudHidden || !owner.enabled()) return;
        UUID mob = subject();
        MobStateS2C state = owner.views.state(mob);
        MobLabelS2C label = owner.views.label(mob);
        if (state == null || label == null || label.name().isBlank()) return;
        TextRenderer font = client.textRenderer;

        MoodSpell spell = MoodSpell.byKey(state.spell());
        String wordKey = spell != null ? spell.key : state.word();
        int sign = spell != null ? spell.sign : state.wordSign();
        Text word = Text.literal(word(wordKey, state.female())
                + (spell != null && state.spellMinutes() > 0 ? " · " + state.spellMinutes() + " мин" : ""))
                .formatted(sign > 0 ? Formatting.GREEN : sign < 0 ? Formatting.RED : Formatting.GRAY);
        String[] names = { "Настроение", "Спокойствие", "Бодрость", "Доверие к вам", "Интерес к вам" };
        int[] values = { state.mood(), state.calm(), state.energy(), state.trust(), state.interest() };
        int labelWidth = 0;
        for (String name : names) labelWidth = Math.max(labelWidth, font.getWidth(name));
        int width = Math.max(PAD + labelWidth + 4 + BAR + PAD, PAD + 22 + Math.max(font.getWidth(label.name()), font.getWidth(word)) + PAD);
        int inner = width - 2 * PAD;

        List<OrderedText> help = new ArrayList<>();
        if (spell != null && !spell.help.isBlank())
            help.addAll(font.wrapLines(Text.literal("Поможет: " + spell.help).formatted(Formatting.GOLD), inner));
        List<OrderedText> reasons = new ArrayList<>();
        for (String line : state.reasons().split("\n")) {
            if (line.length() < 2) continue;
            char mark = line.charAt(0);
            Formatting color = mark == '+' ? Formatting.GREEN : mark == '-' ? Formatting.RED : Formatting.GRAY;
            reasons.addAll(font.wrapLines(Text.literal((mark == '+' ? "+ " : mark == '-' ? "− " : "· ") + line.substring(1)).formatted(color), inner));
        }

        int height = PAD + 18 + 4 + names.length * 9 + (help.isEmpty() ? 0 : 3 + help.size() * 9)
                + (reasons.isEmpty() ? 0 : 3 + 9 + reasons.size() * 9) + PAD - 2;
        int x = Math.min(context.getScaledWindowWidth() / 2 + 16, context.getScaledWindowWidth() - width - 4);
        int y = Math.max(4, Math.min(context.getScaledWindowHeight() / 2 - height / 2, context.getScaledWindowHeight() - height - 4));
        TooltipBackgroundRenderer.render(context, x, y, width, height, 0);

        int cx = x + PAD, cy = y + PAD;
        context.getMatrices().push();
        context.getMatrices().translate(cx, cy, 0);
        context.getMatrices().scale(2, 2, 1);
        context.drawText(font, face(state.face()), 0, 0, 0xFFFFFFFF, false);
        context.getMatrices().pop();
        context.drawText(font, Text.literal(label.name()).formatted(Formatting.WHITE), cx + 22, cy, 0xFFFFFFFF, true);
        context.drawText(font, word, cx + 22, cy + 10, 0xFFFFFFFF, true);
        cy += 22;
        for (int i = 0; i < names.length; i++) {
            context.drawText(font, names[i], cx, cy, 0xFFA8A8A8, false);
            bar(context, cx + labelWidth + 4, cy + 1, values[i]);
            cy += 9;
        }
        if (!help.isEmpty()) {
            cy += 3;
            for (OrderedText line : help) { context.drawText(font, line, cx, cy, 0xFFFFFFFF, false); cy += 9; }
        }
        if (!reasons.isEmpty()) {
            cy += 3;
            context.drawText(font, "Почему:", cx, cy, 0xFFA8A8A8, false);
            cy += 9;
            for (OrderedText line : reasons) { context.drawText(font, line, cx, cy, 0xFFFFFFFF, false); cy += 9; }
        }
    }

    /** A pixel bar: a dark frame, an empty groove, the fill from red through amber to green with a light top row. */
    private static void bar(DrawContext context, int x, int y, int value) {
        int v = Math.max(0, Math.min(100, value));
        context.fill(x, y, x + BAR, y + 6, 0xFF1A1410);
        context.fill(x + 1, y + 1, x + BAR - 1, y + 5, 0xFF3A2E26);
        int filled = Math.max(2, Math.round((BAR - 2) * v / 100f)); // a red stub even at zero: «empty» reads as a value, not a gap
        int color = v < 25 ? 0xFFD8283C : v < 45 ? 0xFFE8862A : v <= 60 ? 0xFFE8C83A : v < 80 ? 0xFF8CCB3C : 0xFF4FBF3F;
        context.fill(x + 1, y + 1, x + 1 + filled, y + 5, color);
        context.fill(x + 1, y + 1, x + 1 + filled, y + 2, lighter(color));
        context.fill(x + 1, y + 4, x + 1 + filled, y + 5, darker(color));
    }
    private static int lighter(int c) { return mix(c, 0xFFFFFFFF, 0.35f); }
    private static int darker(int c) { return mix(c, 0xFF000000, 0.3f); }
    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t), g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t),
                bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }
}
