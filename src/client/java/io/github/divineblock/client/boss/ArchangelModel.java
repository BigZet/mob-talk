package io.github.divineblock.client.boss;

import io.github.divineblock.boss.ArchangelEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Архангел гнева: walks with the sword low; his wings stay folded on the ground in phase 1, half open from phase 2 and beat
 * when he leaps or hangs in the air; the sword lifts to cast, the trumpet goes to his lips for its blast (any cast with the
 * left arm up), and for the Last Judgement he spreads his wings wide and raises the sword over his head. In the last phase
 * the halo pulses larger. */
public final class ArchangelModel extends BossModel<ArchangelEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "archangel"), "main");
    private final ModelPart body, head, halo, rightArm, leftArm, trumpet, rightLeg, leftLeg, leftWing, rightWing, leftTip, rightTip;

    public ArchangelModel(ModelPart root) {
        super(root);
        body = part("body"); head = part("head"); halo = part("halo"); rightArm = part("right_arm"); leftArm = part("left_arm");
        trumpet = part("trumpet"); rightLeg = part("right_leg"); leftLeg = part("left_leg");
        leftWing = part("left_wing"); rightWing = part("right_wing"); leftTip = part("left_wing_tip"); rightTip = part("right_wing_tip");
    }

    /** «Опаление» is a server rule: nothing to hook on the client. */
    static void registerClient() { }

    @Override protected void animate(ArchangelEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        int phase = boss.syncedPhase(), cast = boss.castState();
        boolean airborne = !boss.isOnGround();
        head.yaw = headYaw;
        head.pitch = headPitch * 0.6f;
        float walk = MathHelper.cos(limbAngle * 0.6662f) * limbDistance;
        rightLeg.pitch = walk * 0.9f;
        leftLeg.pitch = -walk * 0.9f;
        body.pitch = MathHelper.sin(age * 0.06f) * 0.02f;
        rightArm.pitch = -0.25f - walk * 0.4f;
        leftArm.pitch = walk * 0.4f;
        leftArm.roll = -0.08f;
        rightArm.roll = 0.08f;

        // Wings: folded, half open (phase 2+), beating in the air, wide for the ultimate.
        float open = phase >= 2 ? 0.55f : 0f;
        float beat = 0;
        if (airborne) { open = 0.9f; beat = MathHelper.sin(age * 0.7f) * 0.45f; }
        else if (phase >= 2) beat = MathHelper.sin(age * 0.12f) * 0.06f;
        if (cast == 2) { open = 1.05f; beat = MathHelper.sin(age * 0.25f) * 0.12f; }
        float fold = 1.1f * (1 - open);
        leftWing.yaw = -fold;
        rightWing.yaw = fold;
        leftWing.roll = -beat - open * 0.2f;
        rightWing.roll = beat + open * 0.2f;
        leftTip.yaw = -0.5f * (1 - open);
        rightTip.yaw = 0.5f * (1 - open);
        leftTip.roll = -beat * 0.6f;
        rightTip.roll = beat * 0.6f;

        // The halo turns slowly; in the last phase it pulses larger.
        halo.roll = age * 0.02f;
        float glow = phase >= 3 ? 1.2f + MathHelper.sin(age * 0.3f) * 0.1f : 1f;
        halo.xScale = halo.yScale = glow;

        if (cast == 1) {
            // Either the sword rises or the trumpet: the left hand lifts the horn, the right holds the blade high.
            rightArm.pitch = -2.2f + MathHelper.sin(age * 0.5f) * 0.05f;
            leftArm.pitch = -1.4f;
            leftArm.yaw = 0.5f;
            trumpet.pitch = -1.2f;
            trumpet.roll = 0.6f;
        }
        if (cast == 2) {
            rightArm.pitch = -2.9f;
            rightArm.roll = 0.15f;
            leftArm.roll = -1.0f;
            head.pitch = -0.3f;
        }
        if (airborne && cast == 0) {
            rightLeg.pitch = 0.3f;
            leftLeg.pitch = 0.15f;
            body.pitch = 0.12f;
        }
        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            rightArm.pitch = -2.0f + swing * 1.8f;
            rightArm.yaw = -swing * 0.8f;
            body.yaw = swing * 0.4f;
        }
    }
}
