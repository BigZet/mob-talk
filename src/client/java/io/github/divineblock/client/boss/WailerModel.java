package io.github.divineblock.client.boss;

import io.github.divineblock.boss.WailerEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Плакальщица погоста: she floats, rising and sinking slowly, her hem trailing as she glides; her head stays bowed and the
 * candle arm held before her. Casting: she throws her head back to wail, draws a long arm back to grasp, spreads her arms to
 * slip away, lifts the candle high; for «Отпевание» both arms rise over the graves and she sways. In phase 3 she trembles. */
public final class WailerModel extends BossModel<WailerEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "wailer"), "main");
    private final ModelPart skirt, body, head, rightArm, leftArm, candle;

    public WailerModel(ModelPart root) {
        super(root);
        skirt = part("skirt"); body = part("body"); head = part("head");
        rightArm = part("right_arm"); leftArm = part("left_arm"); candle = part("candle");
    }

    /** The Panikhida is all server side: nothing to hook on the client. */
    static void registerClient() { }

    @Override protected void animate(WailerEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        skirt.pivotY += MathHelper.sin(age * 0.08f) * 0.8f - 0.8f;
        skirt.pitch += Math.min(0.35f, limbDistance * 0.4f);
        skirt.roll += MathHelper.sin(age * 0.06f) * 0.03f;
        body.pitch += 0.06f - Math.min(0.2f, limbDistance * 0.2f) + MathHelper.sin(age * 0.05f) * 0.02f;
        head.yaw += headYaw;
        head.pitch += 0.3f + headPitch * 0.4f;
        rightArm.pitch += MathHelper.sin(age * 0.06f) * 0.06f + limbDistance * 0.2f;
        rightArm.roll += MathHelper.sin(age * 0.05f) * 0.03f;
        leftArm.pitch += MathHelper.sin(age * 0.06f + 1f) * 0.03f;
        if (boss.syncedPhase() >= 3) {
            body.roll += MathHelper.sin(age * 1.7f) * 0.025f;
            head.roll += MathHelper.sin(age * 2.3f) * 0.05f;
        }
        int cast = boss.castState();
        if (cast == 2) {
            rightArm.pitch -= 2.7f; leftArm.pitch -= 2.4f;
            rightArm.roll += 0.35f; leftArm.roll -= 0.35f;
            candle.pitch += 2.0f;
            head.pitch -= 0.8f;
            body.pitch -= 0.15f;
            skirt.roll += MathHelper.sin(age * 0.25f) * 0.08f;
        } else if (cast == 1) {
            switch (boss.move()) {
                case WailerEntity.MOVE_WAIL -> {
                    head.pitch -= 0.9f + MathHelper.sin(age * 1.5f) * 0.05f;
                    body.pitch -= 0.25f;
                    rightArm.roll += 0.8f; leftArm.roll -= 0.5f;
                }
                case WailerEntity.MOVE_GRASP -> {
                    rightArm.pitch -= 1.6f;
                    rightArm.yaw += 0.3f;
                    body.yaw -= 0.25f;
                }
                case WailerEntity.MOVE_GLIDE -> {
                    rightArm.roll += 1.1f; leftArm.roll -= 1.1f;
                    skirt.pitch += 0.3f;
                }
                case WailerEntity.MOVE_CANDLES -> {
                    leftArm.pitch -= 1.8f;
                    candle.pitch += 1.8f;
                }
                default -> { }
            }
        }
        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            rightArm.pitch -= swing * 1.8f;
            body.yaw += swing * 0.3f;
        }
    }
}
