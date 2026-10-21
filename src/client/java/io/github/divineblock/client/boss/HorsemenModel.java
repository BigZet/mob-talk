package io.github.divineblock.client.boss;

import io.github.divineblock.boss.HorsemenEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Четыре всадника: the horse gallops (legs in pairs, the body bobbing, mane and tail streaming), rears when a seal breaks
 * and leans into a charge. The rider's gear follows the phase: bow and crown, great sword and helm, scales and hood,
 * scythe and hood. The colours come from the renderer, which picks one of four textures. */
public final class HorsemenModel extends BossModel<HorsemenEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "horsemen"), "main");
    private final ModelPart horse, neck, horseHead, tail, rider, riderHead, rightArm, leftArm, cape;
    private final ModelPart crown, helm, hood, bow, sword, scales, scythe;
    private final ModelPart frontRight, frontLeft, backRight, backLeft;

    public HorsemenModel(ModelPart root) {
        super(root);
        horse = part("horse"); neck = part("neck"); horseHead = part("horse_head"); tail = part("tail");
        rider = part("rider"); riderHead = part("rider_head"); rightArm = part("right_arm"); leftArm = part("left_arm"); cape = part("cape");
        crown = part("crown"); helm = part("helm"); hood = part("hood");
        bow = part("bow"); sword = part("sword"); scales = part("scales"); scythe = part("scythe");
        frontRight = part("leg_front_right"); frontLeft = part("leg_front_left"); backRight = part("leg_back_right"); backLeft = part("leg_back_left");
    }

    /** «Голод» has only server rules: nothing to hook on the client. */
    static void registerClient() { }

    @Override protected void animate(HorsemenEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        int phase = Math.max(1, Math.min(4, boss.syncedPhase()));
        crown.visible = bow.visible = phase == 1;
        helm.visible = sword.visible = phase == 2;
        hood.visible = phase >= 3;
        scales.visible = phase == 3;
        scythe.visible = phase == 4;

        // The gallop: diagonal pairs of legs, the body rising and falling with the stride.
        float stride = limbAngle * 0.55f, reach = Math.min(1f, limbDistance) * 0.9f;
        frontRight.pitch = MathHelper.cos(stride) * reach;
        backLeft.pitch = MathHelper.cos(stride + 0.6f) * reach;
        frontLeft.pitch = MathHelper.cos(stride + MathHelper.PI) * reach;
        backRight.pitch = MathHelper.cos(stride + MathHelper.PI + 0.6f) * reach;
        float bob = MathHelper.sin(stride * 2) * reach;
        horse.pivotY += bob * 0.8f;
        horse.pitch = bob * 0.03f;
        neck.pitch += MathHelper.sin(stride * 2 + 0.5f) * reach * 0.12f + MathHelper.sin(age * 0.05f) * 0.03f;
        horseHead.yaw = headYaw * 0.3f;
        tail.pitch += reach * 0.6f + MathHelper.sin(age * 0.12f) * 0.08f;
        tail.yaw = MathHelper.sin(age * 0.09f) * 0.15f;
        cape.pitch += reach * 0.7f + MathHelper.sin(age * 0.25f) * 0.05f * (0.3f + reach);
        rider.pitch = -bob * 0.04f;

        riderHead.yaw = headYaw * 0.7f;
        riderHead.pitch = headPitch * 0.5f;
        rightArm.pitch = -0.5f;
        leftArm.pitch = -0.6f;
        leftArm.yaw = 0.1f;
        rightArm.yaw = -0.1f;
        int cast = boss.castState();
        switch (phase) {
            case 1 -> {
                leftArm.pitch = -0.4f;
                if (cast != 0) {
                    // Drawing the bow: one arm out, the other pulling back.
                    leftArm.pitch = -1.5f; leftArm.yaw = 0.15f;
                    rightArm.pitch = -1.5f; rightArm.yaw = -0.55f + MathHelper.sin(age * 0.3f) * 0.05f;
                }
            }
            case 2 -> {
                rightArm.pitch = -0.6f + MathHelper.sin(stride) * reach * 0.15f;
                if (cast != 0) { rightArm.pitch = -2.7f; rightArm.yaw = -0.2f; }
            }
            case 3 -> {
                leftArm.pitch = -1.2f + MathHelper.sin(age * 0.08f) * 0.05f;
                if (cast != 0) leftArm.pitch = -2.1f;
                scales.pitch = -leftArm.pitch;
                scales.roll = MathHelper.sin(age * (cast != 0 ? 0.35f : 0.1f)) * (cast != 0 ? 0.3f : 0.08f);
            }
            default -> {
                rightArm.pitch = -0.35f;
                if (cast != 0) { rightArm.pitch = -2.4f; rightArm.roll = 0.5f; }
            }
        }
        if (cast == 2) { leftArm.pitch = -2.6f; leftArm.roll = -0.3f; if (phase != 2) { rightArm.pitch = -2.6f; rightArm.roll = 0.3f; } }
        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            if (phase == 1) leftArm.pitch = -1.5f + swing * 0.3f;
            else {
                rightArm.pitch = -2.6f + swing * 2.3f;
                rightArm.roll = phase == 4 ? 0.5f - swing * 1.0f : 0;
                rider.yaw = swing * (phase == 4 ? 0.6f : 0.35f);
            }
        }

        int stance = boss.stance();
        if (stance == HorsemenEntity.STANCE_REAR) {
            // The horse rises on its hind legs; the front hooves paw the air.
            float paw = MathHelper.sin(age * 0.5f) * 0.35f;
            horse.pitch = -0.6f;
            horse.pivotY -= 5f;
            neck.pitch -= 0.3f;
            frontRight.pivotY = 0.9f; frontLeft.pivotY = 0.9f;
            frontRight.pivotZ = -7.4f; frontLeft.pivotZ = -7.4f;
            frontRight.pitch = -1.2f + paw; frontLeft.pitch = -1.0f - paw;
            backRight.pitch = backLeft.pitch = 0.25f;
            rider.pitch = 0.45f;
            tail.pitch += 0.5f;
        } else if (stance == HorsemenEntity.STANCE_CHARGE) {
            horse.pitch += 0.08f;
            neck.pitch += 0.25f;
            rider.pitch = 0.3f;
            cape.pitch += 0.6f;
            tail.pitch += 0.4f;
        }
    }
}
