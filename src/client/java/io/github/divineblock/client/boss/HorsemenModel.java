package io.github.divineblock.client.boss;

import io.github.divineblock.boss.HorsemenEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.Map;
import java.util.WeakHashMap;

/** Четыре всадника: a great warhorse with its rider. The horse gallops in a real four-beat order (left hind, right hind, left
 * fore, right fore), the knees and hocks fold as each leg swings forward, the body rises and pitches with the stride and the
 * neck pumps; the mane, the tail, the caparison and the cloak stream back with speed. At rest it breathes, swishes its tail,
 * flicks its ears and stamps. It rears when a seal breaks (and for the ultimate), pawing the air, and lowers itself with its
 * ears pinned for a charge. The rider's gear follows the phase: bow and crown, great sword and helm, scales on chains and a
 * hood, Death's hood, shroud and scythe; each weapon has its own cast pose and blow. Stance changes ease in and out.
 * Every motion is added to the rest pose of the generated model. */
public final class HorsemenModel extends BossModel<HorsemenEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "horsemen"), "main");
    private static final float[] GAIT = { 1.88f, 2.51f, 0f, 0.63f }; // front left, front right, back left, back right

    private final ModelPart horse, horseHead, tail0, rider, riderBody, riderHead, rightArm, leftArm, rightFore, leftFore, cloak;
    private final ModelPart crown, helm, hood, bow, quiver, sword, scales, scythe, shroud, pauldronR, pauldronL, fumes, earL, earR;
    private final ModelPart chainL, chainR, panL, panR;
    private final ModelPart[] neck = new ModelPart[4], tail = new ModelPart[6], smoke = new ModelPart[2], fume = new ModelPart[8];
    private final ModelPart[] legs = new ModelPart[4], lows = new ModelPart[4], hooves = new ModelPart[4];
    private final ModelPart[] maneRoots = new ModelPart[16], maneTips = new ModelPart[16];
    private final ModelPart[][] cloakSegs = new ModelPart[5][3];
    private final ModelPart[] barding = new ModelPart[12], peytral = new ModelPart[3];
    /** Per boss: the eased rear and charge weights and the age they were last updated at. */
    private final Map<HorsemenEntity, float[]> ease = new WeakHashMap<>();

    public HorsemenModel(ModelPart root) {
        super(root);
        horse = part("horse"); horseHead = part("horse_head"); tail0 = part("tail_0");
        rider = part("rider"); riderBody = part("rider_body"); riderHead = part("rider_head");
        rightArm = part("right_arm"); leftArm = part("left_arm"); rightFore = part("right_arm_fore"); leftFore = part("left_arm_fore");
        cloak = part("cloak"); crown = part("crown"); helm = part("helm"); hood = part("hood");
        bow = part("bow"); quiver = part("quiver"); sword = part("sword"); scales = part("scales"); scythe = part("scythe");
        shroud = part("shroud"); pauldronR = part("pauldron_r"); pauldronL = part("pauldron_l"); fumes = part("fumes");
        earL = part("ear_l"); earR = part("ear_r");
        chainL = part("scale_chain_l"); chainR = part("scale_chain_r"); panL = part("scale_pan_l"); panR = part("scale_pan_r");
        for (int i = 0; i < 4; i++) neck[i] = part("neck_" + i);
        for (int i = 0; i < 6; i++) tail[i] = part("tail_" + i);
        for (int i = 0; i < 2; i++) smoke[i] = part("nostril_smoke_" + i);
        for (int i = 0; i < 8; i++) fume[i] = part("fume_" + i);
        String[] legNames = { "leg_front_left", "leg_front_right", "leg_back_left", "leg_back_right" };
        for (int i = 0; i < 4; i++) {
            legs[i] = part(legNames[i]); lows[i] = part(legNames[i] + "_low"); hooves[i] = part(legNames[i] + "_hoof");
        }
        int m = 0;
        for (int i = 0; i < 4; i++) for (int k = 0; k < 2; k++) for (String s : new String[] { "l", "r" }) {
            String name = "mane_" + i + "_" + k + "_" + s;
            maneRoots[m] = part(name + "_0"); maneTips[m] = part(name + "_1"); m++;
        }
        for (int i = 0; i < 5; i++) for (int k = 0; k < 3; k++) cloakSegs[i][k] = part("cloak_" + i + "_" + k);
        for (int i = 0; i < 6; i++) { barding[i] = part("barding_l_" + i); barding[6 + i] = part("barding_r_" + i); }
        for (int i = 0; i < 3; i++) peytral[i] = part("peytral_" + i);
    }

    /** «Голод» has only server rules: nothing to hook on the client. */
    static void registerClient() { }

    @Override protected void animate(HorsemenEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        int phase = Math.max(1, Math.min(4, boss.syncedPhase()));
        int cast = boss.castState(), stance = boss.stance();

        crown.visible = bow.visible = quiver.visible = phase == 1;
        helm.visible = sword.visible = phase == 2;
        hood.visible = phase >= 3;
        scales.visible = phase == 3;
        scythe.visible = shroud.visible = phase == 4;
        pauldronR.visible = pauldronL.visible = phase != 4;
        boolean smoking = phase == 2 || phase == 4;
        fumes.visible = smoke[0].visible = smoke[1].visible = smoking;
        // War's hooves smoke with fire and his nostrils breathe it; the fumes of Hades also trail behind Death.
        for (int i = 4; i < 8; i++) fume[i].visible = phase == 4;

        // Stances ease in and out over a few ticks instead of snapping.
        float[] e = ease.computeIfAbsent(boss, b -> new float[] { 0, 0, age });
        float dt = MathHelper.clamp(age - e[2], 0f, 5f);
        e[2] = age;
        float rate = 1f - (float) Math.pow(0.75, dt);
        e[0] += ((stance == HorsemenEntity.STANCE_REAR ? 1 : 0) - e[0]) * rate;
        e[1] += ((stance == HorsemenEntity.STANCE_CHARGE ? 1 : 0) - e[1]) * rate;
        float rear = e[0], charge = e[1];

        // ---- The gait. ----
        float reach = Math.max(Math.min(1f, limbDistance * 1.5f), charge);
        float stride = charge > 0.05f ? age * 0.6f : limbAngle * 0.5f;
        float breath = MathHelper.sin(age * 0.06f);
        for (int i = 0; i < 4; i++) {
            boolean front = i < 2;
            float phi = stride + GAIT[i];
            float flex = Math.max(0f, MathHelper.sin(phi)) * reach;
            legs[i].pitch += MathHelper.cos(phi) * reach * (front ? 0.7f : 0.55f);
            if (front) {
                lows[i].pitch += flex * 1.5f;
                hooves[i].pitch += flex * 0.8f;
            } else {
                lows[i].pitch -= flex * 0.5f;
                hooves[i].pitch += flex * 0.9f;
            }
        }
        float bob = MathHelper.cos(stride + 0.9f) * reach;
        horse.pivotY += bob * 1.2f + breath * 0.15f * (1 - reach);
        horse.pitch += MathHelper.sin(stride + 0.4f) * 0.06f * reach;
        neck[0].pitch += MathHelper.sin(stride + 1.6f) * 0.12f * reach;
        neck[3].pitch += MathHelper.sin(age * 0.05f) * 0.04f * (1 - reach);
        horseHead.yaw += headYaw * 0.25f;
        neck[2].yaw += headYaw * 0.12f;

        // At rest: an ear flicks now and then, the right fore stamps, the tail swishes.
        float idle = 1 - reach;
        float flick = Math.max(0, MathHelper.sin(age * 0.11f) - 0.85f) * 6f;
        earL.roll -= flick * 0.4f * idle;
        earR.pitch -= Math.max(0, MathHelper.sin(age * 0.07f + 2f) - 0.8f) * 3f * idle;
        float stamp = Math.max(0, MathHelper.sin(age * 0.045f) - 0.92f) * 12f * idle;
        legs[1].pitch -= stamp * 0.35f;
        lows[1].pitch += stamp * 0.9f;

        // Hair and cloth: they stream with speed and ripple on their own.
        float wind = reach + charge * 0.3f;
        for (int m = 0; m < maneRoots.length; m++) {
            float freq = 0.12f + 0.25f * reach;
            maneRoots[m].pitch += wind * 0.18f + MathHelper.sin(age * freq + m * 0.9f) * (0.05f + 0.1f * reach);
            maneTips[m].pitch += wind * 0.1f + MathHelper.sin(age * freq + m * 0.9f - 0.9f) * (0.08f + 0.15f * reach);
        }
        tail0.pitch += wind * 0.4f;
        tail0.yaw += MathHelper.sin(age * 0.07f) * 0.25f * idle;
        for (int i = 1; i < tail.length; i++) {
            tail[i].pitch += wind * 0.04f + MathHelper.sin(age * 0.4f - i * 0.7f) * 0.12f * reach;
            tail[i].yaw += MathHelper.sin(age * 0.07f - i * 0.5f) * 0.1f * idle + MathHelper.sin(age * 0.3f - i * 0.6f) * 0.05f * reach;
        }
        for (int i = 0; i < barding.length; i++) {
            float w = MathHelper.sin(age * (0.08f + 0.3f * reach) + i * 0.8f);
            barding[i].pitch += wind * 0.2f + w * (0.02f + 0.06f * reach);
            barding[i].roll += (i < 6 ? -1 : 1) * reach * 0.08f;
        }
        for (int i = 0; i < peytral.length; i++) peytral[i].pitch -= reach * 0.25f + MathHelper.sin(age * 0.1f + i) * 0.03f;
        for (int i = 0; i < cloakSegs.length; i++) for (int k = 0; k < 3; k++) {
            float w = MathHelper.sin(age * (0.1f + 0.3f * reach) + i * 0.7f - k * 0.9f);
            cloakSegs[i][k].pitch += (k == 0 ? wind * 0.45f : wind * 0.12f) + w * (0.04f + 0.1f * reach);
        }

        // Fumes drift and breathe.
        for (int i = 0; i < fume.length; i++) {
            fume[i].yaw += age * 0.02f * (i % 2 == 0 ? 1 : -1);
            float puff = 0.85f + 0.2f * MathHelper.sin(age * 0.15f + i * 1.3f);
            fume[i].xScale = fume[i].zScale = puff;
            fume[i].yScale = puff + 0.25f * reach;
        }
        for (int i = 0; i < 2; i++) { float puff = 0.6f + 0.5f * Math.abs(MathHelper.sin(age * 0.12f + i)); smoke[i].yScale = puff; smoke[i].xScale = puff; }

        // ---- The rider. ----
        rider.pitch -= bob * 0.04f;
        riderBody.pitch += breath * 0.015f;
        riderHead.yaw += headYaw * 0.6f;
        riderHead.pitch += headPitch * 0.5f;
        rightArm.pitch += -0.25f;
        rightFore.pitch += -0.6f;
        leftArm.pitch += -0.35f;
        leftFore.pitch += -0.55f;
        rightArm.roll += 0.12f;
        leftArm.roll -= 0.35f;
        switch (phase) {
            case 1 -> {
                if (cast != 0) {
                    // He draws the bow: the bow arm straight out, the string hand at his cheek.
                    leftArm.pitch += -1.2f; leftFore.pitch += 0.55f; leftArm.yaw += 0.2f;
                    rightArm.pitch += -1.25f; rightArm.yaw += -0.7f; rightFore.pitch += -0.6f; rightFore.yaw += 0.6f;
                    riderBody.yaw += 0.25f;
                }
            }
            case 2 -> {
                rightArm.pitch += MathHelper.sin(stride) * reach * 0.12f;
                if (cast != 0) { rightArm.pitch += -2.4f; rightFore.pitch += 0.4f; rightArm.roll -= 0.2f; }
            }
            case 3 -> {
                leftArm.pitch += -0.7f + MathHelper.sin(age * 0.08f) * 0.04f;
                if (cast != 0) { leftArm.pitch += -1.6f; leftFore.pitch += 0.5f; }
            }
            default -> {
                rightArm.pitch += -0.1f;
                if (cast != 0) { rightArm.pitch += -2.1f; rightArm.roll += 0.4f; rightFore.pitch += 0.3f; }
            }
        }
        if (cast == 2) {
            leftArm.pitch += -1.4f; leftArm.roll -= 0.3f;
            riderHead.pitch -= 0.3f;
        }
        if (charge > 0) {
            riderBody.pitch += 0.35f * charge;
            switch (phase) {
                case 1 -> { leftArm.pitch += -0.9f * charge; leftFore.pitch += 0.4f * charge; }
                case 2 -> { rightArm.pitch += -0.9f * charge; rightFore.pitch += 0.5f * charge; }
                case 3 -> leftArm.pitch += -1.1f * charge;
                default -> { rightArm.pitch += -0.8f * charge; rightArm.roll += 0.5f * charge; }
            }
        }
        if (handSwingProgress > 0) {
            float swing = MathHelper.sin(MathHelper.sqrt(handSwingProgress) * MathHelper.PI);
            float through = handSwingProgress;
            switch (phase) {
                case 1 -> { rightArm.pitch += -1.2f * swing; rightArm.yaw += -0.7f * swing; leftArm.pitch += -1.1f * swing; }
                case 2 -> {
                    // An overhead cut that comes down across the body.
                    rightArm.pitch += -2.6f + through * 3.2f;
                    rightArm.yaw += through * 0.6f;
                    riderBody.yaw += swing * 0.4f;
                }
                case 3 -> { leftArm.pitch += -1.4f * swing; scales.roll += swing * 0.4f; }
                default -> {
                    // The scythe reaps sideways, low and wide.
                    rightArm.pitch += -1.2f * swing;
                    rightArm.roll += 1.3f - through * 2.4f;
                    riderBody.yaw += 0.5f - through * 1.0f;
                }
            }
        }

        // ---- Stances. ----
        if (rear > 0) {
            float paw = MathHelper.sin(age * 0.5f) * 0.35f;
            horse.pitch -= 0.85f * rear;
            for (int i = 2; i < 4; i++) { legs[i].pitch += 1.1f * rear; lows[i].pitch -= 0.3f * rear; hooves[i].pitch += 0.15f * rear; }
            legs[0].pitch -= (0.95f + paw) * rear; lows[0].pitch += 1.6f * rear;
            legs[1].pitch -= (0.75f - paw) * rear; lows[1].pitch += 1.4f * rear;
            neck[0].pitch -= 0.1f * rear;
            horseHead.pitch -= 0.3f * rear;
            earL.pitch -= 0.5f * rear; earR.pitch -= 0.5f * rear;
            tail0.pitch += 0.75f * rear;
            rider.pitch += 0.65f * rear;
            cloak.pitch += 0.3f * rear;
            for (ModelPart b : barding) b.pitch += 0.5f * rear;
            horse.pivotY += 1.5f * rear;
            if (cast == 0 && handSwingProgress == 0) { rightArm.pitch += -1.2f * rear; leftArm.pitch += -0.6f * rear; }
        }
        if (charge > 0) {
            horse.pivotY += 1.2f * charge;
            horse.pitch += 0.05f * charge;
            neck[0].pitch += 0.4f * charge;
            neck[1].pitch += 0.1f * charge;
            horseHead.pitch -= 0.45f * charge;
            earL.pitch -= 0.8f * charge; earR.pitch -= 0.8f * charge;
            rider.pitch += 0.1f * charge;
        }

        // Gear that keeps to gravity whatever the arm does: the bow upright, the scales level, the pans hanging.
        float leftSum = leftArm.pitch + leftFore.pitch + riderBody.pitch + rider.pitch + horse.pitch;
        bow.pitch += -leftSum;
        scales.pitch += -1.2f - leftSum;
        float sway = MathHelper.sin(age * (cast != 0 ? 0.3f : 0.09f)) * (cast != 0 ? 0.35f : 0.08f) + MathHelper.sin(stride) * 0.15f * reach;
        chainL.roll += sway; chainR.roll += sway;
        chainL.pitch += MathHelper.sin(age * 0.07f) * 0.06f; chainR.pitch += MathHelper.sin(age * 0.07f + 1f) * 0.06f;
        panL.roll -= sway; panR.roll -= sway;
        if (cast != 0) scales.roll += MathHelper.sin(age * 0.2f) * 0.06f;
    }
}
