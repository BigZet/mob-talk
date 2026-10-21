package io.github.divineblock.client.boss;

import io.github.divineblock.boss.GodAvatarEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** «Бог с булавой»: the elder breathes, his beard and hair stir, the rays of glory turn slowly behind the nimbus and he
 * looks down on the mortal. For each strike the arm moves as the server's stage says: the mace raised over the head and
 * brought down (the whole figure lunging after it), drawn back to the side and swept across, or the blessing hand raised
 * and thrust forward to throw lightning. Coming and going, he rises out of and sinks back into the cloud bank. Every
 * motion is added to the generated rest pose. */
public final class GodModel {
    public static final EntityModelLayer LAYER = new EntityModelLayer(Identifier.of("divineblock", "god_avatar"), "main");
    final ModelPart root, figure, clouds;
    private final ModelPart torso, head, halo, rays, rightArm, rightFore, leftArm, leftFore, handGlow, mace, robe;
    private final List<ModelPart> beard = new ArrayList<>(), hair = new ArrayList<>(), puffs = new ArrayList<>();

    public GodModel(ModelPart root) {
        this.root = root;
        figure = part("figure"); clouds = part("clouds"); torso = part("torso"); head = part("head"); halo = part("halo");
        rays = part("halo_rays"); rightArm = part("right_arm"); rightFore = part("right_arm_fore"); leftArm = part("left_arm");
        leftFore = part("left_arm_fore"); handGlow = part("left_hand_glow"); mace = part("mace"); robe = part("robe");
        for (int i = 0; i < 40; i++) { ModelPart lock = find(root, "beard_" + i); if (lock != null) beard.add(lock); }
        for (int i = 0; i < 26; i++) { ModelPart lock = find(root, "hair_" + i); if (lock != null) hair.add(lock); }
        for (int i = 0; i < 80; i++) { ModelPart puff = find(root, "cloud_" + i); if (puff != null) puffs.add(puff); }
    }

    private ModelPart part(String name) {
        ModelPart found = find(root, name);
        return found != null ? found : new ModelPart(List.of(), java.util.Map.of());
    }
    private static ModelPart find(ModelPart from, String name) {
        return from.traverse().filter(part -> part.hasChild(name)).findFirst().map(part -> part.getChild(name)).orElse(null);
    }

    /** 0..1 how far he has come out of the clouds, and how gathered the clouds are, for this frame. */
    static float emerge(GodAvatarEntity god, float tickDelta) {
        float p = god.stageProgress(tickDelta);
        return switch (god.stage()) {
            case GodAvatarEntity.ARRIVE -> smooth((p - 0.3f) / 0.7f);
            case GodAvatarEntity.LEAVE -> smooth(1f - p / 0.75f);
            default -> 1f;
        };
    }
    static float gather(GodAvatarEntity god, float tickDelta) {
        float p = god.stageProgress(tickDelta);
        return switch (god.stage()) {
            case GodAvatarEntity.ARRIVE -> smooth(p / 0.4f);
            case GodAvatarEntity.LEAVE -> smooth((1f - p) / 0.45f);
            default -> 1f;
        };
    }
    static float smooth(float t) { t = MathHelper.clamp(t, 0f, 1f); return t * t * (3 - 2 * t); }

