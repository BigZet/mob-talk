package io.github.mobtalk.client.settings;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.Tunables;
import java.util.Locale;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/** One number of {@link Tunables} as a slider over the settings draft: the label carries the value and its unit
 * ({@code mobtalk.tune.<field>}), the tooltip explains it and shows the range and the default. Arrow keys move one step. */
public final class TunableSlider extends SliderWidget {
    private final Tunables.Tunable tunable;
    private final MobTalkConfig draft;

    public TunableSlider(Tunables.Tunable tunable, MobTalkConfig draft) {
        super(0, 0, 100, 20, Text.empty(), position(tunable, tunable.normalize(tunable.get(draft))));
        this.tunable = tunable; this.draft = draft;
        updateMessage();
        setTooltip(Tooltip.of(Text.translatable(tunable.langKey() + ".tooltip").append("\n")
                .append(Text.translatable("mobtalk.settings.range", format(tunable.min()), format(tunable.max()), format(tunable.defaultValue())))));
    }

    /** The explanation from the lang file, for the settings search. */
    public String explanation() { return Text.translatable(tunable.langKey() + ".tooltip").getString(); }

    private static double position(Tunables.Tunable tunable, double value) {
        return tunable.max() <= tunable.min() ? 0 : Math.max(0, Math.min(1, (value - tunable.min()) / (tunable.max() - tunable.min())));
    }
    /** Whole numbers without a fraction, half steps with one digit. */
    public static String format(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    @Override protected void updateMessage() {
        if (tunable == null) return; // Called from the super constructor before the fields are set.
        setMessage(Text.translatable(tunable.langKey(), format(tunable.get(draft))));
    }
    @Override protected void applyValue() {
        tunable.set(draft, tunable.min() + value * (tunable.max() - tunable.min()));
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // One step per arrow press: a slider of 0..3000 would otherwise move by a dozen points at a time.
        boolean left = keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT, right = keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
        if (!left && !right) return super.keyPressed(keyCode, scanCode, modifiers);
        tunable.set(draft, tunable.get(draft) + (left ? -tunable.step() : tunable.step()));
        value = position(tunable, tunable.get(draft));
        updateMessage();
        return true;
    }
}
