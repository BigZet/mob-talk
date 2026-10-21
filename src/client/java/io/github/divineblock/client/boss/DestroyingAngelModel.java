package io.github.divineblock.client.boss;

import io.github.divineblock.boss.DestroyingAngelEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Ангел-губитель: he hovers above the ground and drifts; the vast dark wings beat slowly, the tips trailing behind the stroke;
 * the strips of his robe ripple one after another like smoke and stream back as he moves; the hood follows the foe and the
 * drawn sword is held before him. Casting, he raises the sword and half opens the wings; for the tenth plague he spreads them
 * wide, lifts both arms and bows the hood. Every motion is added to the rest pose of the generated model. */
public final class DestroyingAngelModel extends BossModel<DestroyingAngelEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "destroying_angel"), "main");
    private static final int ROBE = 16, ROBE_IN = 12, TRAIL = 5;
    private final ModelPart figure, body, head, rightArm, leftArm, rightForearm, leftForearm, sword, rightWing, leftWing, rightTip, leftTip;
    private final ModelPart rightSleeve, leftSleeve, sash0, sash1;
    private final ModelPart[] robe = new ModelPart[ROBE], robeIn = new ModelPart[ROBE_IN], trail = new ModelPart[TRAIL];

    public DestroyingAngelModel(ModelPart root) {
        super(root);
        figure = part("figure"); body = part("body"); head = part("head"); sword = part("sword");
        rightArm = part("right_arm"); leftArm = part("left_arm"); rightForearm = part("right_forearm"); leftForearm = part("left_forearm");
        rightSleeve = part("right_sleeve"); leftSleeve = part("left_sleeve");
        rightWing = part("right_wing"); leftWing = part("left_wing"); rightTip = part("right_wing_tip"); leftTip = part("left_wing_tip");
        sash0 = part("sash_end_0"); sash1 = part("sash_end_1");
        for (int i = 0; i < ROBE; i++) robe[i] = part("robe_" + i);
        for (int i = 0; i < ROBE_IN; i++) robeIn[i] = part("robe_in_" + i);
        for (int i = 0; i < TRAIL; i++) trail[i] = part("trail_" + i);
    }

    /** The mark's rules are on the server. */
    static void registerClient() { }

    @Override protected void animate(DestroyingAngelEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        int cast = boss.castState();
        float bob = MathHelper.sin(age * 0.06f);

        // He never touches the ground: a hover, a slow rise and fall, a lean into his flight.
        figure.pivotY -= 4f + bob * 1.1f;
        figure.pitch += limbDistance * 0.18f;
        figure.roll += MathHelper.sin(age * 0.035f) * 0.02f;
        head.yaw += headYaw * 0.85f;
        head.pitch += headPitch * 0.5f;
        body.pitch += MathHelper.sin(age * 0.06f - 0.6f) * 0.015f;

        // The robe is smoke: each strip ripples on its own beat and streams back with speed.
        float stream = limbDistance * 0.5f;
        for (int i = 0; i < ROBE; i++) {
            double a = Math.PI * 2 * i / ROBE;
            float back = (float) -Math.cos(a);
            robe[i].pitch += MathHelper.sin(age * 0.09f + i * 0.9f) * 0.06f - stream * (0.4f + 0.6f * back);
            robe[i].roll += MathHelper.sin(age * 0.07f + i * 1.3f) * 0.04f;
        }
        for (int i = 0; i < ROBE_IN; i++) robeIn[i].pitch += MathHelper.sin(age * 0.08f + i * 1.1f) * 0.04f - stream * 0.3f;
        for (int i = 0; i < TRAIL; i++) {
            trail[i].pitch -= 0.12f + MathHelper.sin(age * 0.07f + i * 1.4f) * 0.1f + stream * 0.8f;
            trail[i].roll += MathHelper.sin(age * 0.05f + i * 2.1f) * 0.08f;
        }
        sash0.pitch += MathHelper.sin(age * 0.1f) * 0.06f - stream * 0.6f;
        sash1.pitch += MathHelper.sin(age * 0.1f - 0.7f) * 0.07f - stream * 0.6f;
        rightSleeve.pitch += MathHelper.sin(age * 0.08f) * 0.05f;
        leftSleeve.pitch += MathHelper.sin(age * 0.08f + 1f) * 0.05f;

        // The drawn sword held low before him; the left hand hangs open.
        rightArm.pitch += -0.55f + bob * 0.02f;
        rightArm.roll += 0.12f;
        rightForearm.pitch += -0.55f;
        leftArm.pitch += -0.15f;
        leftArm.roll -= 0.12f;
        leftForearm.pitch += -0.3f;

        // The wings: a slow, heavy beat; the tips follow behind the stroke.
        float open = 0f, beat = MathHelper.sin(age * 0.055f) * 0.12f;
        float lag = MathHelper.sin(age * 0.055f - 1.0f) * 0.1f;
        if (limbDistance > 0.1f) { beat = MathHelper.sin(age * 0.16f) * 0.22f; lag = MathHelper.sin(age * 0.16f - 1.0f) * 0.2f; }
        if (cast == 1) open = 0.25f;
        if (cast == 2) { open = 0.5f; beat = MathHelper.sin(age * 0.12f) * 0.1f; }
        rightWing.roll += open * 0.5f + beat;
        leftWing.roll -= open * 0.5f + beat;
        rightWing.yaw -= open * 0.3f;
        leftWing.yaw += open * 0.3f;
        rightTip.roll += lag + open * 0.5f;
        leftTip.roll -= lag + open * 0.5f;

        if (cast == 1) {
            // The sword rises over his head.
            rightArm.pitch += -2.1f + MathHelper.sin(age * 0.6f) * 0.05f;
            rightForearm.pitch += 0.45f;
            sword.pitch -= 0.5f;
            leftArm.pitch += -0.6f;
            leftArm.roll -= 0.3f;
        }
        if (cast == 2) {
            // The tenth plague: both arms lifted over the land, the hood bowed.
            rightArm.pitch += -2.5f;
            rightArm.roll += 0.25f;
            rightForearm.pitch += 0.5f;
            leftArm.pitch += -2.5f;
            leftArm.roll -= 0.25f;
            leftForearm.pitch += 0.3f;
            head.pitch += 0.45f;
            body.pitch += 0.08f;
        }
        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            rightArm.pitch += -1.8f + swing * 2.0f;
            rightArm.yaw += swing * 0.5f;
            body.yaw += swing * 0.3f;
        }
    }
}
