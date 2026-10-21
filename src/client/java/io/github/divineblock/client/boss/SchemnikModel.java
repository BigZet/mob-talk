package io.github.divineblock.client.boss;

import io.github.divineblock.boss.SchemnikEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Чёрный Схимник: glides with his robe swaying, the staff walks with him, the candle hand lifts to cast; for the ultimate
 * he spreads both arms and bows his head. */
public final class SchemnikModel extends BossModel<SchemnikEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "black_schemnik"), "main");
    private final ModelPart robe, body, head, rightArm, leftArm;

    public SchemnikModel(ModelPart root) {
        super(root);
        robe = part("robe"); body = part("body"); head = part("head"); rightArm = part("right_arm"); leftArm = part("left_arm");
    }

    @Override protected void animate(SchemnikEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        head.yaw = headYaw;
        head.pitch = headPitch * 0.6f;
        float walk = MathHelper.cos(limbAngle * 0.5f) * limbDistance;
        robe.roll = walk * 0.05f;
        robe.pitch = MathHelper.sin(age * 0.05f) * 0.02f;
        body.pitch = 0.08f + MathHelper.sin(age * 0.07f) * 0.03f;
        rightArm.pitch = -0.25f - walk * 0.3f;
        leftArm.pitch = -0.45f + walk * 0.3f;
        leftArm.roll = -0.08f;
        int cast = boss.castState();
        if (cast == 1) { leftArm.pitch = -1.5f + MathHelper.sin(age * 0.6f) * 0.08f; leftArm.yaw = 0.2f; }
        if (cast == 2) {
            rightArm.roll = 0.9f; leftArm.roll = -0.9f; rightArm.pitch = leftArm.pitch = -0.5f;
            head.pitch = 0.5f; body.pitch = 0.25f + MathHelper.sin(age * 0.3f) * 0.05f;
        }
        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            rightArm.pitch = -0.25f - swing * 1.6f;
            body.yaw = swing * 0.3f;
        }
    }
}
