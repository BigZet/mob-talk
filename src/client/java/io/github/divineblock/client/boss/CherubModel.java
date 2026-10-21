package io.github.divineblock.client.boss;

import io.github.divineblock.boss.CherubEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Херувим: he walks heavily on his bronze hooves with the flaming sword held forward; the blade turns slowly about itself
 * and its tongues of flame flicker, the four faces follow the foe, the raised wings stir with the tips trailing and the
 * folded ones breathe, and the wheels within wheels turn behind him. Casting lifts the sword; for the ultimate he raises it
 * over his head with the wings up, and while the sword turns every way he spins with the blade held straight out from his
 * arm, wings flung wide and the wheels whirling. Every motion is added to the rest pose of the generated model. */
public final class CherubModel extends BossModel<CherubEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "cherub"), "main");
    private static final int TONGUES = 8;
    private final ModelPart figure, body, head, rightArm, leftArm, rightLeg, leftLeg, sword, blade, tunic, scales;
    private final ModelPart upperLeft, upperRight, upperLeftTip, upperRightTip, lowerLeft, lowerRight, wheelOuter, wheelInner;
    private final ModelPart[] tongues = new ModelPart[TONGUES];

    public CherubModel(ModelPart root) {
        super(root);
        figure = part("figure"); body = part("body"); head = part("head"); rightArm = part("right_arm"); leftArm = part("left_arm");
        rightLeg = part("right_leg"); leftLeg = part("left_leg"); sword = part("sword"); blade = part("blade");
        tunic = part("tunic"); scales = part("scales");
        upperLeft = part("upper_left_wing"); upperRight = part("upper_right_wing");
        upperLeftTip = part("upper_left_wing_tip"); upperRightTip = part("upper_right_wing_tip");
        lowerLeft = part("lower_left_wing"); lowerRight = part("lower_right_wing");
        wheelOuter = part("wheel_outer"); wheelInner = part("wheel_inner");
        for (int i = 0; i < TONGUES; i++) tongues[i] = part("blade_flame_" + i);
    }

    /** «Изгнание из рая» is kept on the server: nothing to hook on the client. */
    static void registerClient() { }

    @Override protected void animate(CherubEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        int cast = boss.castState(), phase = boss.syncedPhase();
        boolean spinning = boss.isSpinning();
        float breath = MathHelper.sin(age * 0.08f);

        // The four faces follow the foe; the chest rises and falls.
        head.yaw += headYaw * 0.8f;
        head.pitch += headPitch * 0.5f;
        body.pitch += breath * 0.012f;

        // A heavy tread: short strides and a sway of the robe.
        float walk = MathHelper.cos(limbAngle * 0.5f) * limbDistance;
        rightLeg.pitch += walk * 0.55f;
        leftLeg.pitch -= walk * 0.55f;
        figure.pivotY += Math.abs(MathHelper.sin(limbAngle * 0.5f)) * limbDistance * 0.6f;
        tunic.roll += walk * 0.03f;
        scales.roll += walk * 0.02f;
        rightArm.pitch += -0.35f - walk * 0.1f;
        rightArm.roll += 0.06f;
        leftArm.pitch += -0.2f + walk * 0.25f;
        leftArm.roll -= 0.12f;

        // The sword that turns every way: the blade turns about itself, its tongues lick and flicker.
        blade.yaw += age * (spinning ? 0.3f : 0.06f);
        for (int i = 0; i < TONGUES; i++) {
            tongues[i].yScale = 0.75f + 0.35f * (0.5f + 0.5f * MathHelper.sin(age * 0.5f + i * 1.3f));
            tongues[i].roll += MathHelper.sin(age * 0.27f + i) * 0.12f;
        }

        // Wings: the raised pair stirs slowly with the tips trailing; the folded pair breathes over the body.
        float stir = MathHelper.sin(age * 0.07f) * 0.06f, lag = MathHelper.sin(age * 0.07f - 0.9f) * 0.05f;
        upperRight.roll += stir; upperLeft.roll -= stir;
        upperRightTip.roll += lag; upperLeftTip.roll -= lag;
        lowerRight.yaw -= breath * 0.04f; lowerLeft.yaw += breath * 0.04f;

        // The wheels within wheels turn, faster as the fight grows and wildly while the sword turns.
        float turn = spinning ? 0.12f : cast == 2 ? 0.06f : 0.012f + phase * 0.004f;
        wheelOuter.roll += age * turn;
        wheelInner.roll -= age * turn * 1.6f;

        if (cast == 1) {
            rightArm.pitch += -1.5f + MathHelper.sin(age * 0.5f) * 0.05f;
            upperRight.roll -= 0.15f; upperLeft.roll += 0.15f;
        }
        if (cast == 2 && !spinning) {
            // The sword lifted over his head, slanting out, the other hand raised; wings up.
            rightArm.pitch += -2.35f;
            rightArm.roll -= 0.06f;
            sword.pitch -= 0.75f;
            sword.roll -= 1.73f;
            leftArm.pitch += -2.3f;
            leftArm.roll -= 0.18f;
            upperRight.roll += 0.25f; upperLeft.roll -= 0.25f;
            lowerRight.roll += 0.4f; lowerLeft.roll -= 0.4f;
            head.pitch -= 0.35f;
        }
        if (spinning) {
            // He turns with the blade held straight out from his arm, the wings flung wide.
            body.yaw += age * 0.6f;
            rightArm.pitch -= -0.35f;
            rightArm.roll += 1.45f;
            sword.pitch += 2.24f;
            sword.roll += 0.15f;
            leftArm.pitch -= -0.2f;
            leftArm.roll -= 1.1f;
            upperLeft.roll += 0.6f; upperRight.roll -= 0.6f;
            lowerLeft.roll -= 0.8f; lowerRight.roll += 0.8f;
            lowerLeft.yaw -= 0.4f; lowerRight.yaw += 0.4f;
            head.pitch -= 0.25f;
        }
        if (handSwingProgress > 0) {
            // A sweep from the right across the front.
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            rightArm.pitch += -1.1f;
            rightArm.yaw += 0.9f - swing * 1.8f;
            body.yaw += swing * 0.4f;
        }
    }
}
