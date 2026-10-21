package io.github.divineblock.client.boss;

import io.github.divineblock.boss.ArchangelEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Архангел гнева: he hovers a little above the ground and breathes; the wings beat slowly at rest and strongly in the air, the
 * tips trailing; the cloak and the tunic sway; the head follows the foe. The sword lifts to cast and the trumpet goes to his
 * lips; for the Last Judgement he spreads his wings wide and raises the sword over his head. In the last phase the halo pulses.
 * Every motion is added to the rest pose of the generated model. */
public final class ArchangelModel extends BossModel<ArchangelEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "archangel"), "main");
    private final ModelPart figure, body, head, halo, rightArm, leftArm, trumpet, rightLeg, leftLeg, leftWing, rightWing, leftTip, rightTip, cloak, tunic;
    private final ModelPart[] cloakPanels = new ModelPart[7];

    public ArchangelModel(ModelPart root) {
        super(root);
        figure = part("figure"); body = part("body"); head = part("head"); halo = part("halo"); rightArm = part("right_arm"); leftArm = part("left_arm");
        trumpet = part("trumpet"); rightLeg = part("right_leg"); leftLeg = part("left_leg"); cloak = part("cloak"); tunic = part("tunic");
        leftWing = part("left_wing"); rightWing = part("right_wing"); leftTip = part("left_wing_tip"); rightTip = part("right_wing_tip");
        for (int i = 0; i < cloakPanels.length; i++) cloakPanels[i] = part("cloak_" + i);
    }

    /** «Опаление» is a server rule: nothing to hook on the client. */
    static void registerClient() { }

    @Override protected void animate(ArchangelEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        int phase = boss.syncedPhase(), cast = boss.castState();
        boolean airborne = !boss.isOnGround();
        float breath = MathHelper.sin(age * 0.08f);

        // He never quite touches the earth: a hover and a slow bob.
        figure.pivotY -= 3f + breath * 0.8f;
        head.yaw += headYaw * 0.8f;
        head.pitch += headPitch * 0.5f;
        body.pitch += breath * 0.015f;

        float walk = MathHelper.cos(limbAngle * 0.5f) * limbDistance;
        rightLeg.pitch += walk * 0.5f + 0.08f;
        leftLeg.pitch += -walk * 0.5f + 0.02f;
        rightArm.pitch += -0.35f - walk * 0.2f;
        leftArm.pitch += -0.15f + walk * 0.2f;
        rightArm.roll += 0.06f;
        leftArm.roll -= 0.1f;

        // Cloth: the cloak trails behind as he moves and ripples panel by panel; the tunic sways.
        cloak.pitch += 0.12f + limbDistance * 0.35f;
        for (int i = 0; i < cloakPanels.length; i++) cloakPanels[i].pitch += MathHelper.sin(age * 0.09f + i * 0.8f) * 0.05f;
        tunic.roll += walk * 0.03f;

        // Wings: a slow beat at rest, strong beats in the air, wide open for the ultimate.
        float open = phase >= 2 ? 0.25f : 0f, beat = MathHelper.sin(age * 0.07f) * 0.08f;
        if (airborne) { open = 0.45f; beat = MathHelper.sin(age * 0.45f) * 0.4f; }
        if (cast == 2) { open = 0.6f; beat = MathHelper.sin(age * 0.2f) * 0.12f; }
        rightWing.roll += open * 0.4f + beat;
        leftWing.roll -= open * 0.4f + beat;
        rightWing.yaw -= open * 0.35f;
        leftWing.yaw += open * 0.35f;
        // The tips lag behind the inner wing, which makes the beat look like a stroke, not a hinge.
        float lag = MathHelper.sin(age * (airborne ? 0.45f : 0.07f) - 0.9f) * (airborne ? 0.3f : 0.06f);
        rightTip.roll += lag + open * 0.3f;
        leftTip.roll -= lag + open * 0.3f;

        // The halo turns slowly; in the last phase it pulses larger.
        halo.roll += age * 0.01f;
        if (phase >= 3) { float pulse = 1.08f + MathHelper.sin(age * 0.3f) * 0.07f; halo.xScale = halo.yScale = pulse; }

        if (cast == 1) {
            // The sword rises and the trumpet goes to his lips.
            rightArm.pitch += -1.9f + MathHelper.sin(age * 0.5f) * 0.05f;
            leftArm.pitch += -1.2f;
            leftArm.yaw += 0.5f;
            trumpet.pitch += -1.0f;
        }
        if (cast == 2) {
            rightArm.pitch += -2.6f;
            rightArm.roll += 0.2f;
            leftArm.roll -= 0.9f;
            head.pitch -= 0.35f;
            body.pitch -= 0.1f;
        }
        if (airborne && cast == 0) {
            rightLeg.pitch += 0.35f;
            leftLeg.pitch += 0.15f;
            body.pitch += 0.1f;
        }
        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            rightArm.pitch += -1.6f + swing * 1.9f;
            rightArm.yaw -= swing * 0.7f;
            body.yaw += swing * 0.35f;
        }
    }
}
