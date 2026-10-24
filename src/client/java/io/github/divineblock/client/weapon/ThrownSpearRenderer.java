package io.github.divineblock.client.weapon;

import io.github.divineblock.weapon.DivineWeapons;
import io.github.divineblock.weapon.ThrownSpearEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/** The spear of St George in flight: its sprite turned along its flight, as an arrow is, spinning slowly about its shaft
 * on the way back. */
public final class ThrownSpearRenderer extends EntityRenderer<ThrownSpearEntity> {
    private static final ItemStack SPEAR = new ItemStack(DivineWeapons.GEORGE_SPEAR);

    public ThrownSpearRenderer(EntityRendererFactory.Context context) { super(context); }

    @Override public void render(ThrownSpearEntity spear, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(MathHelper.lerp(tickDelta, spear.prevYaw, spear.getYaw()) - 90f));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.lerp(tickDelta, spear.prevPitch, spear.getPitch())));
        // Now +X runs along the flight, as for an arrow.
        if (spear.isReturning()) matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees((spear.age + tickDelta) * 20f));
        // The sprite's point is its upper-right corner: turned onto +X.
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-45f));
        matrices.scale(2.2f, 2.2f, 1f);
        MinecraftClient.getInstance().getItemRenderer().renderItem(SPEAR, ModelTransformationMode.NONE, false, matrices, vertexConsumers, light,
                OverlayTexture.DEFAULT_UV, WeaponRenderer.baked("george_spear_hand"));
        matrices.pop();
        super.render(spear, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    @Override public Identifier getTexture(ThrownSpearEntity spear) { return PlayerScreenHandler.BLOCK_ATLAS_TEXTURE; }
}
