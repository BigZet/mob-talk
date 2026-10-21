package io.github.divineblock.client.boss;

import io.github.divineblock.boss.SacredBoss;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.EyesFeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** Draws a boss: its texture, and over it the glow texture at full brightness (eyes, flames, candles shine in the dark). */
public class SacredBossRenderer<T extends SacredBoss, M extends EntityModel<T>> extends MobEntityRenderer<T, M> {
    private final Identifier texture;
    private final float scale;

    public SacredBossRenderer(EntityRendererFactory.Context context, M model, String id, float shadow, float scale) {
        super(context, model, shadow);
        this.texture = Identifier.of("divineblock", "textures/entity/boss/" + id + ".png");
        this.scale = scale;
        RenderLayer glow = RenderLayer.getEyes(Identifier.of("divineblock", "textures/entity/boss/" + id + "_glow.png"));
        addFeature(new EyesFeatureRenderer<>(this) { @Override public RenderLayer getEyesTexture() { return glow; } });
    }

    @Override public Identifier getTexture(T boss) { return texture; }
    @Override protected void scale(T boss, MatrixStack matrices, float tickDelta) { matrices.scale(scale, scale, scale); }
}
