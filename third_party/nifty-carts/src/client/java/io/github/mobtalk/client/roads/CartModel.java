package io.github.mobtalk.client.roads;

import io.github.mobtalk.game.RoadCartEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.MathHelper;

/** The common part of a horse-drawn cart: a body and wheels of eight rims and spokes that turn as it rolls. Ported from NiftyCarts'
 * {@code CartModel} (MIT, third_party/nifty-carts/NOTICE.md) to Yarn names; the wobble when hit is left out (a road cart cannot be
 * hit) and the wheels turn by the distance the cart has rolled. */
abstract class CartModel extends EntityModel<RoadCartEntity> {
    protected final ModelPart body;
    protected final ModelPart rotating;
    protected final List<ModelPart> leftWheels = new ArrayList<>(), rightWheels = new ArrayList<>();
    /** The wheel's radius in blocks, for turning it by the distance rolled. */
    final float wheelRadius;

    protected CartModel(ModelPart root, int axles, float rimLength) {
        body = root.getChild("body");
        rotating = root.hasChild("rotating") ? root.getChild("rotating") : null;
        for (int i = 0; i < axles; i++) { leftWheels.add(root.getChild("leftWheel_" + i)); rightWheels.add(root.getChild("rightWheel_" + i)); }
        wheelRadius = radius(rimLength) / 16f;
    }

    ModelPart body() { return body; }

    @Override public void render(MatrixStack matrices, VertexConsumer vertices, int light, int overlay, int color) {
        body.render(matrices, vertices, light, overlay, color);
        if (rotating != null) rotating.render(matrices, vertices, light, overlay, color);
        for (ModelPart part : leftWheels) part.render(matrices, vertices, light, overlay, color);
        for (ModelPart part : rightWheels) part.render(matrices, vertices, light, overlay, color);
    }

    /** {@code limbAngle} carries the wheels' turn in radians, {@code headPitch} the cart's pitch in degrees. */
    @Override public void setAngles(RoadCartEntity entity, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
        if (rotating == null) body.pitch = (float) Math.toRadians(headPitch);
        else rotating.pitch = (float) Math.toRadians(headPitch);
        for (ModelPart part : rightWheels) { part.pitch = limbAngle; part.roll = 0; }
        for (ModelPart part : leftWheels) { part.pitch = limbAngle; part.roll = 0; }
    }

    static float radius(float rimLength) {
        float angle = MathHelper.PI / 8f;
        return (rimLength / 2f) / (MathHelper.sin(angle) / MathHelper.cos(angle));
    }

    static ModelData createDefinition(float rimLength, float axleLength) { return createDefinition(rimLength, axleLength, 1, 0); }

    static ModelData createDefinition(float rimLength, float axleLength, int axleCount, float axleDist) {
        ModelData def = new ModelData();
        float xOffset = axleLength / 2f + 2;
        for (int k = 0; k < axleCount; k++) {
            createWheel("leftWheel_" + k, xOffset, 0, rimLength, axleDist, -2, k, axleCount).build(def.getRoot());
            createWheel("rightWheel_" + k, -xOffset, 0, rimLength, axleDist, 0, k, axleCount).build(def.getRoot());
        }
        return def;
    }

    static EasyMeshBuilder createWheel(String name, float xOffset, float yOffset, float rimLength, float axleDist, float boxOffset, int axle, int maxAxle) {
        float wheelRadius = radius(rimLength);
        EasyMeshBuilder wheel = new EasyMeshBuilder(name, 46, 60);
        float d = (float) axle - ((maxAxle - 1) / 2f);
        wheel.setRotationPoint(xOffset, yOffset - wheelRadius, 1.0F + axleDist * d);
        wheel.addBox(boxOffset, -1, -1, 2, 2, 2);
        for (int i = 0; i < 8; i++) {
            EasyMeshBuilder rim = new EasyMeshBuilder(name + "_rim_" + i, 58, 64 - ((int) rimLength + 1));
            float epsilon = 0.001f * i % 2;
            rim.addBox(boxOffset + epsilon, -rimLength / 2f, wheelRadius - 1, 2, rimLength, 1);
            rim.xRot = i * (float) Math.PI / 4.0F;
            wheel.addChild(rim);
            EasyMeshBuilder spoke = new EasyMeshBuilder(name + "_spoke_" + i, 54, 64 - MathHelper.ceil(wheelRadius - 1));
            spoke.addBox(0.5f + boxOffset, 1.0F, -0.5F, 1, wheelRadius - 2, 1);
            spoke.xRot = i * (float) Math.PI / 4.0F;
            wheel.addChild(spoke);
        }
        return wheel;
    }

    static EasyMeshBuilder createBody(int rimLength) {
        EasyMeshBuilder body = new EasyMeshBuilder("body");
        body.setRotationPoint(0.0F, -radius(rimLength), 1.0F);
        return body;
    }
}
