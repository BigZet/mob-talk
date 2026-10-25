package io.github.mobtalk.client.game;

import io.github.mobtalk.game.ShopContent;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;

/** A player holding a shopping cart pushes it: the cart stands on the ground in front of them, its handle at their hands (the
 * held item itself is drawn at scale 0 in third person, see tools/city/shop_goods.py). */
public final class ShopCartRenderer extends FeatureRenderer<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> {
    public ShopCartRenderer(FeatureRendererContext<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>> context) { super(context); }

    public static void register() {
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof PlayerEntityRenderer player) helper.register(new ShopCartRenderer(player));
        });
    }

    @Override public void render(MatrixStack matrices, VertexConsumerProvider consumers, int light, AbstractClientPlayerEntity player,
                                 float limbAngle, float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
        ItemStack cart = player.getMainHandStack().isOf(ShopContent.CART) ? player.getMainHandStack()
                : player.getOffHandStack().isOf(ShopContent.CART) ? player.getOffHandStack() : ItemStack.EMPTY;
        if (cart.isEmpty() || player.isSleeping() || player.hasVehicle()) return;
        matrices.push();
        // The model space is turned with the body and upside down, the feet at y 1.5 and the front towards -z: back to upright, on
        // the ground ahead of the body (so the cart follows the body, as pushed, not the head), turned so that the handle (the
        // model's south side) is at the player's hands; a little smaller than a block.
        matrices.translate(0, 1.501, 0);
        matrices.scale(-1, -1, 1);
        matrices.translate(0, 0.5 * 0.85, -0.9 - 0.04 * Math.sin(limbAngle * 0.6) * limbDistance);
        matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(180));
        matrices.scale(0.85f, 0.85f, 0.85f);
        MinecraftClient.getInstance().getItemRenderer().renderItem(cart, ModelTransformationMode.NONE, light, OverlayTexture.DEFAULT_UV, matrices, consumers,
                player.getWorld(), player.getId());
        matrices.pop();
    }
}
