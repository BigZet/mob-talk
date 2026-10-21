package io.github.divineblock.client.boss;

import io.github.divineblock.boss.GoliathEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Голиаф: a slow, heavy stride (the hips sink at every footfall, the knees bend, the torso rolls), the great spear upright
 * in his fist and the round shield before his left side; he breathes, the crest sways and the head follows the foe. He levels
 * the spear and draws it back to thrust, then lunges; pulls the shield in and bashes with it; throws his head back and spreads
 * his arms to roar; lifts the spear over his shoulder to throw; raises a foot to stamp. For the ultimate he rears up with the
 * spear over his head. A stone in the forehead drops him on one knee, leaning on the spear with his head hanging.
 * Every motion is added to the rest pose of the generated model. */
public final class GoliathModel extends BossModel<GoliathEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "goliath"), "main");
    private final ModelPart hips, body, head, crest, rightArm, leftArm, rightForearm, leftForearm, spear, shield, rightLeg, leftLeg,
            rightShin, leftShin, kilt, pteruges, beard;

    public GoliathModel(ModelPart root) {
        super(root);
        hips = part("hips"); body = part("body"); head = part("head"); crest = part("crest"); beard = part("beard");
        rightArm = part("right_arm"); leftArm = part("left_arm"); rightForearm = part("right_arm_forearm"); leftForearm = part("left_arm_forearm");
        spear = part("spear"); shield = part("shield");
        rightLeg = part("right_leg"); leftLeg = part("left_leg"); rightShin = part("right_leg_shin"); leftShin = part("left_leg_shin");
        kilt = part("kilt"); pteruges = part("pteruges");
    }

    /** Stun has no client side of its own: its rule is on the server. */
    static void registerClient() { }

    @Override protected void animate(GoliathEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        float breath = MathHelper.sin(age * 0.06f);
        float step = limbAngle * 0.42f;
        float walk = MathHelper.cos(step) * limbDistance, lift = MathHelper.sin(step);

        if (boss.kneeling()) { kneel(age, breath); return; }

        head.yaw += headYaw * 0.75f;
        head.pitch += headPitch * 0.5f;
        body.yaw += headYaw * 0.1f;
        body.pitch += breath * 0.018f;
        beard.pitch += breath * 0.03f;
        crest.pitch += MathHelper.sin(age * 0.09f) * 0.03f - walk * 0.06f;

        // The heavy stride: legs swing slowly, the lifted leg bends at the knee, the hips sink at each footfall.
        rightLeg.pitch += walk * 0.55f;
        leftLeg.pitch -= walk * 0.55f;
        rightShin.pitch += Math.max(0f, lift) * 0.8f * limbDistance;
        leftShin.pitch += Math.max(0f, -lift) * 0.8f * limbDistance;
        hips.pivotY += (1f - Math.abs(lift)) * 1.4f * limbDistance;
        body.roll += walk * 0.05f;
        body.yaw -= walk * 0.07f;
        kilt.roll += walk * 0.03f;
        pteruges.roll += walk * 0.04f;
        pteruges.yaw -= walk * 0.03f;
        rightArm.pitch -= walk * 0.12f;
        leftArm.pitch += walk * 0.1f;
        rightArm.roll += breath * 0.015f;
        leftArm.roll -= breath * 0.015f;

        int cast = boss.castState(), move = boss.move();
        if (cast == 2) {
            // «Вызов воинству»: he rears up, the spear over his head ready to fly, the shield thrust forward.
            float tremble = MathHelper.sin(age * 1.7f) * 0.02f;
            raiseSpear(-2.7f + tremble, 0.25f);
            body.pitch -= 0.14f;
            body.yaw += 0.35f;
            head.pitch -= 0.15f;
            leftArm.pitch -= 0.35f;
            leftLeg.pitch -= 0.25f;
            rightLeg.pitch += 0.2f;
        } else if (cast == 1) {
            switch (move) {
                case GoliathEntity.MOVE_THRUST -> {
                    // The spear levelled and drawn back, the right shoulder turned away.
                    rightArm.pitch += -0.2f + MathHelper.sin(age * 0.6f) * 0.03f;
                    rightForearm.pitch += 0.4f;
                    spear.pitch += MathHelper.HALF_PI - 0.2f;
                    body.yaw += 0.4f;
                    body.pitch -= 0.05f;
                    leftArm.pitch -= 0.2f;
                }
                case GoliathEntity.MOVE_BASH -> {
                    // The shield pulled in to the chest, the left shoulder turned back, a crouch.
                    leftArm.pitch += 0.25f;
                    leftArm.roll += 0.2f;
                    body.yaw -= 0.35f;
                    hips.pivotY += 1.5f;
                    rightLeg.pitch += 0.2f;
                    leftLeg.pitch -= 0.25f;
                }
                case GoliathEntity.MOVE_ROAR -> {
                    float shake = MathHelper.sin(age * 2.1f) * 0.025f;
                    head.pitch -= 0.4f + shake;
                    body.pitch -= 0.12f;
                    rightArm.roll += 0.55f;
                    leftArm.roll -= 0.5f;
                    rightArm.pitch -= 0.2f;
                    leftArm.pitch -= 0.1f;
                    beard.pitch -= 0.25f;
                }
                case GoliathEntity.MOVE_JAVELIN -> {
                    raiseSpear(-2.4f, 0.3f);
                    body.yaw += 0.45f;
                    body.pitch -= 0.08f;
                    leftArm.pitch -= 0.4f;
                }
                case GoliathEntity.MOVE_SLAM -> {
                    // A foot lifted high to stamp, the arms spread for balance.
                    rightLeg.pitch -= 1.0f;
                    rightShin.pitch += 1.1f;
                    hips.pivotY -= 0.6f;
                    body.pitch -= 0.08f;
                    rightArm.roll += 0.35f;
                    leftArm.roll -= 0.3f;
                }
                default -> { }
            }
        }

        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            if (boss.preferredHand == Hand.OFF_HAND) {
                // The bash: the shield driven forward with the whole body behind it.
                float armAdd = -0.75f * swing, foreAdd = 0.55f * swing;
                leftArm.pitch += armAdd;
                leftForearm.pitch += foreAdd;
                shield.pitch -= armAdd + foreAdd;
                body.yaw += 0.4f * swing;
                body.pitch += 0.15f * swing;
                leftLeg.pitch -= 0.3f * swing;
            } else {
                // The thrust: arm and spear drive forward level, the body lunges after them.
                float armAdd = -0.5f - 0.6f * swing, foreAdd = 0.5f + 0.4f * swing;
                rightArm.pitch += armAdd;
                rightForearm.pitch += foreAdd;
                spear.pitch += MathHelper.HALF_PI - (armAdd + foreAdd) - 0.2f * (1 - swing);
                body.yaw -= 0.3f * swing;
                body.pitch += 0.15f * swing;
                leftLeg.pitch -= 0.3f * swing;
                rightLeg.pitch += 0.25f * swing;
            }
        }
    }

    /** The spear raised behind his head with the point forward, for a throw. */
    private void raiseSpear(float armAdd, float foreAdd) {
        rightArm.pitch += armAdd;
        rightArm.roll += 0.15f;
        rightForearm.pitch += foreAdd;
        spear.pitch += MathHelper.HALF_PI + 0.25f - armAdd - foreAdd;
    }

    /** On one knee after the stone: the right knee on the ground, the left foot planted, leaning on the spear, head hanging. */
    private void kneel(float age, float breath) {
        hips.pivotY += 12f;
        rightLeg.pitch += 0.08f;
        rightShin.pitch += 1.5f;
        leftLeg.pitch -= 1.4f;
        leftShin.pitch += 1.4f;
        body.pitch += 0.38f + breath * 0.03f;
        head.pitch += 0.55f;
        head.roll += MathHelper.sin(age * 0.2f) * 0.05f;
        crest.pitch += 0.2f;
        rightArm.pitch += 0.15f;
        spear.pitch += 0.45f;
        leftArm.pitch += 0.55f;
        leftArm.roll += 0.1f;
    }
}
