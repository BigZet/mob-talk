package io.github.divineblock.client.boss;

import io.github.divineblock.boss.SeraphEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Шестикрылый серафим: it hovers and bobs; the flight pair beats with the tips trailing and the primaries rippling, the
 * covering pairs breathe over the face and the feet, flames flicker between the wings and the rays of the nimbus turn.
 * Casting sweeps the flight wings back; on «Нисхождение» and for the ultimate the wings over the face lift and part (and
 * the feet's wings open a little), baring the face of light, and the nimbus flares. Every motion is added to the rest pose. */
public final class SeraphModel extends BossModel<SeraphEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "seraph"), "main");
    private static final int FLAMES = 12, CORONA = 12, PRIMARIES = 12;
    private final ModelPart seraph, core, face, nimbusDisc, nimbusRays, left, right, leftTip, rightTip, upperLeft, upperRight, lowerLeft, lowerRight;
    private final ModelPart[] flames = new ModelPart[FLAMES], corona = new ModelPart[CORONA];
    private final ModelPart[][] primaries = new ModelPart[4][PRIMARIES];
    /** How far the face wings are open, eased from frame to frame (one seraph at a time is enough for this). */
    private float open;
    private float lastAge = -1;

    public SeraphModel(ModelPart root) {
        super(root);
        seraph = part("seraph"); core = part("core"); face = part("face"); nimbusDisc = part("nimbus_disc"); nimbusRays = part("nimbus_rays");
        left = part("left_wing"); right = part("right_wing"); leftTip = part("left_wing_tip"); rightTip = part("right_wing_tip");
        upperLeft = part("upper_left_wing"); upperRight = part("upper_right_wing");
        lowerLeft = part("lower_left_wing"); lowerRight = part("lower_right_wing");
        for (int i = 0; i < FLAMES; i++) flames[i] = part("flame_" + i);
        for (int i = 0; i < CORONA; i++) corona[i] = part("corona_" + i);
        String[] wings = { "left_wing", "right_wing", "left_wing_tip", "right_wing_tip" };
        for (int w = 0; w < 4; w++) for (int i = 0; i < PRIMARIES; i++) primaries[w][i] = part(wings[w] + "_f0_" + i);
    }

    /** «Опаление» is server-side (healing burns): nothing to hook on the client. */
    static void registerClient() { }

    @Override protected void animate(SeraphEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        boolean down = boss.isDescending();
        int cast = boss.castState(), phase = boss.syncedPhase();
        float step = lastAge < 0 || age < lastAge ? 1f : Math.min(1f, (age - lastAge) * 0.08f);
        lastAge = age;
        open += ((down || cast == 2 ? 1f : 0f) - open) * step;

        // A hover and a slow bob; the face leans toward the foe.
        float breath = MathHelper.sin(age * 0.07f);
        seraph.pivotY += MathHelper.sin(age * 0.08f) * 1.2f;
        core.pitch += headPitch * 0.25f;
        face.yaw += headYaw * 0.3f;

        // The flight pair: strong beats aloft, slow ones on the ground; the tips lag behind the stroke.
        float speed = down ? 0.1f : 0.26f;
        float beat = MathHelper.sin(age * speed) * (down ? 0.12f : 0.32f);
        float lag = MathHelper.sin(age * speed - 1.0f) * (down ? 0.08f : 0.22f);
        right.roll += beat; left.roll -= beat;
        rightTip.roll += lag; leftTip.roll -= lag;
        for (int w = 0; w < 4; w++) {
            float sign = w % 2 == 0 ? -1f : 1f;
            for (int i = 0; i < PRIMARIES; i++) primaries[w][i].roll += sign * MathHelper.sin(age * 0.3f + i * 0.55f + w) * 0.04f;
        }

        // The covering pairs breathe: a slow swell over the face and the feet.
        upperLeft.roll += breath * 0.05f; upperRight.roll -= breath * 0.05f;
        lowerLeft.roll -= breath * 0.04f; lowerRight.roll += breath * 0.04f;
        upperLeft.yaw += breath * 0.03f; upperRight.yaw -= breath * 0.03f;

        // Opening: the wings over the face lift and swing aside, those over the feet part a little, the flight pair rises.
        upperLeft.roll += open * 1.3f; upperRight.roll -= open * 1.3f;
        upperLeft.yaw += open * 0.5f; upperRight.yaw -= open * 0.5f;
        lowerLeft.roll += open * 0.5f; lowerRight.roll -= open * 0.5f;
        left.roll += open * 0.3f; right.roll -= open * 0.3f;

        if (cast == 1) {
            // Gathering fire: the flight wings sweep back and flare.
            right.yaw += 0.45f; left.yaw -= 0.45f;
            rightTip.roll -= 0.2f; leftTip.roll += 0.2f;
        }
        if (cast == 2) {
            right.roll += MathHelper.sin(age * 0.5f) * 0.1f; left.roll -= MathHelper.sin(age * 0.5f) * 0.1f;
            core.pitch -= 0.15f;
        }

        // Fire: the tongues between the wings flicker and lick; the corona round the face breathes.
        for (int i = 0; i < FLAMES; i++) {
            flames[i].yScale = 0.8f + 0.3f * (0.5f + 0.5f * MathHelper.sin(age * 0.45f + i * 1.7f));
            flames[i].roll += MathHelper.sin(age * 0.23f + i * 2.1f) * 0.1f;
        }
        for (int i = 0; i < CORONA; i++) corona[i].yScale = 0.85f + 0.3f * (0.5f + 0.5f * MathHelper.sin(age * 0.6f + i * 0.9f));

        // The nimbus: its rays turn; it flares when the face is bared and pulses in the last phase.
        nimbusRays.roll += age * 0.015f;
        float flare = 1f + open * 0.15f + (phase >= 3 ? 0.06f + MathHelper.sin(age * 0.3f) * 0.05f : 0f);
        nimbusDisc.xScale = nimbusDisc.yScale = flare;
        nimbusRays.xScale = nimbusRays.yScale = flare;
    }
}
