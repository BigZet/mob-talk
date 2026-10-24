package io.github.divineblock.client.weapon;

import io.github.divineblock.weapon.ThrownSpearEntity;
import io.github.divineblock.weapon.WeaponKind;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/** The spear of St George in flight: its generated model turned along its flight, as the trident is, spinning slowly about
 * its shaft on the way back. */
public final class ThrownSpearRenderer extends EntityRenderer<ThrownSpearEntity> {
    private static final Identifier TEXTURE = Identifier.of("divineblock", "textures/entity/weapon/george_spear.png");
    private final ModelPart model;

    public ThrownSpearRenderer(EntityRendererFactory.Context context) {
        super(context);
        model = WeaponClient.model(WeaponKind.GEORGE_SPEAR);
    }

    @Override public void render(ThrownSpearEntity spear, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(MathHelper.lerp(tickDelta, spear.prevYaw, spear.getYaw()) - 90f));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.lerp(tickDelta, spear.prevPitch, spear.getPitch()) + 90f));
        if (spear.isReturning()) matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees((spear.age + tickDelta) * 20f));
        float s = WeaponKind.GEORGE_SPEAR.scale / 16f * 1.4f;
        matrices.scale(s, s, s);
        model.traverse().forEach(ModelPart::resetTransform);
        model.render(matrices, vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE)), light, net.minecraft.client.render.OverlayTexture.DEFAULT_UV);
        matrices.pop();
        super.render(spear, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    @Override public Identifier getTexture(ThrownSpearEntity spear) { return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE; }
}
