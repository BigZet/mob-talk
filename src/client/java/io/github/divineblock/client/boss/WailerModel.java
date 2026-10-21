package io.github.divineblock.client.boss;

import io.github.divineblock.boss.WailerEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Плакальщица погоста: she floats with no feet, rising and sinking slowly; every torn strip of her shroud and veil ripples,
 * piece by piece, and trails behind as she glides; her long fingers flex; the candle burns upright before her face. Casting:
 * for the wail she raises both arms and throws her head back with the jaw dropped; she reaches out a long arm to grasp,
 * spreads her arms to slip away, lifts the candle high; for «Отпевание» both arms rise over the graves and the strips flare.
 * In phase 3 she trembles. Every motion is added to the rest pose of the generated model. */
public final class WailerModel extends BossModel<WailerEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "wailer"), "main");
    private final ModelPart figure, body, head, jaw, rightArm, leftArm, rightFore, leftFore, candle, flame, halfVeil;
    private final Layer[] layers;
    private final ModelPart[][] fingers = new ModelPart[4][3];

    /** One ring of torn strips: the root piece of each strip, the hinged pieces under it and the strip's angle round her. */
    private record Layer(ModelPart[] roots, ModelPart[][] pieces, float[] angle, float ripple) { }

    public WailerModel(ModelPart root) {
        super(root);
        figure = part("figure"); body = part("body"); head = part("head"); jaw = part("jaw"); halfVeil = part("half_veil");
        rightArm = part("right_arm"); leftArm = part("left_arm"); rightFore = part("right_arm_forearm"); leftFore = part("left_arm_forearm");
        candle = part("candle"); flame = part("candle_flame");
        layers = new Layer[] {
            layer("hem", 15, 3, 0f, MathHelper.TAU, 0f, true, 1f),
            layer("drape", 12, 3, 0f, MathHelper.TAU, 0.5f, true, 0.9f),
            layer("shawl", 12, 2, 0f, MathHelper.TAU, 0f, true, 0.6f),
            layer("veil", 8, 4, 2f, MathHelper.TAU - 2f, 0f, false, 1.2f),
            layer("right_arm_rags", 5, 2, 0f, MathHelper.TAU, 0f, true, 0.8f),
            layer("left_arm_rags", 5, 2, 0f, MathHelper.TAU, 0f, true, 0.8f),
        };
        for (int i = 0; i < 4; i++) for (int k = 0; k < 3; k++) fingers[i][k] = part("right_arm_hand_finger" + i + "_" + k);
    }

    private Layer layer(String name, int count, int segs, float a0, float a1, float offset, boolean full, float ripple) {
        ModelPart[] roots = new ModelPart[count];
        ModelPart[][] pieces = new ModelPart[count][segs - 1];
        float[] angle = new float[count];
        for (int i = 0; i < count; i++) {
            roots[i] = part(name + "_" + i);
            for (int k = 1; k < segs; k++) pieces[i][k - 1] = part(name + "_" + i + "_" + k);
            float t = full ? (i + offset) / count : (i + offset) / Math.max(1, count - 1);
            angle[i] = a0 + (a1 - a0) * t;
        }
        return new Layer(roots, pieces, angle, ripple);
    }

    /** Her shroud has no Panikhida rules of its own on the client: nothing to hook. */
    static void registerClient() { }

    @Override protected void animate(WailerEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        int cast = boss.castState(), phase = boss.syncedPhase();
        int move = cast == 1 ? boss.move() : 0;
        float glide = Math.min(1f, limbDistance * 1.6f);
        boolean tremble = phase >= 3;

        // She floats: no feet, a slow rise and fall, leaning into the glide.
        figure.pivotY -= 1.6f + MathHelper.sin(age * 0.06f) * 0.9f;
        figure.pitch += glide * 0.12f;
        float bow = 0.05f + MathHelper.sin(age * 0.05f) * 0.02f;
        head.yaw += headYaw * 0.6f;
        head.pitch += headPitch * 0.4f;
        float mouth = 0.06f + MathHelper.sin(age * 0.07f) * 0.04f;
        float rArm = MathHelper.sin(age * 0.06f) * 0.05f + glide * 0.25f, lArm = MathHelper.sin(age * 0.06f + 1f) * 0.03f;
        float rFore = 0f, lFore = 0f, rRoll = MathHelper.sin(age * 0.05f) * 0.03f, lRoll = 0f, flare = 0f, flameScale = 1f;

        if (cast == 2) {
            // «Отпевание»: both arms over the graves, the head thrown back, the strips flaring.
            rArm -= 2.6f; lArm -= 2.3f; lFore += 1.7f;
            rRoll += 0.35f; lRoll -= 0.35f;
            head.pitch -= 0.8f; bow -= 0.15f; mouth += 0.55f;
            body.roll += MathHelper.sin(age * 0.25f) * 0.06f;
            flare = 0.16f; flameScale = 1.6f;
        } else if (move == WailerEntity.MOVE_WAIL) {
            // «Плач»: arms raised, head back, the jaw dropped in a scream.
            rArm -= 2.4f; lArm -= 1.5f; lFore += 0.9f;
            rRoll += 0.55f; lRoll -= 0.5f;
            head.pitch -= 0.85f + MathHelper.sin(age * 1.5f) * 0.05f; bow -= 0.25f; mouth += 0.6f;
            flare = 0.08f;
        } else if (move == WailerEntity.MOVE_GRASP) {
            // «Долгие руки»: one arm drawn back, then reaching.
            rArm -= 1.5f; rFore += 0.3f;
            rightArm.yaw += 0.3f; body.yaw -= 0.25f; mouth += 0.2f;
        } else if (move == WailerEntity.MOVE_GLIDE) {
            rRoll += 1.1f; lRoll -= 1.0f;
            flare = 0.1f;
        } else if (move == WailerEntity.MOVE_CANDLES) {
            lArm -= 1.3f; lFore += 0.8f; flameScale = 1.5f;
        }
        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            rArm -= swing * 1.8f;
            body.yaw += swing * 0.3f;
        }
        if (tremble) {
            body.roll += MathHelper.sin(age * 2.1f) * 0.025f + MathHelper.sin(age * 3.7f) * 0.012f;
            head.roll += MathHelper.sin(age * 2.9f) * 0.05f;
            head.yaw += MathHelper.sin(age * 3.3f) * 0.03f;
            rArm += MathHelper.sin(age * 2.5f) * 0.04f;
            lArm += MathHelper.sin(age * 2.2f + 1f) * 0.03f;
        }
        body.pitch += bow;
        rightArm.pitch += rArm; leftArm.pitch += lArm; rightFore.pitch += rFore; leftFore.pitch += lFore;
        rightArm.roll += rRoll; leftArm.roll += lRoll;
        jaw.pitch += mouth;
        halfVeil.pitch += MathHelper.sin(age * 0.08f) * 0.04f - head.pitch * 0.2f;

        // The candle stays upright in her hand; its flame flickers and swells when she casts.
        candle.pitch -= lArm + lFore + bow;
        candle.roll -= lRoll;
        float flicker = flameScale * (1f + MathHelper.sin(age * 0.9f) * 0.08f + MathHelper.sin(age * 2.3f) * 0.05f);
        flame.yScale = flicker;
        flame.xScale = flame.zScale = 0.9f + (flicker - 1f) * 0.5f;

        // Long fingers flex slowly, twitch in phase 3, splay to grasp.
        for (int i = 0; i < 4; i++) for (int k = 0; k < 3; k++) {
            float flex = MathHelper.sin(age * 0.09f + i * 0.7f - k * 0.5f) * 0.1f;
            if (tremble) flex += MathHelper.sin(age * 3.1f + i * 1.3f) * 0.06f;
            if (move == WailerEntity.MOVE_GRASP) flex += 0.25f;
            fingers[i][k].pitch -= flex;
        }

        // Every strip ripples piece by piece, the lower pieces lagging, and trails behind the glide.
        float speed = tremble ? 0.2f : 0.13f, amp = tremble ? 0.075f : 0.05f;
        for (Layer layer : layers) {
            for (int i = 0; i < layer.roots.length; i++) {
                float back = MathHelper.cos(layer.angle[i]);
                layer.roots[i].pitch += MathHelper.sin(age * speed * 0.8f + i * 0.9f) * amp * 0.5f * layer.ripple + glide * 0.2f * back - flare;
                ModelPart[] pieces = layer.pieces[i];
                for (int k = 0; k < pieces.length; k++) {
                    float wave = age * speed + i * 0.9f - (k + 1) * 0.9f;
                    pieces[k].pitch += MathHelper.sin(wave) * amp * layer.ripple * (1f + glide) + glide * 0.06f * back;
                    pieces[k].roll += MathHelper.sin(wave * 0.8f + i * 1.3f) * amp * 0.6f * layer.ripple;
                }
            }
        }
    }
}
