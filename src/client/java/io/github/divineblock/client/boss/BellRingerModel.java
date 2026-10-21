package io.github.divineblock.client.boss;

import io.github.divineblock.boss.BellRingerEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Звонарь погоста: the bell sways a little as it hops and its clapper swings late behind it; the ringer pulls its ropes.
 * Casting: it rears up for the slam, rocks for «Благовест», tips back to swing the clapper, shudders before throwing shards;
 * for «Набат» the whole bell swings wide while both ropes are hauled in turn (faster in later phases). */
public final class BellRingerModel extends BossModel<BellRingerEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "bell_ringer"), "main");
    private final ModelPart bell, clapper, head, rightArm, leftArm, rightRope, leftRope;

    public BellRingerModel(ModelPart root) {
        super(root);
        bell = part("bell"); clapper = part("clapper"); head = part("head");
        rightArm = part("right_arm"); leftArm = part("left_arm"); rightRope = part("right_rope"); leftRope = part("left_rope");
    }

    /** «Звон в ушах» is drawn by BossClient and the mixins: nothing more here. */
    static void registerClient() { }

    @Override protected void animate(BellRingerEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        head.yaw += headYaw * 0.5f;
        head.pitch += headPitch * 0.3f + MathHelper.sin(age * 0.06f) * 0.04f;
        float walk = MathHelper.sin(limbAngle * 0.4f) * limbDistance;
        bell.roll += walk * 0.08f + MathHelper.sin(age * 0.05f) * 0.025f;
        clapper.roll -= walk * 0.25f + MathHelper.sin(age * 0.05f - 0.8f) * 0.08f;
        rightArm.pitch += MathHelper.sin(age * 0.07f) * 0.06f;
        leftArm.pitch += MathHelper.sin(age * 0.07f + 1.5f) * 0.06f;
        if (!boss.isOnGround()) {
            // In the air: arms fling out, the clapper lags behind.
            rightArm.roll += 0.5f; leftArm.roll -= 0.5f;
            clapper.pitch += 0.4f;
        }
        int cast = boss.castState();
        float pace = boss.syncedPhase() >= 2 ? 1.5f : 1f;
        if (cast == 2) {
            float swing = MathHelper.sin(age * 0.35f * pace);
            bell.roll += swing * 0.32f;
            clapper.roll -= MathHelper.sin(age * 0.35f * pace - 0.9f) * 0.75f;
            rightArm.pitch += -1.2f + swing * 0.9f;
            leftArm.pitch += -1.2f - swing * 0.9f;
            head.pitch += 0.2f;
        } else if (cast == 1) {
            switch (boss.move()) {
                case BellRingerEntity.MOVE_SLAM -> {
                    rightArm.pitch -= 2.6f; leftArm.pitch -= 2.6f;
                    rightArm.roll += 0.2f; leftArm.roll -= 0.2f;
                    clapper.pitch += 0.3f;
                }
                case BellRingerEntity.MOVE_TOLL -> {
                    float swing = MathHelper.sin(age * 0.3f * pace);
                    bell.roll += swing * 0.2f;
                    clapper.roll -= MathHelper.sin(age * 0.3f * pace - 0.8f) * 0.5f;
                    rightArm.pitch += -0.9f + swing * 0.6f;
                    leftArm.pitch += -0.9f - swing * 0.6f;
                }
                case BellRingerEntity.MOVE_CLAPPER -> {
                    bell.pitch += 0.3f + MathHelper.sin(age * 0.5f) * 0.05f;
                    clapper.pitch -= 1.0f;
                    rightArm.pitch -= 0.8f; leftArm.pitch -= 0.8f;
                }
                case BellRingerEntity.MOVE_SHARDS -> {
                    bell.roll += MathHelper.sin(age * 2.1f) * 0.05f;
                    bell.pitch += MathHelper.cos(age * 1.7f) * 0.04f;
                    clapper.roll += MathHelper.sin(age * 1.3f) * 0.4f;
                }
                default -> { }
            }
        }
        // The ropes hang straight whatever the arms do.
        rightRope.pitch -= rightArm.pitch * 0.6f;
        leftRope.pitch -= leftArm.pitch * 0.6f;
    }
}
