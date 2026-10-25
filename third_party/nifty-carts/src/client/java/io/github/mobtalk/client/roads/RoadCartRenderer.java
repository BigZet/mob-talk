package io.github.mobtalk.client.roads;

import io.github.mobtalk.game.RoadCartEntity;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/** Draws the road carts with NiftyCarts' models: turned to the cart's heading, flipped the way vanilla entity models are, the shafts
 * pitched toward the horse and the wheels turned by the distance rolled; the wagon gets its wool roof. Ported from NiftyCarts'
 * {@code DrawnRenderer}, {@code AnimalCartRenderer} and {@code WagonRenderer} (MIT, third_party/nifty-carts/NOTICE.md) to Yarn names;
 * banners, chests and the wobble when hit are left out. */
public final class RoadCartRenderer extends EntityRenderer<RoadCartEntity> {
    static final EntityModelLayer CART = new EntityModelLayer(Identifier.of("mobtalk", "road_cart"), "main");
    static final EntityModelLayer WAGON = new EntityModelLayer(Identifier.of("mobtalk", "road_wagon"), "main");
    static final EntityModelLayer WAGON_ROOF = new EntityModelLayer(Identifier.of("mobtalk", "road_wagon"), "roof");

    private final AnimalCartModel cart;
    private final WagonModel wagon;

    private RoadCartRenderer(EntityRendererFactory.Context context) {
        super(context);
        cart = new AnimalCartModel(context.getPart(CART));
        wagon = new WagonModel(context.getPart(WAGON), context.getPart(WAGON_ROOF));
        shadowRadius = 1.0F;
    }

    public static void register() {
        EntityModelLayerRegistry.registerModelLayer(CART, AnimalCartModel::createLayer);
        EntityModelLayerRegistry.registerModelLayer(WAGON, WagonModel::createLayer);
        EntityModelLayerRegistry.registerModelLayer(WAGON_ROOF, WagonModel::createRoofLayer);
        EntityRendererRegistry.register(RoadCartEntity.CART, RoadCartRenderer::new);
        EntityRendererRegistry.register(RoadCartEntity.WAGON, RoadCartRenderer::new);
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public Identifier getFabricId() { return Identifier.of("mobtalk", "road_cart_textures"); }
            @Override public void reload(ResourceManager manager) { CartTextures.clear(); }
        });
    }

    @Override public void render(RoadCartEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertices, int light) {
        matrices.push();
        float heading = MathHelper.lerpAngleDegrees(tickDelta, entity.prevYaw, entity.getYaw());
        float pitch = MathHelper.lerp(tickDelta, entity.prevPitch, entity.getPitch());
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0F - heading));
        matrices.scale(-1.0F, -1.0F, 1.0F);
        CartModel model = entity.wagon() ? wagon : cart;
        // Vanilla pitch is positive looking down: the cart's nose goes down on a descent.
        model.setAngles(entity, MathHelper.lerp(tickDelta, entity.prevWheel, entity.wheel), 0, 0, 0, -pitch);
        model.render(matrices, vertices.getBuffer(model.getLayer(getTexture(entity))), light, OverlayTexture.DEFAULT_UV);
        if (entity.wagon()) {
            matrices.push();
            wagon.body().rotate(matrices);
            wagon.roof().render(matrices, vertices.getBuffer(wagon.getLayer(CartTextures.roof(entity.roof()))), light, OverlayTexture.DEFAULT_UV);
            matrices.pop();
        }
        matrices.pop();
        super.render(entity, yaw, tickDelta, matrices, vertices, light);
    }

    @Override public Identifier getTexture(RoadCartEntity entity) {
        return entity.wagon() ? CartTextures.wagon(entity.wood()) : CartTextures.cart(entity.wood());
    }
}
