package io.github.divineblock.client.boss;

import io.github.divineblock.boss.FacelessIconEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Безликий лик: the icon floats and bobs and leans toward its foe; its lampadas and votive chains swing on their own,
 * the lower links lagging. From the second phase a thin crack of light shows between the two leaves of the board. While it
 * casts it trembles; for the ultimate it rises, its leaves crack open like doors on a core of light and the face flares.
 * Every motion is added to the rest pose of the generated model. */
public final class FacelessIconModel extends BossModel<FacelessIconEntity> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "faceless_icon"), "main");
    private final ModelPart icon, leafR, leafL, light, face, venets, cross;
    private final ModelPart[] lamps = new ModelPart[3], votives = new ModelPart[2], votivesLower = new ModelPart[2];

    public FacelessIconModel(ModelPart root) {
        super(root);
        icon = part("icon"); leafR = part("leaf_r"); leafL = part("leaf_l"); light = part("light"); face = part("face");
        venets = part("venets"); cross = part("crest_cross");
        lamps[0] = part("lamp_c"); lamps[1] = part("lamp_r"); lamps[2] = part("lamp_l");
        votives[0] = part("votive_r"); votives[1] = part("votive_l");
        votivesLower[0] = part("votive_r_lower"); votivesLower[1] = part("votive_l_lower");
    }

    /** «Взор лика» on the client lives in {@link BossClient}: nothing more to hook. */
    static void registerClient() { }

    @Override protected void animate(FacelessIconEntity boss, float limbAngle, float limbDistance, float age, float headYaw, float headPitch) {
        int cast = boss.castState(), phase = boss.syncedPhase();
        icon.pivotY += MathHelper.sin(age * 0.08f) * 1.8f;
        icon.roll += MathHelper.sin(age * 0.03f) * 0.025f;
        // It leans toward its foe: forward when the foe is below, and a little after its turn.
        icon.pitch += MathHelper.clamp(headPitch * 0.35f, -0.25f, 0.3f) + limbDistance * 0.08f;
        icon.yaw += headYaw * 0.3f;

        // The leaves part a crack in the later phases, and open like doors on the light for the ultimate.
        float open = phase >= 3 ? 0.6f : phase == 2 ? 0.3f : 0f, swingOpen = 0f, glow = 1f;
        if (cast == 1) {
            // Trembling while it casts.
            icon.roll += MathHelper.sin(age * 2.3f) * 0.02f;
            icon.pivotX += MathHelper.sin(age * 3.1f) * 0.35f;
            icon.pitch -= 0.06f;
            glow = 1.06f + MathHelper.sin(age * 0.9f) * 0.04f;
        } else if (cast == 2) {
            icon.pivotY -= 8f;
            icon.roll += MathHelper.sin(age * 2.3f) * 0.035f;
            icon.pivotX += MathHelper.sin(age * 3.7f) * 0.5f;
            open = 2.2f;
            swingOpen = 0.32f + MathHelper.sin(age * 0.4f) * 0.04f;
            glow = 1.18f + MathHelper.sin(age * 0.6f) * 0.06f;
        }
        leafR.pivotX -= open;
        leafL.pivotX += open;
        leafR.yaw += swingOpen;
        leafL.yaw -= swingOpen;
        light.xScale = 1f + open * 0.9f;
        light.yScale = light.zScale = 1f;
        face.xScale = face.yScale = glow;
        face.zScale = 1f;
        venets.roll += age * 0.004f * (cast == 2 ? 6f : 1f);
        cross.roll += MathHelper.sin(age * 0.05f + 0.6f) * 0.03f;

        // Lampadas and votive chains hang plumb against the icon's lean and swing on their own; the lower links lag.
        for (int i = 0; i < lamps.length; i++) {
            float sway = cast == 2 ? 0.3f : cast == 1 ? 0.16f : 0.08f;
            lamps[i].roll += MathHelper.sin(age * 0.09f + i * 1.3f) * sway - icon.roll;
            lamps[i].pitch += MathHelper.cos(age * 0.07f + i) * sway * 0.7f - icon.pitch;
        }
        for (int i = 0; i < votives.length; i++) {
            float sway = cast == 2 ? 0.25f : 0.07f;
            float t = age * 0.08f + i * 2.1f;
            votives[i].roll += MathHelper.sin(t) * sway - icon.roll;
            votives[i].pitch += MathHelper.cos(t * 0.8f) * sway * 0.5f - icon.pitch;
            votivesLower[i].roll += MathHelper.sin(t - 0.9f) * sway * 0.9f;
        }
    }
}
