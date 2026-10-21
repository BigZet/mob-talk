package io.github.divineblock.client.boss;

import io.github.divineblock.boss.GoliathEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Голиаф: a heavy stride with the spear held upright; for an attack he levels the spear forward, for the ultimate he lifts it
 * over his head to throw; struck in the forehead, he drops to one knee with his head hanging. */
public final class GoliathModel extends BossModel<GoliathEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "goliath"), "main");
    private final ModelPart hips, body, head, rightArm, leftArm, rightLeg, leftLeg, spear;

    public GoliathModel(ModelPart root) {
        super(root);
        hips = part("hips"); body = part("body"); head = part("head"); rightArm = part("right_arm"); leftArm = part("left_arm");
        rightLeg = part("right_leg"); leftLeg = part("left_leg"); spear = part("spear");
    }

    /** Stun has no client side of its own: its rule is on the server. */
    static void registerClient() { }

    @Override protected void animate(GoliathEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        head.yaw = headYaw;
        head.pitch = headPitch * 0.5f;
        float walk = MathHelper.cos(limbAngle * 0.5f) * limbDistance;
        rightLeg.pitch = walk * 0.7f;
        leftLeg.pitch = -walk * 0.7f;
        body.roll = walk * 0.04f;
        body.pitch = MathHelper.sin(age * 0.05f) * 0.02f;
        rightArm.pitch = -0.3f - walk * 0.2f;
        spear.pitch = -rightArm.pitch;
        leftArm.pitch = -0.6f + walk * 0.2f;
        leftArm.yaw = -0.3f;

        if (boss.kneeling()) {
            hips.pivotY += 6f;
            rightLeg.pitch = -1.3f;
            leftLeg.pitch = 1.1f;
            body.pitch = 0.35f;
            head.pitch = 0.6f;
            rightArm.pitch = -0.1f;
            spear.pitch = 1.2f;
            leftArm.pitch = 0.2f;
            return;
        }
        int cast = boss.castState();
        if (cast == 1) {
            // The spear levelled forward, drawn back for the thrust.
            rightArm.pitch = -1.1f + MathHelper.sin(age * 0.5f) * 0.05f;
            spear.pitch = MathHelper.HALF_PI - rightArm.pitch;
            body.yaw = 0.25f;
            leftArm.pitch = -1.2f;
        }
        if (cast == 2) {
            // Raised over his head for the throw, the shield before him.
            rightArm.pitch = -2.8f;
            spear.pitch = MathHelper.HALF_PI - rightArm.pitch;
            body.pitch = -0.12f;
            body.yaw = 0.35f;
            head.pitch = -0.2f;
            leftArm.pitch = -1.3f;
        }
        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            rightArm.pitch = -1.1f - swing * 0.3f;
            spear.pitch = MathHelper.HALF_PI - rightArm.pitch;
            body.yaw = -swing * 0.4f;
        }
    }
}
