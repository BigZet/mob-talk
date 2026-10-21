package io.github.divineblock.client.boss;

import io.github.divineblock.boss.NimrodEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Нимрод: a measured royal stride with the layered robe swinging and the fringes trailing, the bow at his side and a brick in
 * his fist; he breathes, the beard and the crown's fillets sway and his head follows the foe. To shoot he raises the great bow
 * before him and draws the string to his ear, an arrow on it (the string halves swing back as he draws); to throw he weighs
 * the brick over his shoulder and hurls it; to stamp he lifts a foot from under the robe. For «Башня до небес» he raises both
 * arms to the sky, bow and brick held high, and looks up. Every motion is added to the rest pose of the generated model. */
public final class NimrodModel extends BossModel<NimrodEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "nimrod"), "main");
    /** The bow's string: half its length, for the angle of the draw (see scripts/bosses/nimrod.py). */
    private static final float HALF_STRING = 14.4f;
    private final ModelPart hips, body, head, beard, ribbons, rightArm, leftArm, rightForearm, leftForearm, bow, stringUpper, stringLower,
            arrow, rightLeg, leftLeg, rightShin, leftShin, robeUnder, robeOver, shawlSkirt, apron, quiver;

    public NimrodModel(ModelPart root) {
        super(root);
        hips = part("hips"); body = part("body"); head = part("head"); beard = part("beard"); ribbons = part("ribbons");
        rightArm = part("right_arm"); leftArm = part("left_arm"); rightForearm = part("right_arm_forearm"); leftForearm = part("left_arm_forearm");
        bow = part("bow"); stringUpper = part("string_upper"); stringLower = part("string_lower"); arrow = part("nocked_arrow");
        rightLeg = part("right_leg"); leftLeg = part("left_leg"); rightShin = part("right_leg_shin"); leftShin = part("left_leg_shin");
        robeUnder = part("robe_under"); robeOver = part("robe_over"); shawlSkirt = part("shawl_skirt"); apron = part("apron"); quiver = part("quiver");
    }

    /** «Смешение языков» on the client: see {@link NimrodConfusion} and its mixins. */
    static void registerClient() { }

    @Override protected void animate(NimrodEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        float breath = MathHelper.sin(age * 0.07f);
        float step = limbAngle * 0.55f;
        float walk = MathHelper.cos(step) * limbDistance, lift = MathHelper.sin(step);

        head.yaw += headYaw * 0.8f;
        head.pitch += headPitch * 0.6f;
        body.pitch += breath * 0.015f;
        beard.pitch += breath * 0.025f + walk * 0.03f;
        ribbons.pitch += 0.05f + limbDistance * 0.35f + MathHelper.sin(age * 0.11f) * 0.04f;

        // The royal stride: legs under the robe, the hems swinging after them, the body turning a little.
        rightLeg.pitch += walk * 0.5f;
        leftLeg.pitch -= walk * 0.5f;
        rightShin.pitch += Math.max(0f, lift) * 0.5f * limbDistance;
        leftShin.pitch += Math.max(0f, -lift) * 0.5f * limbDistance;
        hips.pivotY += (1f - Math.abs(lift)) * 0.6f * limbDistance;
        body.yaw += walk * 0.06f;
        robeOver.yaw -= walk * 0.05f;
        robeOver.pitch += limbDistance * 0.05f;
        robeUnder.yaw -= walk * 0.03f;
        shawlSkirt.roll += walk * 0.03f;
        apron.pitch -= Math.abs(walk) * 0.15f;
        quiver.roll += walk * 0.04f;
        rightArm.pitch -= walk * 0.35f;
        leftArm.pitch += walk * 0.3f;
        rightArm.roll += breath * 0.02f;
        leftArm.roll -= breath * 0.02f;

        float draw = 0f;
        int cast = boss.castState(), move = boss.move();
        if (cast == 2) {
            // «Башня до небес»: both arms to the sky, bow and brick held high, the face lifted.
            float lift2 = MathHelper.sin(age * 0.35f) * 0.04f;
            rightArm.pitch += -2.75f + lift2;
            leftArm.pitch += -2.75f - lift2;
            rightArm.roll += 0.3f;
            leftArm.roll -= 0.3f;
            rightForearm.pitch += 0.6f;
            leftForearm.pitch += 0.1f;
            head.pitch -= 0.5f;
            body.pitch -= 0.1f;
            ribbons.pitch += 0.3f;
        } else if (move == NimrodEntity.MOVE_DRAW) {
            // The bow raised before him, canted a little; the right hand draws the string back to the ear.
            float armAdd = -1.55f, foreAdd = 0.15f;
            leftArm.pitch += armAdd;
            leftArm.yaw -= 0.15f;
            leftForearm.pitch += foreAdd;
            bow.pitch -= armAdd + foreAdd;
            bow.roll += 0.25f;
            bow.yaw += 1.2f;
            rightArm.pitch -= 1.5f;
            rightArm.yaw += 0.55f;
            rightForearm.pitch -= 1.7f;
            body.yaw -= 0.2f;
            head.yaw -= 0.1f;
            draw = 6.5f + MathHelper.sin(age * 0.6f) * 0.3f;
        } else if (move == NimrodEntity.MOVE_THROW && cast == 1) {
            // He weighs the brick over his shoulder, the body turned back.
            rightArm.pitch -= 2.5f;
            rightArm.roll += 0.2f;
            rightForearm.pitch -= 0.6f + MathHelper.sin(age * 0.5f) * 0.06f;
            body.yaw += 0.35f;
            body.pitch -= 0.06f;
            leftArm.pitch -= 0.5f;
            leftArm.roll -= 0.2f;
        } else if (move == NimrodEntity.MOVE_STOMP && cast == 1) {
            rightLeg.pitch -= 0.9f;
            rightShin.pitch += 0.7f;
            robeOver.pitch -= 0.1f;
            body.pitch -= 0.05f;
            rightArm.roll += 0.4f;
            leftArm.roll -= 0.4f;
        }

        if (handSwingProgress > 0 && cast != 2 && move != NimrodEntity.MOVE_DRAW) {
            // The throw (or a blow): the right arm swings over and down, the body follows.
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            rightArm.pitch += -2.5f + handSwingProgress * 2.2f;
            rightForearm.pitch -= 0.3f * swing;
            body.yaw -= 0.35f * swing;
            body.pitch += 0.1f * swing;
        }

        // The string: its halves swing back toward the drawing hand and stretch; the arrow shows only on a drawn string.
        if (draw > 0) {
            float angle = (float) Math.atan2(draw, HALF_STRING), stretch = MathHelper.sqrt(draw * draw + HALF_STRING * HALF_STRING) / HALF_STRING;
            stringUpper.pitch += angle;
            stringLower.pitch -= angle;
            stringUpper.yScale = stringLower.yScale = stretch;
            arrow.pivotZ += draw;
        }
        arrow.visible = draw > 0;
    }
}
