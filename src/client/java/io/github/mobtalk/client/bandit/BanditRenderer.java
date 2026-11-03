package io.github.mobtalk.client.bandit;

import io.github.mobtalk.game.BanditContent;
import io.github.mobtalk.game.BanditEntity;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** The city bandit drawn as a man: the player's model with one of the skins put together by tools/bandits/skins.py from the MCA
 * pieces (skin, face, clothes, hair) under a balaclava, a cap or a hood. The knife or hatchet in his hand is raised while he fights. */
public final class BanditRenderer extends BipedEntityRenderer<BanditEntity, PlayerEntityModel<BanditEntity>> {
    private static final Identifier[] SKINS = new Identifier[BanditEntity.SKINS];
    static { for (int i = 0; i < SKINS.length; i++) SKINS[i] = Identifier.of("mobtalk", "textures/entity/bandit/" + i + ".png"); }

    public BanditRenderer(EntityRendererFactory.Context context) {
        super(context, new PlayerEntityModel<>(context.getPart(EntityModelLayers.PLAYER), false), 0.5f);
        addFeature(new ArmorFeatureRenderer<>(this, new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
                new BipedEntityModel<>(context.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
    }

    public static void register() { EntityRendererRegistry.register(BanditContent.BANDIT, BanditRenderer::new); }

    @Override public void render(BanditEntity bandit, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers, int light) {
        boolean armed = !bandit.getMainHandStack().isEmpty();
        model.rightArmPose = armed ? BipedEntityModel.ArmPose.ITEM : BipedEntityModel.ArmPose.EMPTY;
        model.leftArmPose = BipedEntityModel.ArmPose.EMPTY;
        super.render(bandit, yaw, tickDelta, matrices, consumers, light);
    }

    @Override protected void scale(BanditEntity bandit, MatrixStack matrices, float tickDelta) { matrices.scale(0.9375f, 0.9375f, 0.9375f); }

    @Override public Identifier getTexture(BanditEntity bandit) { return SKINS[bandit.skin()]; }
}
