package io.github.divineblock.client.boss;

import io.github.divineblock.boss.BeastEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Зверь из бездны: a heavy bear's walk with the shoulders rolling and the forearms folding; the seven necks sway each on its own
 * rhythm, a wave running up the segments like a serpent's, and the jaws snap now and then; the middle head follows the gaze. Dead
 * heads hang limp; the wounded head droops with a smouldering gash until the wound heals in the last phase, when it rises and the
 * gash becomes a scar. Casting, all living heads lean in with open jaws; for the ultimate they rear up and fan out. The heavy tail
 * swings with a lag down its length. Every motion is added to the rest pose of the generated model. */
public final class BeastModel extends BossModel<BeastEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "beast"), "main");
    /** The order the heads die in (the wounded one, 5, is apart: it droops from the start until it heals). */
    private static final int[] DEATH_ORDER = { 0, 6, 1, 4, 2, 3 };
    private static final int WOUNDED = 5, TAIL = 9;
    private final ModelPart body, wound, scar;
    private final ModelPart[] legs = new ModelPart[4], lowers = new ModelPart[4], paws = new ModelPart[4], tail = new ModelPart[TAIL];
    private final ModelPart[][] necks = new ModelPart[BeastEntity.HEADS][];
    private final ModelPart[] heads = new ModelPart[BeastEntity.HEADS], jaws = new ModelPart[BeastEntity.HEADS];

    public BeastModel(ModelPart root) {
        super(root);
        body = part("body"); wound = part("wound_" + WOUNDED); scar = part("scar_" + WOUNDED);
        String[] legNames = { "front_right_leg", "front_left_leg", "back_right_leg", "back_left_leg" };
        for (int k = 0; k < 4; k++) { legs[k] = part(legNames[k]); lowers[k] = part(legNames[k] + "_lower"); paws[k] = part(legNames[k] + "_paw"); }
        for (int k = 0; k < TAIL; k++) tail[k] = part(k == 0 ? "tail" : "tail_" + k);
        for (int i = 0; i < BeastEntity.HEADS; i++) {
            int count = Math.abs(i - 3) <= 1 ? 7 : 6;
            necks[i] = new ModelPart[count];
            for (int k = 0; k < count; k++) necks[i][k] = part(k == 0 ? "neck_" + i : "neck_" + i + "_" + k);
            heads[i] = part("head_" + i);
            jaws[i] = part("jaw_" + i);
        }
    }

    /** «Начертание» is a server rule: nothing to hook on the client. */
    static void registerClient() { }

    @Override protected void animate(BeastEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        int cast = boss.castState();
        float breath = MathHelper.sin(age * 0.06f);

        // A bear's gait: diagonal pairs, the forearm folding as the paw lifts, the body rolling over the shoulders.
        float stride = limbAngle * 0.45f;
        float[] phase = { 0f, MathHelper.PI, MathHelper.PI, 0f };
        for (int k = 0; k < 4; k++) {
            float s = MathHelper.cos(stride + phase[k]) * limbDistance;
            float lift = Math.max(0f, MathHelper.sin(stride + phase[k])) * limbDistance;
            legs[k].pitch += s * 0.5f;
            lowers[k].pitch += -lift * 0.6f;
            paws[k].pitch += lift * 0.5f - s * 0.25f;
        }
        body.roll += MathHelper.cos(stride) * limbDistance * 0.04f;
        body.pivotY += Math.abs(MathHelper.sin(stride)) * limbDistance * 0.8f + breath * 0.25f;
        body.pitch += breath * 0.01f;

        // The tail: a slow swing that travels to the tip, a little more when walking.
        for (int k = 0; k < TAIL; k++) {
            float lagged = age * 0.06f - k * 0.45f;
            tail[k].yaw += MathHelper.sin(lagged) * (0.06f + 0.012f * k) * (1f + limbDistance);
            tail[k].pitch += MathHelper.sin(lagged * 0.7f + 1f) * 0.025f;
        }

        int lost = boss.headsLost();
        boolean[] dead = new boolean[BeastEntity.HEADS];
        for (int k = 0; k < Math.min(lost, DEATH_ORDER.length); k++) dead[DEATH_ORDER[k]] = true;
        boolean woundOpen = !boss.woundHealed();
        wound.visible = woundOpen;
        scar.visible = !woundOpen;

        for (int i = 0; i < BeastEntity.HEADS; i++) {
            ModelPart[] neck = necks[i];
            ModelPart head = heads[i], jaw = jaws[i];
            float side = i - 3;
            float rate = 0.045f + i * 0.0061f, offset = i * 1.93f;
            if (dead[i] || i == WOUNDED && woundOpen) {
                // The neck falls forward and curls down, the head hangs, the jaw slack; a faint twitch is all that is left.
                float droop = i == WOUNDED ? 0.85f : 1.0f;
                neck[0].pitch += droop * 0.9f;
                neck[0].roll += side * 0.08f;
                for (int k = 1; k < neck.length; k++) neck[k].pitch += droop * 0.26f + MathHelper.sin(age * 0.03f + k) * 0.01f;
                head.pitch += 0.8f;
                jaw.pitch += 0.55f;
                continue;
            }
            // A serpent's sway: a sideways wave and a rise-and-fall, both travelling up the segments.
            for (int k = 0; k < neck.length; k++) {
                float wave = age * rate - k * 0.55f + offset;
                neck[k].roll += MathHelper.sin(wave) * (k == 0 ? 0.05f : 0.07f);
                neck[k].pitch += MathHelper.sin(wave * 0.8f + 1.3f) * (k == 0 ? 0.04f : 0.035f);
            }
            float look = MathHelper.sin(age * rate * 0.7f + offset * 1.3f);
            head.yaw += look * 0.25f;
            head.roll += -MathHelper.sin(age * rate - neck.length * 0.55f + offset) * 0.12f;
            head.pitch += MathHelper.sin(age * rate * 1.3f + offset) * 0.08f;
            // A snap of the jaws now and then, each head at its own moment, with a snarl between.
            float snap = Math.max(0, MathHelper.sin(age * 0.09f + i * 2.3f) - 0.7f) * 2.2f;
            jaw.pitch += 0.06f + snap + MathHelper.sin(age * 0.2f + i) * 0.03f;
            if (i == 3) { head.yaw += headYaw * 0.6f; head.pitch += headPitch * 0.4f; }

            if (cast == 1) {
                // All living heads lean in toward the foe and gape.
                neck[0].pitch += 0.3f;
                for (int k = 1; k < neck.length; k++) neck[k].pitch += 0.04f;
                head.pitch -= 0.15f;
                jaw.pitch += 0.6f + MathHelper.sin(age * 0.5f + i) * 0.08f;
            }
            if (cast == 2) {
                // They rear up and fan out, roaring.
                neck[0].pitch -= 0.45f;
                neck[0].roll += side * 0.1f;
                for (int k = 1; k < neck.length; k++) neck[k].pitch -= 0.05f;
                head.pitch += 0.15f;
                jaw.pitch += 0.85f;
            }
        }
        if (cast == 2) body.pitch -= 0.06f;
        if (handSwingProgress > 0) {
            // A swipe of the right forepaw.
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            legs[0].pitch -= swing * 1.1f;
            legs[0].roll += swing * 0.15f;
            paws[0].pitch -= swing * 0.5f;
        }
    }
}
