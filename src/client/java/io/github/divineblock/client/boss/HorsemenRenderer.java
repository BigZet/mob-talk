package io.github.divineblock.client.boss;

import io.github.divineblock.boss.HorsemenEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** Draws the Four Horsemen: one model, four colourings. The texture and its glow follow the boss's phase: the white,
 * the red, the black and the pale rider. */
public final class HorsemenRenderer extends MobEntityRenderer<HorsemenEntity, HorsemenModel> {
    private static final String[] RIDERS = { "white", "red", "black", "pale" };
    private static final Identifier[] TEXTURES = new Identifier[4];
    private static final RenderLayer[] GLOWS = new RenderLayer[4];
    static {
        for (int i = 0; i < 4; i++) {
            TEXTURES[i] = Identifier.of("divineblock", "textures/entity/boss/horsemen_" + RIDERS[i] + ".png");
            GLOWS[i] = RenderLayer.getEyes(Identifier.of("divineblock", "textures/entity/boss/horsemen_" + RIDERS[i] + "_glow.png"));
        }
    }
    private static final float SCALE = 1.1f;

    public HorsemenRenderer(EntityRendererFactory.Context context) {
        super(context, new HorsemenModel(context.getPart(HorsemenModel.LAYER)), 1.3f);
        addFeature(new FeatureRenderer<>(this) {
            @Override public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, HorsemenEntity boss,
                                         float limbAngle, float limbDistance, float tickDelta, float animationProgress, float headYaw, float headPitch) {
                if (boss.isInvisible()) return;
                getContextModel().render(matrices, vertexConsumers.getBuffer(GLOWS[rider(boss)]), 0xF00000, OverlayTexture.DEFAULT_UV);
            }
        });
    }

    private static int rider(HorsemenEntity boss) { return Math.max(0, Math.min(3, boss.syncedPhase() - 1)); }

    @Override public Identifier getTexture(HorsemenEntity boss) { return TEXTURES[rider(boss)]; }
    @Override protected void scale(HorsemenEntity boss, MatrixStack matrices, float tickDelta) { matrices.scale(SCALE, SCALE, SCALE); }
}
