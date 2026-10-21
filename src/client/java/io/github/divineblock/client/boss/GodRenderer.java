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
 * figure comes down from higher up into the bank; going, it rises again. The bank itself is {@link GodClouds}. */
public final class GodRenderer extends EntityRenderer<GodAvatarEntity> {
    /** Model units to blocks: 48 units from the waist to the top of the nimbus make about 33 blocks. */
    public static final float SCALE = 11f;
    private static final Identifier TEXTURE = Identifier.of("divineblock", "textures/entity/boss/god_avatar.png");
    private static final Identifier GLOW = Identifier.of("divineblock", "textures/entity/boss/god_avatar_glow.png");
    private static final RenderLayer FIGURE = GodLayers.figure(TEXTURE);
    private final GodModel model;
    private final java.util.Map<Integer, GodClouds> banks = new java.util.HashMap<>();

    /** Each god's own bank (puffs laid out from his id), forgotten when he is gone. */
    private GodClouds clouds(GodAvatarEntity god) {
        if (banks.size() > 8) banks.keySet().removeIf(id -> god.getWorld().getEntityById(id) == null);
        return banks.computeIfAbsent(god.getId(), id -> new GodClouds(0x5EEDL * 31 + id));
    }

    public GodRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.model = new GodModel(context.getPart(GodModel.LAYER));
        this.shadowRadius = 0;
    }

    @Override public boolean shouldRender(GodAvatarEntity god, Frustum frustum, double x, double y, double z) { return true; }
    @Override public Identifier getTexture(GodAvatarEntity god) { return TEXTURE; }

    /** Per god: the lean toward this client's camera now, and when it was last eased (nanoseconds). */
    private final java.util.Map<Integer, double[]> leans = new java.util.HashMap<>();

    /** How far he leans toward the camera: about the camera's elevation angle up to him (he looks down at the one who
     * looks up), 0..50 degrees, eased smoothly so it never snaps. */
    private float lean(GodAvatarEntity god, double dx, double dy, double dz) {
        double target = MathHelper.clamp(Math.atan2(dy, Math.hypot(dx, dz)) * 0.95, 0, Math.toRadians(50));
        long now = System.nanoTime();
        double[] state = leans.computeIfAbsent(god.getId(), id -> new double[] { target, now });
        double seconds = Math.min(0.5, (now - (long) state[1]) / 1e9);
        state[0] += (target - state[0]) * (1 - Math.exp(-seconds * 2.5));
        state[1] = now;
        if (leans.size() > 8) leans.keySet().removeIf(id -> god.getWorld().getEntityById(id) == null);
        return (float) state[0];
    }

    @Override public void render(GodAvatarEntity god, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers, int light) {
        var eye = dispatcher.camera.getPos();
        double gx = MathHelper.lerp(tickDelta, god.lastRenderX, god.getX()), gy = MathHelper.lerp(tickDelta, god.lastRenderY, god.getY()),
                gz = MathHelper.lerp(tickDelta, god.lastRenderZ, god.getZ());
        // The elevation to his chest, a dozen blocks over the waist.
        float lean = lean(god, gx - eye.x, gy + 12 - eye.y, gz - eye.z);
        model.animate(god, tickDelta, lean);
        float emerge = GodModel.emerge(god, tickDelta), gather = GodModel.gather(god, tickDelta);
        int bright = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        matrices.push();
        // He comes down from on high into the bank and goes back up.
        float drop = (1 - Math.min(emerge, gather * 1.5f)) * 18;
        matrices.translate(0, drop, 0);
        float facing = MathHelper.lerpAngleDegrees(tickDelta, god.prevYaw, god.getYaw());
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - facing));
        matrices.scale(-SCALE, -SCALE, SCALE);
        if (emerge > 0.01f) {
            model.figure.render(matrices, consumers.getBuffer(FIGURE), bright, OverlayTexture.DEFAULT_UV, 0xFFFFFFFF);
            model.figure.render(matrices, consumers.getBuffer(RenderLayer.getEyes(GLOW)), bright, OverlayTexture.DEFAULT_UV, 0xFFFFFFFF);
        }
        matrices.pop();
        // The cloud bank round his waist, in blocks from it: it gathers where he will be and stays there while he comes
        // down through it and goes back up.
        // Where his head is now (about 23 blocks over the waist, leaned toward the camera), for the golden rim behind it.
        double front = Math.toRadians(facing + 90), reach = 23 * GodModel.emerge(god, tickDelta);
        var head = new net.minecraft.util.math.Vec3d(Math.cos(front) * Math.sin(lean) * reach, Math.cos(lean) * reach + drop, Math.sin(front) * Math.sin(lean) * reach);
        clouds(god).render(god, tickDelta, facing, emerge, gather, matrices, consumers, dispatcher.getRotation(),
                new net.minecraft.util.math.Vec3d(eye.x - gx, eye.y - gy, eye.z - gz), head);
    }
}
