package io.github.divineblock.client.boss;

import io.github.divineblock.boss.CherubEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Херувим: walks heavily with the flaming sword held forward and his wings stirring; casting he lifts the sword, a swing
 * sweeps it across, and while the sword turns (the ultimate) he spins with his arm out and wings spread. */
public final class CherubModel extends BossModel<CherubEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "cherub"), "main");
    private final ModelPart body, head, rightArm, leftArm, rightLeg, leftLeg, upperLeft, upperRight, lowerLeft, lowerRight;

    public CherubModel(ModelPart root) {
        super(root);
        body = part("body"); head = part("head"); rightArm = part("right_arm"); leftArm = part("left_arm");
        rightLeg = part("right_leg"); leftLeg = part("left_leg");
        upperLeft = part("upper_left_wing"); upperRight = part("upper_right_wing");
        lowerLeft = part("lower_left_wing"); lowerRight = part("lower_right_wing");
    }

    /** «Изгнание из рая» is kept on the server: nothing to hook on the client. */
    static void registerClient() { }

    @Override protected void animate(CherubEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        head.yaw = headYaw;
        head.pitch = headPitch * 0.6f;
        float walk = MathHelper.cos(limbAngle * 0.6f) * limbDistance;
        rightLeg.pitch = walk * 0.8f;
        leftLeg.pitch = -walk * 0.8f;
        rightArm.pitch = -0.5f - walk * 0.15f;
        leftArm.pitch = walk * 0.4f;
        float stir = MathHelper.sin(age * 0.1f) * 0.06f;
        upperLeft.roll += stir; upperRight.roll -= stir;
        lowerLeft.roll -= stir * 0.5f; lowerRight.roll += stir * 0.5f;
        int cast = boss.castState();
        if (cast == 1) { rightArm.pitch = -2.4f + MathHelper.sin(age * 0.5f) * 0.1f; leftArm.pitch = -0.6f; }
        if (cast == 2) {
            rightArm.pitch = -2.9f; leftArm.roll = -0.7f;
            upperLeft.roll += 0.4f; upperRight.roll -= 0.4f;
            head.pitch = -0.3f;
        }
        if (boss.isSpinning()) {
            body.yaw = age * 0.6f;
            rightArm.pitch = 0; rightArm.roll = 1.4f;
            leftArm.roll = -1.2f;
            upperLeft.roll += 0.6f; upperRight.roll -= 0.6f;
            lowerLeft.roll -= 0.4f; lowerRight.roll += 0.4f;
        }
        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            rightArm.pitch = -1.3f;
            rightArm.yaw = 0.9f - swing * 1.8f;
            body.yaw += swing * 0.4f;
        }
    }
}