    void animate(GodAvatarEntity god, float tickDelta) {
        root.traverse().forEach(ModelPart::resetTransform);
        float age = god.age + tickDelta, p = god.stageProgress(tickDelta);
        int stage = god.stage(), kind = god.strikeKind();
        float emerge = emerge(god, tickDelta), gather = gather(god, tickDelta);

        // Rising out of the cloud bank: sunk and smaller at first.
        figure.pivotY += (1 - emerge) * 16f;
        float grow = 0.45f + 0.55f * emerge;
        figure.xScale = figure.yScale = figure.zScale = grow;
        float cloudScale = 0.35f + 0.65f * gather;
        clouds.xScale = clouds.zScale = cloudScale;
        clouds.yScale = 0.6f + 0.4f * gather;
        clouds.yaw += age * 0.0015f;
        for (int i = 0; i < puffs.size(); i++) puffs.get(i).pivotY += MathHelper.sin(age * 0.03f + i * 1.3f) * 0.5f;

        // Breath, the beard and hair stirring, the glory turning, the gaze down on the mortal.
        float breath = MathHelper.sin(age * 0.05f);
        torso.pitch += breath * 0.012f;
        figure.pivotY += breath * 0.3f;
        head.pitch += 0.22f;
        head.yaw += MathHelper.sin(age * 0.02f) * 0.04f;
        for (int i = 0; i < beard.size(); i++) beard.get(i).roll += MathHelper.sin(age * 0.045f + i * 0.7f) * 0.025f;
        for (int i = 0; i < hair.size(); i++) hair.get(i).pitch += MathHelper.sin(age * 0.04f + i * 0.9f) * 0.03f;
        rays.roll += age * 0.004f;
        float pulse = 1f + MathHelper.sin(age * 0.08f) * 0.03f;
        halo.xScale = halo.yScale = pulse;
        robe.yaw += MathHelper.sin(age * 0.03f) * 0.02f;
        rightArm.pitch += MathHelper.sin(age * 0.06f) * 0.03f;
        leftArm.pitch += MathHelper.sin(age * 0.06f + 1.5f) * 0.03f;
        handGlow.visible = false;

        // Arms spread as he comes and goes.
        leftArm.roll -= 0.35f * (1 - emerge);
        rightArm.roll += 0.35f * (1 - emerge);

        if (stage == GodAvatarEntity.WARN) {
            float raise = smooth(p * 1.6f);
            switch (kind) {
                case GodAvatarEntity.OVERHEAD -> {
                    rightArm.pitch += -2.75f * raise;
                    rightArm.roll += 0.2f * raise;
                    rightFore.pitch += 0.45f * raise;
                    torso.pitch -= 0.12f * raise;
                    head.pitch -= 0.15f * raise;
                }
                case GodAvatarEntity.SWEEP -> {
                    rightArm.pitch += -1.25f * raise;
                    rightArm.yaw += 1.15f * raise;
                    rightFore.pitch += 0.5f * raise;
                    torso.yaw += 0.3f * raise;
                }
                default -> {
                    leftArm.pitch += -2.7f * raise;
                    leftArm.roll -= 0.15f * raise;
                    leftFore.pitch += 0.6f * raise;
                    handGlow.visible = true;
                }
            }
        } else if (stage == GodAvatarEntity.STRIKE) {
            float hit = smooth(p / 0.3f), back = smooth((p - 0.45f) / 0.55f), hold = hit * (1 - back);
            switch (kind) {
                case GodAvatarEntity.OVERHEAD -> {
                    rightArm.pitch += -2.75f * (1 - hit) + -0.55f * hold;
                    rightArm.roll += 0.2f * (1 - hit);
                    rightFore.pitch += 0.45f * (1 - hit) - 0.3f * hold;
                    torso.pitch += 0.38f * hold - 0.12f * (1 - hit) * (1 - back);
                    figure.pivotY += 7f * hold;
                    head.pitch += 0.15f * hold;
                }
                case GodAvatarEntity.SWEEP -> {
                    float yaw = 1.15f - 2.4f * hit;
                    rightArm.pitch += -1.25f * (1 - back);
                    rightArm.yaw += yaw * (1 - back);
                    rightFore.pitch += 0.5f * (1 - back);
                    torso.yaw += (0.3f - 0.65f * hit) * (1 - back);
                    figure.pivotY += 3f * hold;
                }
                default -> {
                    leftArm.pitch += (-2.7f + 1.1f * hit) * (1 - back);
                    leftFore.pitch += 0.6f * (1 - hit) * (1 - back);
                    torso.pitch += 0.15f * hold;
                    handGlow.visible = p < 0.5f;
                }
            }
        }
    }
}
