package io.github.divineblock.client.boss;

import io.github.divineblock.boss.SchemnikEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Чёрный Схимник: he drifts a little above the floor, rising and sinking slowly, stooped under the кукуль; the mantle and the
 * ряса sway fold by fold and trail behind as he glides; the prayer rope swings from his wrist; the candle flame flickers. To
 * cast he lifts the candle high and the flame grows; for «Вечная схима» he spreads his arms, raises the staff and bows his
 * head while the robes flare. Every motion is added to the rest pose of the generated model. */
public final class SchemnikModel extends BossModel<SchemnikEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "black_schemnik"), "main");
    private static final int MANTLE = 17, RYASA = 16, CAPE = 14, BEADS = 16;
    private final ModelPart figure, body, head, rightArm, leftArm, rightFore, leftFore, staff, candle, flame, chotki, mantle, ryasa, analavLow;
    private final ModelPart[] mantlePanels = new ModelPart[MANTLE], ryasaPanels = new ModelPart[RYASA], capePanels = new ModelPart[CAPE];
    private final ModelPart[] beads = new ModelPart[BEADS], lappets, drapes;
    private final float[] mantleAngle = new float[MANTLE];

    public SchemnikModel(ModelPart root) {
        super(root);
        figure = part("figure"); body = part("body"); head = part("head");
        rightArm = part("right_arm"); leftArm = part("left_arm"); rightFore = part("right_arm_forearm"); leftFore = part("left_arm_forearm");
        staff = part("staff"); candle = part("candle"); flame = part("candle_flame"); chotki = part("chotki_0");
        mantle = part("mantle"); ryasa = part("ryasa"); analavLow = part("analav_low");
        for (int i = 0; i < MANTLE; i++) {
            mantlePanels[i] = part("mantle_" + i);
            mantleAngle[i] = 1.25f + (MathHelper.TAU - 2.5f) * i / (MANTLE - 1);
        }
        for (int i = 0; i < RYASA; i++) ryasaPanels[i] = part("ryasa_" + i);
        for (int i = 0; i < CAPE; i++) capePanels[i] = part("cape_" + i);
        for (int i = 0; i < BEADS; i++) beads[i] = part("chotki_" + i);
        lappets = new ModelPart[] { part("lappet_r"), part("lappet_l"), part("lappet_r_low"), part("lappet_l_low") };
        drapes = new ModelPart[] { part("right_arm_drape"), part("left_arm_drape") };
    }

    @Override protected void animate(SchemnikEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        int cast = boss.castState(), phase = boss.syncedPhase();
        float drift = MathHelper.sin(age * 0.045f), breath = MathHelper.sin(age * 0.07f);
        float glide = Math.min(1f, limbDistance * 1.6f);

        // He drifts: a slow rise and fall above the floor, leaning into the glide.
        figure.pivotY -= 0.9f + drift * 0.7f;
        figure.pitch += glide * 0.06f;
        float stoop = 0.12f + breath * 0.015f + (phase >= 3 ? 0.04f : 0f);
        body.pitch += stoop;
        head.yaw += headYaw * 0.75f;
        head.pitch += 0.12f + headPitch * 0.5f;

        // Arms: the staff hand keeps time with the glide, the candle hand stays steady.
        float walk = MathHelper.cos(limbAngle * 0.4f) * limbDistance;
        float rArm = -walk * 0.15f + breath * 0.02f, lArm = walk * 0.06f, lFore = 0f, rFore = 0f;
        float flameScale = 1f + MathHelper.sin(age * 0.9f) * 0.08f + MathHelper.sin(age * 2.3f) * 0.05f;
        float flare = 0f, raise = 0f;

        if (cast == 1) {
            // The candle is lifted high and the flame swells.
            lArm += -0.9f + MathHelper.sin(age * 0.4f) * 0.04f;
            lFore += 0.35f;
            head.pitch -= 0.25f;
            flameScale *= 1.5f;
            flare = 0.05f;
        }
        if (cast == 2) {
            // «Вечная схима»: arms spread, the staff raised, the head bowed, the robes flaring.
            rightArm.roll += 0.85f; leftArm.roll -= 0.85f;
            rArm += -0.5f; lArm += -0.3f; rFore += 0.3f; lFore += 0.5f;
            raise = 0.85f;
            head.pitch += 0.4f;
            body.pitch += 0.12f + MathHelper.sin(age * 0.25f) * 0.04f;
            flameScale *= 1.8f;
            flare = 0.18f + MathHelper.sin(age * 0.3f) * 0.03f;
        }
        if (handSwingProgress > 0) {
            // A blow with the staff.
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            rArm += -swing * 1.5f;
            rFore += swing * 0.3f;
            body.yaw += swing * 0.3f;
        }
        rightArm.pitch += rArm; leftArm.pitch += lArm; rightFore.pitch += rFore; leftFore.pitch += lFore;
        // The staff stands up straight in his hand except while it is raised; the candle always burns upward.
        staff.pitch -= (rArm + rFore + stoop) * (1f - raise) + raise * 0.2f;
        staff.roll -= cast == 2 ? 0.6f : 0f;
        candle.pitch -= lArm + lFore + stoop;
        candle.roll += cast == 2 ? 0.75f : 0f;
        flame.xScale = flame.zScale = 0.9f + (flameScale - 1f) * 0.5f;
        flame.yScale = flameScale;

        // The prayer rope hangs from the wrist and swings, each bead a little behind the one above.
        chotki.pitch -= lArm + lFore + stoop;
        chotki.roll += cast == 2 ? 0.75f : 0f;
        for (int i = 0; i < BEADS; i++) {
            beads[i].pitch += MathHelper.sin(age * 0.08f - i * 0.32f) * 0.035f + glide * 0.02f;
            beads[i].roll += MathHelper.sin(age * 0.06f - i * 0.28f) * 0.025f;
        }

        // Cloth: the mantle hangs straight whatever the stoop, sways fold by fold and trails behind the glide.
        mantle.pitch -= stoop;
        for (int i = 0; i < MANTLE; i++) {
            float back = MathHelper.cos(mantleAngle[i]);
            mantlePanels[i].pitch += MathHelper.sin(age * 0.06f + i * 0.7f) * 0.025f + glide * 0.18f * back - flare;
        }
        for (int i = 0; i < RYASA; i++) {
            float a = MathHelper.TAU * i / RYASA;
            ryasaPanels[i].pitch += MathHelper.sin(age * 0.07f + i * 0.9f) * 0.02f + glide * 0.12f * MathHelper.cos(a) - flare * 0.6f;
        }
        for (int i = 0; i < CAPE; i++) capePanels[i].pitch += MathHelper.sin(age * 0.08f + i * 0.6f) * 0.02f - flare * 0.4f;
        ryasa.roll += walk * 0.03f + drift * 0.01f;
        analavLow.pitch += glide * 0.12f + MathHelper.sin(age * 0.07f) * 0.015f;
        for (int i = 0; i < lappets.length; i++) lappets[i].pitch += MathHelper.sin(age * 0.07f - i * 0.8f) * 0.03f + glide * 0.15f - (i < 2 ? stoop * 0.5f : 0f);
        drapes[0].pitch -= rArm + rFore;
        drapes[1].pitch -= (lArm + lFore) * 0.8f;
        drapes[0].pitch += MathHelper.sin(age * 0.09f) * 0.04f;
        drapes[1].pitch += MathHelper.sin(age * 0.09f + 1.3f) * 0.04f;
    }
}
