package io.github.divineblock.client.weapon;

import io.github.divineblock.weapon.ThrownSpearEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/** The spear of St George in flight: its sprite turned point-first along its flight, as the trident is, and spinning
 * about its shaft on the way back. */
public final class ThrownSpearRenderer extends EntityRenderer<ThrownSpearEntity> {
    private final ItemRenderer items;

    public ThrownSpearRenderer(EntityRendererFactory.Context context) {
        super(context);
        items = context.getItemRenderer();
    }

    @Override public void render(ThrownSpearEntity spear, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(MathHelper.lerp(tickDelta, spear.prevYaw, spear.getYaw()) - 90f));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-MathHelper.lerp(tickDelta, spear.prevPitch, spear.getPitch())));
        if (spear.isReturning()) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees((spear.age + tickDelta) * 25f));
        // The sprite's point is at its top right; a turn of -45° lays it along +X, the flight.
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-45f));
        matrices.scale(1.6f, 1.6f, 1.6f);
        items.renderItem(spear.getItemStack(), ModelTransformationMode.NONE, light, OverlayTexture.DEFAULT_UV, matrices, vertexConsumers, spear.getWorld(), spear.getId());
        matrices.pop();
        super.render(spear, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    @Override public Identifier getTexture(ThrownSpearEntity spear) { return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE; }
}
