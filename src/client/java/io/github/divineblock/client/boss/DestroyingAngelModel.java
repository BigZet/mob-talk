package io.github.divineblock.client.boss;

import io.github.divineblock.boss.DestroyingAngelEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Ангел-губитель: hovers a little above the ground with slow wingbeats and the sword held low before him; for an attack he
 * raises the sword and opens his wings, for the ultimate he spreads them wide, lifts both arms and bows the hood. */
public final class DestroyingAngelModel extends BossModel<DestroyingAngelEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "destroying_angel"), "main");
    private final ModelPart robe, body, head, rightArm, leftArm, rightWing, leftWing, sword;

    public DestroyingAngelModel(ModelPart root) {
        super(root);
        robe = part("robe"); body = part("body"); head = part("head"); rightArm = part("right_arm"); leftArm = part("left_arm");
        rightWing = part("right_wing"); leftWing = part("left_wing"); sword = part("sword");
    }

    /** The mark's rules are on the server. */
    static void registerClient() { }

    @Override protected void animate(DestroyingAngelEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        head.yaw = headYaw;
        head.pitch = headPitch * 0.6f;
        robe.pivotY -= 1.5f + MathHelper.sin(age * 0.08f) * 1.2f;
        robe.pitch = limbDistance * 0.15f;
        float beat = MathHelper.sin(age * 0.12f);
        rightWing.yaw = 0.45f + beat * 0.12f;
        leftWing.yaw = -0.45f - beat * 0.12f;
        rightArm.pitch = -0.2f;
        sword.pitch = -1.0f;
        leftArm.pitch = -0.1f;
        leftArm.roll = -0.08f;

        int cast = boss.castState();
        if (cast == 1) {
            rightArm.pitch = -2.5f + MathHelper.sin(age * 0.6f) * 0.06f;
            sword.pitch = 0.4f;
            rightWing.yaw = leftWing.yaw = 0f;
            rightWing.roll = -0.2f;
            leftWing.roll = 0.2f;
        }
        if (cast == 2) {
            rightArm.pitch = leftArm.pitch = -2.7f;
            rightArm.roll = 0.3f;
            leftArm.roll = -0.3f;
            sword.pitch = 0.2f;
            rightWing.yaw = leftWing.yaw = -0.05f;
            rightWing.roll = -0.5f + beat * 0.1f;
            leftWing.roll = 0.5f - beat * 0.1f;
            head.pitch = 0.5f;
            body.pitch = 0.1f;
        }
        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            rightArm.pitch = -2.2f + swing * 2.0f;
            rightArm.yaw = swing * 0.5f;
            body.yaw = swing * 0.35f;
        }
    }
}
