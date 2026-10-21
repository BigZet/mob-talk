package io.github.divineblock.client.boss;

import io.github.divineblock.boss.GodAvatarEntity;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/** Draws the god over the clouds, always at full brightness and never culled (he fills half the sky): the figure cut out,
 * its glow (eyes, nimbus, rays, the blessing hand) added on top, then the cloud bank in a translucent pass. Coming, the
 * whole bank sinks from higher up; going, it rises again. */
public final class GodRenderer extends EntityRenderer<GodAvatarEntity> {
    /** Model units to blocks: 48 units from the waist to the top of the nimbus make about 33 blocks. */
    public static final float SCALE = 11f;
    private static final Identifier TEXTURE = Identifier.of("divineblock", "textures/entity/boss/god_avatar.png");
    private static final Identifier GLOW = Identifier.of("divineblock", "textures/entity/boss/god_avatar_glow.png");
    private final GodModel model;

    public GodRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.model = new GodModel(context.getPart(GodModel.LAYER));
        this.shadowRadius = 0;
    }

    @Override public boolean shouldRender(GodAvatarEntity god, Frustum frustum, double x, double y, double z) { return true; }
    @Override public Identifier getTexture(GodAvatarEntity god) { return TEXTURE; }

    @Override public void render(GodAvatarEntity god, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers, int light) {
        model.animate(god, tickDelta);
        float emerge = GodModel.emerge(god, tickDelta), gather = GodModel.gather(god, tickDelta);
        int bright = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        matrices.push();
        // The bank comes down from on high and goes back up.
        matrices.translate(0, (1 - Math.min(emerge, gather * 1.5f)) * 18, 0);
        float facing = MathHelper.lerpAngleDegrees(tickDelta, god.prevYaw, god.getYaw());
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - facing));
        matrices.scale(-SCALE, -SCALE, SCALE);
        if (emerge > 0.01f) {
            model.figure.render(matrices, consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(TEXTURE)), bright, OverlayTexture.DEFAULT_UV, 0xFFFFFFFF);
            model.figure.render(matrices, consumers.getBuffer(RenderLayer.getEyes(GLOW)), bright, OverlayTexture.DEFAULT_UV, 0xFFFFFFFF);
        }
        int alpha = (int) (255 * MathHelper.clamp(gather, 0f, 1f));
        if (alpha > 4) model.clouds.render(matrices, consumers.getBuffer(RenderLayer.getEntityTranslucent(TEXTURE)), bright, OverlayTexture.DEFAULT_UV,
                (alpha << 24) | 0xE4E4EC);
        matrices.pop();
    }
}
