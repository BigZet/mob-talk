package io.github.divineblock.client.boss;

import io.github.divineblock.boss.BeastEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Зверь из бездны: a heavy bear's walk; seven necks sway each on its own rhythm and the jaws snap now and then; the middle
 * head follows the gaze. Dead heads hang limp; the wounded head droops until its wound heals in the last phase. Casting, all
 * living heads lean in with open jaws; for the ultimate they rear up and fan out. */
public final class BeastModel extends BossModel<BeastEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "beast"), "main");
    /** The order the heads die in (the wounded one, 5, is apart: it droops from the start until it heals). */
    private static final int[] DEATH_ORDER = { 0, 6, 1, 4, 2, 3 };
    private static final int WOUNDED = 5;
    private final ModelPart body, tail, frontRight, frontLeft, backRight, backLeft;
    private final ModelPart[] necks = new ModelPart[BeastEntity.HEADS], heads = new ModelPart[BeastEntity.HEADS], jaws = new ModelPart[BeastEntity.HEADS];

    public BeastModel(ModelPart root) {
        super(root);
        body = part("body"); tail = part("tail");
        frontRight = part("front_right_leg"); frontLeft = part("front_left_leg"); backRight = part("back_right_leg"); backLeft = part("back_left_leg");
        for (int i = 0; i < BeastEntity.HEADS; i++) { necks[i] = part("neck_" + i); heads[i] = part("head_" + i); jaws[i] = part("jaw_" + i); }
    }

    /** «Начертание» is a server rule: nothing to hook on the client. */
    static void registerClient() { }

    @Override protected void animate(BeastEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        float walk = MathHelper.cos(limbAngle * 0.5f) * limbDistance;
        frontRight.pitch = walk * 0.7f;
        backLeft.pitch = walk * 0.7f;
        frontLeft.pitch = -walk * 0.7f;
        backRight.pitch = -walk * 0.7f;
        body.roll = walk * 0.04f;
        body.pitch = MathHelper.sin(age * 0.05f) * 0.015f;
        tail.yaw = MathHelper.sin(age * 0.08f) * 0.35f;
        tail.pitch = -0.6f + MathHelper.sin(age * 0.05f) * 0.1f;

        int cast = boss.castState(), lost = boss.headsLost();
        boolean[] dead = new boolean[BeastEntity.HEADS];
        for (int k = 0; k < Math.min(lost, DEATH_ORDER.length); k++) dead[DEATH_ORDER[k]] = true;
        boolean woundDown = !boss.woundHealed();
        for (int i = 0; i < BeastEntity.HEADS; i++) {
            ModelPart neck = necks[i], head = heads[i], jaw = jaws[i];
            float side = i - 3;
            float sway = MathHelper.sin(age * (0.05f + i * 0.007f) + i * 1.7f);
            float bob = MathHelper.sin(age * (0.07f + i * 0.005f) + i * 0.9f);
            neck.pitch = 0.45f + bob * 0.08f;
            neck.roll = side * 0.24f + sway * 0.07f;
            neck.yaw = sway * 0.05f;
            head.pitch = -0.45f - bob * 0.05f;
            head.yaw = side * -0.06f + sway * 0.12f;
            // A snap of the jaws now and then, each head at its own moment.
            float snap = Math.max(0, MathHelper.sin(age * 0.11f + i * 2.3f) - 0.75f) * 2.4f;
            jaw.pitch = 0.08f + snap;
            if (i == 3) { head.yaw = headYaw * 0.7f; head.pitch = -0.45f + headPitch * 0.5f; }
            if (dead[i] || i == WOUNDED && woundDown) {
                // Limp: the neck falls forward and aside, the head hangs, the jaw slack.
                float limp = i == WOUNDED ? 0.95f : 1.25f;
                neck.pitch = 0.45f + limp + bob * 0.02f;
                neck.roll = side * 0.3f;
                head.pitch = 0.6f;
                head.yaw = 0;
                jaw.pitch = 0.5f;
                continue;
            }
            if (cast == 1) { neck.pitch = 0.85f + bob * 0.04f; head.pitch = -0.7f; jaw.pitch = 0.75f + sway * 0.1f; }
            if (cast == 2) { neck.pitch = 0.05f + bob * 0.05f; neck.roll = side * 0.34f; head.pitch = -0.2f; jaw.pitch = 0.9f; }
        }
        if (cast == 2) body.pitch = -0.08f;
        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            frontRight.pitch = -swing * 1.2f;
            frontRight.roll = swing * 0.2f;
        }
    }
}
