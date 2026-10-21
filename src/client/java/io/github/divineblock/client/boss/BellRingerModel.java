package io.github.divineblock.client.boss;

import io.github.divineblock.boss.BellRingerEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Звонарь погоста: the bell hangs from its beam and rocks there; its clapper (the foot it hops on) swings a beat late behind
 * every motion, and the ringer on the crown keeps its balance, breathes and tugs the ropes. Hops trail the clapper and fling
 * the ringer's arms; the slam rears the bell back and hauls the ropes high; «Благовест» rings it side to side; the clapper
 * blow tips the mouth back to throw the tongue forward; the shards make it shudder; for «Набат» the bell swings wide and the
 * ringer hauls rope after rope, faster in later phases. Every motion is added to the rest pose of the generated model. */
public final class BellRingerModel extends BossModel<BellRingerEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "bell_ringer"), "main");
    private final ModelPart figure, bell, clapper, ringer, torso, head, rightArm, leftArm, rightForearm, leftForearm, rightRope, leftRope,
            rightLeg, leftLeg, robe;

    public BellRingerModel(ModelPart root) {
        super(root);
        figure = part("figure"); bell = part("bell"); clapper = part("clapper"); ringer = part("ringer"); torso = part("torso"); head = part("head");
        rightArm = part("right_arm"); leftArm = part("left_arm"); rightForearm = part("right_forearm"); leftForearm = part("left_forearm");
        rightRope = part("right_rope"); leftRope = part("left_rope"); rightLeg = part("right_leg"); leftLeg = part("left_leg"); robe = part("robe");
    }

    /** «Звон в ушах» is drawn by BossClient and the mixins: nothing more here. */
    static void registerClient() { }

    @Override protected void animate(BellRingerEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        int cast = boss.castState(), move = boss.move();
        float pace = boss.syncedPhase() >= 2 ? 1.5f : 1f;
        boolean airborne = !boss.isOnGround();
        float breath = MathHelper.sin(age * 0.06f);

        // The swing of the bell, side to side, and its phase, so the clapper can lag behind it.
        float speed = 0.05f, amplitude = 0.03f, lift = 0f;
        if (cast == 2) { speed = 0.32f * pace; amplitude = 0.34f; lift = 4f; }
        else if (cast == 1 && move == BellRingerEntity.MOVE_TOLL) { speed = 0.28f * pace; amplitude = 0.22f; lift = 2f; }
        float phase = age * speed, swing = MathHelper.sin(phase);
        float walk = MathHelper.sin(limbAngle * 0.4f) * limbDistance * 0.1f;
        float roll = swing * amplitude + walk;
        bell.roll += roll;
        // A rung bell rises as it swings, so its lip does not dig into the ground.
        figure.pivotY -= Math.abs(swing) * lift;
        // The clapper hangs: it cancels the bell's tilt and then trails a beat behind, striking the side late.
        clapper.roll += -roll + MathHelper.sin(phase - 1.1f) * amplitude * 1.4f + walk * 0.6f;

        // The ringer balances against the swing, breathes and watches its foe.
        ringer.roll -= roll * 0.7f;
        torso.pitch += breath * 0.03f;
        head.yaw += headYaw * 0.6f;
        head.pitch += headPitch * 0.4f + breath * 0.03f;
        float tug = MathHelper.sin(age * 0.07f) * 0.03f;
        rightArm.roll += tug;
        leftArm.roll += tug;
        robe.pitch += breath * 0.02f;

        if (airborne) {
            // A hop: the bell tips with its flight, the clapper trails and the ringer clings with its arms flung wide.
            bell.pitch += 0.12f;
            clapper.pitch += 0.45f;
            clapper.roll += MathHelper.sin(age * 0.4f) * 0.15f;
            rightArm.roll -= 0.35f; leftArm.roll += 0.35f;
            torso.pitch += 0.2f;
            rightLeg.pitch -= 0.3f; leftLeg.pitch -= 0.3f;
        }

        if (cast == 2) {
            // «Набат»: rope after rope, hauled hard.
            float haul = MathHelper.sin(age * 0.32f * pace);
            rightArm.pitch -= 0.5f + haul * 0.5f;
            leftArm.pitch -= 0.5f - haul * 0.5f;
            rightForearm.roll -= 0.3f + haul * 0.2f;
            leftForearm.roll += 0.3f - haul * 0.2f;
            torso.pitch -= 0.25f + Math.abs(haul) * 0.15f;
            head.pitch -= 0.3f;
        } else if (cast == 1) {
            switch (move) {
                case BellRingerEntity.MOVE_SLAM -> {
                    // Rearing back to leap: the mouth tips up, the ropes are hauled high over the ringer's head.
                    bell.pitch += 0.22f;
                    figure.pivotY -= 1.5f;
                    clapper.pitch -= 0.5f;
                    rightArm.roll += 0.7f; leftArm.roll -= 0.7f;
                    rightArm.pitch -= 0.6f; leftArm.pitch -= 0.6f;
                    torso.pitch -= 0.35f;
                }
                case BellRingerEntity.MOVE_TOLL -> {
                    rightArm.pitch -= 0.3f + swing * 0.4f;
                    leftArm.pitch -= 0.3f - swing * 0.4f;
                }
                case BellRingerEntity.MOVE_CLAPPER -> {
                    // The mouth tips back and the tongue is thrown forward.
                    bell.pitch -= 0.28f + MathHelper.sin(age * 0.5f) * 0.03f;
                    figure.pivotY -= 2.5f;
                    clapper.pitch -= 0.45f;
                    rightArm.pitch -= 0.5f; leftArm.pitch -= 0.5f;
                    torso.pitch += 0.15f;
                }
                case BellRingerEntity.MOVE_SHARDS -> {
                    bell.roll += MathHelper.sin(age * 2.1f) * 0.04f;
                    bell.pitch += MathHelper.cos(age * 1.7f) * 0.035f;
                    clapper.roll += MathHelper.sin(age * 1.3f) * 0.35f;
                    torso.pitch -= 0.3f;
                    head.pitch -= 0.4f;
                }
                default -> { }
            }
        }

        // The ropes hang plumb from the beam whatever the bell does (its rest pose has no rotation).
        rightRope.roll -= bell.roll;
        leftRope.roll -= bell.roll;
        rightRope.pitch -= bell.pitch;
        leftRope.pitch -= bell.pitch;
    }
}
