package io.github.divineblock.client.fx;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/** The god's effects atlas ({@link FxSprite}, {@link FxLayers#ATLAS}) on the screen instead of in the world: glows, sparks,
 * stars, halos, feathers, confetti, beams and flares drawn over the HUD with smooth filtering, either adding light (the
 * {@link FxLayers#GLOW} look) or ordinarily see-through (the {@link FxLayers#SOFT} look). For the punishment roulette and any
 * other overlay; also a door to the world engine's bursts ({@link FxEngine}) at a point in the world. The world API stays as
 * it was. */
public final class FxHud {
    /** The atlas cells an overlay may draw. */
    public enum Sprite {
        GLOW(FxSprite.GLOW), SPARK(FxSprite.SPARK), STAR(FxSprite.STAR), HALO(FxSprite.HALO), FEATHER(FxSprite.FEATHER), CONFETTI(FxSprite.CONFETTI),
        STRIP(FxSprite.STRIP), BEAM(FxSprite.BEAM), FLARE(FxSprite.FLARE), EMBER(FxSprite.EMBER), SMOKE(FxSprite.SMOKE);
        final FxSprite cell;
        Sprite(FxSprite cell) { this.cell = cell; }
    }

    private FxHud() { }

    /** The effects' quality the player chose: 0 off .. 3 high (the overlay scales its own counts by it). */
    public static int quality() { return FxQuality.level(); }

    /** Starts a batch of sprites in the current matrices of {@code context}; {@code additive} adds light. End with {@link Batch#draw}. */
    public static Batch begin(DrawContext context, boolean additive) {
        context.draw(); // what the context has buffered goes under the sprites
        return new Batch(context.getMatrices().peek().getPositionMatrix(), additive);
    }

    public static final class Batch {
        private final Matrix4f matrix;
        private final boolean additive;
        private final BufferBuilder buffer;
        private int quads;

        private Batch(Matrix4f matrix, boolean additive) {
            this.matrix = new Matrix4f(matrix);
            this.additive = additive;
            buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        }

        /** A sprite centred at (x, y), {@code size} across, turned by {@code roll} radians, its width times {@code squeeze}. */
        public Batch sprite(Sprite sprite, float x, float y, float size, float roll, float squeeze, int rgb, float alpha) {
            return quad(sprite, x, y, size * squeeze / 2, size / 2, roll, rgb, alpha);
        }

        /** A sprite stretched along a direction: half-length {@code length} along {@code angle}, half-width {@code width}. A beam
         * of light, a spark's trail. */
        public Batch streak(Sprite sprite, float x, float y, float length, float width, float angle, int rgb, float alpha) {
            return quad(sprite, x, y, width, length, angle, rgb, alpha);
        }

        private Batch quad(Sprite sprite, float x, float y, float halfW, float halfH, float roll, int rgb, float alpha) {
            if (alpha <= 0.003f || halfW <= 0 || halfH <= 0) return this;
            FxSprite cell = sprite.cell;
            float cos = MathHelper.cos(roll), sin = MathHelper.sin(roll);
            float rx = cos * halfW, ry = sin * halfW, ux = -sin * halfH, uy = cos * halfH;
            int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255, a = MathHelper.clamp((int) (alpha * 255), 0, 255);
            buffer.vertex(matrix, x - rx - ux, y - ry - uy, 0).texture(cell.u0, cell.v0).color(r, g, b, a);
            buffer.vertex(matrix, x - rx + ux, y - ry + uy, 0).texture(cell.u0, cell.v1).color(r, g, b, a);
            buffer.vertex(matrix, x + rx + ux, y + ry + uy, 0).texture(cell.u1, cell.v1).color(r, g, b, a);
            buffer.vertex(matrix, x + rx - ux, y + ry - uy, 0).texture(cell.u1, cell.v0).color(r, g, b, a);
            quads++;
            return this;
        }

        public void draw() {
            if (quads == 0) { var built = buffer.endNullable(); if (built != null) built.close(); return; }
            MinecraftClient.getInstance().getTextureManager().getTexture(FxLayers.ATLAS).setFilter(true, false);
            RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
            RenderSystem.setShaderTexture(0, FxLayers.ATLAS);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.enableBlend();
            if (additive) RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
            else RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            BufferRenderer.drawWithGlobalProgram(buffer.end());
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
        }
    }

    // ---- The world engine's bursts at a point (for overlays that want the world to answer too). ----

    public static void sparks(Vec3d at, int color, float amount) { FxEngine.sparks(at.x, at.y, at.z, color, amount); }
    public static void confetti(Vec3d at, float amount) { FxEngine.confetti(at.x, at.y, at.z, amount); }
    public static void feathers(Vec3d at, float amount) { FxEngine.feathers(at.x, at.y, at.z, amount); }
}
