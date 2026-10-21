/*
 * Mob Talk — human villagers (track M).
 * Derived from MCA Reborn's VillagerEntityBaseModelMCA, VillagerEntityModelMCA and CommonVillagerModel
 * (net.conczin.mca.client.model), Copyright (C) the MCA Reborn contributors, licensed under the GNU General Public License v3.0.
 * Modified for Mob Talk: ported to Yarn mappings and the vanilla VillagerEntity; head-size dimensions, panic arms and
 * Armourers Workshop hooks are left out.
 */
package io.github.mobtalk.client.human;

import net.minecraft.client.model.*;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.EntityModelPartNames;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;

/** A player-shaped villager with an optional bust. The renderer animates one instance; each visible layer has its own instance
 * (a different dilation) and copies the pose with {@link #copyFrom}. */
public final class HumanModel extends PlayerEntityModel<VillagerEntity> {
    private static final String BREASTS = "breasts", BREASTPLATE = "breastplate";
    final ModelPart breasts, breastplate;
    float breastSize;
    boolean female;

    public HumanModel(ModelPart root) {
        super(root, false);
        breasts = root.getChild(BREASTS);
        breastplate = root.getChild(BREASTPLATE);
    }

    /** Body, wear and bust cubes on the 64×64 skin layout. */
    public static ModelData body(Dilation dilation) {
        ModelData data = PlayerEntityModel.getTexturedModelData(dilation, false);
        ModelPartData root = data.getRoot();
        root.addChild(BREASTS, bust(dilation, 0), ModelTransform.NONE);
        root.addChild(BREASTPLATE, bust(dilation.add(0.1f), 16), ModelTransform.NONE);
        return data;
    }
    /** The body plus a hat cube a little larger than the head, for hair. */
    public static ModelData hair(Dilation dilation) {
        ModelData data = body(dilation);
        data.getRoot().addChild(EntityModelPartNames.HAT, ModelPartBuilder.create().uv(32, 0).cuboid(-4, -8, -4, 8, 8, 8, dilation.add(0.3f)), ModelTransform.NONE);
        return data;
    }
    private static ModelPartBuilder bust(Dilation dilation, int dv) {
        return ModelPartBuilder.create().uv(18, 21 + dv).cuboid(-3.25f, -1.25f, -1.5f, 6, 3, 3, dilation);
    }
    public static HumanModel bake(ModelData data) { return new HumanModel(TexturedModelData.of(data, 64, 64).createModel()); }

    void setLook(boolean female, float breastSize) { this.female = female; this.breastSize = female ? breastSize : 0; }

    @Override public void animateModel(VillagerEntity villager, float limbAngle, float limbDistance, float tickDelta) {
        leftArmPose = ArmPose.EMPTY; rightArmPose = ArmPose.EMPTY;
        boolean right = villager.getMainArm() == Arm.RIGHT;
        if (!villager.getStackInHand(Hand.MAIN_HAND).isEmpty()) { if (right) rightArmPose = ArmPose.ITEM; else leftArmPose = ArmPose.ITEM; }
        if (!villager.getStackInHand(Hand.OFF_HAND).isEmpty()) { if (right) leftArmPose = ArmPose.ITEM; else rightArmPose = ArmPose.ITEM; }
        super.animateModel(villager, limbAngle, limbDistance, tickDelta);
    }

    @Override public void setAngles(VillagerEntity villager, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
        super.setAngles(villager, limbAngle, limbDistance, animationProgress, headYaw, headPitch);
        placeBust();
    }

    /** Takes the animated pose of {@code parent}: bones, arm poses, child flag, then the wear parts and bust follow the bones. */
    void copyFrom(HumanModel parent) {
        parent.copyBipedStateTo(this);
        female = parent.female; breastSize = parent.breastSize;
        jacket.copyTransform(body); leftSleeve.copyTransform(leftArm); rightSleeve.copyTransform(rightArm);
        leftPants.copyTransform(leftLeg); rightPants.copyTransform(rightLeg);
        placeBust();
    }

    private void placeBust() {
        float y = (float) (5.0f - Math.pow(breastSize, 0.5) * 2.5f), z = -1.5f + breastSize * 0.25f;
        for (ModelPart part : new ModelPart[] { breasts, breastplate }) { part.setAngles((float) Math.PI * 0.3f, 0, 0); part.setPivot(0.25f, y, z); }
    }

    @Override public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay, int color) {
        super.render(matrices, vertices, light, overlay, color);
        if (!female || child || breastSize <= 0 || !body.visible || !breasts.visible) return;
        matrices.push();
        // Грудь лежит в корне модели, но держится за туловище: сначала поза туловища (наклон, присед), потом размер.
        body.rotate(matrices);
        matrices.scale(breastSize * 0.2f + 1.05f, breastSize * 0.75f + 0.75f, breastSize * 0.75f + 0.75f);
        breasts.render(matrices, vertices, light, overlay, color);
        if (breastplate.visible) breastplate.render(matrices, vertices, light, overlay, color);
        matrices.pop();
    }
}
