package io.github.divineblock.client.boss;

import io.github.divineblock.boss.NimrodEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Нимрод: strides with his robe swinging and the bow at his side; casting, he raises the bow and draws the string back;
 * for the ultimate he lifts both arms to the sky and looks up. */
public final class NimrodModel extends BossModel<NimrodEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "nimrod"), "main");
    private final ModelPart robe, body, head, rightArm, leftArm, bow;

    public NimrodModel(ModelPart root) {
        super(root);
        robe = part("robe"); body = part("body"); head = part("head"); rightArm = part("right_arm"); leftArm = part("left_arm"); bow = part("bow");
    }

    /** «Смешение языков» on the client: see {@link NimrodConfusion} and its mixins. */
    static void registerClient() { }

    @Override protected void animate(NimrodEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        head.yaw = headYaw;
        head.pitch = headPitch * 0.7f;
        float walk = MathHelper.cos(limbAngle * 0.6f) * limbDistance;
        robe.roll = walk * 0.04f;
        body.yaw = walk * 0.06f;
        body.pitch = MathHelper.sin(age * 0.06f) * 0.02f;
        rightArm.pitch = -walk * 0.7f;
        leftArm.pitch = walk * 0.7f - 0.1f;
        leftArm.roll = -0.06f;
        rightArm.roll = 0.06f;
        int cast = boss.castState();
        if (cast == 1) {
            // The bow raised before him, the string drawn back to the ear.
            leftArm.pitch = -1.45f;
            leftArm.yaw = 0.1f;
            rightArm.pitch = -1.5f + MathHelper.sin(age * 0.5f) * 0.05f;
            rightArm.yaw = -0.45f;
            bow.roll = -0.15f;
            head.pitch = Math.min(head.pitch, 0.1f);
        } else if (cast == 2) {
            rightArm.pitch = leftArm.pitch = -2.7f;
            rightArm.roll = 0.35f;
            leftArm.roll = -0.35f;
            head.pitch = -0.45f;
            body.pitch = -0.08f + MathHelper.sin(age * 0.4f) * 0.03f;
        }
        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            rightArm.pitch = -0.3f - swing * 1.7f;
            body.yaw = swing * 0.35f;
        }
    }
}
